package libcore

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptrace"
	"sync"
	"time"
)

// probeHTTP preserves the two real GET samples and one user-configured HTTP
// deadline used by libneko. Only disposable cores use this path: their owner
// cancellation must also interrupt an HTTP read, not merely close the core.
var probeNoRedirect = errors.New("probe redirect")

func probeHTTP(parent context.Context, client *http.Client, link string, timeout int32) (int32, error) {
	if timeout <= 0 {
		return 0, errors.New("invalid probe timeout")
	}
	ctx, cancel := context.WithTimeout(context.WithValue(parent, "nb4a_no_concurrent_dial", true), time.Duration(timeout)*time.Millisecond)
	defer cancel()
	defer client.CloseIdleConnections()
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return probeNoRedirect }
	var measured time.Duration
	for sample := 0; sample < 2; sample++ {
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
		resp.Body.Close()
		lock.Lock()
		if written.IsZero() {
			written = started
		}
		if firstByte.IsZero() {
			firstByte = time.Now()
		}
		measured = firstByte.Sub(written)
		lock.Unlock()
	}
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	return int32(max(0, measured.Milliseconds())), nil
}
