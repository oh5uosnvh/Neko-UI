package libcore

import (
	"fmt"
	"libcore/procfs"
	"log"
	"net/netip"
	"strings"
	"syscall"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/adapter"
	libbox "github.com/sagernet/sing-box/experimental/libbox"
	sblog "github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
	N "github.com/sagernet/sing/common/network"
)

var boxPlatformInterfaceInstance libbox.PlatformInterface = &boxPlatformInterfaceWrapper{}

type boxPlatformInterfaceWrapper struct{}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState() *libbox.WIFIState {
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
	// call protect_path
	if !isBgProcess {
		_ = sendFdToProtect(int(fd), "protect_path")
		return nil
	}
	// bg process call VPNService
	return intfBox.AutoDetectInterfaceControl(fd)
}

// 1.14: OpenTun returns the tun file descriptor; the tunnel object is created
// by the core from it.
// 1.14: OpenTun receives the libbox.TunOptions handle from the Java side and
// returns the duplicated tunnel fd; the core builds the tunnel itself.
func (w *boxPlatformInterfaceWrapper) OpenTun(options libbox.TunOptions) (int32, error) {
	tunFd, err := intfBox.OpenTun("", "")
	if err != nil {
		return 0, fmt.Errorf("intfBox.OpenTun: %v", err)
	}
	tunFd, err = syscall.Dup(tunFd)
	if err != nil {
		return 0, fmt.Errorf("syscall.Dup: %v", err)
	}
	return int32(tunFd), nil
}

func (w *boxPlatformInterfaceWrapper) FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (*libbox.ConnectionOwner, error) {
	source := netip.MustParseAddrPort(sourceAddress + ":" + fmt.Sprint(sourcePort))
	destination := netip.MustParseAddrPort(destinationAddress + ":" + fmt.Sprint(destinationPort))
	network := N.NetworkTCP
	if ipProtocol == syscall.IPPROTO_UDP {
		network = N.NetworkUDP
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
	item := it.items[it.pos]
	it.pos++
	return item
}

func (it *stringIterator) HasNext() bool {
	return it.pos < len(it.items)
}

func (w *boxPlatformInterfaceWrapper) LocalDNSTransport() libbox.LocalDNSTransport {
	return &localTransportWrapper{iif: gLocalDNSTransport}
}

// localTransportWrapper adapts the gobind-bound LocalDNSTransport to the
// libbox interface expected by the 1.14 platform DNS transport.
type localTransportWrapper struct {
	iif LocalDNSTransport
}

func (w *localTransportWrapper) Raw() bool { return w.iif.Raw() }

func (w *localTransportWrapper) Lookup(lctx *libbox.ExchangeContext, network string, domain string) error {
	return w.iif.Lookup(&ExchangeContext{inner: lctx}, network, domain)
}

func (w *localTransportWrapper) Exchange(lctx *libbox.ExchangeContext, message []byte) error {
	return w.iif.Exchange(&ExchangeContext{inner: lctx}, message)
}

func (w *boxPlatformInterfaceWrapper) StartDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	// Android: the default interface monitor is driven by VPNService
	// callbacks outside the core; the stub keeps sing-tun satisfied.
	return nil
}

func (w *boxPlatformInterfaceWrapper) CloseDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) GetInterfaces() (libbox.NetworkInterfaceIterator, error) {
	return nil, E.New("android: platform interface getter not used")
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
