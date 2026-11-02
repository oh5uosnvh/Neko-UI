package dnspolicy

import (
	"context"
	"fmt"
	"net"
	"sync"
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
	poolMu    sync.Mutex
	idle      []idleStream
	closed    bool
}
type idleStream struct {
	conn net.Conn
	used time.Time
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
func (s *streamTransport) Close() error {
	s.poolMu.Lock()
	s.closed = true
	idle := s.idle
	s.idle = nil
	s.poolMu.Unlock()
	for _, item := range idle {
		_ = item.conn.Close()
	}
	return nil
}
func (s *streamTransport) takeIdle() net.Conn {
	s.poolMu.Lock()
	defer s.poolMu.Unlock()
	for len(s.idle) > 0 {
		last := len(s.idle) - 1
		item := s.idle[last]
		s.idle = s.idle[:last]
		if time.Since(item.used) < 20*time.Second {
			return item.conn
		}
		_ = item.conn.Close()
	}
	return nil
}
func (s *streamTransport) keepIdle(conn net.Conn) {
	s.poolMu.Lock()
	defer s.poolMu.Unlock()
	if s.closed || len(s.idle) >= 4 {
		_ = conn.Close()
		return
	}
	_ = conn.SetDeadline(time.Time{})
	s.idle = append(s.idle, idleStream{conn, time.Now()})
}

func (s *streamTransport) Exchange(ctx context.Context, message *dns.Msg) (*dns.Msg, error) {
	s.poolMu.Lock()
	closed := s.closed
	s.poolMu.Unlock()
	if closed {
		return nil, fmt.Errorf("DNS transport is closed")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	conn := s.takeIdle()
	if conn != nil {
		response, err := s.exchangeStream(ctx, conn, message)
		if err == nil {
			return response, nil
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
	}
	tcp, err := s.dialer.DialContext(ctx, N.NetworkTCP, s.address)
	if err != nil {
		return nil, err
	}
	conn = tcp
	if deadline, ok := ctx.Deadline(); ok {
		_ = tcp.SetDeadline(deadline)
	}
	if s.tlsConfig != nil {
		stop := context.AfterFunc(ctx, func() { _ = tcp.Close() })
		conn, err = tls.ClientHandshake(ctx, tcp, s.tlsConfig)
		stopped := stop()
		if err != nil || !stopped || ctx.Err() != nil {
			_ = tcp.Close()
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			return nil, err
		}
	}
	return s.exchangeStream(ctx, conn, message)
}
func (s *streamTransport) exchangeStream(ctx context.Context, conn net.Conn, message *dns.Msg) (*dns.Msg, error) {
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	err := transport.WriteMessage(conn, message.Id, message)
	var response *dns.Msg
	if err == nil {
		response, err = transport.ReadMessage(conn)
	}
	stopped := stop()
	if err != nil || !stopped || ctx.Err() != nil {
		_ = conn.Close()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, err
	}
	if response == nil || response.Id != message.Id || !validReply(response, message) {
		_ = conn.Close()
		return nil, fmt.Errorf("invalid DNS stream response")
	}
	s.keepIdle(conn)
	return response, nil
}
