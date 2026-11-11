package modeharness

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	mdns "github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	boxservice "github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	_ "github.com/sagernet/sing-box/experimental/clashapi"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing-box/protocol/mixed"
	"github.com/sagernet/sing-box/protocol/socks"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

type fixture struct {
	b     *box.Box
	clash adapter.ClashServer
	dns   adapter.DNSRouter
	port  int
}

func freePort(t *testing.T) int {
	t.Helper()
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	p := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return p
}
func newFixture(t *testing.T, rules, dnsConfig, cache string, initialMode ...string) *fixture {
	t.Helper()
	ir := inbound.NewRegistry()
	mixed.RegisterInbound(ir)
	or := outbound.NewRegistry()
	direct.RegisterOutbound(or)
	socks.RegisterOutbound(or)
	dr := dns.NewTransportRegistry()
	transport.RegisterUDP(dr)
	ctx := box.Context(context.Background(), ir, or, endpoint.NewRegistry(), dr, boxservice.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	p := freePort(t)
	config := fmt.Sprintf(`{"log":{"disabled":true},"inbounds":[{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":%d}],"outbounds":[{"type":"direct","tag":"rule"},{"type":"direct","tag":"global"}],"route":{"rules":%s,"final":"rule"},"dns":%s,"experimental":{"clash_api":{"default_mode":"Rule"}%s}}`, p, rules, dnsConfig, cache)
	if len(initialMode) > 0 {
		config = strings.Replace(config, `"default_mode":"Rule"`, `"default_mode":"`+initialMode[0]+`"`, 1)
	}
	var options option.Options
	if e := options.UnmarshalJSONContext(ctx, []byte(config)); e != nil {
		t.Fatal(e)
	}
	b, e := box.New(box.Options{Options: options, Context: ctx})
	if e != nil {
		t.Fatal(e)
	}
	if e = b.Start(); e != nil {
		b.Close()
		t.Fatal(e)
	}
	t.Cleanup(func() { b.Close() })
	return &fixture{b, service.FromContext[adapter.ClashServer](ctx), service.FromContext[adapter.DNSRouter](ctx), p}
}

const modeRules = `[{"clash_mode":"Global","action":"route","outbound":"global"},{"clash_mode":"Rule","action":"route","outbound":"rule"}]`

// The destination IP/port is overridden by each branch, so the response proves
// actual outbound routing rather than just reading back the mode string.
func TestMixedRuleGlobalRuleAndLongConnection(t *testing.T) {
	var ruleHits, globalHits atomic.Int64
	server := func(label string, hits *atomic.Int64) *httptest.Server {
		return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1); fmt.Fprint(w, label) }))
	}
	a := server("RULE", &ruleHits)
	defer a.Close()
	b := server("GLOBAL", &globalHits)
	defer b.Close()
	ap := a.Listener.Addr().(*net.TCPAddr).Port
	bp := b.Listener.Addr().(*net.TCPAddr).Port
	rules := fmt.Sprintf(`[{"clash_mode":"Global","action":"route","outbound":"global","override_address":"127.0.0.1","override_port":%d},{"clash_mode":"Rule","action":"route","outbound":"rule","override_address":"127.0.0.1","override_port":%d}]`, bp, ap)
	f := newFixture(t, rules, `{}`, "")
	proxyURL, _ := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", f.port))
	tr := &http.Transport{Proxy: http.ProxyURL(proxyURL), DisableKeepAlives: true}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, Timeout: 5 * time.Second}
	check := func(want string) {
		t.Helper()
		r, e := client.Get("http://route.test/marker")
		if e != nil {
			t.Fatal(e)
		}
		got, e := io.ReadAll(r.Body)
		r.Body.Close()
		if e != nil || string(got) != want {
			t.Fatalf("got %q (%v), want %s", got, e, want)
		}
	}
	check("RULE")
	// Keep an HTTP CONNECT tunnel open before switching. Each request on it
	// remains on the original endpoint, while fresh mixed traffic switches.
	c, e := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", f.port), 5*time.Second)
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(10 * time.Second))
	fmt.Fprint(c, "CONNECT route.test:80 HTTP/1.1\r\nHost: route.test:80\r\n\r\n")
	reader := bufio.NewReader(c)
	resp, e := http.ReadResponse(reader, &http.Request{Method: "CONNECT"})
	if e != nil || resp.StatusCode != 200 {
		t.Fatalf("CONNECT: %v %v", resp, e)
	}
	tunnelCheck := func() {
		t.Helper()
		fmt.Fprint(c, "GET /old HTTP/1.1\r\nHost: route.test\r\n\r\n")
		r, e := http.ReadResponse(reader, &http.Request{Method: "GET"})
		if e != nil {
			t.Fatal(e)
		}
		v, e := io.ReadAll(r.Body)
		r.Body.Close()
		if e != nil || string(v) != "RULE" {
			t.Fatalf("old tunnel moved/closed: %q %v", v, e)
		}
	}
	tunnelCheck()
	for _, step := range []struct{ mode, want string }{{"Global", "GLOBAL"}, {"Rule", "RULE"}} {
		if !f.clash.SetMode(step.mode) {
			t.Fatal("mode refused")
		}
		check(step.want)
		tunnelCheck()
	}
	before := f.clash.Mode()
	if f.clash.SetMode("INVALID") || f.clash.Mode() != before {
		t.Fatal("invalid mode changed state")
	}
	check("RULE")
	if ruleHits.Load() != 6 || globalHits.Load() != 1 {
		t.Fatalf("unexpected endpoint hits rule=%d global=%d", ruleHits.Load(), globalHits.Load())
	}
	t.Logf("same box=%p mixed=127.0.0.1:%d Rule->Global->Rule; old CONNECT survived; endpoint hits rule=%d global=%d", f.b, f.port, ruleHits.Load(), globalHits.Load())
}

func TestModeReadWriteRaceAndPreMatch(t *testing.T) {
	f := newFixture(t, `[{"clash_mode":"Rule","action":"reject"},{"clash_mode":"Global","outbound":"global"}]`, `{}`, "")
	var wg sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		wg.Add(1)
		go func(worker int) {
			defer wg.Done()
			for n := 0; n < 1000; n++ {
				if worker%2 == 0 {
					f.clash.SetMode([]string{"Rule", "Global"}[n%2])
				} else {
					_ = f.clash.Mode()
					list := f.clash.ModeList()
					if len(list) > 0 {
						list[0] = "mutated-copy"
					}
					if e := f.b.Router().PreMatch(adapter.InboundContext{Destination: M.ParseSocksaddr("127.0.0.1:80")}); e != nil {
						t.Error(e)
					}
				}
			}
		}(worker)
	}
	wg.Wait()
	if !f.clash.SetMode("Rule") {
		t.Fatal("Rule unavailable")
	}
	if e := f.b.Router().PreMatch(adapter.InboundContext{Destination: M.ParseSocksaddr("127.0.0.1:80")}); e != nil {
		t.Fatalf("stale early deny: %v", e)
	}
	t.Log("8 workers x 1000 mode/status/PreMatch operations completed")
}

func dnsServer(t *testing.T, ip string, entered chan<- struct{}, release <-chan struct{}) (int, *atomic.Int64) {
	t.Helper()
	pc, e := net.ListenPacket("udp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	hits := new(atomic.Int64)
	s := &mdns.Server{PacketConn: pc, Handler: mdns.HandlerFunc(func(w mdns.ResponseWriter, m *mdns.Msg) {
		hits.Add(1)
		if entered != nil && strings.HasPrefix(m.Question[0].Name, "slow.") {
			entered <- struct{}{}
			<-release
		}
		r := new(mdns.Msg)
		r.SetReply(m)
		rr, _ := mdns.NewRR(m.Question[0].Name + " 300 IN A " + ip)
		r.Answer = []mdns.RR{rr}
		w.WriteMsg(r)
	})}
	go s.ActivateAndServe()
	t.Cleanup(func() { s.Shutdown() })
	return pc.LocalAddr().(*net.UDPAddr).Port, hits
}

func TestDNSModeCacheAndInflightIsolation(t *testing.T) {
	entered := make(chan struct{}, 1)
	release := make(chan struct{})
	a, ah := dnsServer(t, "192.0.2.1", entered, release)
	b, bh := dnsServer(t, "192.0.2.2", nil, nil)
	cfg := fmt.Sprintf(`{"servers":[{"type":"udp","tag":"a","server":"127.0.0.1","server_port":%d},{"type":"udp","tag":"b","server":"127.0.0.1","server_port":%d}],"rules":[{"clash_mode":"Rule","server":"a"},{"clash_mode":"Global","server":"b"}],"final":"a"}`, a, b)
	f := newFixture(t, modeRules, cfg, "")
	query := func(name, want string) {
		t.Helper()
		m := new(mdns.Msg)
		m.SetQuestion(name, mdns.TypeA)
		r, e := f.dns.Exchange(context.Background(), m, adapter.DNSQueryOptions{})
		if e != nil || len(r.Answer) != 1 {
			t.Fatalf("DNS %s: %v %v", name, r, e)
		}
		if !strings.HasSuffix(r.Answer[0].String(), want) {
			t.Fatalf("DNS got %s want %s", r.Answer[0], want)
		}
	}
	query("cache.test.", "192.0.2.1")
	f.clash.SetMode("Global")
	query("cache.test.", "192.0.2.2")
	f.clash.SetMode("Rule")
	query("cache.test.", "192.0.2.1")
	done := make(chan struct{})
	go func() { defer close(done); query("slow.test.", "192.0.2.1") }()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("slow query not entered")
	}
	f.clash.SetMode("Global")
	query("slow.test.", "192.0.2.2")
	close(release)
	<-done
	query("slow.test.", "192.0.2.2")
	if ah.Load() != 3 || bh.Load() != 3 {
		t.Fatalf("cache/singleflight leaked across modes: a=%d b=%d", ah.Load(), bh.Load())
	}
	t.Logf("real UDP DNS Rule/Global/Rule and old-inflight completion isolated; upstream hits a=%d b=%d", ah.Load(), bh.Load())
}

func TestHijackedDNSTCPUsesCurrentModePerMessage(t *testing.T) {
	a, _ := dnsServer(t, "192.0.2.1", nil, nil)
	b, _ := dnsServer(t, "192.0.2.2", nil, nil)
	cfg := fmt.Sprintf(`{"servers":[{"type":"udp","tag":"a","server":"127.0.0.1","server_port":%d},{"type":"udp","tag":"b","server":"127.0.0.1","server_port":%d}],"rules":[{"clash_mode":"Rule","server":"a"},{"clash_mode":"Global","server":"b"}],"final":"a"}`, a, b)
	f := newFixture(t, `[{"port":53,"action":"hijack-dns"},{"clash_mode":"Global","outbound":"global"},{"clash_mode":"Rule","outbound":"rule"}]`, cfg, "")
	c, e := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", f.port), 5*time.Second)
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(10 * time.Second))
	fmt.Fprint(c, "CONNECT 198.51.100.53:53 HTTP/1.1\r\nHost: 198.51.100.53:53\r\n\r\n")
	reader := bufio.NewReader(c)
	resp, e := http.ReadResponse(reader, &http.Request{Method: "CONNECT"})
	if e != nil || resp.StatusCode != 200 {
		t.Fatalf("CONNECT %v %v", resp, e)
	}
	for _, step := range []struct{ mode, want string }{{"Rule", "192.0.2.1"}, {"Global", "192.0.2.2"}, {"Rule", "192.0.2.1"}} {
		f.clash.SetMode(step.mode)
		m := new(mdns.Msg)
		m.SetQuestion("socket.test.", mdns.TypeA)
		wire, _ := m.Pack()
		header := []byte{byte(len(wire) >> 8), byte(len(wire))}
		if _, e = c.Write(append(header, wire...)); e != nil {
			t.Fatal(e)
		}
		if _, e = io.ReadFull(reader, header); e != nil {
			t.Fatal(e)
		}
		body := make([]byte, int(header[0])<<8|int(header[1]))
		if _, e = io.ReadFull(reader, body); e != nil {
			t.Fatal(e)
		}
		r := new(mdns.Msg)
		if e = r.Unpack(body); e != nil {
			t.Fatal(e)
		}
		if len(r.Answer) != 1 || !strings.HasSuffix(r.Answer[0].String(), step.want) {
			t.Fatalf("same DNS socket mode %s got %v", step.mode, r)
		}
	}
	t.Log("one mixed CONNECT DNS TCP socket served Rule/Global/Rule answers")
}

func TestExplicitInitialModeWinsCacheButLegacyRestores(t *testing.T) {
	path := t.TempDir() + "/core-cache.db"
	cache := fmt.Sprintf(`,"cache_file":{"enabled":true,"path":%q}`, path)
	first := newFixture(t, modeRules, `{}`, cache)
	if !first.clash.SetMode("Global") {
		t.Fatal("Global unavailable")
	}
	if e := first.b.Close(); e != nil {
		t.Fatal(e)
	}
	second := newFixture(t, modeRules, `{}`, cache)
	if second.clash.Mode() != "Rule" {
		t.Fatalf("explicit Rule overwritten by cache: %s", second.clash.Mode())
	}
	second.clash.SetMode("Global")
	second.b.Close()
	legacy := newFixture(t, modeRules, `{}`, cache, "")
	if legacy.clash.Mode() != "Global" {
		t.Fatalf("legacy raw mode not restored: %s", legacy.clash.Mode())
	}
	t.Log("explicit default_mode wins cached Global; raw omitted default still restores Global")
}
