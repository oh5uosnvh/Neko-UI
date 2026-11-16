package libcore

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptrace"
	"sync"
	"time"
)

// probeHTTP performs the direct fallback URLTest. It deliberately mirrors the
// HTTP portion of sing-box common/urltest.URLTest: one HEAD request, no
// redirect following, and response arrival is the sample endpoint.
func probeHTTP(parent context.Context, client *http.Client, link string, timeout int32) (int32, error) {
	if timeout <= 0 {
		return 0, errors.New("invalid probe timeout")
	}
	if client == nil {
		return 0, errors.New("missing probe client")
	}

	// boxapi's generic client has fixed 3-second stage timers. Clone the
	// transport and remove those timers: the caller's deadline is the only
	// end-to-end budget for this probe, without mutating a shared client.
	probeClient := *client
	if transport, ok := client.Transport.(*http.Transport); ok {
		transport = transport.Clone()
		transport.TLSHandshakeTimeout = 0
		transport.ResponseHeaderTimeout = 0
		probeClient.Transport = transport
	}
	budget := time.Duration(timeout) * time.Millisecond
	probeClient.Timeout = budget
	probeClient.CheckRedirect = func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}
	defer probeClient.CloseIdleConnections()

	ctx, cancel := context.WithTimeout(
		context.WithValue(parent, "nb4a_no_concurrent_dial", true), budget,
	)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, link, nil)
	if err != nil {
		return 0, err
	}

	var lock sync.Mutex
	measurementStart := time.Now()
	firstByte := time.Time{}
	trace := &httptrace.ClientTrace{
		// This is the same timing adjustment as common/urltest.URLTest:
		// deferred protocol handshakes are not charged to outbound dialing.
		GotConn: func(info httptrace.GotConnInfo) {
			// Keep this compatible with the sing version pinned by libcore:
			// early-writer is an optional interface on the wrapped connection.
			if early, ok := info.Conn.(interface{ NeedHandshakeForWrite() bool }); !ok || !early.NeedHandshakeForWrite() {
				return
			}
			lock.Lock()
			measurementStart = time.Now()
			lock.Unlock()
		},
		GotFirstResponseByte: func() {
			lock.Lock()
			firstByte = time.Now()
			lock.Unlock()
		},
	}
	resp, err := probeClient.Do(req.WithContext(httptrace.WithClientTrace(ctx, trace)))
	if err != nil {
		return 0, err
	}
	if resp == nil {
		return 0, errors.New("probe returned no response")
	}
	resp.Body.Close()

	lock.Lock()
	if firstByte.IsZero() {
		firstByte = time.Now()
	}
	measured := firstByte.Sub(measurementStart)
	lock.Unlock()
	if measured < 0 {
		measured = 0
	}
	return int32(measured.Milliseconds()), nil
}
