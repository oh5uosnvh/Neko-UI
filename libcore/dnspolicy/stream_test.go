package dnspolicy

import (
	"context"
	"errors"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
)

func localTCPDNS(t *testing.T, handler dns.HandlerFunc) uint16 {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	started := make(chan struct{})
	server := &dns.Server{Listener: listener, Handler: handler, NotifyStartedFunc: func() { close(started) }}
	go func() { _ = server.ActivateAndServe() }()
	<-started
	t.Cleanup(func() { _ = server.Shutdown() })
	return uint16(listener.Addr().(*net.TCPAddr).Port)
}
func tcpUpstream(t *testing.T, tag string, port uint16) adapter.DNSTransport {
	t.Helper()
	upstream, err := newTCP(context.Background(), nil, tag, option.RemoteDNSServerOptions{
		DNSServerAddressOptions: option.DNSServerAddressOptions{Server: "127.0.0.1", ServerPort: port},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := upstream.Start(adapter.StartStateStart); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = upstream.Close() })
	return upstream
}
func TestRealTCPPolicyFallbackAndAlias(t *testing.T) {
	var privateCalls, publicCalls atomic.Int32
	privatePort := localTCPDNS(t, func(w dns.ResponseWriter, q *dns.Msg) {
		privateCalls.Add(1)
		if q.Question[0].Name != "target.private.test." {
			t.Errorf("private DNS received unrelated name: %s", q.Question[0].Name)
		}
		_ = w.WriteMsg(addressReply(q, "192.0.2.71"))
	})
	publicPort := localTCPDNS(t, func(w dns.ResponseWriter, q *dns.Msg) {
		publicCalls.Add(1)
		_ = w.WriteMsg(addressReply(q, "192.0.2.72"))
	})
	raw, err := New(context.Background(), nil, "scope", Options{
		Fallback: []string{"public"}, Hosts: map[string][]string{"node.test": {"target.private.test"}},
		Rules: []Rule{{"+.private.test", []string{"private"}}},
	})
	if err != nil {
		t.Fatal(err)
	}
	resolver := raw.(*Transport)
	resolver.upstreams = map[string]adapter.DNSTransport{"private": tcpUpstream(t, "private", privatePort), "public": tcpUpstream(t, "public", publicPort)}
	q := testQuery("node.test", dns.TypeA)
	response, err := resolver.Exchange(context.Background(), q)
	if err != nil {
		t.Fatal(err)
	}
	assertCNAME(t, response.Answer[0], "node.test", "target.private.test")
	assertAddressRecords(t, response.Answer[1:], "target.private.test", "192.0.2.71")
	if publicCalls.Load() != 0 || privateCalls.Load() != 1 {
		t.Fatal("private lookup leaked")
	}
	response, err = resolver.Exchange(context.Background(), testQuery("ordinary.test", dns.TypeA))
	if err != nil {
		t.Fatal(err)
	}
	assertAddressRecords(t, response.Answer, "ordinary.test", "192.0.2.72")
	if publicCalls.Load() != 1 || privateCalls.Load() != 1 {
		t.Fatal("ordinary lookup hit private DNS")
	}
}
func TestRealSilentTCPServerTimesOutAndTriesNextPrivateServer(t *testing.T) {
	// Accept the TCP connection and read the request, then send no response.
	silentPort := localTCPDNS(t, func(_ dns.ResponseWriter, _ *dns.Msg) {})
	goodPort := localTCPDNS(t, func(w dns.ResponseWriter, q *dns.Msg) { _ = w.WriteMsg(addressReply(q, "192.0.2.73")) })
	raw, err := New(context.Background(), nil, "scope", Options{Fallback: []string{"public"}, Timeout: badoption.Duration(60 * time.Millisecond),
		Rules: []Rule{{"+.private.test", []string{"silent", "good"}}},
	})
	if err != nil {
		t.Fatal(err)
	}
	resolver := raw.(*Transport)
	resolver.upstreams = map[string]adapter.DNSTransport{"silent": tcpUpstream(t, "silent", silentPort), "good": tcpUpstream(t, "good", goodPort)}
	begin := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	response, err := resolver.Exchange(ctx, testQuery("node.private.test", dns.TypeA))
	if err != nil {
		t.Fatal(err)
	}
	assertAddressRecords(t, response.Answer, "node.private.test", "192.0.2.73")
	if elapsed := time.Since(begin); elapsed < 40*time.Millisecond || elapsed > time.Second {
		t.Fatalf("unbounded timeout: %s", elapsed)
	}
}
func TestRealTCPCancellationClosesBlockedConnection(t *testing.T) {
	port := localTCPDNS(t, func(_ dns.ResponseWriter, _ *dns.Msg) {})
	upstream := tcpUpstream(t, "silent", port)
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Millisecond)
	defer cancel()
	begin := time.Now()
	_, err := upstream.Exchange(ctx, testQuery("node.private.test", dns.TypeA))
	if err == nil {
		t.Fatal("expected cancellation/read deadline")
	}
	if time.Since(begin) > time.Second {
		t.Fatal("blocked after context cancellation")
	}
	if !errors.Is(ctx.Err(), context.DeadlineExceeded) {
		t.Fatal("query failed before its timeout")
	}
}

func TestTCPReusesConnectionWithoutKeepingCancelledSocket(t *testing.T) {
	addresses := make(chan string, 2)
	port := localTCPDNS(t, func(w dns.ResponseWriter, q *dns.Msg) {
		addresses <- w.RemoteAddr().String()
		_ = w.WriteMsg(addressReply(q, "192.0.2.18"))
	})
	transport := tcpUpstream(t, "reuse", port)
	for _, name := range []string{"first.test", "second.test"} {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		_, err := transport.Exchange(ctx, testQuery(name, dns.TypeA))
		cancel()
		if err != nil {
			t.Fatal(err)
		}
	}
	first, second := <-addresses, <-addresses
	if first != second {
		t.Fatalf("TCP DNS was not reused: %s != %s", first, second)
	}
	if got := len(transport.(*streamTransport).idle); got != 1 {
		t.Fatalf("idle connections %d", got)
	}
	_ = transport.Close()
	if len(transport.(*streamTransport).idle) != 0 {
		t.Fatal("Close leaked pooled connections")
	}
}
