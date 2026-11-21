package libcore

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	stdjson "encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/filemanager"
)

const configFixtureTarget = "v1.15.0-alpha.13"

var configFixtureScenarios = []string{
	"hev-ipv4-sniff-off", "hev-dual-sniff-on", "hev-ipv6-sniff-override-resolve",
	"native-ipv4-system-sniff-off", "native-dual-mixed-sniff-on-resolve", "native-ipv6-gvisor-sniff-override",
	"hev-fakeip", "hev-local-dns-hosts", "hev-domain-bootstrap-dns",
	"hev-subscription-single", "hev-subscription-selector", "hev-custom-merge",
}

type configFixtureEntry struct {
	Name              string `json:"name"`
	SHA256            string `json:"sha256"`
	Bytes             int    `json:"bytes"`
	Scenario          string `json:"scenario"`
	ForTest           bool   `json:"for_test"`
	HostLifecycle     bool   `json:"host_lifecycle"`
	DeviceTunRequired bool   `json:"device_tun_required"`
}

type configFixtureManifest struct {
	SchemaVersion int                  `json:"schema_version"`
	Target        string               `json:"target_sing_box"`
	Producer      string               `json:"producer"`
	SourceHashes  map[string]string    `json:"source_sha256"`
	Fixtures      []configFixtureEntry `json:"fixtures"`
}

// Use EVERY production registry, including custom protocols/DNS and certificate providers.
// This is intentionally in package libcore: no cloned or minimal registry can drift green.
func configFixtureContext(ctx context.Context, localTransport LocalDNSTransport) context.Context {
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry(),
	)
	return option.ContextWithNekoCompatibility(service.ContextWithDefaultRegistry(ctx))
}

func configFixtureRegistryContext(ctx context.Context, localTransport LocalDNSTransport) context.Context {
	// Strict upstream controls deliberately do NOT opt into production compatibility.
	return service.ContextWithDefaultRegistry(box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry(),
	))
}

// Exercise the Android local-DNS registration branch during decode without using Android APIs.
type configFixtureLocalDNS struct{}

func (*configFixtureLocalDNS) Raw() bool            { return false }
func (*configFixtureLocalDNS) NetworkHandle() int64 { return 0 }
func (*configFixtureLocalDNS) Lookup(*ExchangeContext, string, string) error {
	return errors.New("decode-only provider unexpectedly queried")
}
func (*configFixtureLocalDNS) Exchange(*ExchangeContext, []byte) error {
	return errors.New("decode-only provider unexpectedly queried")
}

func configFixtureRead(t *testing.T) (configFixtureManifest, map[string][]byte) {
	t.Helper()
	dir := os.Getenv("NEKO_CONFIG_FIXTURE_DIR")
	if dir == "" {
		t.Fatal("NEKO_CONFIG_FIXTURE_DIR is required; missing fixtures are a FAILURE, not Skip")
	}
	raw, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
	if err != nil || len(raw) == 0 {
		t.Fatalf("missing/empty completion manifest: %v", err)
	}
	var manifest configFixtureManifest
	if err = stdjson.Unmarshal(raw, &manifest); err != nil {
		t.Fatal(err)
	}
	if manifest.SchemaVersion != 1 || manifest.Target != configFixtureTarget || manifest.Producer != "ConfigBuilderFixtureExportTest/buildConfig(profile,false|true)" {
		t.Fatalf("unexpected fixture provenance: schema=%d target=%q producer=%q", manifest.SchemaVersion, manifest.Target, manifest.Producer)
	}
	if len(manifest.SourceHashes) < 7 {
		t.Fatal("production source identity is missing")
	}
	expected := map[string]bool{}
	for _, name := range configFixtureScenarios {
		expected[name+"-production.json"] = true
		expected[name+"-urltest.json"] = true
	}
	if len(manifest.Fixtures) != len(expected) {
		t.Fatalf("expected %d real fixtures, got %d", len(expected), len(manifest.Fixtures))
	}
	contents := map[string][]byte{}
	for _, entry := range manifest.Fixtures {
		if !expected[entry.Name] || contents[entry.Name] != nil {
			t.Fatalf("unexpected/duplicate fixture %q", entry.Name)
		}
		forTest := strings.HasSuffix(entry.Name, "-urltest.json")
		scenario := strings.TrimSuffix(strings.TrimSuffix(entry.Name, "-production.json"), "-urltest.json")
		if entry.Scenario != scenario {
			t.Fatalf("fixture scenario metadata does not match file name: %s", entry.Name)
		}
		native := strings.HasPrefix(entry.Name, "native-") && !forTest
		if entry.ForTest != forTest || entry.HostLifecycle != !native || entry.DeviceTunRequired != native {
			t.Fatalf("fixture %s was mislabeled; lifecycle gates cannot be disabled via manifest", entry.Name)
		}
		data, readErr := os.ReadFile(filepath.Join(dir, entry.Name))
		if readErr != nil || len(strings.TrimSpace(string(data))) == 0 {
			t.Fatalf("required fixture %s missing/empty: %v", entry.Name, readErr)
		}
		hash := sha256.Sum256(data)
		if len(data) != entry.Bytes || hex.EncodeToString(hash[:]) != entry.SHA256 {
			t.Fatalf("fixture %s does not match completion manifest", entry.Name)
		}
		contents[entry.Name] = data
	}
	for name := range expected {
		if contents[name] == nil {
			t.Fatalf("required fixture %s absent", name)
		}
	}
	return manifest, contents
}

func configFixtureDecode(t *testing.T, ctx context.Context, content []byte) option.Options {
	t.Helper()
	var options option.Options
	// Authoritative first gate: ORIGINAL bytes, no deletion, projection or JSON rewrite.
	if err := options.UnmarshalJSONContext(ctx, content); err != nil {
		t.Fatalf("FULL ORIGINAL CONFIG decode: %v", err)
	}
	return options
}

func configFixtureRule(t *testing.T, content string) []option.HeadlessRule {
	t.Helper()
	var rules []option.HeadlessRule
	if err := stdjson.Unmarshal([]byte(content), &rules); err != nil {
		t.Fatal(err)
	}
	return rules
}

func configFixtureGeoProviders(t *testing.T) map[string]int {
	t.Helper()
	previousSite, previousIP := nekoutils.GetGeoSiteHeadlessRules, nekoutils.GetGeoIPHeadlessRules
	calls := map[string]int{}
	nekoutils.GetGeoSiteHeadlessRules = func(code string) ([]option.HeadlessRule, error) {
		calls["geosite:"+code]++
		switch code {
		case "cn":
			return configFixtureRule(t, `[{"domain_suffix":["cn.fixture.example"]}]`), nil
		case "category-ads-all":
			return configFixtureRule(t, `[{"domain_suffix":["ads.fixture.example"]}]`), nil
		default:
			return nil, fmt.Errorf("unexpected test geo code %s", code)
		}
	}
	nekoutils.GetGeoIPHeadlessRules = func(code string) ([]option.HeadlessRule, error) {
		calls["geoip:"+code]++
		if code != "cn" {
			return nil, fmt.Errorf("unexpected test geo code %s", code)
		}
		return configFixtureRule(t, `[{"ip_cidr":["198.51.100.0/24"]}]`), nil
	}
	t.Cleanup(func() { nekoutils.GetGeoSiteHeadlessRules, nekoutils.GetGeoIPHeadlessRules = previousSite, previousIP })
	return calls
}

func configFixtureHostContext(t *testing.T) context.Context {
	t.Helper()
	root := t.TempDir()
	base := filepath.Join(root, "no_backup")
	for _, dir := range []string{base, filepath.Join(root, "cache"), filepath.Join(root, "files"), filepath.Join(base, "rules"), filepath.Join(root, "tmp")} {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	source := []byte(`{"version":5,"rules":[{"domain_suffix":["source.fixture.example"]}]}`)
	if err := os.WriteFile(filepath.Join(base, "rules/fixture-source.json"), source, 0o600); err != nil {
		t.Fatal(err)
	}
	binaryFile, err := os.Create(filepath.Join(base, "rules/fixture-binary.srs"))
	if err != nil {
		t.Fatal(err)
	}
	err = srs.Write(binaryFile, option.PlainRuleSet{Rules: configFixtureRule(t, `[{"ip_cidr":["203.0.113.0/24"]}]`)}, C.RuleSetVersionCurrent)
	closeErr := binaryFile.Close()
	if err != nil || closeErr != nil {
		t.Fatalf("test SRS resource creation: %v / %v", err, closeErr)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	ctx = configFixtureContext(ctx, nil) // Production host local transport; NOT an Android platform mock.
	return filemanager.WithDefault(ctx, base, filepath.Join(root, "tmp"), os.Getuid(), os.Getgid())
}

func configFixtureAssertRuleLoaded(t *testing.T, instance *box.Box, tag, match, mismatch string) {
	t.Helper()
	set, exists := instance.Router().RuleSet(tag)
	if !exists {
		t.Fatalf("full route initialization failed to load %s", tag)
	}
	metadata := adapter.InboundContext{Destination: M.ParseSocksaddrHostPort(match, 443), Domain: match}
	if !set.Match(&metadata) {
		t.Fatalf("loaded %s did not match controlled positive %s", tag, match)
	}
	metadata = adapter.InboundContext{Destination: M.ParseSocksaddrHostPort(mismatch, 443), Domain: mismatch}
	if set.Match(&metadata) {
		t.Fatalf("loaded %s silently matches unrelated destination %s", tag, mismatch)
	}
}

func testConfigBuilderFixturesFullDecode(t *testing.T) {
	manifest, contents := configFixtureRead(t)
	for _, entry := range manifest.Fixtures {
		t.Run(entry.Name, func(t *testing.T) {
			// Both local-DNS branches share the same complete production registry set.
			for _, localTransport := range []LocalDNSTransport{nil, &configFixtureLocalDNS{}} {
				configFixtureDecode(t, configFixtureContext(context.Background(), localTransport), contents[entry.Name])
			}
			if entry.DeviceTunRequired {
				t.Log("FULL Android TUN configuration decoded; Android VPN/OpenTun/Start is NOT device-validated")
			}
		})
	}
}

func testConfigBuilderFixturesHostLifecycle(t *testing.T) {
	manifest, contents := configFixtureRead(t)
	for _, entry := range manifest.Fixtures {
		t.Run(entry.Name, func(t *testing.T) {
			ctx := configFixtureHostContext(t)
			options := configFixtureDecode(t, ctx, contents[entry.Name])
			if entry.DeviceTunRequired {
				// This entry still passed the entire original-byte decode above. A separate
				// Android device gate owns its Start; never drop its inbounds to pretend green.
				t.Log("BOUNDARY: native Android TUN Start requires real device; host gate = full decode ONLY")
				return
			}
			calls := configFixtureGeoProviders(t)
			instance, err := box.New(box.Options{Context: ctx, Options: options})
			if err != nil {
				t.Fatalf("FULL ORIGINAL CONFIG New: %v", err)
			}
			closed := false
			t.Cleanup(func() {
				if !closed {
					if err := instance.Close(); err != nil {
						t.Errorf("cleanup Close: %v", err)
					}
				}
			})
			if !entry.ForTest {
				for _, tag := range []string{"geosite:cn", "geoip:cn", "geosite:category-ads-all"} {
					if calls[tag] != 1 {
						t.Fatalf("full New must consume geo alias provider %s exactly once; got %d (never create literal alias files)", tag, calls[tag])
					}
				}
				configFixtureAssertRuleLoaded(t, instance, "geosite:cn", "www.cn.fixture.example", "unrelated.example")
				configFixtureAssertRuleLoaded(t, instance, "geoip:cn", "198.51.100.7", "192.0.2.7")
				configFixtureAssertRuleLoaded(t, instance, "geosite:category-ads-all", "ads.fixture.example", "unrelated.example")
			} else if len(calls) != 0 {
				t.Fatalf("URLTest should not load default geo resources: %v", calls)
			}
			if entry.Scenario == "hev-custom-merge" {
				if !entry.ForTest {
					configFixtureAssertRuleLoaded(t, instance, "fixture-source", "source.fixture.example", "unrelated.example")
				}
				configFixtureAssertRuleLoaded(t, instance, "fixture-binary", "203.0.113.7", "192.0.2.7")
			}
			if err = instance.Start(); err != nil {
				t.Fatalf("FULL ORIGINAL CONFIG Start: %v", err)
			}
			if err = instance.Close(); err != nil {
				t.Fatalf("FULL ORIGINAL CONFIG Close: %v", err)
			}
			closed = true
			t.Log("FULL original JSON New -> Start -> Close passed; geo resources = controlled hooks, not APK databases; no proxy handshake asserted")
		})
	}
}

func testConfigBuilderFixturesGeoResourceErrors(t *testing.T) {
	_, contents := configFixtureRead(t)
	original := contents["hev-ipv4-sniff-off-production.json"]
	for _, testCase := range []string{"missing-geosite-provider", "geosite-provider-error", "geoip-provider-error"} {
		t.Run(testCase, func(t *testing.T) {
			ctx := configFixtureHostContext(t)
			options := configFixtureDecode(t, ctx, original) // Do not obscure schema failures behind an expected resource error.
			configFixtureGeoProviders(t)
			switch testCase {
			case "missing-geosite-provider":
				nekoutils.GetGeoSiteHeadlessRules = nil
			case "geosite-provider-error":
				nekoutils.GetGeoSiteHeadlessRules = func(string) ([]option.HeadlessRule, error) {
					return nil, errors.New("fixture-geosite-resource-unavailable")
				}
			case "geoip-provider-error":
				nekoutils.GetGeoIPHeadlessRules = func(string) ([]option.HeadlessRule, error) {
					return nil, errors.New("fixture-geoip-resource-unavailable")
				}
			}
			instance, err := box.New(box.Options{Context: ctx, Options: options})
			if instance != nil {
				_ = instance.Close()
			}
			if err == nil {
				t.Fatal("New silently accepted missing geo provider/resource on original App default route")
			}
			expected := "geosite:"
			if testCase == "geoip-provider-error" {
				expected = "geoip:cn"
			}
			if !strings.Contains(err.Error(), expected) {
				t.Fatalf("resource error must preserve geo alias context %q: %v", expected, err)
			}
			if strings.HasSuffix(testCase, "provider-error") {
				marker := "fixture-geosite-resource-unavailable"
				if testCase == "geoip-provider-error" {
					marker = "fixture-geoip-resource-unavailable"
				}
				if !strings.Contains(err.Error(), marker) {
					t.Fatalf("provider error was not propagated: %v", err)
				}
			}
		})
	}
}

// Explicit hand-written controls are NOT App fixtures and never replace either App gate.
func testConfigBuilderFixtureDecoderControls(t *testing.T) {
	ctx := configFixtureRegistryContext(context.Background(), nil)
	for _, legacy := range []struct{ name, original, message string }{
		{"legacy-dns-address", `{"dns":{"servers":[{"tag":"local","address":"local"}]}}`, "legacy DNS server"},
		{"legacy-dns-fakeip", `{"dns":{"fakeip":{"enabled":true,"inet4_range":"198.18.0.0/15"}}}`, "legacy DNS fakeip"},
		{"legacy-mixed-sniff", `{"inbounds":[{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":32080,"sniff":true}]}`, "removed"},
		{"legacy-mixed-resolve", `{"inbounds":[{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":32080,"domain_strategy":"ipv4_only"}]}`, "removed"},
	} {
		t.Run(legacy.name, func(t *testing.T) {
			var options option.Options
			err := options.UnmarshalJSONContext(ctx, []byte(legacy.original))
			if err == nil || !strings.Contains(err.Error(), legacy.message) {
				t.Fatalf("legacy negative control expected %q, got %v", legacy.message, err)
			}
		})
	}
	t.Run("hand-written-modern-schema-control", func(t *testing.T) {
		ctx := configFixtureHostContext(t)
		original := []byte(`{"dns":{"servers":[{"type":"local","tag":"local"}],"final":"local"},"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"default_domain_resolver":"local","final":"direct"},"experimental":{"cache_file":{"enabled":true,"path":"../cache/control.db"}}}`)
		options := configFixtureDecode(t, ctx, original)
		instance, err := box.New(box.Options{Context: ctx, Options: options})
		if err != nil {
			t.Fatal(err)
		}
		defer instance.Close()
		if err = instance.Start(); err != nil {
			t.Fatal(err)
		}
		if err = instance.Close(); err != nil {
			t.Fatal(err)
		}
	})
}

// CI libcore pre-export job may explicitly exclude ONLY this top-level test with
// -skip '^TestPre26AppConfigFixtures$'; the post-JVM build job must run it without
// -skip. Missing fixtures always fail here, and no Skip/Skipf exists in this file.
func TestPre26AppConfigFixtures(t *testing.T) {
	configFixtureRead(t)
	t.Log("App gates use complete production registries + option.ContextWithNekoCompatibility; upstream strict acceptance is NOT claimed")
	if !t.Run("FullOriginalDecode", testConfigBuilderFixturesFullDecode) {
		return
	}
	if !t.Run("StrictDecoderControls", testConfigBuilderFixtureDecoderControls) {
		return
	}
	if !t.Run("FullOriginalHostLifecycle", testConfigBuilderFixturesHostLifecycle) {
		return
	}
	t.Run("GeoResourceErrors", testConfigBuilderFixturesGeoResourceErrors)
}
