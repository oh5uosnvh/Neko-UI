package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/husiurltest"
	"log"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

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
	if mainInstance == nil {
		return
	}
	if system {
		mainInstance.Box.Network().ResetNetwork(mainInstance.ctx)
		log.Println("Reset system connections done")
	} else {
		mainInstance.Box.Router().ResetNetwork()
		log.Println("Reset user connections done")
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

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	if coreAssetsError != nil {
		return nil, fmt.Errorf("core assets are not ready: %w", coreAssetsError)
	}

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	ctx = option.ContextWithNekoCompatibility(ctx)
	ctx = pause.WithDefaultManager(ctx)
	ctx = service.ContextWithPtr(ctx, urltest.NewHistoryStorage())
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)
	defer func() {
		if err != nil {
			cancel()
		}
	}()

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		return nil, fmt.Errorf("decode config: %v", err)
	}

	rootCAMutex.RLock()
	rootCA := externalRootCA
	rootCAMutex.RUnlock()
	if rootCA != "" {
		if options.Certificate == nil {
			options.Certificate = &option.CertificateOptions{}
		}
		options.Certificate.Certificate = append(options.Certificate.Certificate, rootCA)
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
	b.state = 3
	if err = b.Box.Start(); err != nil {
		return err
	}
	b.state = 1
	return nil
}

func (b *BoxInstance) Close() (err error) {
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

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

func UrlTest(i *BoxInstance, link string, timeout int32) (int32, error) {
	// Keep the pre26 JNI surface and its RTT intent: husi's unified delay
	// measures the second HEAD over the established connection.
	return UrlTestWithFlags(i, link, timeout, int32(husiurltest.UnifiedDelay))
}

func UrlTestWithFlags(i *BoxInstance, link string, timeout int32, flags int32) (latency int32, err error) {
	latency = -1
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	if flags < 0 || flags & ^int32(husiurltest.UnifiedDelay|husiurltest.IgnoreHandshakeTime) != 0 {
		return -1, errors.New("unsupported URLTest flags")
	}
	if i == nil {
		i = mainInstance
	}
	if i == nil {
		return husiurltest.RunDirect(context.Background(), link, timeout, husiurltest.Flags(flags))
	}
	return husiurltest.RunTag(i.ctx, i.Outbound(), "", link, timeout, husiurltest.Flags(flags))
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
