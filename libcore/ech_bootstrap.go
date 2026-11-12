package libcore

import (
	"context"
	"net"
	"net/netip"

	"github.com/sagernet/sing-box/adapter"

	mDNS "github.com/miekg/dns"
)

// bootstrapDNSTransport adapts the Go system resolver to adapter.DNSTransport
// for the ECH key fetch path. Since 1.14 the platform local DNS transport no
// longer implements adapter.DNSTransport; the ECH bootstrap query does not need
// the platform loopback — the system resolver is sufficient and always
// available.
type bootstrapDNSTransport struct {
	domain string
}

func newBootstrapDNSTransport(domain string) adapter.DNSTransport {
	return &bootstrapDNSTransport{domain: domain}
}

func (t *bootstrapDNSTransport) Name() string { return "ech-bootstrap" }

func (t *bootstrapDNSTransport) Type() string { return "ech-bootstrap" }

func (t *bootstrapDNSTransport) Tag() string { return "ech-bootstrap" }

func (t *bootstrapDNSTransport) Dependencies() []string { return nil }

func (t *bootstrapDNSTransport) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	return nil
}

func (t *bootstrapDNSTransport) Close() error { return nil }

func (t *bootstrapDNSTransport) Reset() {}

func (t *bootstrapDNSTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	reply := new(mDNS.Msg)
	reply.SetReply(message)
	var resolver net.Resolver
	for _, q := range message.Question {
		switch q.Qtype {
		case mDNS.TypeA, mDNS.TypeAAAA:
			addrs, err := resolver.LookupHost(ctx, q.Name)
			if err != nil {
				return nil, err
			}
			for _, addr := range addrs {
				ip := net.ParseIP(addr)
				if ip == nil {
					continue
				}
				if q.Qtype == mDNS.TypeA {
					if ip4 := ip.To4(); ip4 != nil {
						reply.Answer = append(reply.Answer, &mDNS.A{Hdr: mDNS.RR_Header{Name: q.Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60}, A: ip4})
					}
				} else {
					reply.Answer = append(reply.Answer, &mDNS.AAAA{Hdr: mDNS.RR_Header{Name: q.Name, Rrtype: mDNS.TypeAAAA, Class: mDNS.ClassINET, Ttl: 60}, AAAA: ip})
				}
			}
		}
	}
	return reply, nil
}

func (t *bootstrapDNSTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	resp, err := t.Exchange(ctx, message)
	callback(resp, err)
}

func (t *bootstrapDNSTransport) Lookup(ctx context.Context, domain string) ([]netip.Addr, error) {
	var resolver net.Resolver
	addrs, err := resolver.LookupHost(ctx, domain)
	if err != nil {
		return nil, err
	}
	out := make([]netip.Addr, 0, len(addrs))
	for _, a := range addrs {
		if ip := net.ParseIP(a); ip != nil {
			out = append(out, netip.AddrFrom4([4]byte(ip.To4())))
		}
	}
	return out, nil
}

