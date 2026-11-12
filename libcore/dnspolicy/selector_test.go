package dnspolicy

import (
	"context"
	"fmt"
	"net/netip"
	"testing"

	"github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	boxservice "github.com/sagernet/sing-box/adapter/service"
	boxdns "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing/service"
)

func TestPinnedCoreSelectorDispatchKeepsGroupHostsAndFallbackIsolated(t *testing.T) {
	port := localTCPDNS(t, func(w dns.ResponseWriter, q *dns.Msg) { _ = w.WriteMsg(addressReply(q, "192.0.2.99")) })
	registry := boxdns.NewTransportRegistry()
	RegisterTransport(registry)
	out := outbound.NewRegistry()
	direct.RegisterOutbound(out)
	group.RegisterSelector(out)
	ctx := box.Context(context.Background(), inbound.NewRegistry(), out, endpoint.NewRegistry(), registry, boxservice.NewRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	config := fmt.Sprintf(`{
 "log":{"disabled":true},
 "dns":{"independent_cache":true,"servers":[
 {"type":"neko-tcp","tag":"remote","server":"127.0.0.1","server_port":%d},
 {"type":"neko-policy","tag":"a-dns","fallback":["remote"],"hosts":{"same.test":["192.0.2.11"],"a-only.test":["192.0.2.21"]}},
 {"type":"neko-policy","tag":"b-dns","fallback":["remote"],"hosts":{"same.test":["192.0.2.12"]}},
 {"type":"neko-selector","tag":"dispatch","selector":"proxy","choices":{"a":"a-dns","b":"b-dns"}}
 ],"rules":[{"domain_suffix":["test"],"server":"dispatch","disable_cache":true}],"final":"remote"},
 "outbounds":[{"type":"selector","tag":"proxy","outbounds":["a","b"],"default":"a"},{"type":"direct","tag":"a"},{"type":"direct","tag":"b"}],
 "route":{"final":"proxy"}}`, port)
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, []byte(config)); err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = instance.Close() })
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	outbound, _ := instance.Outbound().Outbound("proxy")
	selector := outbound.(*group.Selector)
	router := service.FromContext[adapter.DNSRouter](ctx)
	for _, tc := range []struct{ node, host, ip string }{
		{"a", "same.test", "192.0.2.11"}, {"b", "same.test", "192.0.2.12"}, {"a", "same.test", "192.0.2.11"},
		{"b", "a-only.test", "192.0.2.99"}, {"a", "a-only.test", "192.0.2.21"}, {"b", "same.test", "192.0.2.12"},
	} {
		if !selector.SelectOutbound(tc.node) {
			t.Fatal("native selector rejected compiled node")
		}
		ips, err := router.Lookup(ctx, tc.host, adapter.DNSQueryOptions{})
		valid := err == nil && len(ips) > 0
		for _, ip := range ips {
			valid = valid && ip == netip.MustParseAddr(tc.ip)
		}
		if !valid {
			t.Fatalf("%s %s: %v %v", tc.node, tc.host, ips, err)
		}
	}
	if selector.SelectOutbound("missing") {
		t.Fatal("nonmember falsely acknowledged")
	}
}

func TestSelectorTransportRejectsMissingMembershipInsteadOfBorrowingAnotherGroup(t *testing.T) {
	if _, err := newSelector(context.Background(), nil, "dns", SelectorOptions{Selector: "proxy", Choices: map[string]string{"a": "dns"}}); err == nil {
		t.Fatal("recursive resolver accepted")
	}
	if _, err := newSelector(context.Background(), nil, "dns", SelectorOptions{Selector: "proxy"}); err == nil {
		t.Fatal("empty resolver graph accepted")
	}
}
