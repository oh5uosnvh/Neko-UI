package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"libcore/procfs"
	"log"
	"net"
	"net/netip"
	"strings"
	"syscall"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/adapter"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	N "github.com/sagernet/sing/common/network"
)

var boxPlatformInterfaceInstance adapter.PlatformInterface = &boxPlatformInterfaceWrapper{}

type boxPlatformInterfaceWrapper struct{}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState(ctx context.Context) adapter.WIFIState {
	if intfBox == nil {
		return adapter.WIFIState{}
	}
	state := strings.SplitN(intfBox.WIFIState(), ",", 2)
	if len(state) < 2 {
		state = append(state, "")
	}
	return adapter.WIFIState{
		SSID:  state[0],
		BSSID: state[1],
	}
}

func (w *boxPlatformInterfaceWrapper) Initialize(n adapter.NetworkManager) error {
	// Populate the core finder before it can fall back to Go's Android netlink.
	if err := n.UpdateInterfaces(); err != nil {
		return err
	}
	if monitor, ok := n.InterfaceMonitor().(*interfaceMonitor); ok {
		monitor.refresh = n.UpdateInterfaces
		if monitor.getter == nil {
			monitor.validate = func(iif *control.Interface, data string) error {
				return validateAndroidNetworkRefresh(n.NetworkInterfaces(), iif, data)
			}
		} // explicit host getter/lookup fixtures have no Android JSON fingerprint
	}
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) AutoDetectInterfaceControl(fd int) error {
	provider := androidNetworkProvider()
	if provider == nil {
		return errors.New("Android socket protection state provider is not initialized")
	}
	required, err := provider.SocketProtectionRequired()
	if err != nil {
		return fmt.Errorf("Android socket protection state: %w", err)
	}
	if !required {
		return nil
	} // confirmed no VPN: UI and proxy mode can dial directly
	// call protect_path
	if !isBgProcess {
		return sendFdToProtect(fd, "protect_path")
	}
	// bg process call VPNService
	if intfBox == nil {
		return errors.New("Android VPN service is not initialized")
	}
	return intfBox.AutoDetectInterfaceControl(int32(fd))
}

func (w *boxPlatformInterfaceWrapper) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	if len(options.IncludeUID) > 0 || len(options.ExcludeUID) > 0 {
		return nil, E.New("android: unsupported uid options")
	}
	if len(options.IncludeAndroidUser) > 0 {
		return nil, E.New("android: unsupported android_user option")
	}
	if intfBox == nil {
		return nil, errors.New("Android VPN service is not initialized")
	}
	a, err := json.Marshal(options)
	if err != nil {
		return nil, err
	}
	b, err := json.Marshal(platformOptions)
	if err != nil {
		return nil, err
	}
	tunFd, err := intfBox.OpenTun(string(a), string(b))
	if err != nil {
		return nil, fmt.Errorf("intfBox.OpenTun: %v", err)
	}
	// JNI returns a borrowed ParcelFileDescriptor owned by VpnService.
	// Exactly one dup is owned by sing-tun and released at scope Close.
	tunFd, err = syscall.Dup(tunFd)
	if err != nil {
		return nil, fmt.Errorf("syscall.Dup: %v", err)
	}
	//
	options.FileDescriptor = int(tunFd)
	device, err := tun.New(*options)
	if err != nil {
		_ = syscall.Close(tunFd)
	}
	return device, err
}

func (w *boxPlatformInterfaceWrapper) CloseTun() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformDefaultInterfaceMonitor() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) CreateDefaultInterfaceMonitor(l logger.Logger) tun.DefaultInterfaceMonitor {
	return newInterfaceMonitor()
}

func (w *boxPlatformInterfaceWrapper) UsePlatformInterfaceGetter() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) Interfaces() ([]adapter.NetworkInterface, error) {
	return w.NetworkInterfaces()
}

func (w *boxPlatformInterfaceWrapper) IncludeAllNetworks() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) SendNotification(notification *adapter.Notification) error {
	return nil
}

func (s *boxPlatformInterfaceWrapper) SystemCertificates() []string {
	return nil
}

// Android not using

func (w *boxPlatformInterfaceWrapper) UnderNetworkExtension() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) ClearDNSCache() {
}

// process.Searcher

func (w *boxPlatformInterfaceWrapper) FindProcessInfo(ctx context.Context, network string, source netip.AddrPort, destination netip.AddrPort) (*adapter.ConnectionOwner, error) {
	var uid int32
	if useProcfs {
		uid = procfs.ResolveSocketByProcSearch(network, source, destination)
		if uid == -1 {
			return nil, E.New("procfs: not found")
		}
	} else {
		var ipProtocol int32
		switch N.NetworkName(network) {
		case N.NetworkTCP:
			ipProtocol = syscall.IPPROTO_TCP
		case N.NetworkUDP:
			ipProtocol = syscall.IPPROTO_UDP
		default:
			return nil, E.New("unknown network: ", network)
		}
		var err error
		uid, err = intfBox.FindConnectionOwner(ipProtocol, source.Addr().String(), int32(source.Port()), destination.Addr().String(), int32(destination.Port()))
		if err != nil {
			return nil, err
		}
	}
	packageName, _ := intfBox.PackageNameByUid(uid)
	return &adapter.ConnectionOwner{UserId: uid, PackageNames: []string{packageName}}, nil
}

// io.Writer

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

func (w *boxPlatformInterfaceWrapper) UsePlatformInterface() bool { return true }
func (w *boxPlatformInterfaceWrapper) ProcessPlatformOptions(options option.TunPlatformOptions) error {
	return nil
}                                                                         // forwarded in full by OpenInterface
func (w *boxPlatformInterfaceWrapper) UsePlatformNetworkInterfaces() bool { return true }
func (w *boxPlatformInterfaceWrapper) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	snapshot, err := readAndroidNetworkSnapshot()
	if err != nil {
		return nil, err
	}
	return snapshot.interfaces, nil
}
func (w *boxPlatformInterfaceWrapper) NetworkExtensionIncludeAllNetworks() bool { return false }
func (w *boxPlatformInterfaceWrapper) RequestPermissionForWIFIState() error     { return nil } // existing JNI WIFIState owns Android permission handling
func (w *boxPlatformInterfaceWrapper) UsePlatformConnectionOwnerFinder() bool   { return true }
func (w *boxPlatformInterfaceWrapper) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	source, err := netip.ParseAddr(request.SourceAddress)
	if err != nil {
		return nil, err
	}
	destination, err := netip.ParseAddr(request.DestinationAddress)
	if err != nil {
		return nil, err
	}
	network := "tcp"
	if request.IpProtocol == syscall.IPPROTO_UDP {
		network = "udp"
	} else if request.IpProtocol != syscall.IPPROTO_TCP {
		return nil, errors.New("unsupported ownership protocol")
	}
	return w.FindProcessInfo(context.Background(), network, netip.AddrPortFrom(source, uint16(request.SourcePort)), netip.AddrPortFrom(destination, uint16(request.DestinationPort)))
}
func (w *boxPlatformInterfaceWrapper) UsePlatformWIFIMonitor() bool  { return false }
func (w *boxPlatformInterfaceWrapper) UsePlatformNotification() bool { return false }
func (w *boxPlatformInterfaceWrapper) CancelNotification(identifier string, typeID int32) error {
	return errors.New("platform notification API unavailable")
}
func (w *boxPlatformInterfaceWrapper) MyInterfaceAddress() []netip.Addr {
	interfaces, err := w.NetworkInterfaces()
	if err != nil {
		// The legacy API cannot return an error. Startup/getter/monitor do.
		log.Printf("Android interface addresses: %v", err)
		return nil
	}
	var result []netip.Addr
	seen := make(map[netip.Addr]bool)
	for _, iif := range interfaces {
		if iif.Flags&net.FlagLoopback != 0 || iif.Flags&net.FlagUp == 0 {
			continue
		}
		for _, prefix := range iif.Addresses {
			address := prefix.Addr()
			if !seen[address] {
				seen[address] = true
				result = append(result, address)
			}
		}
	}
	return result
}
func (w *boxPlatformInterfaceWrapper) UsePlatformNeighborResolver() bool { return false }
func (w *boxPlatformInterfaceWrapper) StartNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return errors.New("platform neighbor monitor unavailable")
}
func (w *boxPlatformInterfaceWrapper) CloseNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return nil
}
func (w *boxPlatformInterfaceWrapper) UsePlatformShell() bool { return false }
func (w *boxPlatformInterfaceWrapper) CheckPlatformShell() error {
	return errors.New("platform shell unavailable")
}
func (w *boxPlatformInterfaceWrapper) OpenShellSession(user *adapter.PlatformUser, command string, env []string, term string, rows, cols int32) (adapter.ShellSession, error) {
	return nil, errors.New("platform shell unavailable")
}
func (w *boxPlatformInterfaceWrapper) LookupUser(username string) (*adapter.PlatformUser, error) {
	return nil, errors.New("platform users unavailable")
}
func (w *boxPlatformInterfaceWrapper) LookupSFTPServer() (string, error) {
	return "", errors.New("platform SFTP unavailable")
}
func (w *boxPlatformInterfaceWrapper) ReadSystemSSHHostKey() ([]byte, error) {
	return nil, errors.New("platform SSH host keys unavailable")
}
func (w *boxPlatformInterfaceWrapper) TailscaleHostname() string { return "" }
func (w *boxPlatformInterfaceWrapper) UsePlatformBridge() bool   { return false }
func (w *boxPlatformInterfaceWrapper) CreateBridge(options adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, errors.New("platform bridge unavailable")
}
func (w *boxPlatformInterfaceWrapper) UsePlatformAutoRedirect() bool { return false }
func (w *boxPlatformInterfaceWrapper) CreateAutoRedirect(options adapter.AutoRedirectOptions) (adapter.AutoRedirectSession, error) {
	return nil, errors.New("platform auto redirect unavailable")
}
