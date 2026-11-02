package dnspolicy

import (
	"context"
	"errors"
	"fmt"
	"net"
	"reflect"
	"slices"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	boxdns "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing/common/json/badoption"
)

type testExchangeFunc func(context.Context, *dns.Msg) (*dns.Msg, error)

type stubDNSTransport struct {
	boxdns.TransportAdapter
	exchange testExchangeFunc
}

var _ adapter.DNSTransport = (*stubDNSTransport)(nil)

func (*stubDNSTransport) Start(adapter.StartStage) error { return nil }
func (*stubDNSTransport) Close() error                   { return nil }
func (s *stubDNSTransport) Exchange(ctx context.Context, q *dns.Msg) (*dns.Msg, error) {
	return s.exchange(ctx, q)
}

type transportFixture struct {
	*Transport
	calls    []string
	requests map[string][]*dns.Msg
}

func newTransportFixture(t *testing.T, options Options, handlers map[string]testExchangeFunc) *transportFixture {
	t.Helper()
	transport, err := New(context.Background(), nil, "test", options)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	f := &transportFixture{Transport: transport.(*Transport), requests: make(map[string][]*dns.Msg)}
	f.upstreams = make(map[string]adapter.DNSTransport, len(handlers))
	for tag, handler := range handlers {
		tag, handler := tag, handler
		if handler == nil {
			handler = successfulExchange
		}
		f.upstreams[tag] = &stubDNSTransport{
			TransportAdapter: boxdns.NewTransportAdapter("mock", tag, nil),
			exchange: func(ctx context.Context, q *dns.Msg) (*dns.Msg, error) {
				f.calls = append(f.calls, tag)
				f.requests[tag] = append(f.requests[tag], q.Copy())
				return handler(ctx, q)
			},
		}
	}
	return f
}

func testQuery(name string, qtype uint16) *dns.Msg {
	q := new(dns.Msg).SetQuestion(dns.Fqdn(name), qtype)
	q.Id = 0x4321
	return q
}

func negativeReply(q *dns.Msg, rcode int) *dns.Msg {
	r := new(dns.Msg).SetReply(q)
	r.Rcode = rcode
	return r
}

func addressReply(q *dns.Msg, address string) *dns.Msg {
	r := negativeReply(q, dns.RcodeSuccess)
	ip := net.ParseIP(address)
	h := dns.RR_Header{Name: q.Question[0].Name, Class: dns.ClassINET, Ttl: 60}
	if ip4 := ip.To4(); ip4 != nil {
		h.Rrtype = dns.TypeA
		r.Answer = []dns.RR{&dns.A{Hdr: h, A: ip4}}
	} else {
		h.Rrtype = dns.TypeAAAA
		r.Answer = []dns.RR{&dns.AAAA{Hdr: h, AAAA: ip}}
	}
	return r
}

func successfulExchange(_ context.Context, q *dns.Msg) (*dns.Msg, error) {
	if q.Question[0].Qtype == dns.TypeAAAA {
		return addressReply(q, "2001:db8::53"), nil
	}
	return addressReply(q, "192.0.2.53"), nil
}

func exchangeForTest(t *testing.T, f *transportFixture, q *dns.Msg) *dns.Msg {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	r, err := f.Exchange(ctx, q)
	if err != nil || r == nil {
		t.Fatalf("Exchange = (%v, %v), want a DNS response", r, err)
	}
	if !r.Response || r.Id != q.Id || !reflect.DeepEqual(r.Question, q.Question) {
		t.Fatalf("response lost original question/ID: question=%v ID=%d response=%v", r.Question, r.Id, r.Response)
	}
	return r
}

func assertCalls(t *testing.T, f *transportFixture, want ...string) {
	t.Helper()
	if !slices.Equal(f.calls, want) {
		t.Fatalf("upstream attempts = %v, want %v", f.calls, want)
	}
}

func assertAddressRecords(t *testing.T, records []dns.RR, owner string, want ...string) {
	t.Helper()
	var got []string
	for _, record := range records {
		h := record.Header()
		if h.Name != dns.Fqdn(owner) || h.Class != dns.ClassINET {
			t.Fatalf("unexpected address header: %+v", h)
		}
		switch record := record.(type) {
		case *dns.A:
			if h.Rrtype != dns.TypeA {
				t.Fatalf("A record has type %d", h.Rrtype)
			}
			got = append(got, record.A.String())
		case *dns.AAAA:
			if h.Rrtype != dns.TypeAAAA {
				t.Fatalf("AAAA record has type %d", h.Rrtype)
			}
			got = append(got, record.AAAA.String())
		default:
			t.Fatalf("unexpected address record: %T", record)
		}
	}
	if !slices.Equal(got, want) {
		t.Fatalf("addresses = %v, want %v", got, want)
	}
}

func assertCNAME(t *testing.T, record dns.RR, owner, target string) {
	t.Helper()
	cname, ok := record.(*dns.CNAME)
	if !ok || cname.Hdr.Name != dns.Fqdn(owner) || cname.Target != dns.Fqdn(target) ||
		cname.Hdr.Rrtype != dns.TypeCNAME || cname.Hdr.Class != dns.ClassINET {
		t.Fatalf("CNAME = %v, want %s -> %s", record, owner, target)
	}
}

func TestTransportPatternRouting(t *testing.T) {
	cases := []struct {
		name, pattern, query string
		match                bool
	}{
		{"exact", "private.test", "private.test", true},
		{"exact-normalized", " Private.Test. ", "PrIvAtE.TeSt.", true},
		{"exact-no-child", "private.test", "db.private.test", false},
		{"exact-no-deep-child", "private.test", "a.db.private.test", false},
		{"exact-no-prefix", "private.test", "notprivate.test", false},
		{"plus-root", "+.private.test", "private.test", true},
		{"plus-child", "+.private.test", "db.private.test", true},
		{"plus-deep-child", "+.private.test", "a.b.db.private.test", true},
		{"plus-label-boundary", "+.private.test", "notprivate.test", false},
		{"plus-no-suffix", "+.private.test", "private.test.evil.test", false},
		{"star-no-root", "*.private.test", "private.test", false},
		{"star-one-level", "*.private.test", "db.private.test", true},
		{"star-no-deep-child", "*.private.test", "a.db.private.test", false},
		{"star-label-boundary", "*.private.test", "notprivate.test", false},
		{"dot-no-root", ".private.test", "private.test", false},
		{"dot-child", ".private.test", "db.private.test", true},
		{"dot-deep-child", ".private.test", "a.b.db.private.test", true},
		{"dot-label-boundary", ".private.test", "notprivate.test", false},
		{"unrelated", "+.private.test", "public.test", false},
	}
	for _, source := range []string{"rules", "server_rules", "hosts"} {
		t.Run(source, func(t *testing.T) {
			for _, tc := range cases {
				t.Run(tc.name, func(t *testing.T) {
					options := Options{Fallback: []string{"default"}}
					rule := Rule{Pattern: tc.pattern, Servers: []string{"private"}}
					switch source {
					case "rules":
						options.Rules = []Rule{rule}
					case "server_rules":
						options.ServerRules = []Rule{rule}
					case "hosts":
						options.Hosts = map[string][]string{tc.pattern: {"192.0.2.10"}}
					}
					f := newTransportFixture(t, options, map[string]testExchangeFunc{"private": nil, "default": nil})
					r := exchangeForTest(t, f, testQuery(tc.query, dns.TypeA))
					if r.Rcode != dns.RcodeSuccess || len(r.Answer) != 1 {
						t.Fatalf("unexpected response: %v", r)
					}
					switch {
					case !tc.match:
						assertCalls(t, f, "default")
					case source == "hosts":
						assertCalls(t, f)
					default:
						assertCalls(t, f, "private")
					}
				})
			}
		})
	}
}

func TestTransportServerRulesTakePriority(t *testing.T) {
	for _, tc := range []struct{ query, want string }{
		{"db.override.private.test", "override"}, // A broader server rule beats an exact subscription rule.
		{"a.db.override.private.test", "override"},
		{"db.private.test", "private"},
		{"public.test", "default"},
	} {
		t.Run(tc.query, func(t *testing.T) {
			f := newTransportFixture(t, Options{
				Fallback: []string{"default"},
				Rules: []Rule{
					{Pattern: "db.override.private.test", Servers: []string{"private"}},
					{Pattern: "+.private.test", Servers: []string{"private"}},
				},
				ServerRules: []Rule{{Pattern: "+.override.private.test", Servers: []string{"override"}}},
			}, map[string]testExchangeFunc{"default": nil, "private": nil, "override": nil})
			exchangeForTest(t, f, testQuery(tc.query, dns.TypeA))
			assertCalls(t, f, tc.want)
		})
	}
}

func TestHostsAliasAndAddressFamilies(t *testing.T) {
	options := Options{Fallback: []string{"default"},
		Hosts: map[string][]string{"node.test": {"middle.test"}, "middle.test": {"target.test"}, "target.test": {"192.0.2.1", "192.0.2.2", "2001:db8::1"}},
	}
	f := newTransportFixture(t, options, map[string]testExchangeFunc{"default": nil})
	q := testQuery("NoDe.TeSt.", dns.TypeA)
	original := q.Copy()
	r := exchangeForTest(t, f, q)
	if !reflect.DeepEqual(original.Question, q.Question) || original.MsgHdr != q.MsgHdr || original.String() != q.String() {
		t.Fatal("mutated input question")
	}
	if len(r.Answer) != 4 {
		t.Fatalf("answers: %v", r.Answer)
	}
	assertCNAME(t, r.Answer[0], "node.test", "middle.test")
	assertCNAME(t, r.Answer[1], "middle.test", "target.test")
	assertAddressRecords(t, r.Answer[2:], "target.test", "192.0.2.1", "192.0.2.2")
	assertCalls(t, f)
	r = exchangeForTest(t, f, testQuery("node.test", dns.TypeAAAA))
	assertAddressRecords(t, r.Answer[2:], "target.test", "2001:db8::1")
	f = newTransportFixture(t, Options{Fallback: []string{"default"}, Hosts: map[string][]string{"v4.test": {"192.0.2.1"}}}, map[string]testExchangeFunc{"default": nil})
	r = exchangeForTest(t, f, testQuery("v4.test", dns.TypeAAAA))
	if len(r.Answer) != 0 || r.Rcode != dns.RcodeSuccess {
		t.Fatal("expected NODATA")
	}
	assertCalls(t, f)
}

func TestAliasUsesTargetPolicyWithoutChangingOriginalQuestion(t *testing.T) {
	f := newTransportFixture(t, Options{Fallback: []string{"default"},
		Hosts: map[string][]string{"node.test": {"target.private.test"}},
		Rules: []Rule{{Pattern: "+.private.test", Servers: []string{"private"}}},
	}, map[string]testExchangeFunc{"default": nil, "private": nil})
	r := exchangeForTest(t, f, testQuery("node.test", dns.TypeA))
	assertCalls(t, f, "private")
	assertCNAME(t, r.Answer[0], "node.test", "target.private.test")
	assertAddressRecords(t, r.Answer[1:], "target.private.test", "192.0.2.53")
	if f.requests["private"][0].Question[0].Name != "target.private.test." {
		t.Fatal("alias was not followed")
	}
}

func TestFailoverStaysInsideMatchedPolicy(t *testing.T) {
	for _, failure := range []string{"error", "timeout", "servfail", "nxdomain", "nodata"} {
		t.Run(failure, func(t *testing.T) {
			bad := func(ctx context.Context, q *dns.Msg) (*dns.Msg, error) {
				switch failure {
				case "error":
					return nil, errors.New("offline")
				case "timeout":
					<-ctx.Done()
					return nil, ctx.Err()
				case "servfail":
					return negativeReply(q, dns.RcodeServerFailure), nil
				case "nxdomain":
					return negativeReply(q, dns.RcodeNameError), nil
				default:
					return negativeReply(q, dns.RcodeSuccess), nil
				}
			}
			f := newTransportFixture(t, Options{Fallback: []string{"default"}, Timeout: badoption.Duration(20 * time.Millisecond),
				Rules: []Rule{{Pattern: "+.private.test", Servers: []string{"first", "second"}}},
			}, map[string]testExchangeFunc{"default": nil, "first": bad, "second": nil})
			exchangeForTest(t, f, testQuery("db.private.test", dns.TypeA))
			assertCalls(t, f, "first", "second")
		})
	}
}

func TestAllPrivateServersFailWithoutPublicFallback(t *testing.T) {
	failure := func(context.Context, *dns.Msg) (*dns.Msg, error) { return nil, errors.New("offline") }
	f := newTransportFixture(t, Options{Fallback: []string{"default"}, Rules: []Rule{{"private.test", []string{"first", "second"}}}},
		map[string]testExchangeFunc{"default": nil, "first": failure, "second": failure})
	if _, err := f.Exchange(context.Background(), testQuery("private.test", dns.TypeA)); err == nil {
		t.Fatal("expected failure")
	}
	assertCalls(t, f, "first", "second")
}

func TestCancelAndMalformedQuestionNeverQuery(t *testing.T) {
	f := newTransportFixture(t, Options{Fallback: []string{"default"}}, map[string]testExchangeFunc{"default": nil})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := f.Exchange(ctx, testQuery("test.example", dns.TypeA)); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if _, err := f.Exchange(context.Background(), new(dns.Msg)); err == nil {
		t.Fatal("empty question accepted")
	}
	if _, err := f.Exchange(context.Background(), nil); err == nil {
		t.Fatal("nil accepted")
	}
	assertCalls(t, f)
}

func TestConstructorRejectsInvalidOrCyclicConfig(t *testing.T) {
	cases := []Options{
		{}, {Fallback: []string{"test"}},
		{Fallback: []string{"default"}, Rules: []Rule{{"+.test", nil}}},
		{Fallback: []string{"default"}, Rules: []Rule{{"geosite:cn", []string{"default"}}}},
		{Fallback: []string{"default"}, Hosts: map[string][]string{"a.test": {"b.test"}, "b.test": {"a.test"}}},
		{Fallback: []string{"default"}, Hosts: map[string][]string{"a.test": {"a.test"}}},
		{Fallback: []string{"default"}, Hosts: map[string][]string{"a.test": {"192.0.2.1", "b.test"}}},
		{Fallback: []string{"default"}, Hosts: map[string][]string{"a.test": {}}},
	}
	for index, options := range cases {
		t.Run(fmt.Sprint(index), func(t *testing.T) {
			if _, err := New(context.Background(), nil, "test", options); err == nil {
				t.Fatal("invalid config accepted")
			}
		})
	}
}

func TestSameDomainDifferentResolversRemainIndependent(t *testing.T) {
	for _, address := range []string{"192.0.2.1", "192.0.2.2"} {
		f := newTransportFixture(t, Options{Fallback: []string{"default"}, Hosts: map[string][]string{"same.test": {address}}}, map[string]testExchangeFunc{"default": nil})
		r := exchangeForTest(t, f, testQuery("same.test", dns.TypeA))
		assertAddressRecords(t, r.Answer, "same.test", address)
		assertCalls(t, f)
	}
}

func TestMostSpecificPatternWins(t *testing.T) {
	f := newTransportFixture(t, Options{Fallback: []string{"default"}, Rules: []Rule{
		{"+.test", []string{"broad"}}, {"+.private.test", []string{"narrow"}}, {"db.private.test", []string{"exact"}},
	}}, map[string]testExchangeFunc{"default": nil, "broad": nil, "narrow": nil, "exact": nil})
	exchangeForTest(t, f, testQuery("db.private.test", dns.TypeA))
	assertCalls(t, f, "exact")
}
