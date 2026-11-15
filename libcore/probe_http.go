package libcore

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptrace"
	"sync"
	"time"
)

// probeHTTP makes one real GET under one user-configured end-to-end deadline.
// RTT keeps the existing WroteHeaders-to-first-byte metric (which may include
// lazy transport writes). A completed sample must never be discarded by a
// second, hidden warm-up request.
// Only disposable cores use this path; normal VPN/control-bar probes are unchanged.
var probeNoRedirect = errors.New("probe redirect")

func probeHTTP(parent context.Context, client *http.Client, link string, timeout int32) (int32, error) {
	if timeout <= 0 {
		return 0, errors.New("invalid probe timeout")
	}
	if client == nil {
		return 0, errors.New("missing probe client")
	}
	// boxapi's generic client has fixed 3s TLS/header timers. A disposable
	// probe must use the user's ONE total deadline, not a hidden stage cap.
	if transport, ok := client.Transport.(*http.Transport); ok {
		transport = transport.Clone()
		transport.TLSHandshakeTimeout = 0
		transport.ResponseHeaderTimeout = 0
		client.Transport = transport
	}
	ctx, cancel := context.WithTimeout(context.WithValue(parent, "nb4a_no_concurrent_dial", true), time.Duration(timeout)*time.Millisecond)
	defer cancel()
	defer client.CloseIdleConnections()
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return probeNoRedirect }
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return 0, err
	}
	var lock sync.Mutex
	var written, firstByte time.Time
	trace := &httptrace.ClientTrace{
		WroteHeaders:         func() { lock.Lock(); written = time.Now(); lock.Unlock() },
		GotFirstResponseByte: func() { lock.Lock(); firstByte = time.Now(); lock.Unlock() },
	}
	started := time.Now()
	resp, err := client.Do(req.WithContext(httptrace.WithClientTrace(ctx, trace)))
	if err != nil && !errors.Is(err, probeNoRedirect) {
		return 0, err
	}
	if resp == nil {
		return 0, errors.New("probe returned no response")
	}
	// A URL latency test measures the response, not a page download. Closing
	// without draining also keeps arbitrary URLs with large bodies bounded.
	resp.Body.Close()
	lock.Lock()
	if written.IsZero() {
		written = started
	}
	if firstByte.IsZero() {
		firstByte = time.Now()
	}
	measured := firstByte.Sub(written)
	lock.Unlock()
	return int32(max(0, measured.Milliseconds())), nil
}
