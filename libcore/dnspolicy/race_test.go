package dnspolicy

import (
	"context"
	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	boxdns "github.com/sagernet/sing-box/dns"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func TestHedgeAvoidsSlowFirstAndRemembersFastWinner(t *testing.T) {
	var slowCalls, fastCalls, publicCalls atomic.Int32
	var active atomic.Int32
	slow := &stubDNSTransport{TransportAdapter: boxdns.NewTransportAdapter("mock", "slow", nil), exchange: func(ctx context.Context, q *dns.Msg) (*dns.Msg, error) {
		slowCalls.Add(1)
		active.Add(1)
		defer active.Add(-1)
		<-ctx.Done()
		return nil, ctx.Err()
	}}
	fast := &stubDNSTransport{TransportAdapter: boxdns.NewTransportAdapter("mock", "fast", nil), exchange: func(ctx context.Context, q *dns.Msg) (*dns.Msg, error) {
		fastCalls.Add(1)
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(5 * time.Millisecond):
			return addressReply(q, "192.0.2.33"), nil
		}
	}}
	public := &stubDNSTransport{TransportAdapter: boxdns.NewTransportAdapter("mock", "public", nil), exchange: func(ctx context.Context, q *dns.Msg) (*dns.Msg, error) {
		publicCalls.Add(1)
		return addressReply(q, "192.0.2.99"), nil
	}}
	raw, err := New(context.Background(), nil, "scope", Options{Fallback: []string{"public"}, Rules: []Rule{{"+.private.test", []string{"slow", "fast"}}}})
	if err != nil {
		t.Fatal(err)
	}
	resolver := raw.(*Transport)
	resolver.upstreams = map[string]adapter.DNSTransport{"slow": slow, "fast": fast, "public": public}
	start := time.Now()
	q := testQuery("node.private.test", dns.TypeA)
	r, err := resolver.Exchange(context.Background(), q)
	cold := time.Since(start)
	if err != nil {
		t.Fatal(err)
	}
	assertAddressRecords(t, r.Answer, "node.private.test", "192.0.2.33")
	if cold >= 700*time.Millisecond {
		t.Fatalf("still waiting for full slow timeout: %s", cold)
	}
	start = time.Now()
	_, err = resolver.Exchange(context.Background(), testQuery("other.private.test", dns.TypeA))
	warm := time.Since(start)
	if err != nil {
		t.Fatal(err)
	}
	if warm >= hedgeDelay {
		t.Fatalf("did not remember fast winner: %s", warm)
	}
	if publicCalls.Load() != 0 || slowCalls.Load() != 1 || fastCalls.Load() != 2 {
		t.Fatalf("wrong attempts slow=%d fast=%d public=%d", slowCalls.Load(), fastCalls.Load(), publicCalls.Load())
	}
	deadline := time.NewTimer(time.Second)
	defer deadline.Stop()
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	for active.Load() != 0 {
		select {
		case <-deadline.C:
			t.Fatal("loser query not cancelled")
		case <-ticker.C:
		}
	}
	t.Logf("serial baseline timeout=%s; cold hedge=%s; warm preferred=%s", resolver.timeout, cold, warm)
}
func TestWrongFamilyOrCnameOnlyCannotWin(t *testing.T) {
	for _, kind := range []string{"AAAA", "CNAME"} {
		t.Run(kind, func(t *testing.T) {
			first := func(_ context.Context, q *dns.Msg) (*dns.Msg, error) {
				r := negativeReply(q, dns.RcodeSuccess)
				if kind == "AAAA" {
					r.Answer = []dns.RR{&dns.AAAA{Hdr: dns.RR_Header{Name: q.Question[0].Name, Rrtype: dns.TypeAAAA, Class: dns.ClassINET, Ttl: 30}, AAAA: net.ParseIP("2001:db8::1")}}
				} else {
					r.Answer = []dns.RR{&dns.CNAME{Hdr: dns.RR_Header{Name: q.Question[0].Name, Rrtype: dns.TypeCNAME, Class: dns.ClassINET, Ttl: 30}, Target: "missing.test."}}
				}
				return r, nil
			}
			f := newTransportFixture(t, Options{Fallback: []string{"unused"}, Rules: []Rule{{"private.test", []string{"first", "second"}}}}, map[string]testExchangeFunc{"first": first, "second": nil})
			r := exchangeForTest(t, f, testQuery("private.test", dns.TypeA))
			assertAddressRecords(t, r.Answer, "private.test", "192.0.2.53")
			assertCalls(t, f, "first", "second")
		})
	}
}
