package dnspolicy

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"

	"github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	boxservice "github.com/sagernet/sing-box/adapter/service"
	boxdns "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/direct"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

// Decode/start using the exact pinned core, not a permissive JSON validator.
func TestPinnedCoreConfigResolverAndIndependentCache(t *testing.T) {
	privatePort := localTCPDNS(t, func(w dns.ResponseWriter, q *dns.Msg) {
		_ = w.WriteMsg(addressReply(q, "192.0.2.81"))
	})
	generalPort := localTCPDNS(t, func(w dns.ResponseWriter, q *dns.Msg) {
		_ = w.WriteMsg(addressReply(q, "192.0.2.82"))
	})
	dnsRegistry := boxdns.NewTransportRegistry()
	RegisterTransport(dnsRegistry)
	outRegistry := outbound.NewRegistry()
	direct.RegisterOutbound(outRegistry)
	inRegistry := inbound.NewRegistry()
	direct.RegisterInbound(inRegistry)
	ctx := box.Context(context.Background(), inRegistry, outRegistry, endpoint.NewRegistry(), dnsRegistry, boxservice.NewRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	config := fmt.Sprintf(`{
      "log":{"disabled":true},
      "dns":{"independent_cache":true,"servers":[
        {"type":"neko-tcp","tag":"private-up","server":"127.0.0.1","server_port":%d},
        {"type":"neko-tcp","tag":"default-up","server":"127.0.0.1","server_port":%d},
        {"type":"neko-policy","tag":"group-a","fallback":["default-up"],"hosts":{"same.example":["192.0.2.11"],"dial.example":["127.0.0.1"]},"rules":[{"pattern":"+.private.test","servers":["private-up"]}],"server_rules":[]},
        {"type":"neko-policy","tag":"group-b","fallback":["default-up"],"hosts":{"same.example":["192.0.2.12"]}}
      ],"final":"default-up"},
      "outbounds":[{"type":"direct","tag":"direct","domain_resolver":{"server":"group-a","strategy":"ipv4_only"}}],
      "route":{"rules":[{"inbound":["external-map"],"action":"resolve","server":"group-a","strategy":"ipv4_only"}],"final":"direct"}
    }`, privatePort, generalPort)
	var opts option.Options
	if err := opts.UnmarshalJSONContext(ctx, []byte(config)); err != nil {
		t.Fatal("core rejected generated schema:", err)
	}
	instance, err := box.New(box.Options{Options: opts, Context: ctx})
	if err != nil {
		t.Fatal("core creation:", err)
	}
	t.Cleanup(func() { _ = instance.Close() })
	if err = instance.Start(); err != nil {
		t.Fatal("core start:", err)
	}
	manager := service.FromContext[adapter.DNSTransportManager](ctx)
	router := service.FromContext[adapter.DNSRouter](ctx)
	if manager == nil || router == nil {
		t.Fatal("core services missing")
	}
	for _, tc := range []struct{ tag, host, address string }{
		{"group-a", "same.example", "192.0.2.11"}, {"group-b", "same.example", "192.0.2.12"},
		{"group-a", "same.example", "192.0.2.11"}, {"group-b", "same.example", "192.0.2.12"},
		{"group-a", "node.private.test", "192.0.2.81"}, {"group-b", "ordinary.test", "192.0.2.82"},
	} {
		transport, ok := manager.Transport(tc.tag)
		if !ok {
			t.Fatal("missing resolver")
		}
		ips, err := router.Lookup(ctx, tc.host, adapter.DNSQueryOptions{Transport: transport})
		if err != nil {
			t.Fatal(err)
		}
		if len(ips) == 0 || ips[0] != netip.MustParseAddr(tc.address) {
			t.Fatalf("cache crossed resolver: %s %s => %v", tc.tag, tc.host, ips)
		}
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err == nil {
			_, _ = conn.Write([]byte("OK"))
			_ = conn.Close()
		}
	}()
	outManager := service.FromContext[adapter.OutboundManager](ctx)
	directOutbound, ok := outManager.Outbound("direct")
	if !ok {
		t.Fatal("direct outbound missing")
	}
	dialCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	conn, err := directOutbound.DialContext(dialCtx, "tcp", M.ParseSocksaddrHostPort("dial.example", uint16(listener.Addr().(*net.TCPAddr).Port)))
	if err != nil {
		t.Fatal("outbound ignored domain_resolver:", err)
	}
	defer conn.Close()
	_ = conn.SetReadDeadline(time.Now().Add(time.Second))
	buf := make([]byte, 2)
	if _, err = io.ReadFull(conn, buf); err != nil || string(buf) != "OK" {
		t.Fatal("mapped connection failed", err)
	}
}

func TestCustomDirectDNSCanBootstrapLegacyRemoteDNS(t *testing.T) {
	port := localTCPDNS(t, func(w dns.ResponseWriter, q *dns.Msg) { _ = w.WriteMsg(addressReply(q, "192.0.2.99")) })
	dr := boxdns.NewTransportRegistry()
	RegisterTransport(dr)
	transport.RegisterHTTPS(dr)
	or := outbound.NewRegistry()
	direct.RegisterOutbound(or)
	ctx := box.Context(context.Background(), inbound.NewRegistry(), or, endpoint.NewRegistry(), dr, boxservice.NewRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	config := fmt.Sprintf(`{
      "log":{"disabled":true},
      "dns":{"servers":[
        {"type":"neko-tcp","tag":"direct-up","server":"127.0.0.1","server_port":%d},
        {"type":"neko-policy","tag":"dns-direct","fallback":["direct-up"]},
        {"type":"https","tag":"dns-remote","server":"resolver.example","domain_resolver":"dns-direct"}
      ],"final":"dns-remote"},
      "outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}
    }`, port)
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, []byte(config)); err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		t.Fatal("legacy bootstrap creation failed:", err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
}
