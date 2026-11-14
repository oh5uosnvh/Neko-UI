package probe

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptrace"
	"sync"
	"time"
)

// RTT primes one real proxy connection, then measures a warm request. Queueing,
// core initialization, DNS and TLS handshake time are not reported as RTT.
// Each request has its own bounded budget. A successful first response remains
// a real current-run sample if the optional second request fails; no history
// cache is consulted. Cancellation of the owning core never returns success.
func RTT(parent context.Context, client *http.Client, link string, timeout time.Duration) (int32, error) {
	if client == nil {
		return 0, errors.New("no probe client")
	}
	if timeout <= 0 {
		return 0, errors.New("invalid probe timeout")
	}
	if err := parent.Err(); err != nil {
		return 0, err
	}
	defer client.CloseIdleConnections()
	client.CheckRedirect = func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }
	// The user-provided timeout is the complete deadline for this node. Do not
	// add a hidden 5/8-second warm-up allowance or let the second sample extend it.
	probeCtx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()

	first, err := request(probeCtx, client, link, timeout)
	if err != nil {
		return 0, err
	}
	if ownerErr := parent.Err(); ownerErr != nil {
		return 0, ownerErr
	}
	if deadlineErr := probeCtx.Err(); deadlineErr != nil {
		return 0, deadlineErr
	}
	// One request is the complete quick-delay test. A second warm sample would
	// consume part of the user's deadline and could turn a valid result into a
	// delayed 5/8-second-style decision.
	return milliseconds(first), nil
}

func milliseconds(value time.Duration) int32 {
	result := value.Milliseconds()
	if result < 1 {
		result = 1
	}
	if result > int64(^uint32(0)>>1) {
		result = int64(^uint32(0) >> 1)
	}
	return int32(result)
}

func request(parent context.Context, client *http.Client, link string, timeout time.Duration) (time.Duration, error) {
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return 0, fmt.Errorf("invalid probe URL: %w", err)
	}
	var lock sync.Mutex
	var written, firstByte time.Time
	trace := &httptrace.ClientTrace{
		WroteHeaders:         func() { lock.Lock(); written = time.Now(); lock.Unlock() },
		GotFirstResponseByte: func() { lock.Lock(); firstByte = time.Now(); lock.Unlock() },
	}
	req = req.WithContext(httptrace.WithClientTrace(req.Context(), trace))
	started := time.Now()
	response, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 400 {
		return 0, fmt.Errorf("probe endpoint returned HTTP %d", response.StatusCode)
	}
	lock.Lock()
	writeTime, responseTime := written, firstByte
	lock.Unlock()
	if writeTime.IsZero() {
		writeTime = started
	}
	if responseTime.IsZero() {
		responseTime = time.Now()
	}
	measured := responseTime.Sub(writeTime)
	if measured < 0 {
		measured = 0
	}
	// 204 is empty. Drain small alternate endpoints to permit connection reuse,
	// but never download an arbitrary page or count its body time as latency.
	if response.ContentLength >= 0 && response.ContentLength <= 32*1024 {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 32*1024+1))
	}
	if err := parent.Err(); err != nil {
		return 0, err
	}
	return measured, nil
}
