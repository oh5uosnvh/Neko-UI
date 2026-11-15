package dnspolicy

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

// batch is deliberately NOT registered: the import boundary must translate it.
func TestImportedBatchRegistryAndCoreFallback(t *testing.T) {
	ctx := schema14Context()
	var invalid option.Options
	if err := invalid.UnmarshalJSONContext(ctx, []byte(`{"dns":{"servers":[{"type":"batch","tag":"old","servers":["local"]}]}}`)); err == nil || !strings.Contains(err.Error(), "unknown transport type: batch") {
		t.Fatal("source batch unexpectedly accepted by core:", err)
	}
	port := localTCPDNS(t, func(w dns.ResponseWriter, q *dns.Msg) { _ = w.WriteMsg(addressReply(q, "192.0.2.91")) })
	config, err := json.Marshal(map[string]any{
		"log": map[string]bool{"disabled": true},
		"dns": map[string]any{"final": "imported", "servers": []any{
			map[string]any{"type": "hosts", "tag": "negative", "predefined": map[string][]string{"unused.example.invalid": {"192.0.2.1"}}},
			map[string]any{"type": "neko-tcp", "tag": "backup", "server": "127.0.0.1", "server_port": port},
			map[string]any{"type": "neko-policy", "tag": "imported", "fallback": []string{"negative", "backup"}, "domain_resolver": map[string]any{"server": "backup", "strategy": "ipv4_only"}, "detour": "direct"},
		}},
		"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	var options option.Options
	if err = options.UnmarshalJSONContext(ctx, config); err != nil {
		t.Fatal("converted policy decode:", err)
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal("converted policy start:", err)
	}
	manager := service.FromContext[adapter.DNSTransportManager](ctx)
	resolver, exists := manager.Transport("imported")
	if !exists {
		t.Fatal("lost imported resolver tag")
	}
	reply, err := resolver.Exchange(context.Background(), testQuery("node.example.invalid", dns.TypeA))
	if err != nil {
		t.Fatal("fallback failed:", err)
	}
	assertAddressRecords(t, reply.Answer, "node.example.invalid", "192.0.2.91")
}

func TestImportedBatchAll44NodeConfigsDecode(t *testing.T) {
	directory := os.Getenv("NEKO_JSON_DNS_FIXTURE_DIR")
	if directory == "" {
		t.Skip("set NEKO_JSON_DNS_FIXTURE_DIR to Android import fixture export")
	}
	for i := 1; i <= 44; i++ {
		t.Run(fmt.Sprintf("node-%d", i), func(t *testing.T) {
			content, err := os.ReadFile(filepath.Join(directory, fmt.Sprintf("all-%d.json", i)))
			if err != nil {
				t.Fatal(err)
			}
			var options option.Options
			if err = options.UnmarshalJSONContext(schema14Context(), content); err != nil {
				t.Fatal("complete imported node config rejected:", err)
			}
		})
	}
}

// No document is handwritten here: Kotlin exports the complete ConfigBuilder
// output for runtime, URL test, export, multi-node probe and selector paths.
func TestImportedBatchConfigBuilderFixtures(t *testing.T) {
	directory := os.Getenv("NEKO_JSON_DNS_FIXTURE_DIR")
	if directory == "" {
		t.Skip("set NEKO_JSON_DNS_FIXTURE_DIR to Android import fixture export")
	}
	names := []string{"node-1-test", "node-1-runtime", "node-1-export", "node-2-test", "node-2-runtime", "node-2-export", "batch-test", "group-selector"}
	for _, name := range names {
		t.Run(name, func(t *testing.T) {
			content, err := os.ReadFile(filepath.Join(directory, name+".json"))
			if err != nil {
				t.Fatal(err)
			}
			var full option.Options
			if err = full.UnmarshalJSONContext(schema14Context(), content); err != nil {
				t.Fatal("FULL ConfigBuilder decode:", err)
			}
			var raw map[string]json.RawMessage
			if err = json.Unmarshal(content, &raw); err != nil {
				t.Fatal(err)
			}
			var dnsOptions struct {
				Servers []map[string]json.RawMessage `json:"servers"`
			}
			if err = json.Unmarshal(raw["dns"], &dnsOptions); err != nil {
				t.Fatal(err)
			}
			policies := 0
			for _, server := range dnsOptions.Servers {
				if string(server["type"]) == `"batch"` {
					t.Fatal("unconverted batch")
				}
				if string(server["type"]) == `"neko-policy"` {
					policies++
					if server["fallback"] == nil || server["servers"] != nil || server["strategy"] != nil || server["idle_timeout"] != nil {
						t.Fatal("invalid converted policy")
					}
				}
			}
			if policies == 0 {
				t.Fatal("DNS dependency graph was dropped")
			}
			// TUN/listeners are Android-owned. Keep the exact DNS and outbound
			// graph and only project away listeners for host startup acceptance.
			var route map[string]json.RawMessage
			if err = json.Unmarshal(raw["route"], &route); err != nil {
				t.Fatal(err)
			}
			projection, err := json.Marshal(map[string]any{
				"log": map[string]bool{"disabled": true}, "dns": raw["dns"], "outbounds": raw["outbounds"],
				"route": map[string]any{"default_domain_resolver": route["default_domain_resolver"]},
			})
			if err != nil {
				t.Fatal(err)
			}
			startSchema14(t, projection)
		})
	}
}
