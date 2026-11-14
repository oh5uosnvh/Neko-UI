package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/probe"
	"log"
	"net/http"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/experimental/libbox/platform"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/conntrack"
	"github.com/sagernet/sing-box/common/dialer"
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
	ctx    context.Context
	cancel context.CancelFunc
	state  int

	v2api        *boxapi.SbV2rayServer
	selector     *group.Selector
	pauseManager pause.Manager
}

func NewSingBoxProbe(config string, localTransport LocalDNSTransport, p *ProbeContext) (*BoxInstance, error) {
	return newSingBoxInstance(config, localTransport, p.ctx)
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	return newSingBoxInstance(config, localTransport, context.Background())
}

func newSingBoxInstance(config string, localTransport LocalDNSTransport, parent context.Context) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// Probe callers supply a cancellation context BEFORE native construction.
	ctx, cancel := context.WithCancel(parent)
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[platform.Interface](ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %v", err)
	}

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:          instance,
		ctx:          ctx,
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
	// Cancel first: Start may hold access while waiting for DNS/network I/O.
	// Context cancellation is concurrent-safe and makes it release that lock.
	if b.cancel != nil {
		b.cancel()
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
	return b.selector.Now()
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
	if i == nil {
		i = mainInstance
	}
	ctx := context.Background()
	var instance *box.Box
	var connectionTracker adapter.ConnectionTracker
	if i != nil {
		i.access.Lock()
		if i.state != 1 {
			i.access.Unlock()
			return 0, errors.New("probe core is not running")
		}
		instance = i.Box
		if i.ctx != nil {
			ctx = i.ctx
		}
		if i.v2api != nil {
			connectionTracker = i.v2api.StatsService()
		}
		i.access.Unlock()
	}
	// Keep the proxy's deterministic dial behavior. The owning Box cancellation
	// now aborts the HTTP request itself, including read/header waits.
	ctx = context.WithValue(ctx, "nb4a_no_concurrent_dial", true)
	client := boxapi.CreateProxyHttpClient(instance, connectionTracker)
	if transport, ok := client.Transport.(*http.Transport); ok {
		transport.TLSHandshakeTimeout = time.Duration(timeout) * time.Millisecond
		transport.ResponseHeaderTimeout = 0 // each request owns its budget
	}
	return probe.RTT(ctx, client, link, time.Duration(timeout)*time.Millisecond)
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
