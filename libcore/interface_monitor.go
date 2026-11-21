package libcore

import (
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/x/list"
)

// Poll Android's actual underlying-network LinkProperties. We deliberately do
// not use netlink (banned for ordinary Android apps) or first-up heuristics.
// The added JNI getters are snapshots, not NetworkCallback events; hence the
// one-second polling precision. Missing networks are reported as missing.
type interfaceMonitor struct {
	access       sync.Mutex
	current      *control.Interface
	callbacks    list.List[tun.DefaultInterfaceUpdateCallback]
	myInterfaces []string
	done         chan struct{}
	wait         sync.WaitGroup
	getter       func() string
	lookup       func(string) (*net.Interface, error)
	lastData     string
}

var _ tun.DefaultInterfaceMonitor = (*interfaceMonitor)(nil)

func newInterfaceMonitor() *interfaceMonitor {
	return &interfaceMonitor{getter: func() string {
		if intfBox == nil {
			return ""
		}
		return intfBox.DefaultNetworkInterface()
	}, lookup: net.InterfaceByName}
}
func (s *interfaceMonitor) readInterface() (*control.Interface, error) {
	name := strings.TrimSpace(s.getter())
	if name == "" {
		return nil, nil
	}
	s.access.Lock()
	isMine := false
	for _, it := range s.myInterfaces {
		if it == name {
			isMine = true
		}
	}
	s.access.Unlock()
	if isMine || strings.HasPrefix(name, "tun") || strings.HasPrefix(name, "utun") {
		return nil, fmt.Errorf("Android underlying network getter returned VPN interface %q", name)
	}
	iif, err := s.lookup(name)
	if err != nil {
		return nil, err
	}
	if iif.Flags&net.FlagUp == 0 || iif.Flags&net.FlagLoopback != 0 {
		return nil, fmt.Errorf("Android underlying interface %q is not usable", name)
	}
	prefixes := []netip.Prefix{}
	addresses, err := iif.Addrs()
	if err != nil {
		return nil, err
	}
	for _, address := range addresses {
		if prefix, ok := parseInterfacePrefix(address.String()); ok {
			prefixes = append(prefixes, prefix)
		}
	}
	return &control.Interface{Index: iif.Index, MTU: iif.MTU, Name: iif.Name, Flags: iif.Flags, Addresses: prefixes}, nil
}
func parseInterfacePrefix(value string) (netip.Prefix, bool) {
	if slash := strings.LastIndexByte(value, '/'); slash >= 0 {
		host := value[:slash]
		if zone := strings.LastIndexByte(host, '%'); zone >= 0 {
			host = host[:zone]
		}
		prefix, err := netip.ParsePrefix(host + value[slash:])
		return prefix, err == nil
	}
	if zone := strings.LastIndexByte(value, '%'); zone >= 0 {
		value = value[:zone]
	}
	addr, err := netip.ParseAddr(value)
	if err != nil {
		return netip.Prefix{}, false
	}
	return netip.PrefixFrom(addr, addr.BitLen()), true
}
func (s *interfaceMonitor) Start() error {
	s.access.Lock()
	if s.done != nil {
		s.access.Unlock()
		return fmt.Errorf("interface monitor already started")
	}
	s.access.Unlock()
	iif, err := s.readInterface()
	if err != nil {
		return err
	}
	if iif == nil {
		return fmt.Errorf("Android underlying network is unavailable")
	}
	s.access.Lock()
	s.current = iif
	s.done = make(chan struct{})
	done := s.done
	s.wait.Add(1)
	s.access.Unlock()
	go func() {
		defer s.wait.Done()
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				s.update()
			case <-done:
				return
			}
		}
	}()
	return nil
}
func (s *interfaceMonitor) update() {
	next, err := s.readInterface()
	if err != nil {
		next = nil
	}
	data := ""
	if intfBox != nil {
		data = intfBox.DefaultNetworkDNSServers() + "\n" + intfBox.DefaultNetworkGateways()
	}
	s.access.Lock()
	previous := s.current
	changed := previous == nil && next != nil || previous != nil && next == nil || previous != nil && next != nil && (previous.Index != next.Index || previous.Name != next.Name) || data != s.lastData
	s.lastData = data
	s.current = next
	callbacks := []tun.DefaultInterfaceUpdateCallback{}
	if changed {
		for it := s.callbacks.Front(); it != nil; it = it.Next() {
			callbacks = append(callbacks, it.Value)
		}
	}
	s.access.Unlock()
	for _, callback := range callbacks {
		callback(next, 0)
	}
}
func (s *interfaceMonitor) Close() error {
	s.access.Lock()
	if s.done == nil {
		s.access.Unlock()
		return nil
	}
	close(s.done)
	s.done = nil
	s.current = nil
	s.access.Unlock()
	s.wait.Wait()
	return nil
}
func (s *interfaceMonitor) DefaultInterface() *control.Interface {
	s.access.Lock()
	defer s.access.Unlock()
	if s.current == nil {
		return nil
	}
	clone := *s.current
	clone.Addresses = append([]netip.Prefix(nil), s.current.Addresses...)
	return &clone
}
func (s *interfaceMonitor) OverrideAndroidVPN() bool { return true }
func (s *interfaceMonitor) AndroidVPNEnabled() bool  { return false }
func (s *interfaceMonitor) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	s.access.Lock()
	element := s.callbacks.PushBack(callback)
	current := s.current
	s.access.Unlock()
	if current != nil {
		callback(current, 0)
	}
	return element
}
func (s *interfaceMonitor) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	if element == nil {
		return
	}
	s.access.Lock()
	s.callbacks.Remove(element)
	s.access.Unlock()
}
func (s *interfaceMonitor) RegisterMyInterface(name string) {
	s.access.Lock()
	defer s.access.Unlock()
	for _, value := range s.myInterfaces {
		if value == name {
			return
		}
	}
	s.myInterfaces = append(s.myInterfaces, name)
}
func (s *interfaceMonitor) MyInterfaces() []string {
	s.access.Lock()
	defer s.access.Unlock()
	return append([]string(nil), s.myInterfaces...)
}
