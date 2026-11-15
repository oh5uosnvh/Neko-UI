package libcore

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// One valid URL response must not be discarded by an invisible second GET.
// Scaled timings model a slow cold connection + low RTT inside the user budget.
func TestProbeHTTPDoesNotChargeSecondRequestToCompletedSample(t *testing.T) {
	var calls atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		select {
		case <-time.After(90 * time.Millisecond):
			w.WriteHeader(http.StatusNoContent)
		case <-r.Context().Done():
		}
	}))
	defer target.Close()
	client := &http.Client{Transport: &http.Transport{DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
		select {
		case <-time.After(110 * time.Millisecond):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	}}}
	latency, err := probeHTTP(context.Background(), client, target.URL, 270)
	if err != nil {
		t.Fatalf("valid first response was incorrectly timed out: %v", err)
	}
	if n := calls.Load(); n != 1 {
		t.Fatalf("got %d requests for one sample", n)
	}
	if latency < 60 || latency > 160 {
		t.Fatalf("RTT must exclude cold dial: %dms", latency)
	}
}

func TestProbeHTTPKeepsUserBudgetForColdConnection(t *testing.T) {
	client := &http.Client{Transport: &http.Transport{DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	}}}
	began := time.Now()
	_, err := probeHTTP(context.Background(), client, "http://probe.invalid", 80)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("wanted deadline, got %v", err)
	}
	if elapsed := time.Since(began); elapsed > 500*time.Millisecond {
		t.Fatalf("user budget was extended: %v", elapsed)
	}
}

func TestProbeHTTPNeverWaitsForResponseBody(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "10000000")
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	defer target.Close()
	began := time.Now()
	if _, err := probeHTTP(context.Background(), target.Client(), target.URL, 400); err != nil {
		t.Fatal(err)
	}
	if elapsed := time.Since(began); elapsed > 250*time.Millisecond {
		t.Fatalf("waited for body: %v", elapsed)
	}
}

// A lazy transport may perform its handshake on first Write, after the HTTP
// transport has already fired WroteHeaders. Keep that cost visible rather than
// manufacturing a lower RTT or requiring a second warm request.
type probeDelayedWriteConn struct {
	net.Conn
	once sync.Once
}

func (c *probeDelayedWriteConn) Write(p []byte) (int, error) {
	c.once.Do(func() { time.Sleep(100 * time.Millisecond) })
	return c.Conn.Write(p)
}
func TestProbeHTTPRTTKeepsLazyTransportWriteCostVisible(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(50 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer target.Close()
	client := &http.Client{Transport: &http.Transport{DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
		c, err := (&net.Dialer{}).DialContext(ctx, network, addr)
		if err != nil {
			return nil, err
		}
		return &probeDelayedWriteConn{Conn: c}, nil
	}}}
	latency, err := probeHTTP(context.Background(), client, target.URL, 500)
	if err != nil {
		t.Fatal(err)
	}
	if latency < 120 || latency > 300 {
		t.Fatalf("request-to-response cost was hidden or inflated: %dms", latency)
	}
}

func TestProbeHTTP94Nodes36Workers(t *testing.T) {
	const count, workers = 94, 36
	var inFlight, peak, calls atomic.Int32
	release := make(chan struct{})
	allStarted := make(chan struct{}, 1)
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		n := inFlight.Add(1)
		defer inFlight.Add(-1)
		for old := peak.Load(); n > old; old = peak.Load() {
			if peak.CompareAndSwap(old, n) {
				break
			}
		}
		if n >= workers {
			select {
			case allStarted <- struct{}{}:
			default:
			}
		}
		select {
		case <-release:
		case <-r.Context().Done():
			return
		}
		time.Sleep(20 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer target.Close()
	var queue atomic.Int32
	var wg sync.WaitGroup
	failures := make(chan error, count)
	began := time.Now()
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for queue.Add(1) <= count {
				_, err := probeHTTP(context.Background(), &http.Client{}, target.URL, 2000)
				if err != nil {
					failures <- err
				}
			}
		}()
	}
	select {
	case <-allStarted:
	case <-time.After(1500 * time.Millisecond):
		t.Error("36 independent HTTP requests could not run concurrently")
	}
	close(release)
	wg.Wait()
	close(failures)
	for err := range failures {
		t.Error(err)
	}
	if peak.Load() < workers {
		t.Errorf("peak concurrency %d, expected at least36", peak.Load())
	}
	if calls.Load() != count {
		t.Errorf("made%d GETs for%d nodes", calls.Load(), count)
	}
	t.Logf("nodes=%d workers=%d peak=%d requests=%d elapsed=%s", count, workers, peak.Load(), calls.Load(), time.Since(began))
}

func TestProbeHTTPDoesNotFollowRedirects(t *testing.T) {
	var calls atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		http.Redirect(w, r, "/again", http.StatusFound)
	}))
	defer target.Close()
	if _, err := probeHTTP(context.Background(), target.Client(), target.URL, 500); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 1 {
		t.Fatalf("followed redirect or made extra request: %d", calls.Load())
	}
}

func TestProbeHTTPUsesUserDeadlineInsteadOfHiddenHeaderCap(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(80 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer target.Close()
	generic := &http.Transport{ResponseHeaderTimeout: 20 * time.Millisecond}
	client := &http.Client{Transport: generic}
	if _, err := probeHTTP(context.Background(), client, target.URL, 400); err != nil {
		t.Fatalf("hidden generic timer overrode the user budget: %v", err)
	}
	if generic.ResponseHeaderTimeout != 20*time.Millisecond {
		t.Fatal("probe mutated the generic/shared transport")
	}
}
