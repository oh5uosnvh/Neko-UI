package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/service"
)

// Also supplies valid metadata to the existing production platform tests.
// This is a callback fixture, NOT a replacement adapter.PlatformInterface.
func (*platformTestInterface) SocketProtectionRequired() (bool, error) { return true, nil }
func (p *platformTestInterface) NetworkInterfacesJSON() (string, error) {
	name := p.name
	if name == "" {
		name = "wlan0"
	}
	return networkTestJSON(name, 77, "192.0.2.2/24"), nil
}
func networkTestJSON(name string, index int, addresses ...string) string {
	flags, metered, vpn := uint32(net.FlagUp|net.FlagRunning|net.FlagMulticast), false, false
	value, _ := json.Marshal(androidNetworkJSON{Version: 1, DefaultInterface: name, DefaultIndex: index,
		Interfaces: []androidInterfaceJSON{{Name: name, Index: index, MTU: 1500, Flags: &flags, Addresses: addresses,
			Type: "wifi", DNSServers: []string{"192.0.2.53", "2001:db8::53"}, Gateways: []string{"192.0.2.1"},
			DNSSearchDomains: []string{"example.test"}, Metered: &metered, VPN: &vpn}}})
	return string(value)
}

type networkCallback struct {
	value string
	err   error
}

func (p *networkCallback) NetworkInterfacesJSON() (string, error)  { return p.value, p.err }
func (p *networkCallback) SocketProtectionRequired() (bool, error) { return true, p.err }

func TestAndroidNetworkSnapshotValidation(t *testing.T) {
	good := networkTestJSON("wlan0", 77, "192.0.2.2/24", "fe80::1%wlan0/64", "2001:db8::2/64")
	s, err := parseAndroidNetworkSnapshot(good)
	if err != nil {
		t.Fatal(err)
	}
	iif := s.interfaces[0]
	if iif.Type != C.InterfaceTypeWIFI || iif.MTU != 1500 || iif.Index != 77 || len(iif.Addresses) != 3 || len(iif.DNSServers) != 2 || len(iif.Gateways) != 1 || iif.Expensive {
		t.Fatalf("incomplete interface: %+v", iif)
	}
	for _, value := range []string{"", "null", "{}", `{"version":1,"interfaces":[]}`, good + "{}",
		strings.Replace(good, `"version":1`, `"version":2`, 1),
		strings.Replace(good, `"index":77`, `"index":0`, 1),
		strings.Replace(good, `"flags":49`, `"flags":null`, 1),
		strings.Replace(good, `"metered":false`, `"metered":null`, 1),
		strings.Replace(good, `"vpn":false`, `"vpn":true`, 1),
		strings.Replace(good, "192.0.2.2/24", "bad-prefix", 1),
		strings.Replace(good, `"defaultIndex":77`, `"defaultIndex":78`, 1),
		strings.Replace(good, `"type":"wifi"`, `"type":"made-up"`, 1),
		networkTestJSON("tun0", 9, "10.0.0.2/24"),
	} {
		if _, err := parseAndroidNetworkSnapshot(value); err == nil {
			t.Fatalf("invalid metadata accepted: %s", value)
		}
	}
}
func TestAndroidNetworkMissingCallbackFailsProductionNew(t *testing.T) {
	old, provider := intfBox, networkInterfaceProvider
	defer func() { intfBox, networkInterfaceProvider = old, provider }()
	intfBox = nil
	networkInterfaceProvider = nil
	if _, err := (&boxPlatformInterfaceWrapper{}).NetworkInterfaces(); err == nil {
		t.Fatal("missing callback accepted")
	}
	for _, callback := range []*networkCallback{{err: errors.New("permission denied")}, {value: "{}"}, {value: ""}} {
		SetNetworkInterfaceProvider(callback)
		if instance, err := NewSingBoxInstance(networkTestConfig, nil); err == nil {
			instance.Close()
			t.Fatal("production New accepted broken callback")
		}
		if err := newInterfaceMonitor().Start(); err == nil {
			t.Fatal("monitor accepted broken callback")
		}
	}
}

const networkTestConfig = `{"dns":{"servers":[{"type":"local","tag":"local"}]},"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct","auto_detect_interface":true}}`

func TestAndroidNetworkMonitorSwitchAndReusedName(t *testing.T) {
	old := networkInterfaceProvider
	defer func() { networkInterfaceProvider = old }()
	callback := &networkCallback{value: networkTestJSON("wlan0", 77, "192.0.2.2/24")}
	SetNetworkInterfaceProvider(callback)
	monitor := newInterfaceMonitor()
	finder := control.NewDefaultInterfaceFinder()
	refreshes := 0
	monitor.refresh = func() error {
		interfaces, err := (&boxPlatformInterfaceWrapper{}).NetworkInterfaces()
		if err != nil {
			return err
		}
		values := make([]control.Interface, len(interfaces))
		for i := range interfaces {
			values[i] = interfaces[i].Interface
		}
		finder.UpdateInterfaces(values)
		refreshes++
		return nil
	}
	// Deterministic updates without the background polling goroutine.
	monitor.current, monitor.lastData, _ = monitor.readInterfaceData()
	updates := 0
	monitor.RegisterCallback(func(iif *control.Interface, _ int) { updates++ })
	monitor.update()
	if updates != 0 {
		t.Fatal("initial registration/same snapshot must not queue network reset")
	}
	callback.value = networkTestJSON("wlan0", 78, "2001:db8::2/64")
	monitor.update()
	if monitor.DefaultInterface().Index != 78 || refreshes != 1 {
		t.Fatal("same-name replacement not propagated")
	}
	found, err := finder.ByName("wlan0")
	if err != nil || found.Index != 78 || found.Addresses[0].Addr() != netip.MustParseAddr("2001:db8::2") {
		t.Fatalf("stale finder: %+v %v", found, err)
	}
	callback.value = networkTestJSON("wlan0", 78, "2001:db8::3/64")
	monitor.update()
	if refreshes != 2 {
		t.Fatal("address-only change ignored")
	}
	callback.value = networkTestJSON("rmnet_data0", 80, "198.51.100.2/24")
	monitor.update()
	if monitor.DefaultInterface().Name != "rmnet_data0" || len(finder.Interfaces()) != 1 {
		t.Fatal("old network retained")
	}
	callback.value = "{}"
	monitor.update()
	if monitor.DefaultInterface() != nil {
		t.Fatal("broken provider left stale default")
	}
	callback.value = networkTestJSON("wlan0", 81, "192.0.2.3/24")
	monitor.update()
	if monitor.DefaultInterface().Index != 81 || updates != 5 {
		t.Fatalf("recovery/callback failure: %d", updates)
	}
}

// Integration exercise uses the REAL production adapter, Box and URLTest.
// The default is route-selected host ethernet/Wi-Fi; loopback is in the same
// callback for the HTTP probe, but it is NEVER claimed as a physical default.
type liveNetworkCallback struct {
	platformTestInterface
	value     string
	calls     atomic.Int32
	protected atomic.Int32
	noVPN     bool
	stateErr  error
}

func (p *liveNetworkCallback) SocketProtectionRequired() (bool, error) { return !p.noVPN, p.stateErr }
func (p *liveNetworkCallback) NetworkInterfacesJSON() (string, error) {
	p.calls.Add(1)
	return p.value, nil
}
func (p *liveNetworkCallback) AutoDetectInterfaceControl(fd int32) error {
	var stat syscall.Stat_t
	if err := syscall.Fstat(int(fd), &stat); err != nil {
		return err
	}
	kind, err := syscall.GetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_TYPE)
	if err != nil || kind != syscall.SOCK_STREAM {
		return errors.New("protect callback did not receive live TCP socket")
	}
	p.protected.Add(1)
	return nil
}
func hostNetworkCallback(t *testing.T) *liveNetworkCallback {
	t.Helper()
	fixture := newConfigFixturePlatform(t)
	// Host discovery is confined to the test. The production path receives JSON.
	values, err := net.Interfaces()
	if err != nil {
		t.Fatal(err)
	}
	wire := androidNetworkJSON{Version: 1, DefaultInterface: fixture.defaultInterface.Name, DefaultIndex: fixture.defaultInterface.Index}
	for _, value := range values {
		addresses, err := value.Addrs()
		if err != nil {
			t.Fatal(err)
		}
		prefixes := []string{}
		for _, a := range addresses {
			if p, ok := parseInterfacePrefix(a.String()); ok {
				prefixes = append(prefixes, p.String())
			}
		}
		if len(prefixes) == 0 {
			continue
		}
		flags, metered, vpn := uint32(value.Flags), false, false
		wire.Interfaces = append(wire.Interfaces, androidInterfaceJSON{Name: value.Name, Index: value.Index, MTU: value.MTU, Flags: &flags,
			Addresses: prefixes, Type: "ethernet", DNSServers: []string{"192.0.2.53"}, Metered: &metered, VPN: &vpn})
	}
	value, err := json.Marshal(wire)
	if err != nil {
		t.Fatal(err)
	}
	return &liveNetworkCallback{value: string(value)}
}
func TestAndroidNetworkProductionNewStartURLTestClose(t *testing.T) {
	callback := hostNetworkCallback(t)
	runProductionNetworkLifecycle(t, callback, true)
}
func TestAndroidNetworkProductionVPNMainIPC(t *testing.T) {
	callback := hostNetworkCallback(t)
	oldDirectory, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	directory := t.TempDir()
	if err := os.Chdir(directory); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(oldDirectory)
	server, err := serveAndroidProtect(filepath.Join(directory, "protect_path"), func(fd int) error {
		return callback.AutoDetectInterfaceControl(int32(fd))
	})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	runProductionNetworkLifecycle(t, callback, false)
}
func TestAndroidNetworkProductionNoVPNMainAndBackground(t *testing.T) {
	for _, bg := range []bool{false, true} {
		t.Run(fmt.Sprintf("background=%v", bg), func(t *testing.T) {
			callback := hostNetworkCallback(t)
			callback.noVPN = true
			runProductionNetworkLifecycle(t, callback, bg)
		})
	}
}
func runProductionNetworkLifecycle(t *testing.T, callback *liveNetworkCallback, bgProcess bool) {
	t.Helper()
	oldIntf, oldProvider, oldBG := intfBox, networkInterfaceProvider, isBgProcess
	defer func() { intfBox, networkInterfaceProvider, isBgProcess = oldIntf, oldProvider, oldBG }()
	intfBox, isBgProcess = callback, bgProcess
	SetNetworkInterfaceProvider(callback)
	wrapper := boxPlatformInterfaceInstance.(*boxPlatformInterfaceWrapper)
	if !wrapper.UsePlatformNetworkInterfaces() || !wrapper.UsePlatformInterfaceGetter() {
		t.Fatal("production platform getter disabled")
	}
	interfaces, err := wrapper.NetworkInterfaces()
	if err != nil || len(interfaces) == 0 {
		t.Fatalf("production getter: %v", err)
	}
	addresses := wrapper.MyInterfaceAddress()
	if len(addresses) == 0 {
		t.Fatal("production interface addresses missing")
	}
	for _, a := range addresses {
		if a.IsLoopback() {
			t.Fatal("loopback leaked into MyInterfaceAddress")
		}
	}
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodHead {
			t.Errorf("URLTest method: %s", r.Method)
		}
		requests.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	instance, err := NewSingBoxInstance(networkTestConfig, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if service.FromContext[adapter.PlatformInterface](instance.ctx) != boxPlatformInterfaceInstance {
		t.Fatal("host adapter override masked production")
	}
	beforeInterfaces := instance.Network().NetworkInterfaces()
	if len(beforeInterfaces) == 0 || len(instance.Network().InterfaceFinder().Interfaces()) == 0 {
		t.Fatal("production New did not populate core finder")
	}
	if !reflect.DeepEqual(beforeInterfaces, interfaces) {
		t.Fatal("core finder/network list differs from callback before Start")
	}
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	iif := instance.Network().InterfaceMonitor().DefaultInterface()
	if iif == nil || iif.Flags&net.FlagLoopback != 0 {
		t.Fatalf("bad production default: %+v", iif)
	}
	if err := validateAndroidNetworkRefresh(instance.Network().NetworkInterfaces(), iif, callback.value); err != nil {
		t.Fatal(err)
	}
	if _, err := UrlTest(instance, server.URL, 5000); err != nil {
		t.Fatal(err)
	}
	if requests.Load() < 2 || (!callback.noVPN && callback.protected.Load() == 0) || callback.calls.Load() < 3 {
		t.Fatalf("missing real two-HEAD/protect/JSON path: HEAD=%d protect=%d callback=%d", requests.Load(), callback.protected.Load(), callback.calls.Load())
	}
	if callback.noVPN && callback.protected.Load() != 0 {
		t.Fatal("no-VPN path unnecessarily invoked VPN protect")
	}
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
	if !errors.Is(instance.ctx.Err(), context.Canceled) {
		t.Fatal("Box Close did not cancel lifetime")
	}
	if _, err := UrlTest(instance, server.URL, 5000); err == nil {
		t.Fatal("closed Box still accepts URLTest")
	}
}

func TestAndroidNetworkSnapshotMultinetworkVPN(t *testing.T) {
	var data androidNetworkJSON
	if err := json.Unmarshal([]byte(networkTestJSON("wlan0", 77, "192.0.2.2/24", "2001:db8::2/64")), &data); err != nil {
		t.Fatal(err)
	}
	cellular := data.Interfaces[0]
	flags, metered, vpn := *cellular.Flags, true, false
	cellular.Name, cellular.Index, cellular.Type, cellular.Addresses = "rmnet_data0", 78, "cellular", []string{"198.51.100.2/24"}
	cellular.Flags, cellular.Metered, cellular.VPN = &flags, &metered, &vpn
	tunnel := cellular
	tunnelVPN := true
	tunnel.Name, tunnel.Index, tunnel.Type, tunnel.VPN = "tun0", 79, "other", &tunnelVPN
	data.Interfaces = append(data.Interfaces, cellular, tunnel)
	raw, _ := json.Marshal(data)
	snapshot, err := parseAndroidNetworkSnapshot(string(raw))
	if err != nil || len(snapshot.interfaces) != 3 || snapshot.defaultInterface.Name != "wlan0" || !snapshot.interfaces[1].Expensive {
		t.Fatalf("multi-network/VPN: %+v %v", snapshot, err)
	}
	data.DefaultInterface, data.DefaultIndex = "tun0", 79
	raw, _ = json.Marshal(data)
	if _, err := parseAndroidNetworkSnapshot(string(raw)); err == nil {
		t.Fatal("VPN default accepted")
	}
}

func TestAndroidSocketProtectionStateFailClosed(t *testing.T) {
	oldIntf, oldProvider, oldBG := intfBox, networkInterfaceProvider, isBgProcess
	defer func() { intfBox, networkInterfaceProvider, isBgProcess = oldIntf, oldProvider, oldBG }()
	wrapper := &boxPlatformInterfaceWrapper{}
	intfBox, networkInterfaceProvider = nil, nil
	if err := wrapper.AutoDetectInterfaceControl(-1); err == nil {
		t.Fatal("missing state provider allowed socket")
	}
	callback := &liveNetworkCallback{stateErr: errors.New("VPN status permission denied")}
	intfBox = callback
	SetNetworkInterfaceProvider(callback)
	for _, bg := range []bool{false, true} {
		isBgProcess = bg
		if err := wrapper.AutoDetectInterfaceControl(-1); !errors.Is(err, callback.stateErr) {
			t.Fatalf("VPN state error swallowed: %v", err)
		}
		callback.stateErr, callback.noVPN = nil, true
		if err := wrapper.AutoDetectInterfaceControl(-1); err != nil {
			t.Fatalf("confirmed no VPN tried IPC/JNI: %v", err)
		}
		callback.stateErr, callback.noVPN = errors.New("VPN status permission denied"), false
	}
	callback.stateErr = nil
	SetNetworkInterfaceProvider(callback)
	intfBox, isBgProcess = nil, true
	if err := wrapper.AutoDetectInterfaceControl(-1); err == nil {
		t.Fatal("required protection without VPN JNI accepted")
	}
}

func TestAndroidNetworkMonitorInstancesIndependent(t *testing.T) {
	first, second := newInterfaceMonitor(), newInterfaceMonitor()
	value := networkTestJSON("wlan0", 77, "192.0.2.2/24")
	first.readSnapshot = func() (*androidNetworkSnapshot, error) { return parseAndroidNetworkSnapshot(value) }
	second.readSnapshot = func() (*androidNetworkSnapshot, error) { return parseAndroidNetworkSnapshot(value) }
	if err := first.Start(); err != nil {
		t.Fatal(err)
	}
	if err := second.Start(); err != nil {
		first.Close()
		t.Fatal(err)
	}
	defer second.Close()
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}
	if second.DefaultInterface() == nil {
		t.Fatal("closing one instance removed other's interface")
	}
	clone := second.DefaultInterface()
	clone.Addresses[0] = netip.MustParsePrefix("198.51.100.4/24")
	if second.DefaultInterface().Addresses[0] == clone.Addresses[0] {
		t.Fatal("default address snapshot aliases caller")
	}
}

func TestAndroidNetworkRefreshRejectsRacingSnapshot(t *testing.T) {
	before, err := parseAndroidNetworkSnapshot(networkTestJSON("wlan0", 77, "192.0.2.2/24"))
	if err != nil {
		t.Fatal(err)
	}
	after, err := parseAndroidNetworkSnapshot(networkTestJSON("wlan0", 78, "2001:db8::2/64"))
	if err != nil {
		t.Fatal(err)
	}
	if err := validateAndroidNetworkRefresh(after.interfaces, before.defaultInterface, before.fingerprint); err == nil {
		t.Fatal("stale default published against fresh finder")
	}
	if err := validateAndroidNetworkRefresh(after.interfaces, after.defaultInterface, after.fingerprint); err != nil {
		t.Fatal(err)
	}
	monitor := newInterfaceMonitor()
	monitor.readSnapshot = func() (*androidNetworkSnapshot, error) { return before, nil }
	monitor.refresh = func() error { return nil }
	monitor.validate = func(iif *control.Interface, data string) error {
		return validateAndroidNetworkRefresh(after.interfaces, iif, data)
	}
	if err := monitor.Start(); err == nil {
		monitor.Close()
		t.Fatal("monitor started with a racing/stale snapshot")
	}
}
