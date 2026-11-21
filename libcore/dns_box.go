// Package-local JNI DNS contracts are intentionally retained for gobind.
package libcore

import (
	"context"
	"net/netip"
	"strings"
	"sync"
	"syscall"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
)

var rawQueryFunc func(networkHandle int64, request []byte) ([]byte, error)

type LocalDNSTransport interface {
	Raw() bool
	NetworkHandle() int64
	Lookup(ctx *ExchangeContext, network string, domain string) error
	Exchange(ctx *ExchangeContext, message []byte) error
}

var gLocalDNSTransport *platformLocalDNSTransport

type platformLocalDNSTransport struct {
	dns.TransportAdapter
	iif LocalDNSTransport
	raw bool
}

func newPlatformTransport(iif LocalDNSTransport, tag string, options option.LocalDNSServerOptions) *platformLocalDNSTransport {
	return &platformLocalDNSTransport{TransportAdapter: dns.NewTransportAdapterWithLocalOptions(constant.DNSTypeLocal, tag, options), iif: iif, raw: iif != nil && iif.Raw()}
}
func (p *platformLocalDNSTransport) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	return nil
}
func (p *platformLocalDNSTransport) Close() error { return nil }
func (p *platformLocalDNSTransport) Reset()       {} // no retained sockets
func (p *platformLocalDNSTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	go func() { response, err := p.Exchange(ctx, message); callback(response, err) }()
}
func (p *platformLocalDNSTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	if p.iif == nil || message == nil || len(message.Question) != 1 {
		return nil, E.New("platform DNS is unavailable or query is invalid")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	question := message.Question[0]
	if p.raw && rawQueryFunc != nil {
		raw, err := message.Pack()
		if err != nil {
			return nil, err
		}
		raw, err = rawQueryFunc(p.iif.NetworkHandle(), raw)
		if err != nil {
			return nil, err
		}
		reply := new(mDNS.Msg)
		if err = reply.Unpack(raw); err != nil {
			return nil, err
		}
		if err = ctx.Err(); err != nil {
			return nil, err
		}
		return reply, nil
	}
	exchange := &ExchangeContext{context: ctx, done: make(chan struct{})}
	if p.raw {
		raw, err := message.Pack()
		if err != nil {
			return nil, err
		}
		if err = p.iif.Exchange(exchange, raw); err != nil {
			exchange.complete(nil, nil, err)
		}
	} else {
		network := "ip4"
		if question.Qtype == mDNS.TypeAAAA {
			network = "ip6"
		} else if question.Qtype != mDNS.TypeA {
			return nil, E.New("current Android DNS API supports only address queries")
		}
		if err := p.iif.Lookup(exchange, network, question.Name); err != nil {
			exchange.complete(nil, nil, err)
		}
	}
	select {
	case <-exchange.done:
	case <-ctx.Done():
		exchange.complete(nil, nil, ctx.Err())
	}
	exchange.access.Lock()
	defer exchange.access.Unlock()
	// Cancellation wins even when completion and ctx.Done become ready together.
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if exchange.error != nil {
		return nil, exchange.error
	}
	if exchange.message != nil {
		return exchange.message.Copy(), nil
	}
	return dns.FixedResponse(message.Id, question, append([]netip.Addr(nil), exchange.addresses...), constant.DefaultDNSTTL), nil
}

type Func interface{ Invoke() error }
type ExchangeContext struct {
	context   context.Context
	access    sync.Mutex
	message   *mDNS.Msg
	addresses []netip.Addr
	error     error
	done      chan struct{}
	completed bool
}

func (c *ExchangeContext) complete(message *mDNS.Msg, addresses []netip.Addr, err error) {
	c.access.Lock()
	defer c.access.Unlock()
	if c.completed {
		return
	}
	if ctxErr := c.context.Err(); ctxErr != nil {
		err = ctxErr
		message = nil
		addresses = nil
	}
	c.completed = true
	c.error = err
	if message != nil {
		c.message = message.Copy()
	}
	c.addresses = append([]netip.Addr(nil), addresses...)
	close(c.done)
}
func (c *ExchangeContext) OnCancel(callback Func) {
	if callback == nil {
		return
	}
	go func() {
		select {
		case <-c.context.Done():
			c.complete(nil, nil, c.context.Err())
			_ = callback.Invoke()
		case <-c.done:
		}
	}()
}
func (c *ExchangeContext) Success(result string) {
	var addresses []netip.Addr
	for _, value := range strings.Fields(result) {
		addr, err := netip.ParseAddr(value)
		if err != nil {
			c.complete(nil, nil, E.Cause(err, "invalid platform DNS address"))
			return
		}
		addresses = append(addresses, addr.Unmap())
	}
	c.complete(nil, addresses, nil)
}
func (c *ExchangeContext) RawSuccess(result []byte) {
	reply := new(mDNS.Msg)
	if err := reply.Unpack(result); err != nil {
		c.complete(nil, nil, E.Cause(err, "parse platform DNS response"))
		return
	}
	c.complete(reply, nil, nil)
}
func (c *ExchangeContext) ErrorCode(code int32) { c.complete(nil, nil, dns.RcodeError(code)) }
func (c *ExchangeContext) ErrnoCode(code int32) { c.complete(nil, nil, syscall.Errno(code)) }
