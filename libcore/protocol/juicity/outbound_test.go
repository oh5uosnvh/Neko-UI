package juicity

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"testing"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
)

func TestConstructorAndCertificateHelpers(t *testing.T) {
	logger := log.NewNOPFactory().Logger()
	if _, err := NewOutbound(context.Background(), nil, logger, "fixture", JuicityOutboundOptions{}); !errors.Is(err, C.ErrTLSRequired) {
		t.Fatalf("missing TLS: %v", err)
	}
	options := JuicityOutboundOptions{
		ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: 443},
		UUID:          "00112233-4455-6677-8899-aabbccddeeff", Password: "fixture",
		OutboundTLSOptionsContainer: option.OutboundTLSOptionsContainer{TLS: &option.OutboundTLSOptions{Enabled: true}},
	}
	out, err := NewOutbound(context.Background(), nil, logger, "fixture", options)
	if err != nil {
		t.Fatal(err)
	}
	defer out.(*Outbound).Close()
	if out.Type() != "juicity" || out.Tag() != "fixture" {
		t.Fatalf("adapter = %s/%s", out.Type(), out.Tag())
	}
	if len(options.TLS.ALPN) != 1 || options.TLS.ALPN[0] != "h3" {
		t.Fatal("missing default ALPN")
	}
	pin := sha256.Sum256([]byte("fixture-certificate"))
	for _, encoding := range []*base64.Encoding{base64.StdEncoding, base64.URLEncoding} {
		got, err := tryDecodeBase64(encoding.EncodeToString(pin[:]))
		if err != nil || !bytes.Equal(got, pin[:]) {
			t.Fatalf("pin decode: %x %v", got, err)
		}
	}
	if _, err := tryDecodeBase64("%%%invalid"); err == nil {
		t.Fatal("invalid pin accepted")
	}
	if !bytes.Equal(certChainHash([][]byte{[]byte("fixture-certificate")}), pin[:]) {
		t.Fatal("certificate hash mismatch")
	}
}
