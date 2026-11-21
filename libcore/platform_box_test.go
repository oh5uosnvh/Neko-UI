package libcore

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"math/big"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type platformTestInterface struct {
	fd         int
	protectErr error
	name       string
	received   string
}

func (p *platformTestInterface) AutoDetectInterfaceControl(int32) error { return p.protectErr }
func (p *platformTestInterface) OpenTun(a, b string) (int, error) {
	p.received = a + b
	return p.fd, nil
}
func (*platformTestInterface) UseProcFS() bool { return false }
func (*platformTestInterface) FindConnectionOwner(int32, string, int32, string, int32) (int32, error) {
	return 42, nil
}
func (*platformTestInterface) PackageNameByUid(int32) (string, error) { return "fixture.package", nil }
func (*platformTestInterface) UIDByPackageName(string) (int32, error) { return 42, nil }
func (*platformTestInterface) WIFIState() string                      { return "" }
func (p *platformTestInterface) DefaultNetworkInterface() string      { return p.name }
func (*platformTestInterface) DefaultNetworkDNSServers() string       { return "192.0.2.53" }
func (*platformTestInterface) DefaultNetworkGateways() string         { return "192.0.2.1" }
func TestNekoPlatformProtectErrorAndOwner(t *testing.T) {
	old, bg := intfBox, isBgProcess
	defer func() { intfBox = old; isBgProcess = bg }()
	want := errors.New("protect denied")
	intfBox = &platformTestInterface{protectErr: want}
	isBgProcess = true
	wrapper := &boxPlatformInterfaceWrapper{}
	if !errors.Is(wrapper.AutoDetectInterfaceControl(1), want) {
		t.Fatal("protect error swallowed")
	}
	owner, err := wrapper.FindConnectionOwner(&adapter.FindConnectionOwnerRequest{IpProtocol: syscall.IPPROTO_TCP, SourceAddress: "2001:db8::1", SourcePort: 123, DestinationAddress: "2001:db8::2", DestinationPort: 443})
	if err != nil || owner.UserId != 42 || len(owner.PackageNames) != 1 {
		t.Fatalf("owner not propagated: %#v %v", owner, err)
	}
}
func TestNekoMonitorRejectsMissingAndVPNDoesNotGuess(t *testing.T) {
	monitor := newInterfaceMonitor()
	monitor.getter = func() string { return "" }
	monitor.lookup = func(string) (*control.Interface, error) {
		t.Fatal("no-network getter must not enumerate/fallback")
		return nil, nil
	}
	if err := monitor.Start(); err == nil {
		t.Fatal("missing network silently accepted")
	}
	monitor.getter = func() string { return "tun0" }
	if _, err := monitor.readInterface(); err == nil {
		t.Fatal("VPN loopback was accepted as underlying network")
	}
}
func TestNekoBoxContextHistoryPausePlatformShared(t *testing.T) {
	old := intfBox
	defer func() { intfBox = old }()
	intfBox = &platformTestInterface{}
	instance, err := NewSingBoxInstance(`{"dns":{"servers":[{"type":"local","tag":"local"}]},"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if instance.ctx == nil || instance.pauseManager == nil {
		t.Fatal("instance has no lifetime context/pause manager")
	}
	if service.FromContext[pause.Manager](instance.ctx) != instance.pauseManager {
		t.Fatal("pause manager not shared")
	}
	if service.PtrFromContext[urltest.HistoryStorage](instance.ctx) == nil {
		t.Fatal("history is not shared with Box/group")
	}
	if service.FromContext[adapter.PlatformInterface](instance.ctx) != boxPlatformInterfaceInstance {
		t.Fatal("core consumes wrong platform service key")
	}
	if err = instance.Close(); err != nil {
		t.Fatal(err)
	}
	if !errors.Is(instance.ctx.Err(), context.Canceled) {
		t.Fatal("closing instance does not cancel URLTest lifetime")
	}
}
func TestNekoStrictUnknownFieldsStillFail(t *testing.T) {
	var options option.Options
	if err := options.UnmarshalJSONContext(configFixtureContext(context.Background(), nil), []byte(`{"unknown_typo":true}`)); err == nil || !strings.Contains(err.Error(), "unknown") {
		t.Fatalf("unknown fields silently ignored: %v", err)
	}
}

func TestNekoBoxCustomCARootPoolVisibleToURLTest(t *testing.T) {
	oldIntf := intfBox
	intfBox = &platformTestInterface{}
	defer func() { intfBox = oldIntf }()
	rootCAMutex.Lock()
	previous := externalRootCA
	rootCAMutex.Unlock()
	defer func() { rootCAMutex.Lock(); externalRootCA = previous; rootCAMutex.Unlock() }()
	pem := makePlatformTestCA(t)
	updateRootCACerts(pem)
	instance, err := NewSingBoxInstance(`{"dns":{"servers":[{"type":"local","tag":"local"}]},"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	pool := adapter.RootPoolFromContext(instance.ctx)
	if pool == nil {
		t.Fatal("URLTest lifetime context cannot see Box certificate store")
	}
	certificate, err := parsePlatformTestCA(pem)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = certificate.Verify(x509.VerifyOptions{Roots: pool}); err != nil {
		t.Fatalf("custom CA missing from actual shared root pool: %v", err)
	}
}

func makePlatformTestCA(t *testing.T) []byte {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Neko fixture root"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
}
func parsePlatformTestCA(raw []byte) (*x509.Certificate, error) {
	block, _ := pem.Decode(raw)
	if block == nil {
		return nil, errors.New("invalid fixture PEM")
	}
	return x509.ParseCertificate(block.Bytes)
}
