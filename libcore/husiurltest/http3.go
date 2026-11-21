//go:build with_quic

package husiurltest

import (
	"context"
	"crypto/tls"
	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"net"
	"net/http"
)

func newHTTP3Transport(conn net.Conn, tlsConfig *tls.Config) (http.RoundTripper, error) {
	return &http3.Transport{
		TLSClientConfig: tlsConfig,
		Dial: func(ctx context.Context, _ string, tlsConfig *tls.Config, config *quic.Config) (*quic.Conn, error) {
			return quic.DialEarlyConn(ctx, conn, tlsConfig, config)
		},
	}, nil
}
