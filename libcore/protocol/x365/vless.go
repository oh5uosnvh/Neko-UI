package x365

import (
	"context"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	standard "github.com/sagernet/sing-box/protocol/vless"
	E "github.com/sagernet/sing/common/exceptions"
)

type lifecycleVLESS struct{ *standard.Outbound }

var _ adapter.Lifecycle = (*lifecycleVLESS)(nil)
var _ adapter.InterfaceUpdateListener = (*lifecycleVLESS)(nil)

func (o *lifecycleVLESS) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage == adapter.StartStateInitialize {
		scope.Add(o.Outbound.Close)
	}
	return nil
}

func (o *lifecycleVLESS) InterfaceUpdated(ctx context.Context) { o.Outbound.InterfaceUpdated() }

func ParseMarker(value string) (string, bool) {
	if len(value) >= 5 && strings.EqualFold(value[len(value)-5:], "#x365") {
		return value[:len(value)-5], true
	}
	return value, false
}

func RegisterVLESSOutbound(registry *outbound.Registry) {
	outbound.Register[option.VLESSOutboundOptions](registry, "vless", newVLESSOutbound)
}

func newVLESSOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.VLESSOutboundOptions) (adapter.Outbound, error) {
	clean, modified := ParseMarker(options.UUID)
	if !modified {
		inner, err := standard.NewOutbound(ctx, router, logger, tag, options)
		if err != nil {
			return nil, err
		}
		// The fork's VLESS encryption/transport extension still exposes the
		// pre-1.14 Close/InterfaceUpdated ABI. Preserve its wire implementation
		// while adapting only ownership and network notifications.
		if legacy, ok := inner.(*standard.Outbound); ok {
			return &lifecycleVLESS{Outbound: legacy}, nil
		}
		return inner, nil
	}
	if clean == "" {
		return nil, E.New("x365: empty credential")
	}
	if options.Flow != "" || (options.Encryption != "" && options.Encryption != "none") || (options.Multiplex != nil && options.Multiplex.Enabled) {
		return nil, E.New("x365: VLESS flow, encryption and sing-mux are not compatible with the private handshake")
	}
	result, err := NewOutbound(ctx, router, logger, tag, X365OutboundOptions{
		DialerOptions:               options.DialerOptions,
		ServerOptions:               options.ServerOptions,
		UUID:                        clean,
		Network:                     options.Network,
		OutboundTLSOptionsContainer: options.OutboundTLSOptionsContainer,
		Transport:                   options.Transport,
		PacketEncoding:              options.PacketEncoding,
	})
	if err != nil {
		return nil, err
	}
	result.(*Outbound).Adapter = outbound.NewAdapterWithDialerOptions("vless", tag, options.Network.Build(), options.DialerOptions)
	return result, nil
}
