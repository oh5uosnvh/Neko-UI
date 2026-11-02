package dnspolicy

import (
	"context"
	"net"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/tls"
	boxdns "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// The pinned legacy TCP/DoT transports do not cancel a blocking ReadMessage.
// Use core-owned dialers/TLS with an explicit connection deadline, without a detached query goroutine.
type streamTransport struct {
	boxdns.TransportAdapter
	dialer    N.Dialer
	address   M.Socksaddr
	tlsConfig tls.Config
}

func registerStreams(registry *boxdns.TransportRegistry) {
	boxdns.RegisterTransport[option.RemoteDNSServerOptions](registry, "neko-tcp", newTCP)
	boxdns.RegisterTransport[option.RemoteTLSDNSServerOptions](registry, "neko-tls", newTLS)
}
func newTCP(ctx context.Context, _ log.ContextLogger, tag string, options option.RemoteDNSServerOptions) (adapter.DNSTransport, error) {
	d, err := boxdns.NewRemoteDialer(ctx, options)
	if err != nil {
		return nil, err
	}
	address := options.DNSServerAddressOptions.Build()
	if address.Port == 0 {
		address.Port = 53
	}
	return &streamTransport{
		TransportAdapter: boxdns.NewTransportAdapterWithRemoteOptions("neko-tcp", tag, options),
		dialer:           d, address: address,
	}, nil
}
func newTLS(ctx context.Context, _ log.ContextLogger, tag string, options option.RemoteTLSDNSServerOptions) (adapter.DNSTransport, error) {
	d, err := boxdns.NewRemoteDialer(ctx, options.RemoteDNSServerOptions)
	if err != nil {
		return nil, err
	}
	tlsOptions := common.PtrValueOrDefault(options.TLS)
	tlsOptions.Enabled = true
	config, err := tls.NewClient(ctx, options.Server, tlsOptions)
	if err != nil {
		return nil, err
	}
	address := options.DNSServerAddressOptions.Build()
	if address.Port == 0 {
		address.Port = 853
	}
	return &streamTransport{
		TransportAdapter: boxdns.NewTransportAdapterWithRemoteOptions("neko-tls", tag, options.RemoteDNSServerOptions),
		dialer:           d, address: address, tlsConfig: config,
	}, nil
}
func (s *streamTransport) Start(stage adapter.StartStage) error {
	if stage == adapter.StartStateStart {
		return dialer.InitializeDetour(s.dialer)
	}
	return nil
}
func (s *streamTransport) Close() error { return nil }
func (s *streamTransport) Exchange(ctx context.Context, message *dns.Msg) (*dns.Msg, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	conn, err := s.dialer.DialContext(ctx, N.NetworkTCP, s.address)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	closeOnCancel := context.AfterFunc(ctx, func() { conn.Close() })
	defer closeOnCancel()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	var stream net.Conn = conn
	if s.tlsConfig != nil {
		stream, err = tls.ClientHandshake(ctx, conn, s.tlsConfig)
		if err != nil {
			return nil, err
		}
	}
	if err = transport.WriteMessage(stream, message.Id, message); err != nil {
		return nil, err
	}
	return transport.ReadMessage(stream)
}
