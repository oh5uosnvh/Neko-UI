package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	libbox "github.com/sagernet/sing-box/experimental/libbox"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/conntrack"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

func init() {
	dialer.DoNotSelectInterface = true
}

var mainInstance *BoxInstance

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	if system {
		conntrack.Close()
		log.Println("Reset system connections done")
	} else {
		log.Println("TODO: Reset user connections")
	}
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	probeContext *ProbeContext
	cancel       context.CancelFunc
	state        int

	v2api        *boxapi.SbV2rayServer
	selector     *group.Selector
	pauseManager pause.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (*BoxInstance, error) {
	return newSingBoxInstance(config, localTransport, context.Background(), false)
}

// NewSingBoxProbe creates a disposable core without platform-persistent state.
// In 1.14 a PlatformLogWriter implicitly enables cache.db, even when cache_file
// is omitted. Parallel URL tests must not contend on that shared database.
func NewSingBoxProbe(config string, localTransport LocalDNSTransport, p *ProbeContext) (*BoxInstance, error) {
	if p == nil {
		return nil, errors.New("missing probe context")
	}
	b, err := newSingBoxInstance(config, localTransport, p.ctx, true)
	if b != nil {
		b.probeContext = p
	}
	return b, err
}

func newSingBoxInstance(config string, localTransport LocalDNSTransport, parent context.Context, disposable bool) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	platformInterface := &boxPlatformInterfaceWrapper{localDNS: localTransport, probe: disposable}
	// Publish the probe cancellation context before construction/startup.
	ctx, cancel := context.WithCancel(parent)
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[libbox.PlatformInterface](ctx, platformInterface)
	service.MustRegister[adapter.PlatformInterface](ctx, libbox.NewAdapterPlatformInterface(platformInterface))

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %v", err)
	}

	// Keep normal VPN configuration and logging unchanged. Probe cores have no
	// shared cache/API listeners; they report sanitized errors via TestInstance.
	boxOptions := box.Options{Options: options, Context: ctx, PlatformLogWriter: boxPlatformLogWriter}
	if disposable {
		if options.Experimental != nil {
			experimental := *options.Experimental
			experimental.CacheFile = nil
			experimental.ClashAPI = nil
			experimental.V2RayAPI = nil
			options.Experimental = &experimental
		}
		boxOptions.Options = options
		boxOptions.PlatformLogWriter = nil
	}
	instance, err := box.New(boxOptions)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:          instance,
		cancel:       cancel,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state != 0 {
		return errors.New("already started, failed, or closed")
	}
	b.state = 3 // failed/starting is never a selectable running Box
	if err = b.Box.Start(); err != nil {
		return err
	}
	b.state = 1
	return nil
}

func (b *BoxInstance) Close() (err error) {
	// A probe may be blocked in Start while holding access. Interrupt its
	// context before waiting for the lifecycle lock; ordinary VPN is unchanged.
	if b.probeContext != nil {
		b.probeContext.Cancel()
		if b.cancel != nil {
			b.cancel()
		}
	}
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

	// clear main instance
	if mainInstance == b {
		mainInstance = nil
		goServeProtect(false)
	}

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		b.Box.Close()
	}

	return nil
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstance = b
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api.StatsService())
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	return b.v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) (selected bool) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 || b.selector == nil || strings.TrimSpace(tag) == "" {
		return false
	}
	// sing-box owns selector membership and synchronously interrupts only the
	// selector's tracked old generation. No global conntrack reset is needed.
	return b.selector.SelectOutbound(tag)
}

// SelectedOutbound is a non-blocking control-plane read used to reject stale
// asynchronous selector callbacks. It is intentionally not a network probe.
func (b *BoxInstance) SelectedOutbound() string {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 || b.selector == nil {
		return ""
	}
	return b.selector.Selected("tcp").Tag()
}

// SelectorState returns selector generation and actual first-byte-use metrics.
// An ACK or successful Dial is not reported as data-plane success.
func (b *BoxInstance) SelectorState() string {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 || b.selector == nil {
		return "{}"
	}
	if state, ok := interface{}(b.selector).(interface{ SelectorState() string }); ok {
		return state.SelectorState()
	}
	return "{}"
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	if timeout <= 0 {
		return 0, errors.New("invalid probe timeout")
	}
	// Use the same URLTest implementation as official sing-box: one HEAD,
	// one detour DialContext, and NeedHandshakeForWrite-aware timing. The
	// caller's timeout is carried in the context because sing-box's normal
	// URLTest uses its global 15s TCPTimeout.
	if i != nil {
		parent := context.Background()
		if i.probeContext != nil {
			parent = i.probeContext.ctx
		}
		return officialURLTest(parent, i.Box, link, timeout)
	}
	if mainInstance != nil {
		return officialURLTest(context.Background(), mainInstance.Box, link, timeout)
	}
	// No core means a direct, disposable HTTP probe is the only valid path.
	return probeHTTP(context.Background(), boxapi.CreateProxyHttpClient(nil, nil), link, timeout)
}

func officialURLTest(parent context.Context, instance *box.Box, link string, timeout int32) (int32, error) {
	if instance == nil || instance.Outbound() == nil || instance.Outbound().Default() == nil {
		return 0, errors.New("missing default outbound")
	}
	ctx, cancel := context.WithTimeout(
		context.WithValue(parent, "nb4a_no_concurrent_dial", true),
		time.Duration(timeout)*time.Millisecond,
	)
	defer cancel()
	delay, err := urltest.URLTest(ctx, link, instance.Outbound().Default())
	if err != nil {
		return 0, err
	}
	return int32(delay), nil
}

var protectCloser io.Closer

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = protect_server.ServeProtect("protect_path", false, 0, func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
