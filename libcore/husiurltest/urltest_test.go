package husiurltest

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	boxurltest "github.com/sagernet/sing-box/common/urltest"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

type testOutbound struct {
	N.Dialer
	multiplex bool
}

func (*testOutbound) Type() string             { return "direct" }
func (*testOutbound) Tag() string              { return "test" }
func (*testOutbound) Network() []string        { return []string{N.NetworkTCP, N.NetworkUDP} }
func (*testOutbound) Dependencies() []string   { return nil }
func (o *testOutbound) MultiplexEnabled() bool { return o.multiplex }

type countedDialer struct {
	N.Dialer
	count atomic.Int32
}

func (d *countedDialer) DialContext(ctx context.Context, network string, address M.Socksaddr) (net.Conn, error) {
	d.count.Add(1)
	return d.Dialer.DialContext(ctx, network, address)
}

func TestHusiRequestCountsAndConnectionReuse(t *testing.T) {
	for _, tc := range []struct {
		name            string
		flags           Flags
		multiplex       bool
		requests, dials int32
	}{
		{"ordinary", 0, false, 1, 1},
		{"unified", UnifiedDelay, false, 2, 1},
		{"multiplex", 0, true, 2, 2},
		{"multiplexUnified", UnifiedDelay, true, 4, 2},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var count atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodHead {
					t.Errorf("got %s, want HEAD", r.Method)
				}
				count.Add(1)
				w.WriteHeader(http.StatusNoContent)
			}))
			defer server.Close()
			dialer := &countedDialer{Dialer: N.SystemDialer}
			_, err := Measure(t.Context(), server.URL, &testOutbound{Dialer: dialer, multiplex: tc.multiplex}, tc.flags)
			if err != nil {
				t.Fatal(err)
			}
			if got := count.Load(); got != tc.requests {
				t.Fatalf("requests %d, want %d", got, tc.requests)
			}
			if got := dialer.count.Load(); got != tc.dials {
				t.Fatalf("dials %d, want %d", got, tc.dials)
			}
		})
	}
}

func TestUnifiedDelayExcludesFirstResponse(t *testing.T) {
	var count atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if count.Add(1) == 1 {
			timer := time.NewTimer(160 * time.Millisecond)
			<-timer.C
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	delay, err := Measure(t.Context(), server.URL, N.SystemDialer, UnifiedDelay)
	if err != nil {
		t.Fatal(err)
	}
	if count.Load() != 2 {
		t.Fatal("unified delay did not send two HEAD requests")
	}
	if delay >= 120 {
		t.Fatalf("first response included in delay: %dms", delay)
	}
}

func TestRedirectIsNotFollowed(t *testing.T) {
	var count atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		count.Add(1)
		w.Header().Set("Location", "http://127.0.0.1:1/must-not-dial")
		w.WriteHeader(http.StatusFound)
	}))
	defer server.Close()
	if _, err := Measure(t.Context(), server.URL, N.SystemDialer, UnifiedDelay); err != nil {
		t.Fatal(err)
	}
	if count.Load() != 2 {
		t.Fatalf("requests %d", count.Load())
	}
}

type blockedDialer struct {
	closed chan struct{}
	conn   net.Conn
}

func (d *blockedDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	a, b := net.Pipe()
	d.conn = a
	go func() { defer close(d.closed); defer b.Close(); _, _ = io.Copy(io.Discard, b) }()
	return a, nil
}
func (*blockedDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("unused")
}

func TestTimeoutClosesBlockedConnection(t *testing.T) {
	d := &blockedDialer{closed: make(chan struct{})}
	_, err := Run(t.Context(), &testOutbound{Dialer: d}, "http://example.test/", 70, UnifiedDelay)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected deadline, got %v", err)
	}
	select {
	case <-d.closed:
	case <-time.After(time.Second):
		t.Fatal("timed out probe leaked its connection")
	}
}

func TestParentCancellationAndInvalidTimeout(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	_, err := RunDirect(ctx, "http://example.test/", 1000, 0)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled scope returned %v", err)
	}
	for _, timeout := range []int32{0, -1} {
		if _, err := RunDirect(t.Context(), "http://example.test/", timeout, 0); err == nil {
			t.Fatal("invalid timeout accepted")
		}
	}
}

func TestInvalidURLAndDialFailure(t *testing.T) {
	for _, link := range []string{"file:///tmp/secret", "https:///missing-host", "%"} {
		if _, err := Measure(t.Context(), link, N.SystemDialer, 0); err == nil {
			t.Fatalf("invalid URL accepted: %s", link)
		}
	}
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	address := server.URL
	server.Close()
	if _, err := Measure(t.Context(), address, N.SystemDialer, 0); err == nil {
		t.Fatal("dial failure swallowed")
	}
}

type closeProbeBody struct {
	io.Reader
	closed bool
}

func (b *closeProbeBody) Close() error { b.closed = true; return nil }
func TestWarmupResponseDrainedAndClosed(t *testing.T) {
	body := &closeProbeBody{Reader: &oneByteReader{}}
	if err := closeResponse(&http.Response{Body: body}); err != nil {
		t.Fatal(err)
	}
	if !body.closed {
		t.Fatal("warmup response body not closed")
	}
	buf := make([]byte, 1)
	if _, err := body.Read(buf); !errors.Is(err, io.EOF) {
		t.Fatalf("warmup body not drained: %v", err)
	}
}

type oneByteReader struct{ read bool }

func (r *oneByteReader) Read(p []byte) (int, error) {
	if r.read {
		return 0, io.EOF
	}
	r.read = true
	p[0] = 1
	return 1, nil
}

// husi resets the timer after DialContext only for an early-writing connection;
// it does not arbitrarily subtract all TLS/network handshake time.
type earlyTestConn struct{ net.Conn }

func (*earlyTestConn) NeedHandshakeForWrite() bool { return true }

type delayedEarlyDialer struct {
	N.Dialer
	early bool
}

func (d *delayedEarlyDialer) DialContext(ctx context.Context, network string, address M.Socksaddr) (net.Conn, error) {
	timer := time.NewTimer(180 * time.Millisecond)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-timer.C:
	}
	conn, err := d.Dialer.DialContext(ctx, network, address)
	if err != nil {
		return nil, err
	}
	if d.early {
		return &earlyTestConn{Conn: conn}, nil
	}
	return conn, nil
}
func TestIgnoreHandshakeUsesHusiEarlyConnectionSemantics(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) }))
	defer server.Close()
	for _, tc := range []struct {
		name    string
		early   bool
		flags   Flags
		exclude bool
	}{
		{"ordinary", false, 0, false},
		{"flagDoesNotExcludeOrdinaryDial", false, IgnoreHandshakeTime, false},
		{"earlyWithoutFlag", true, 0, false},
		{"earlyWithFlag", true, IgnoreHandshakeTime, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			delay, err := Measure(t.Context(), server.URL, &delayedEarlyDialer{Dialer: N.SystemDialer, early: tc.early}, tc.flags)
			if err != nil {
				t.Fatal(err)
			}
			if tc.exclude && delay >= 140 {
				t.Fatalf("early dial included: %dms", delay)
			}
			if !tc.exclude && delay < 170 {
				t.Fatalf("dial incorrectly excluded: %dms", delay)
			}
		})
	}
}

func TestHistoryStoresExactlyReturnedLatency(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) }))
	defer server.Close()
	history := boxurltest.NewHistoryStorage()
	ctx := service.ContextWithPtr(t.Context(), history)
	outbound := &testOutbound{Dialer: N.SystemDialer}
	delay, err := Run(ctx, outbound, server.URL, 2000, UnifiedDelay)
	if err != nil {
		t.Fatal(err)
	}
	stored := history.LoadURLTestHistory(outbound.Tag())
	if stored == nil || int32(stored.Delay) != delay {
		t.Fatalf("history %v does not match returned delay %d", stored, delay)
	}
	history.DeleteURLTestHistory(outbound.Tag())
	canceled, cancel := context.WithCancel(ctx)
	cancel()
	if _, err := Run(canceled, outbound, server.URL, 2000, UnifiedDelay); !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v", err)
	}
	if history.LoadURLTestHistory(outbound.Tag()) != nil {
		t.Fatal("canceled URLTest published history")
	}
}

type testSelector struct {
	*testOutbound
	selected adapter.Outbound
}

func (*testSelector) Type() string                       { return "selector" }
func (s *testSelector) All() []string                    { return []string{s.selected.Tag()} }
func (s *testSelector) Selected(string) adapter.Outbound { return s.selected }
func (*testSelector) AttachConnection(io.Closer) func()  { return func() {} }
func TestSelectorWarmsMultiplexSelectedNode(t *testing.T) {
	var count atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { count.Add(1); w.WriteHeader(http.StatusNoContent) }))
	defer server.Close()
	node := &testOutbound{Dialer: N.SystemDialer, multiplex: true}
	selector := &testSelector{testOutbound: &testOutbound{Dialer: node}, selected: node}
	if _, err := Measure(t.Context(), server.URL, selector, UnifiedDelay); err != nil {
		t.Fatal(err)
	}
	if count.Load() != 4 {
		t.Fatalf("selected multiplex node not warmed: %d requests", count.Load())
	}
}

func TestUnifiedDelayRedialsWhenServerClosesWarmup(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		w.Header().Set("Connection", "close")
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	dialer := &countedDialer{Dialer: N.SystemDialer}
	if _, err := Measure(t.Context(), server.URL, dialer, UnifiedDelay); err != nil {
		t.Fatal(err)
	}
	if requests.Load() != 2 || dialer.count.Load() != 2 {
		t.Fatalf("requests/dials=%d/%d, want 2/2", requests.Load(), dialer.count.Load())
	}
}

type namedTestOutbound struct {
	*testOutbound
	name string
}

func (o *namedTestOutbound) Tag() string { return o.name }
func TestTimeoutNeverPublishesHistory(t *testing.T) {
	history := boxurltest.NewHistoryStorage()
	ctx := service.ContextWithPtr(t.Context(), history)
	d := &blockedDialer{closed: make(chan struct{})}
	outbound := &namedTestOutbound{testOutbound: &testOutbound{Dialer: d}, name: "timed-out"}
	if _, err := Run(ctx, outbound, "http://example.test/", 60, UnifiedDelay); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("got %v", err)
	}
	select {
	case <-d.closed:
	case <-time.After(time.Second):
		t.Fatal("probe still open")
	}
	if history.LoadURLTestHistory(outbound.Tag()) != nil {
		t.Fatal("deadline result published as success")
	}
}
