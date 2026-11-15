package libcore

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptrace"
	"sync"
	"time"

	N "github.com/sagernet/sing/common/network"
)

// probeHTTP follows sing-box's URLTest semantics:
//   - one HEAD request, not a page download or a hidden warm-up request;
//   - one hard, end-to-end user deadline;
//   - measure until the first response byte;
//   - include DNS/TCP/TLS and early-handshake cost, but exclude a lazy
//     protocol handshake that is intentionally performed on the first write.
//
// The last rule is the same NeedHandshakeForWrite rule used by sing-box's
// common/urltest implementation and prevents Trojan/VMess lazy writes from
// inflating the displayed network delay.
func probeHTTP(parent context.Context, client *http.Client, link string, timeout int32) (int32, error) {
	if timeout <= 0 {
		return 0, errors.New("invalid probe timeout")
	}
	if client == nil {
		return 0, errors.New("missing probe client")
	}

	// Do not mutate the shared proxy client. boxapi installs fixed 3s stage
	// timers; the probe has exactly one user-selected end-to-end budget instead.
	probeClient := *client
	if transport, ok := client.Transport.(*http.Transport); ok {
		transport = transport.Clone()
		transport.TLSHandshakeTimeout = 0
		transport.ResponseHeaderTimeout = 0
		probeClient.Transport = transport
	}
	budget := time.Duration(timeout) * time.Millisecond
	probeClient.Timeout = budget // hard cap even for a non-standard Transport.
	// Match sing-box: do not follow redirects, but a redirect response itself
	// is a valid HTTP response and therefore a successful probe.
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
	started := time.Now()
	measurementStart := started
	firstByte := time.Time{}
	trace := &httptrace.ClientTrace{
		// sing-box starts before DialContext for normal connections, but resets
		// the clock after DialContext when the protocol defers its handshake to
		// the first Write. This is exactly NeedHandshakeForWrite semantics.
		GotConn: func(info httptrace.GotConnInfo) {
			if !N.NeedHandshakeForWrite(info.Conn) {
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
	// HEAD has no useful body. Closing immediately bounds arbitrary test URLs.
	resp.Body.Close()

	lock.Lock()
	if firstByte.IsZero() {
		firstByte = time.Now()
	}
	measured := firstByte.Sub(measurementStart)
	lock.Unlock()
	return int32(max(0, measured.Milliseconds())), nil
}
