package dnspolicy

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	boxservice "github.com/sagernet/sing-box/adapter/service"
	boxdns "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/dns/transport/fakeip"
	"github.com/sagernet/sing-box/dns/transport/hosts"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing-box/protocol/group"
	httpInbound "github.com/sagernet/sing-box/protocol/http"
	mixed "github.com/sagernet/sing-box/protocol/mixed"
	redirect "github.com/sagernet/sing-box/protocol/redirect"
	"github.com/sagernet/sing-box/protocol/socks"
	tunInbound "github.com/sagernet/sing-box/protocol/tun"
	"github.com/sagernet/sing/service"
)

func schema14Context() context.Context {
	dr := boxdns.NewTransportRegistry()
	RegisterTransport(dr)
	transport.RegisterUDP(dr)
	transport.RegisterTCP(dr)
	transport.RegisterTLS(dr)
	transport.RegisterHTTPS(dr)
	local.RegisterTransport(dr)
	hosts.RegisterTransport(dr)
	fakeip.RegisterTransport(dr)
	or := outbound.NewRegistry()
	direct.RegisterOutbound(or)
	group.RegisterSelector(or)
	socks.RegisterOutbound(or)
	ir := inbound.NewRegistry()
	mixed.RegisterInbound(ir)
	tunInbound.RegisterInbound(ir)
	socks.RegisterInbound(ir)
	httpInbound.RegisterInbound(ir)
	direct.RegisterInbound(ir)
	redirect.RegisterRedirect(ir)
	redirect.RegisterTProxy(ir)
	return service.ContextWithDefaultRegistry(box.Context(context.Background(), ir, or,
		endpoint.NewRegistry(), dr, boxservice.NewRegistry(), certificate.NewRegistry()))
}

func startSchema14(t *testing.T, content []byte) {
	t.Helper()
	ctx := schema14Context()
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, content); err != nil {
		t.Fatal("DNS 1.14 decode:", err)
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		t.Fatal("DNS 1.14 create:", err)
	}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		t.Fatal("DNS 1.14 start:", err)
	}
}

// Contract examples are NOT claimed to be App output. Actual App output is
// consumed by TestConfigBuilderDNS14Fixtures after the Robolectric export.
func TestDNS14TypedServersAndPredefined(t *testing.T) {
	startSchema14(t, []byte(`{
  "log":{"disabled":true},
  "dns":{"servers":[
   {"type":"local","tag":"dns-local"},
   {"type":"udp","tag":"dns-direct","server":"192.0.2.53","domain_resolver":{"server":"dns-local","strategy":"prefer_ipv4"}},
   {"type":"https","tag":"dns-remote","server":"resolver.example","server_port":8443,"path":"/custom","domain_resolver":{"server":"dns-direct","strategy":"prefer_ipv4"}},
   {"type":"neko-tcp","tag":"tcp","server":"resolver.example","server_port":5353,"domain_resolver":"dns-direct"},
   {"type":"neko-tls","tag":"tls","server":"192.0.2.53","server_port":8853},
   {"type":"hosts","tag":"dns-hosts","predefined":{"app.example":["203.0.113.8"]}},
   {"type":"fakeip","tag":"dns-fake","inet4_range":"198.18.0.0/15","inet6_range":"fc00::/18"}
  ],"rules":[{"domain":["block.test"],"action":"predefined","rcode":"NOERROR"},
    {"domain":["app.example"],"server":"dns-hosts"},
    {"query_type":["A","AAAA"],"server":"dns-fake","disable_cache":true}],"final":"dns-remote"},
  "outbounds":[{"type":"direct","tag":"direct"}],
  "route":{"default_domain_resolver":{"server":"dns-direct","strategy":"prefer_ipv4"},"final":"direct"}
 }`))
}

func TestDNS14RejectsRemovedAppShapes(t *testing.T) {
	for _, content := range []string{
		`{"dns":{"fakeip":{"enabled":true}}}`,
		`{"dns":{"servers":[{"tag":"direct","address":"local"}]}}`,
		`{"dns":{"rules":[{"domain":["block.test"],"action":"predefined","rcode":"NOERROR","disable_cache":true}]}}`,
	} {
		var options option.Options
		if err := options.UnmarshalJSONContext(schema14Context(), []byte(content)); err == nil {
			t.Fatalf("removed/invalid schema accepted: %s", content)
		}
	}
}

func TestDNS14SelectorSubscriptionAndLocalPaths(t *testing.T) {
	for _, config := range []string{
		`{"log":{"disabled":true},"dns":{"servers":[
    {"type":"local","tag":"dns-local"},
    {"type":"local","tag":"dns-direct","detour":"direct"},
    {"type":"local","tag":"dns-remote","detour":"direct"}
   ],"final":"dns-remote"},"outbounds":[{"type":"direct","tag":"direct"}],
   "route":{"default_domain_resolver":{"server":"dns-direct","strategy":"prefer_ipv4"}}}`,
		`{"log":{"disabled":true},"dns":{"servers":[
    {"type":"local","tag":"dns-local"},
    {"type":"neko-tcp","tag":"sub-up","server":"resolver.example","server_port":5353,"domain_resolver":"dns-local"},
    {"type":"neko-tls","tag":"sub-tls","server":"192.0.2.53","server_port":8853},
    {"type":"neko-policy","tag":"sub-policy","fallback":["sub-up","sub-tls"],"hosts":{"app.example":["203.0.113.8"]}},
    {"type":"neko-selector","tag":"dns-direct","selector":"proxy","choices":{"node":"sub-policy"}},
    {"type":"https","tag":"dns-remote","server":"resolver.example","server_port":443,"path":"/dns-query","domain_resolver":{"server":"dns-direct","strategy":"prefer_ipv4"},"detour":"proxy"},
    {"type":"neko-selector","tag":"dns-selector-general","selector":"proxy","choices":{"node":"sub-policy"}}
   ],"rules":[{"domain":["app.example"],"server":"dns-selector-general","disable_cache":true}],"final":"dns-remote"},
   "outbounds":[{"type":"selector","tag":"proxy","outbounds":["node"],"default":"node"},
    {"type":"socks","tag":"node","server":"192.0.2.1","server_port":1080},{"type":"direct","tag":"direct"}],
   "route":{"default_domain_resolver":{"server":"dns-direct","strategy":"prefer_ipv4","disable_cache":true}}}`,
	} {
		startSchema14(t, []byte(config))
	}
}

// Decode the exact DNS subtree produced by buildConfig, with the real outbound
// graph for neko-selector. Android TUN/listeners/cache paths are intentionally
// excluded: this is DNS decoder+startup acceptance, not an Android VPN test.
func TestConfigBuilderDNS14Fixtures(t *testing.T) {
	directory := os.Getenv("NEKO_DNS_FIXTURE_DIR")
	if directory == "" {
		t.Skip("set NEKO_DNS_FIXTURE_DIR to Robolectric App fixture export directory")
	}
	for _, name := range []string{"forTest", "runtime-fakeip", "subscription-selector-tcp-tls", "block-predefined", "runtime-local", "subscription-single-hosts"} {
		t.Run(name, func(t *testing.T) {
			content, err := os.ReadFile(filepath.Join(directory, name+".json"))
			if err != nil {
				t.Fatal(err)
			}
			var app map[string]json.RawMessage
			if err = json.Unmarshal(content, &app); err != nil {
				t.Fatal(err)
			}
			if len(app["dns"]) == 0 || len(app["outbounds"]) == 0 {
				t.Fatal("missing App DNS/outbound graph")
			}
			// Validate the entire App document before the DNS startup projection.
			// Excluding inbounds here previously hid removed sniff/domain fields.
			var fullOptions option.Options
			if err := fullOptions.UnmarshalJSONContext(schema14Context(), content); err != nil {
				t.Fatal("FULL App config 1.14 decode:", err)
			}
			var dns map[string]json.RawMessage
			if err = json.Unmarshal(app["dns"], &dns); err != nil {
				t.Fatal(err)
			}
			if dns["fakeip"] != nil || dns["independent_cache"] != nil {
				t.Fatal("legacy top-level DNS options")
			}
			var servers []map[string]json.RawMessage
			if err = json.Unmarshal(dns["servers"], &servers); err != nil {
				t.Fatal(err)
			}
			for _, server := range servers {
				if server["type"] == nil || server["address"] != nil || server["address_resolver"] != nil || server["strategy"] != nil {
					t.Fatal("legacy DNS server:", server)
				}
			}
			var rules []map[string]json.RawMessage
			if err = json.Unmarshal(dns["rules"], &rules); err != nil {
				t.Fatal(err)
			}
			for _, rule := range rules {
				if rule["outbound"] != nil {
					t.Fatal("legacy outbound DNS rule")
				}
			}
			var route map[string]json.RawMessage
			if err = json.Unmarshal(app["route"], &route); err != nil {
				t.Fatal(err)
			}
			resolver := route["default_domain_resolver"]
			if len(resolver) == 0 {
				t.Fatal("missing route.default_domain_resolver")
			}
			projection, err := json.Marshal(map[string]any{
				"log": map[string]bool{"disabled": true}, "dns": app["dns"], "outbounds": app["outbounds"],
				"route": map[string]any{"default_domain_resolver": resolver},
			})
			if err != nil {
				t.Fatal(err)
			}
			startSchema14(t, projection)
		})
	}
}
