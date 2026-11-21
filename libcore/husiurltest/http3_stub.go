//go:build !with_quic

package husiurltest

import (
	"crypto/tls"
	C "github.com/sagernet/sing-box/constant"
	"net"
	"net/http"
)

func newHTTP3Transport(net.Conn, *tls.Config) (http.RoundTripper, error) {
	return nil, C.ErrQUICNotIncluded
}
