package dnspolicy

import (
	"context"
	"errors"
	"sort"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	boxdns "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/service"
)

const SelectorType = "neko-selector"

// SelectorOptions contains an immutable, complete outbound -> resolver graph.
// No fallback to a different group is permitted for missing membership.
type SelectorOptions struct {
	Selector string            `json:"selector"`
	Choices  map[string]string `json:"choices"`
}

type SelectorTransport struct {
	boxdns.TransportAdapter
	manager     adapter.DNSTransportManager
	outbounds   adapter.OutboundManager
	selector    adapter.OutboundGroup
	choices     map[string]string
	upstreams   map[string]adapter.DNSTransport
	selectorTag string
}

func newSelector(ctx context.Context, _ log.ContextLogger, tag string, options SelectorOptions) (adapter.DNSTransport, error) {
	if options.Selector == "" || len(options.Choices) == 0 {
		return nil, errors.New("selector DNS requires selector and choices")
	}
	choices, seen := make(map[string]string, len(options.Choices)), make(map[string]bool)
	var dependencies []string
	for outbound, server := range options.Choices {
		if outbound == "" || server == "" || server == tag {
			return nil, errors.New("invalid selector DNS choice")
		}
		choices[outbound] = server
		if !seen[server] {
			seen[server] = true
			dependencies = append(dependencies, server)
		}
	}
	sort.Strings(dependencies)
	return &SelectorTransport{
		TransportAdapter: boxdns.NewTransportAdapter(SelectorType, tag, dependencies),
		manager:          service.FromContext[adapter.DNSTransportManager](ctx),
		outbounds:        service.FromContext[adapter.OutboundManager](ctx),
		choices:          choices, selectorTag: options.Selector,
	}, nil
}

func (t *SelectorTransport) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	if t.manager == nil || t.outbounds == nil {
		return errors.New("missing selector DNS managers")
	}
	outbound, ok := t.outbounds.Outbound(t.selectorTag)
	if !ok {
		return errors.New("selector DNS outbound not found")
	}
	selector, ok := outbound.(adapter.OutboundGroup)
	if !ok {
		return errors.New("selector DNS outbound is not a group")
	}
	for _, tag := range selector.All() {
		if _, ok := t.choices[tag]; !ok {
			return errors.New("incomplete selector DNS membership")
		}
	}
	upstreams := make(map[string]adapter.DNSTransport, len(t.Dependencies()))
	for _, tag := range t.Dependencies() {
		server, ok := t.manager.Transport(tag)
		if !ok {
			return errors.New("selector DNS resolver not found")
		}
		upstreams[tag] = server
	}
	t.selector, t.upstreams = selector.(adapter.OutboundGroup), upstreams
	return nil
}
func (t *SelectorTransport) Close() error { return nil }

// ExchangeAsync adapts the synchronous exchange to the 1.14 interface.
func (t *SelectorTransport) ExchangeAsync(ctx context.Context, message *dns.Msg, callback func(*dns.Msg, error)) {
	resp, err := t.Exchange(ctx, message)
	callback(resp, err)
}

func (t *SelectorTransport) Reset() {
}

func (t *SelectorTransport) Exchange(ctx context.Context, message *dns.Msg) (*dns.Msg, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if t.selector == nil {
		return nil, errors.New("selector DNS not started")
	}
	// Read native selector once. In-flight queries keep that exact group's resolver;
	// the next query observes a new selection, including Clash API selections.
	server := t.upstreams[t.choices[t.selector.Selected("udp").Tag()]]
	if server == nil {
		return nil, errors.New("selected outbound has no DNS resolver")
	}
	return server.Exchange(ctx, message)
}
