package libcore

import (
	"fmt"
	"net/netip"
	"reflect"
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
	readSnapshot func() (*androidNetworkSnapshot, error)
	// Explicit host-test hooks only; production reads the complete JNI snapshot.
	getter   func() string
	lookup   func(string) (*control.Interface, error)
	refresh  func() error
	validate func(*control.Interface, string) error
	lastData string
}

var _ tun.DefaultInterfaceMonitor = (*interfaceMonitor)(nil)

func newInterfaceMonitor() *interfaceMonitor {
	return &interfaceMonitor{readSnapshot: readAndroidNetworkSnapshot}
}
func (s *interfaceMonitor) readInterfaceData() (*control.Interface, string, error) {
	var iif *control.Interface
	var data string
	if s.getter != nil {
		name := strings.TrimSpace(s.getter())
		if name == "" {
			return nil, "", nil
		}
		if strings.HasPrefix(name, "tun") || strings.HasPrefix(name, "utun") {
			return nil, "", fmt.Errorf("VPN cannot be the underlying interface: %q", name)
		}
		if s.lookup == nil {
			return nil, "", fmt.Errorf("host interface lookup was not explicitly injected")
		}
		var err error
		iif, err = s.lookup(name)
		if err != nil {
			return nil, "", err
		}
	} else {
		snapshot, err := s.readSnapshot()
		if err != nil {
			return nil, "", err
		}
		iif, data = snapshot.defaultInterface, snapshot.fingerprint
	}
	if iif == nil {
		return nil, data, nil
	}
	s.access.Lock()
	isMine := false
	for _, name := range s.myInterfaces {
		if name == iif.Name {
			isMine = true
		}
	}
	s.access.Unlock()
	if isMine || strings.HasPrefix(iif.Name, "tun") || strings.HasPrefix(iif.Name, "utun") {
		return nil, "", fmt.Errorf("Android underlying network getter returned VPN interface %q", iif.Name)
	}
	return iif, data, nil
}
func (s *interfaceMonitor) readInterface() (*control.Interface, error) {
	iif, _, err := s.readInterfaceData()
	return iif, err
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
	iif, data, err := s.readInterfaceData()
	if err != nil {
		return err
	}
	// Publish only metadata that is present in this instance's refreshed finder.
	// A network can switch between the monitor read and UpdateInterfaces callback.
	if s.refresh != nil {
		if err := s.refresh(); err != nil {
			return err
		}
		if s.validate != nil {
			if err := s.validate(iif, data); err != nil {
				return err
			}
		}
	}
	if iif == nil {
		return fmt.Errorf("Android underlying network is unavailable")
	}
	s.access.Lock()
	s.current = iif
	s.lastData = data
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
	next, data, err := s.readInterfaceData()
	if err != nil {
		next = nil
	}
	s.access.Lock()
	previous := s.current
	changed := !reflect.DeepEqual(previous, next) || data != s.lastData
	s.access.Unlock()
	// Replace, never append/cache by name. DNS/address/MTU changes and a reused
	// name with a new index must reach the core finder before callbacks wake it.
	if changed && s.refresh != nil {
		if err := s.refresh(); err != nil {
			next, data = nil, ""
		} else if s.validate != nil {
			if err := s.validate(next, data); err != nil {
				next, data = nil, ""
			}
		}
	}
	s.access.Lock()
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
	s.access.Unlock()
	// Initial state is consumed by core PostStart. Registration must not queue
	// ResetNetwork: it races and can close the very first URLTest HEAD socket.
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
