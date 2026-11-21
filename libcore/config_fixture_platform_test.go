package libcore

import (
	"errors"
	"fmt"
	"net"
	"net/netip"
	"syscall"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
)

// Explicit HOST fixture provider, not an Android VPN/protect implementation.
// Original Android JSON remains intact; device TUN entries never call OpenInterface.
type configFixturePlatform struct {
	boxPlatformInterfaceWrapper
	defaultInterface control.Interface
}

var _ adapter.PlatformInterface = (*configFixturePlatform)(nil)

func newConfigFixturePlatform(t *testing.T) *configFixturePlatform {
	t.Helper()
	// UDP connect performs a kernel routing decision without transmitting packets.
	// Match the selected local address, never assume the first up interface is default.
	conn, err := net.Dial("udp4", "192.0.2.1:9")
	if err != nil {
		t.Fatalf("fixture route discovery: %v", err)
	}
	local := conn.LocalAddr().(*net.UDPAddr).IP
	_ = conn.Close()
	wanted, ok := netip.AddrFromSlice(local)
	if !ok {
		t.Fatal("fixture route has invalid local address")
	}
	wanted = wanted.Unmap()
	interfaces, err := net.Interfaces()
	if err != nil {
		t.Fatal(err)
	}
	for _, iif := range interfaces {
		addresses, err := iif.Addrs()
		if err != nil {
			continue
		}
		prefixes := []netip.Prefix{}
		match := false
		for _, a := range addresses {
			prefix, ok := parseInterfacePrefix(a.String())
			if !ok {
				continue
			}
			prefixes = append(prefixes, prefix)
			if prefix.Addr().Unmap() == wanted {
				match = true
			}
		}
		if match {
			if iif.Flags&net.FlagUp == 0 || iif.Flags&net.FlagLoopback != 0 {
				t.Fatalf("route-selected fixture interface not usable: %s", iif.Name)
			}
			return &configFixturePlatform{defaultInterface: control.Interface{Index: iif.Index, MTU: iif.MTU, Name: iif.Name, Flags: iif.Flags, Addresses: prefixes}}
		}
	}
	t.Fatalf("kernel-selected fixture source %s not found in interfaces", wanted)
	return nil
}
func (p *configFixturePlatform) CreateDefaultInterfaceMonitor(logger.Logger) tun.DefaultInterfaceMonitor {
	monitor := newInterfaceMonitor()
	monitor.getter = func() string { return p.defaultInterface.Name }
	return monitor
}
func (*configFixturePlatform) UsePlatformDefaultInterfaceMonitor() bool { return true }
func (*configFixturePlatform) UsePlatformNetworkInterfaces() bool       { return true }
func (p *configFixturePlatform) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return []adapter.NetworkInterface{{Interface: p.defaultInterface}}, nil
}
func (*configFixturePlatform) UsePlatformConnectionOwnerFinder() bool { return false }
func (*configFixturePlatform) AutoDetectInterfaceControl(fd int) error {
	// This is a host stub: assert a real descriptor, but do NOT claim VPN protection.
	var stat syscall.Stat_t
	if err := syscall.Fstat(fd, &stat); err != nil {
		return fmt.Errorf("fixture invalid socket fd: %w", err)
	}
	return nil
}
func (*configFixturePlatform) OpenInterface(*tun.Options, option.TunPlatformOptions) (tun.Tun, error) {
	return nil, errors.New("native TUN Start is a separate real Android device acceptance gate")
}
