package dnspolicy

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	boxdns "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
)

func TestDoTAndDoHPreserveTLSNameAndCustomPath(t *testing.T) {
	cert, certificate := testCertificate(t, "resolver.example")
	tlsOptions := &option.OutboundTLSOptions{Enabled: true, ServerName: "resolver.example", Certificate: []string{certificate}}
	var handshakes atomic.Int32
	listener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}, GetConfigForClient: func(*tls.ClientHelloInfo) (*tls.Config, error) { handshakes.Add(1); return nil, nil }})
	if err != nil {
		t.Fatal(err)
	}
	started := make(chan struct{})
	server := &dns.Server{Listener: listener, Handler: dns.HandlerFunc(func(w dns.ResponseWriter, q *dns.Msg) { _ = w.WriteMsg(addressReply(q, "192.0.2.91")) }), NotifyStartedFunc: func() { close(started) }}
	go func() { _ = server.ActivateAndServe() }()
	<-started
	t.Cleanup(func() { _ = server.Shutdown() })
	dot, err := newTLS(context.Background(), nil, "dot", option.RemoteTLSDNSServerOptions{
		RemoteDNSServerOptions:      option.RemoteDNSServerOptions{DNSServerAddressOptions: option.DNSServerAddressOptions{Server: "127.0.0.1", ServerPort: uint16(listener.Addr().(*net.TCPAddr).Port)}},
		OutboundTLSOptionsContainer: option.OutboundTLSOptionsContainer{TLS: tlsOptions},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err = dot.Start(adapter.StartStateStart, adapter.NewScope(context.Background(), nil)); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	response, err := dot.Exchange(ctx, testQuery("node.private.test", dns.TypeA))
	if err != nil {
		t.Fatal(err)
	}
	assertAddressRecords(t, response.Answer, "node.private.test", "192.0.2.91")
	for _, name := range []string{"second.private.test", "third.private.test"} {
		if _, err := dot.Exchange(ctx, testQuery(name, dns.TypeA)); err != nil {
			t.Fatal(err)
		}
	}
	if handshakes.Load() != 1 {
		t.Fatalf("DoT repeats TLS handshakes: %d", handshakes.Load())
	}

	var calls atomic.Int32
	path := "/dns-query/TEST%2DPATH"
	dohServer := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.EscapedPath() != path {
			t.Errorf("custom DoH path changed: %s", r.URL.EscapedPath())
		}
		if r.TLS == nil || r.TLS.ServerName != "resolver.example" {
			t.Error("DoH SNI changed")
		}
		raw, err := io.ReadAll(r.Body)
		if err != nil {
			w.WriteHeader(400)
			return
		}
		q := new(dns.Msg)
		if err = q.Unpack(raw); err != nil {
			w.WriteHeader(400)
			return
		}
		reply, err := addressReply(q, "192.0.2.92").Pack()
		if err != nil {
			w.WriteHeader(500)
			return
		}
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(reply)
	}))
	dohServer.TLS = &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"http/1.1"}}
	dohServer.StartTLS()
	doh, err := transport.NewHTTPS(context.Background(), log.NewNOPFactory().NewLogger("test"), "doh", option.RemoteHTTPSDNSServerOptions{
		RemoteTLSDNSServerOptions: option.RemoteTLSDNSServerOptions{
			RemoteDNSServerOptions:      option.RemoteDNSServerOptions{DNSServerAddressOptions: option.DNSServerAddressOptions{Server: "127.0.0.1", ServerPort: uint16(dohServer.Listener.Addr().(*net.TCPAddr).Port)}},
			OutboundTLSOptionsContainer: option.OutboundTLSOptionsContainer{TLS: tlsOptions},
		}, Path: path,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err = doh.Start(adapter.StartStateStart, adapter.NewScope(context.Background(), nil)); err != nil {
		t.Fatal(err)
	}
	failed := &stubDNSTransport{TransportAdapter: boxdns.NewTransportAdapter("mock", "failed", nil), exchange: func(context.Context, *dns.Msg) (*dns.Msg, error) { return nil, fmt.Errorf("offline") }}
	rawResolver, err := New(context.Background(), nil, "private", Options{Fallback: []string{"unused-public"}, Rules: []Rule{{"+.private.test", []string{"failed", "doh"}}}, Timeout: badoption.Duration(time.Second)})
	if err != nil {
		t.Fatal(err)
	}
	resolver := rawResolver.(*Transport)
	resolver.upstreams = map[string]adapter.DNSTransport{"failed": failed, "doh": doh}
	response, err = resolver.Exchange(ctx, testQuery("node.private.test", dns.TypeA))
	if err != nil {
		t.Fatal(err)
	}
	assertAddressRecords(t, response.Answer, "node.private.test", "192.0.2.92")
	if calls.Load() != 1 {
		t.Fatal("unexpected DoH query count")
	}
}
