package libcore

import (
	"fmt"
	"libcore/procfs"
	"log"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/adapter"
	libbox "github.com/sagernet/sing-box/experimental/libbox"
	sblog "github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
	N "github.com/sagernet/sing/common/network"
)

var boxPlatformInterfaceInstance libbox.PlatformInterface = &boxPlatformInterfaceWrapper{}

type boxPlatformInterfaceWrapper struct {
	localDNS      LocalDNSTransport
	monitorAccess sync.Mutex
	monitorDone   chan struct{}
	monitorWG     sync.WaitGroup
	monitor       libbox.InterfaceUpdateListener
	myInterface   string
}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState() *libbox.WIFIState {
	if intfBox == nil {
		return nil
	}
	state := strings.Split(intfBox.WIFIState(), ",")
	if len(state) < 2 {
		return nil
	}
	return libbox.NewWIFIState(state[0], state[1])
}

func (w *boxPlatformInterfaceWrapper) Initialize(n adapter.NetworkManager) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) Close() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) AutoDetectInterfaceControl(fd int32) error {
	if intfBox == nil {
		return fmt.Errorf("android: box platform interface unavailable")
	}
	if !isBgProcess {
		return sendFdToProtect(int(fd), "protect_path")
	}
	// bg process call VPNService
	return intfBox.AutoDetectInterfaceControl(fd)
}

// 1.14: OpenTun returns the tun file descriptor; the tunnel object is created
// by the core from it.
// 1.14: OpenTun receives the libbox.TunOptions handle from the Java side and
// returns the duplicated tunnel fd; the core builds the tunnel itself.
func (w *boxPlatformInterfaceWrapper) OpenTun(options libbox.TunOptions) (int32, error) {
	if options == nil {
		return 0, fmt.Errorf("missing tun options")
	}
	singTunOptionsJSON, platformOptionsJSON, err := libbox.MarshalTunOptions(options)
	if err != nil {
		return 0, fmt.Errorf("marshal tun options: %w", err)
	}
	tunFd, err := intfBox.OpenTun(string(singTunOptionsJSON), string(platformOptionsJSON))
	if err != nil {
		return 0, fmt.Errorf("intfBox.OpenTun: %w", err)
	}
	return int32(tunFd), nil
}

func (w *boxPlatformInterfaceWrapper) FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (*libbox.ConnectionOwner, error) {
	source, err := netip.ParseAddrPort(net.JoinHostPort(sourceAddress, strconv.Itoa(int(sourcePort))))
	if err != nil {
		return nil, E.Cause(err, "parse source address")
	}
	destination, err := netip.ParseAddrPort(net.JoinHostPort(destinationAddress, strconv.Itoa(int(destinationPort))))
	if err != nil {
		return nil, E.Cause(err, "parse destination address")
	}
	var network string
	switch ipProtocol {
	case syscall.IPPROTO_TCP:
		network = N.NetworkTCP
	case syscall.IPPROTO_UDP:
		network = N.NetworkUDP
	default:
		return nil, E.New("unknown IP protocol: ", ipProtocol)
	}
	var uid int32
	if useProcfs {
		uid = procfs.ResolveSocketByProcSearch(network, source, destination)
		if uid == -1 {
			return nil, E.New("procfs: not found")
		}
	} else {
		var err error
		uid, err = intfBox.FindConnectionOwner(ipProtocol, source.Addr().String(), int32(source.Port()), destination.Addr().String(), int32(destination.Port()))
		if err != nil {
			return nil, err
		}
	}
	packageName, _ := intfBox.PackageNameByUid(uid)
	owner := &libbox.ConnectionOwner{UserId: int32(uid)}
	if packageName != "" {
		owner.SetAndroidPackageNames(newStringIterator([]string{packageName}))
	}
	return owner, nil
}

func newStringIterator(items []string) libbox.StringIterator {
	return &stringIterator{items: items}
}

type stringIterator struct {
	items []string
	pos   int
}

func (it *stringIterator) Len() int32 {
	return int32(len(it.items))
}

func (it *stringIterator) Next() string {
	if !it.HasNext() {
		return ""
	}
	item := it.items[it.pos]
	it.pos++
	return item
}

func (it *stringIterator) HasNext() bool {
	return it.pos < len(it.items)
}

func (w *boxPlatformInterfaceWrapper) LocalDNSTransport() libbox.LocalDNSTransport {
	if w.localDNS == nil {
		return nil
	}
	return &localTransportWrapper{iif: w.localDNS}
}

// localTransportWrapper adapts the gobind-bound LocalDNSTransport to the
// libbox interface expected by the 1.14 platform DNS transport.
type localTransportWrapper struct {
	iif LocalDNSTransport
}

func (w *localTransportWrapper) Raw() bool { return w.iif != nil && w.iif.Raw() }

func (w *localTransportWrapper) Lookup(lctx *libbox.ExchangeContext, network string, domain string) error {
	if w.iif == nil {
		return E.New("android: local DNS transport unavailable")
	}
	return w.iif.Lookup(&ExchangeContext{inner: lctx}, network, domain)
}

func (w *localTransportWrapper) Exchange(lctx *libbox.ExchangeContext, message []byte) error {
	if w.iif == nil {
		return E.New("android: local DNS transport unavailable")
	}
	return w.iif.Exchange(&ExchangeContext{inner: lctx}, message)
}

func (w *boxPlatformInterfaceWrapper) StartDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	if listener == nil {
		return fmt.Errorf("nil interface monitor listener")
	}
	w.monitorAccess.Lock()
	defer w.monitorAccess.Unlock()
	if w.monitorDone != nil {
		return fmt.Errorf("default interface monitor already started")
	}
	w.monitor = listener
	w.monitorDone = make(chan struct{})
	done := w.monitorDone
	w.monitorWG.Add(1)
	go func() {
		defer w.monitorWG.Done()
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		var lastName string
		var lastIndex int32 = -2
		for {
			name, index := androidDefaultInterface()
			if name != lastName || index != lastIndex {
				listener.UpdateDefaultInterface(name, index, false, false)
				listener.UpdateNetworkPath(name)
				lastName, lastIndex = name, index
			}
			select {
			case <-ticker.C:
			case <-done:
				return
			}
		}
	}()
	return nil
}

func (w *boxPlatformInterfaceWrapper) CloseDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	w.monitorAccess.Lock()
	if w.monitorDone == nil {
		w.monitorAccess.Unlock()
		return nil
	}
	close(w.monitorDone)
	w.monitorDone = nil
	w.monitor = nil
	w.monitorAccess.Unlock()
	w.monitorWG.Wait()
	return nil
}

func androidDefaultInterfaceName() string {
	if intfBox == nil {
		return ""
	}
	return intfBox.DefaultNetworkInterface()
}

func splitLines(value string) []string {
	var result []string
	for _, line := range strings.Split(value, "\n") {
		if line = strings.TrimSpace(line); line != "" {
			result = append(result, line)
		}
	}
	return result
}

func androidDefaultInterface() (string, int32) {
	if intfBox != nil {
		if name := intfBox.DefaultNetworkInterface(); name != "" {
			if iface, err := net.InterfaceByName(name); err == nil {
				return iface.Name, int32(iface.Index)
			}
		}
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return "", -1
	}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		if strings.HasPrefix(iface.Name, "tun") || strings.HasPrefix(iface.Name, "utun") {
			continue
		}
		return iface.Name, int32(iface.Index)
	}
	return "", -1
}

func (w *boxPlatformInterfaceWrapper) GetInterfaces() (libbox.NetworkInterfaceIterator, error) {
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, E.Cause(err, "enumerate network interfaces")
	}
	items := make([]*libbox.NetworkInterface, 0, len(interfaces))
	for _, iface := range interfaces {
		addresses, err := iface.Addrs()
		if err != nil {
			return nil, E.Cause(err, "read interface ", iface.Name)
		}
		var prefixes []string
		for _, addr := range addresses {
			if prefix, ok := parseInterfacePrefix(addr.String()); ok {
				prefixes = append(prefixes, prefix.String())
			}
		}
		var dnsServers []string
		var gateways []string
		if intfBox != nil && iface.Name == androidDefaultInterfaceName() {
			dnsServers = splitLines(intfBox.DefaultNetworkDNSServers())
			gateways = splitLines(intfBox.DefaultNetworkGateways())
		}
		items = append(items, &libbox.NetworkInterface{
			Index: int32(iface.Index), MTU: int32(iface.MTU), Name: iface.Name,
			Addresses: newStringIterator(prefixes), Flags: int32(iface.Flags),
			Type: libbox.InterfaceTypeOther, DNSServer: newStringIterator(dnsServers), Gateway: newStringIterator(gateways),
		})
	}
	return &networkInterfaceIterator{items: items}, nil
}

func parseInterfacePrefix(value string) (netip.Prefix, bool) {
	value = strings.TrimSpace(value)
	if slash := strings.LastIndexByte(value, '/'); slash >= 0 {
		address := value[:slash]
		if zone := strings.LastIndexByte(address, '%'); zone >= 0 {
			address = address[:zone]
		}
		prefix, err := netip.ParsePrefix(address + value[slash:])
		return prefix, err == nil
	}
	if zone := strings.LastIndexByte(value, '%'); zone >= 0 {
		value = value[:zone]
	}
	address, err := netip.ParseAddr(value)
	if err != nil {
		return netip.Prefix{}, false
	}
	return netip.PrefixFrom(address, address.BitLen()), true
}

type networkInterfaceIterator struct {
	items []*libbox.NetworkInterface
	pos   int
}

func (it *networkInterfaceIterator) HasNext() bool { return it.pos < len(it.items) }
func (it *networkInterfaceIterator) Next() *libbox.NetworkInterface {
	if !it.HasNext() {
		return nil
	}
	item := it.items[it.pos]
	it.pos++
	return item
}

func (w *boxPlatformInterfaceWrapper) UnderNetworkExtension() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) IncludeAllNetworks() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) ClearDNSCache() {
}

func (w *boxPlatformInterfaceWrapper) SendNotification(notification *libbox.Notification) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) CancelNotification(identifier string, typeID int32) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) StartNeighborMonitor(listener libbox.NeighborUpdateListener) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) CloseNeighborMonitor(listener libbox.NeighborUpdateListener) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) RegisterMyInterface(name string) {
	w.monitorAccess.Lock()
	defer w.monitorAccess.Unlock()
	// The current Android bridge has no separate callback for the TUN name;
	// retain it for future monitor updates instead of silently dropping it.
	w.myInterface = name
}

func (w *boxPlatformInterfaceWrapper) UsePlatformShell() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CheckPlatformShell() error {
	return E.New("android: platform shell not supported")
}

func (w *boxPlatformInterfaceWrapper) OpenShellSession(user *libbox.PlatformUser, command string, environ libbox.StringIterator, term string, rows int32, cols int32) (libbox.ShellSession, error) {
	return nil, E.New("android: platform shell not supported")
}

func (w *boxPlatformInterfaceWrapper) LookupUser(username string) (*libbox.PlatformUser, error) {
	return nil, E.New("android: platform user lookup not supported")
}

func (w *boxPlatformInterfaceWrapper) LookupSFTPServer() (string, error) {
	return "", E.New("android: platform sftp not supported")
}

func (w *boxPlatformInterfaceWrapper) ReadSystemSSHHostKey() (string, error) {
	return "", E.New("android: platform ssh not supported")
}

func (w *boxPlatformInterfaceWrapper) TailscaleHostname() string {
	return ""
}

func (w *boxPlatformInterfaceWrapper) UsePlatformBridge() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CreateBridge(options *libbox.BridgeOptions) (libbox.BridgeSession, error) {
	return nil, E.New("android: platform bridge not supported")
}

// io.Writer (legacy log drain)

var disableSingBoxLog = false

func (w *boxPlatformInterfaceWrapper) Write(p []byte) (n int, err error) {
	// use neko_log
	if !disableSingBoxLog {
		log.Print(string(p))
	}
	return len(p), nil
}

// 日志

type boxPlatformLogWriterWrapper struct {
}

var boxPlatformLogWriter sblog.PlatformWriter = &boxPlatformLogWriterWrapper{}

func (w *boxPlatformLogWriterWrapper) DisableColors() bool { return true }

func (w *boxPlatformLogWriterWrapper) WriteMessage(level uint8, message string) {
	if !strings.HasSuffix(message, "\n") {
		message += "\n"
	}
	neko_log.LogWriter.Write([]byte(message))
}
