//go:build !android && !with_naive_outbound && with_openvpn && with_openconnect && with_tailscale && with_cloudflared

package libcore

import (
	"context"
	"encoding/json"
	"reflect"
	"slices"
	"testing"

	fastup "github.com/oh5uosnvh/fastup-mod/fastup"
	oppa "github.com/oh5uosnvh/oppa-mod/oppa"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"libcore/protocol/juicity"
	snellcompat "libcore/protocol/snell"
	"libcore/protocol/x365"
	"libcore/protocol/xhttp"
)

// Registry/schema compatibility only: this does not claim a remote gateway works.
func TestNekoBoxHostProtocolRegistries(t *testing.T) {
	type registry interface {
		OptionTypes() []string
		CreateOptions(string) (any, bool)
	}
	check := func(name string, registry registry, want []string) {
		t.Helper()
		got := registry.OptionTypes()
		want = slices.Clone(want)
		slices.Sort(want)
		if !slices.Equal(got, want) {
			t.Errorf("%s registry: got %v, want %v", name, got, want)
		}
		for _, typ := range want {
			if options, ok := registry.CreateOptions(typ); !ok || options == nil {
				t.Errorf("%s %q has no options", name, typ)
			}
		}
		t.Logf("%s (%d): %v", name, len(got), got)
	}
	out := nekoboxAndroidOutboundRegistry()
	check("outbound", out, []string{"direct", "bridge", "block", "dns", "selector", "urltest", "socks", "http", "shadowsocks", "shadowsocksr", "vmess", "trojan", "tor", "ssh", "shadowtls", "vless", "anytls", "snell", "x365", "xhttp", "fastup", "oppa", "hysteria", "tuic", "hysteria2", "juicity"})
	check("inbound", nekoboxAndroidInboundRegistry(), []string{"tun", "redirect", "tproxy", "direct", "socks", "http", "mixed", "cloudflared"})
	check("endpoint", nekoboxAndroidEndpointRegistry(), []string{"wireguard", "openvpn-client", "openvpn-server", "openconnect", "tailscale"})
	check("dns", nekoboxAndroidDNSTransportRegistry(nil), []string{"tcp", "udp", "tls", "https", "hosts", "neko-policy", "neko-selector", "neko-tcp", "neko-tls", "local", "fakeip", "quic", "h3", "openvpn", "openconnect", "tailscale"})
	check("certificate", nekoboxAndroidCertificateRegistry(), []string{"tailscale"})
	check("service", nekoboxAndroidServiceRegistry(), []string{"derp"})
	for typ, want := range map[string]any{
		"fastup": &fastup.FastupOutboundOptions{}, "oppa": &oppa.Options{},
		"x365": &x365.X365OutboundOptions{}, "xhttp": &xhttp.XhttpOutboundOptions{},
		"juicity": &juicity.JuicityOutboundOptions{}, "vless": &option.VLESSOutboundOptions{},
		"anytls": &option.AnyTLSOutboundOptions{}, "shadowsocks": &option.ShadowsocksOutboundOptions{},
		"snell": &snellcompat.Options{},
	} {
		got, _ := out.CreateOptions(typ)
		if reflect.TypeOf(got) != reflect.TypeOf(want) {
			t.Errorf("%q option type = %T, want %T", typ, got, want)
		}
	}
}

// Core 1.14 closes resources through Scope, not the old Close method. These
// checks intentionally expose production migration gaps rather than hiding
// them behind a registration-only smoke test.
func TestNekoBoxHostPrivateLifecycleContracts(t *testing.T) {
	for name, outbound := range map[string]any{
		"x365":    &x365.Outbound{},
		"juicity": &juicity.Outbound{},
	} {
		if _, ok := outbound.(interface {
			Start(adapter.StartStage, *adapter.Scope) error
		}); !ok {
			t.Errorf("%s still only has legacy Close; core 1.14 outbound.Manager skips non-Lifecycle resources", name)
		}
	}
	if _, ok := any(&x365.Outbound{}).(adapter.InterfaceUpdateListener); !ok {
		t.Error("x365 InterfaceUpdated() lacks context.Context; core 1.14 will not reset its transport on interface changes")
	}
}

// Exercise option decoding, actual constructors and all startup stages. AnyTLS
// uses reflection into the replacement client, so compile-only is insufficient.
func TestNekoBoxHostPrivateConstructors(t *testing.T) {
	registry := nekoboxAndroidOutboundRegistry()
	logger := log.NewNOPFactory().Logger()
	fixtures := map[string]string{
		"fastup":      `{"server":"127.0.0.1","server_port":443,"password":"fixture#fastup","mpw":"fixture-mpw","tls":{"enabled":true}}`,
		"oppa":        `{"server":"127.0.0.1","server_port":443,"password":"fixture","tls":{"enabled":true}}`,
		"anytls":      `{"server":"127.0.0.1","server_port":443,"password":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef#SL","tls":{"enabled":true}}`,
		"shadowsocks": `{"server":"127.0.0.1","server_port":443,"method":"chacha20-ietf-poly1305","password":"fixture#vt"}`,
		"snell":       `{"server":"127.0.0.1","server_port":443,"version":4,"psk":"fixture","reuse":true}`,
		"x365":        `{"server":"127.0.0.1","server_port":443,"uuid":"00112233-4455-6677-8899-aabbccddeeff"}`,
		"vless":       `{"server":"127.0.0.1","server_port":443,"uuid":"00112233-4455-6677-8899-aabbccddeeff#x365"}`,
		"xhttp":       `{"server":"127.0.0.1","server_port":443,"password":"fixture","auth":"010203"}`,
		"juicity":     `{"server":"127.0.0.1","server_port":443,"uuid":"00112233-4455-6677-8899-aabbccddeeff","password":"fixture","tls":{"enabled":true}}`,
	}
	for typ, fixture := range fixtures {
		t.Run(typ, func(t *testing.T) {
			options, ok := registry.CreateOptions(typ)
			if !ok {
				t.Fatal("options absent")
			}
			if err := json.Unmarshal([]byte(fixture), options); err != nil {
				t.Fatal(err)
			}
			out, err := registry.CreateOutbound(context.Background(), nil, logger, "fixture", typ, options)
			if err != nil {
				t.Fatal(err)
			}
			if lifecycle, ok := out.(adapter.Lifecycle); ok {
				scope := adapter.NewScope(context.Background(), logger)
				defer scope.Close()
				for _, stage := range adapter.ListStartStages {
					if err := scope.Start("fixture", lifecycle, stage); err != nil {
						t.Fatal(err)
					}
				}
			} else if closer, ok := out.(interface{ Close() error }); ok {
				defer closer.Close()
			}
			t.Logf("%s constructed %T", typ, out)
		})
	}
}
