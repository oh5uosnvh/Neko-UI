//go:build with_quic

package husiurltest

import (
	"crypto/x509"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing-box/adapter"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

type testCertificateStore struct{ pool *x509.CertPool }

func (*testCertificateStore) Name() string                                   { return "urltest-test" }
func (*testCertificateStore) Start(adapter.StartStage, *adapter.Scope) error { return nil }
func (s *testCertificateStore) Pool() *x509.CertPool                         { return s.pool }
func (*testCertificateStore) ExclusiveAnchors() bool                         { return true }

func TestHTTP3AndQUICUnifiedDelay(t *testing.T) {
	var requests atomic.Int32
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests.Add(1); w.WriteHeader(http.StatusNoContent) })
	tlsServer := httptest.NewTLSServer(handler)
	defer tlsServer.Close()
	roots := tlsServer.Client().Transport.(*http.Transport).TLSClientConfig.RootCAs
	conn, err := net.ListenPacket(N.NetworkUDP, "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	tlsConfig := tlsServer.TLS.Clone()
	tlsConfig.NextProtos = []string{http3.NextProtoH3}
	server := &http3.Server{Handler: handler, TLSConfig: tlsConfig}
	go server.Serve(conn)
	defer server.Close()
	defer conn.Close()
	ctx := service.ContextWith[adapter.CertificateStore](t.Context(), &testCertificateStore{pool: roots})
	for _, scheme := range []string{"http3", "quic"} {
		requests.Store(0)
		_, err := Measure(ctx, scheme+"://"+conn.LocalAddr().String()+"/", N.SystemDialer, UnifiedDelay)
		if err != nil {
			t.Fatalf("%s: %v", scheme, err)
		}
		if requests.Load() != 2 {
			t.Fatalf("%s: got %d requests", scheme, requests.Load())
		}
	}
}
