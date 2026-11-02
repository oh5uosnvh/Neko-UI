package dnspolicy

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	boxservice "github.com/sagernet/sing-box/adapter/service"
	boxdns "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/direct"
	httpproxy "github.com/sagernet/sing-box/protocol/http"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

func testCertificate(t *testing.T, name string) (tls.Certificate, string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: name}, DNSNames: []string{name},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, BasicConstraintsValid: true, IsCA: true}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		t.Fatal(err)
	}
	return cert, string(certPEM)
}
func TestMappedTLSNodePreservesCertificateNameAndSNI(t *testing.T) {
	for _, explicit := range []bool{false, true} {
		t.Run(fmt.Sprint("explicit-sni=", explicit), func(t *testing.T) {
			identity := "node.example"
			if explicit {
				identity = "certificate.example"
			}
			cert, certPEM := testCertificate(t, identity)
			listener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"http/1.1"}})
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			receivedSNI := make(chan string, 1)
			serverError := make(chan error, 1)
			go func() {
				conn, err := listener.Accept()
				if err != nil {
					serverError <- err
					return
				}
				defer conn.Close()
				_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
				tlsConn := conn.(*tls.Conn)
				if err = tlsConn.Handshake(); err != nil {
					serverError <- err
					return
				}
				receivedSNI <- tlsConn.ConnectionState().ServerName
				req, err := http.ReadRequest(bufio.NewReader(conn))
				if err != nil {
					serverError <- err
					return
				}
				if req.Method != "CONNECT" {
					serverError <- fmt.Errorf("not CONNECT")
					return
				}
				_, err = conn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\nOK"))
				serverError <- err
			}()
			dr := boxdns.NewTransportRegistry()
			RegisterTransport(dr)
			or := outbound.NewRegistry()
			direct.RegisterOutbound(or)
			httpproxy.RegisterOutbound(or)
			ir := inbound.NewRegistry()
			ctx := box.Context(context.Background(), ir, or, endpoint.NewRegistry(), dr, boxservice.NewRegistry())
			ctx = service.ContextWithDefaultRegistry(ctx)
			tlsOpts := map[string]any{"enabled": true, "certificate": []string{certPEM}, "alpn": []string{"http/1.1"}}
			if explicit {
				tlsOpts["server_name"] = identity
			}
			cfg := map[string]any{
				"log": map[string]any{"disabled": true},
				"dns": map[string]any{"independent_cache": true, "final": "group", "servers": []any{
					map[string]any{"type": "neko-tcp", "tag": "unused", "server": "127.0.0.1", "server_port": 9},
					map[string]any{"type": "neko-policy", "tag": "group", "fallback": []string{"unused"}, "hosts": map[string][]string{"node.example": {"alias.example"}, "alias.example": {"127.0.0.1"}}},
				}},
				"outbounds": []any{map[string]any{"type": "http", "tag": "proxy", "server": "node.example", "server_port": listener.Addr().(*net.TCPAddr).Port,
					"domain_resolver": map[string]any{"server": "group", "strategy": "ipv4_only"}, "tls": tlsOpts}},
				"route": map[string]any{"final": "proxy"},
			}
			raw, err := json.Marshal(cfg)
			if err != nil {
				t.Fatal(err)
			}
			var opts option.Options
			if err = opts.UnmarshalJSONContext(ctx, raw); err != nil {
				t.Fatal(err)
			}
			instance, err := box.New(box.Options{Options: opts, Context: ctx})
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
			manager := service.FromContext[adapter.OutboundManager](ctx)
			proxy, ok := manager.Outbound("proxy")
			if !ok {
				t.Fatal("missing proxy")
			}
			dialCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
			defer cancel()
			conn, err := proxy.DialContext(dialCtx, "tcp", M.ParseSocksaddrHostPort("target.invalid", 80))
			if err != nil {
				t.Fatal("certificate-verified mapped proxy failed:", err)
			}
			defer conn.Close()
			_ = conn.SetReadDeadline(time.Now().Add(time.Second))
			buf := make([]byte, 2)
			if _, err = io.ReadFull(conn, buf); err != nil || string(buf) != "OK" {
				t.Fatal("proxy tunnel failed:", err)
			}
			select {
			case sni := <-receivedSNI:
				if sni != identity {
					t.Fatalf("SNI changed to %q", sni)
				}
			case <-dialCtx.Done():
				t.Fatal("missing TLS handshake")
			}
			if err = <-serverError; err != nil {
				t.Fatal(err)
			}
		})
	}
}
