package libcore

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"reflect"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing/common/control"
)

var intfBox BoxPlatformInterface
var intfNB4A NB4AInterface
var networkInterfaceProvider NetworkInterfaceProvider

// Separate gomobile callback: keep BoxPlatformInterface and InitCore ABI intact.
// The JSON is one versioned snapshot, including the underlying default identity.
type NetworkInterfaceProvider interface {
	NetworkInterfacesJSON() (string, error)
	// False is valid only when the platform confirms no VPN is active/starting.
	SocketProtectionRequired() (bool, error)
}

func SetNetworkInterfaceProvider(provider NetworkInterfaceProvider) {
	networkInterfaceProvider = provider
}

var useProcfs bool
var isBgProcess bool

type NB4AInterface interface {
	UseOfficialAssets() bool
	Selector_OnProxySelected(selectorTag string, tag string)
}

type BoxPlatformInterface interface {
	AutoDetectInterfaceControl(fd int32) error
	OpenTun(singTunOptionsJson, tunPlatformOptionsJson string) (int, error)
	UseProcFS() bool
	FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (int32, error)
	PackageNameByUid(uid int32) (string, error)
	UIDByPackageName(packageName string) (int32, error)
	WIFIState() string
	// Existing JNI methods remain; these getters identify Android's actual
	// underlying LinkProperties rather than guessing from up interfaces.
	DefaultNetworkInterface() string
	DefaultNetworkDNSServers() string
	DefaultNetworkGateways() string
}

// androidNetworkJSON uses Go net.Flags bit positions, NOT Linux IFF_* values.
// No Go net.Interface lookup/enumeration belongs on this path.
type androidNetworkJSON struct {
	Version          int                    `json:"version"`
	DefaultInterface string                 `json:"defaultInterface"`
	DefaultIndex     int                    `json:"defaultIndex"`
	Interfaces       []androidInterfaceJSON `json:"interfaces"`
}
type androidInterfaceJSON struct {
	Name             string   `json:"name"`
	Index            int      `json:"index"`
	MTU              int      `json:"mtu"`
	Flags            *uint32  `json:"flags"`
	Addresses        []string `json:"addresses"`
	Type             string   `json:"type"`
	DNSServers       []string `json:"dnsServers"`
	DNSSearchDomains []string `json:"dnsSearchDomains"`
	Gateways         []string `json:"gateways"`
	Metered          *bool    `json:"metered"`
	VPN              *bool    `json:"vpn"`
}
type androidNetworkSnapshot struct {
	interfaces       []adapter.NetworkInterface
	defaultInterface *control.Interface
	fingerprint      string
}

func androidNetworkProvider() NetworkInterfaceProvider {
	provider := networkInterfaceProvider
	// Direct Go implementations can satisfy both contracts. The gomobile proxy
	// requires the explicit setter: type assertions do not discover Java methods.
	if provider == nil {
		provider, _ = intfBox.(NetworkInterfaceProvider)
	}
	return provider
}
func readAndroidNetworkSnapshot() (*androidNetworkSnapshot, error) {
	provider := androidNetworkProvider()
	if provider == nil {
		return nil, errors.New("Android network interface provider is not initialized")
	}
	value, err := provider.NetworkInterfacesJSON()
	if err != nil {
		return nil, fmt.Errorf("Android network interface callback: %w", err)
	}
	return parseAndroidNetworkSnapshot(value)
}
func parseAndroidNetworkSnapshot(value string) (*androidNetworkSnapshot, error) {
	var data androidNetworkJSON
	decoder := json.NewDecoder(strings.NewReader(value))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&data); err != nil {
		return nil, fmt.Errorf("Android network snapshot JSON: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return nil, errors.New("Android network snapshot has trailing JSON")
	}
	if data.Version != 1 || len(data.Interfaces) == 0 {
		return nil, errors.New("Android network snapshot has unsupported version or no interfaces")
	}
	snapshot := &androidNetworkSnapshot{}
	names, indexes := make(map[string]bool), make(map[int]bool)
	for _, raw := range data.Interfaces {
		if raw.Name == "" || strings.TrimSpace(raw.Name) != raw.Name || raw.Index <= 0 || raw.MTU <= 0 || raw.Flags == nil || raw.Metered == nil || raw.VPN == nil || len(raw.Addresses) == 0 {
			return nil, fmt.Errorf("Android interface %q has missing/invalid metadata", raw.Name)
		}
		if names[raw.Name] || indexes[raw.Index] {
			return nil, fmt.Errorf("Android interface %q has duplicate name/index", raw.Name)
		}
		names[raw.Name], indexes[raw.Index] = true, true
		interfaceType, ok := C.StringToInterfaceType[raw.Type]
		if !ok || *raw.Flags & ^uint32(63) != 0 {
			return nil, fmt.Errorf("Android interface %q has invalid type/flags", raw.Name)
		}
		iif := adapter.NetworkInterface{Interface: control.Interface{Name: raw.Name, Index: raw.Index, MTU: raw.MTU, Flags: net.Flags(*raw.Flags)}, Type: interfaceType, Expensive: *raw.Metered, DNSSearchDomains: raw.DNSSearchDomains}
		for _, address := range raw.Addresses {
			prefix, ok := parseInterfacePrefix(address)
			if !ok || !strings.Contains(address, "/") {
				return nil, fmt.Errorf("Android interface %q has invalid prefix %q", raw.Name, address)
			}
			iif.Addresses = append(iif.Addresses, prefix)
		}
		for _, server := range raw.DNSServers {
			address, err := netip.ParseAddr(server)
			if err != nil {
				return nil, fmt.Errorf("Android interface %q has invalid DNS: %w", raw.Name, err)
			}
			iif.DNSServers = append(iif.DNSServers, address.String())
		}
		for _, gateway := range raw.Gateways {
			address, err := netip.ParseAddr(gateway)
			if err != nil {
				return nil, fmt.Errorf("Android interface %q has invalid gateway: %w", raw.Name, err)
			}
			iif.Gateways = append(iif.Gateways, address)
		}
		snapshot.interfaces = append(snapshot.interfaces, iif)
		if raw.Name == data.DefaultInterface {
			if raw.Index != data.DefaultIndex || *raw.VPN || iif.Flags&net.FlagUp == 0 || iif.Flags&net.FlagLoopback != 0 || strings.HasPrefix(raw.Name, "tun") || strings.HasPrefix(raw.Name, "utun") {
				return nil, fmt.Errorf("Android default interface %q is not a usable underlying network", raw.Name)
			}
			defaultInterface := iif.Interface
			snapshot.defaultInterface = &defaultInterface
		}
	}
	if data.DefaultInterface != "" && snapshot.defaultInterface == nil || data.DefaultInterface == "" && data.DefaultIndex != 0 {
		return nil, errors.New("Android default interface is absent from snapshot")
	}
	// Canonical JSON: ordering is deterministic on the Kotlin side. Includes
	// addresses/MTU/flags/DNS/type/metered and all network changes, not just name.
	canonical, _ := json.Marshal(data)
	snapshot.fingerprint = string(canonical)
	return snapshot, nil
}

// Validate against the already refreshed per-Box list without calling ByName or
// ByIndex (their cache-miss fallback is Android netlink). Fail closed on a racing
// snapshot; the next poll retries rather than publishing a stale index/address.
func validateAndroidNetworkRefresh(available []adapter.NetworkInterface, iif *control.Interface, data string) error {
	if iif == nil {
		return nil
	}
	snapshot, err := parseAndroidNetworkSnapshot(data)
	if err != nil {
		return err
	}
	expected := make([]adapter.NetworkInterface, 0, len(snapshot.interfaces))
	for _, it := range snapshot.interfaces {
		if it.Flags&net.FlagUp != 0 {
			expected = append(expected, it)
		}
	}
	if !reflect.DeepEqual(expected, available) {
		return errors.New("Android network changed while refreshing interfaces")
	}
	for _, it := range available {
		if it.Index == iif.Index && it.Name == iif.Name && reflect.DeepEqual(it.Interface, *iif) {
			return nil
		}
	}
	return errors.New("Android default interface is missing from refreshed interfaces")
}
