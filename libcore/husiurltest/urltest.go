// Package husiurltest ports husi's URLTest measurement semantics to Neko's JNI.
// Upstream reference: oh5uosnvh/husi df68c41, libcore/urltest/urltest.go.
package husiurltest

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/url"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-anytls"
	"github.com/sagernet/sing-box/adapter"
	boxurltest "github.com/sagernet/sing-box/common/urltest"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing-mux"
	"github.com/sagernet/sing-quic"
	"github.com/sagernet/sing-snell"
	"github.com/sagernet/sing/common"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/ntp"
	"github.com/sagernet/sing/service"
)

type Flags uint8

const (
	UnifiedDelay Flags = 1 << iota
	IgnoreHandshakeTime
)

var ErrOutboundNotFound = errors.New("outbound is not found")

const defaultLink = "https://www.gstatic.com/generate_204"

type result struct {
	latency uint16
	err     error
}

// RunTag uses the instance's cancellation scope and the real outbound history.
func RunTag(instanceCtx context.Context, manager adapter.OutboundManager, tag, link string, timeoutMs int32, flags Flags) (int32, error) {
	if instanceCtx == nil {
		return -1, errors.New("instance context not available")
	}
	if manager == nil {
		return -1, errors.New("outbound manager not available")
	}
	var detour adapter.Outbound
	if tag == "" {
		detour = manager.Default()
	} else {
		var found bool
		detour, found = manager.Outbound(tag)
		if !found {
			return -1, fmt.Errorf("%w: %s", ErrOutboundNotFound, tag)
		}
	}
	if detour == nil {
		return -1, ErrOutboundNotFound
	}
	return Run(instanceCtx, detour, link, timeoutMs, flags)
}

func Run(instanceCtx context.Context, detour adapter.Outbound, link string, timeoutMs int32, flags Flags) (int32, error) {
	return run(instanceCtx, detour, detour, link, timeoutMs, flags)
}

// RunDirect is used only when there is no Neko instance; no history is invented.
func RunDirect(ctx context.Context, link string, timeoutMs int32, flags Flags) (int32, error) {
	return run(ctx, N.SystemDialer, nil, link, timeoutMs, flags)
}

func run(parent context.Context, dialer N.Dialer, outbound adapter.Outbound, link string, timeoutMs int32, flags Flags) (int32, error) {
	if parent == nil || dialer == nil {
		return -1, errors.New("URLTest context or dialer missing")
	}
	if timeoutMs <= 0 {
		return -1, errors.New("URLTest timeout must be positive")
	}
	ctx, cancel := context.WithTimeout(parent, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	done := make(chan result, 1)
	network := N.NetworkTCP
	if u, parseErr := url.Parse(link); parseErr == nil && (u.Scheme == "http3" || u.Scheme == "quic") {
		network = N.NetworkUDP
	}
	historyTag := ""
	if outbound != nil {
		historyTag = group.RealTag(outbound, network)
	}
	go func() {
		latency, err := Measure(ctx, link, dialer, flags)
		done <- result{latency, err}
	}()
	select {
	case <-ctx.Done():
		return -1, ctx.Err()
	case r := <-done:
		// Prefer the lifecycle deadline if result and cancellation race.
		if err := ctx.Err(); err != nil {
			return -1, err
		}
		if r.err != nil {
			return -1, r.err
		}
		// Publish only a result accepted by this caller. If a selector switched
		// during the probe, the latency remains valid but must not relabel the
		// newly selected node's history as if it was measured.
		if outbound != nil && historyTag != "" && group.RealTag(outbound, network) == historyTag {
			if history := service.PtrFromContext[boxurltest.HistoryStorage](parent); history != nil {
				history.StoreURLTestHistory(historyTag, &adapter.URLTestHistory{Time: time.Now(), Delay: r.latency})
			}
		}
		return int32(r.latency), nil
	}
}

// Measure keeps husi's multiplex warmup and second-HEAD UnifiedDelay semantics.
// Draining/closing each HEAD response before reuse fixes the old leaked warmup.
func Measure(ctx context.Context, link string, detour N.Dialer, flags Flags) (uint16, error) {
	if detour == nil {
		return 0, errors.New("URLTest dialer missing")
	}
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	// Neko may hand us a selector instead of husi's concrete node. Inspect its
	// selected node for multiplex capability, but dial through the original
	// selector so connection interruption and routing semantics are preserved.
	probe := detour
	network := N.NetworkTCP
	if u, err := url.Parse(link); err == nil && (u.Scheme == "http3" || u.Scheme == "quic") {
		network = N.NetworkUDP
	}
	for depth := 0; depth < 32; depth++ {
		selectedGroup, isGroup := probe.(adapter.OutboundGroup)
		if !isGroup {
			break
		}
		selected := selectedGroup.Selected(network)
		if selected == nil {
			return 0, ErrOutboundNotFound
		}
		probe = selected
		if depth == 31 {
			return 0, errors.New("URLTest outbound group cycle")
		}
	}
	multiplex, ok := common.Cast[adapter.OutboundWithMultiplex](probe)
	if ok && multiplex.MultiplexEnabled() {
		if _, err := measure(contextWithKeepSession(ctx), link, detour, flags); err != nil {
			return 0, err
		}
	}
	return measure(ctx, link, detour, flags)
}

func contextWithKeepSession(ctx context.Context) context.Context {
	ctx = adapter.ContextWithKeepSession(ctx)
	ctx = mux.ContextWithKeepSession(ctx)
	ctx = anytls.ContextWithKeepSession(ctx)
	ctx = qtls.ContextWithKeepSession(ctx)
	return snell.ContextWithKeepSession(ctx)
}

func measure(ctx context.Context, link string, detour N.Dialer, flags Flags) (uint16, error) {
	if link == "" {
		link = defaultLink
	}
	linkURL, err := url.Parse(link)
	if err != nil {
		return 0, err
	}
	network := N.NetworkTCP
	isHTTP3 := linkURL.Scheme == "http3" || linkURL.Scheme == "quic"
	if isHTTP3 {
		if !C.WithQUIC {
			return 0, C.ErrQUICNotIncluded
		}
		network = N.NetworkUDP
		linkURL.Scheme = "https"
	}
	if linkURL.Scheme != "http" && linkURL.Scheme != "https" {
		return 0, fmt.Errorf("unsupported URLTest scheme: %s", linkURL.Scheme)
	}
	hostname, port := linkURL.Hostname(), linkURL.Port()
	if hostname == "" {
		return 0, errors.New("URLTest hostname is empty")
	}
	if port == "" {
		if linkURL.Scheme == "http" {
			port = "80"
		} else {
			port = "443"
		}
	}
	start := time.Now()
	conn, err := detour.DialContext(ctx, network, M.ParseSocksaddrHostPortStr(hostname, port))
	if err != nil {
		return 0, err
	}
	connections := []net.Conn{conn}
	var connectionAccess sync.Mutex
	closeConnections := func() {
		connectionAccess.Lock()
		defer connectionAccess.Unlock()
		for _, connection := range connections {
			_ = connection.Close()
		}
	}
	defer closeConnections()
	// Interrupt every socket acquired by this probe, including a replacement
	// when an HTTP/1 server closes the first HEAD connection.
	stop := context.AfterFunc(ctx, closeConnections)
	defer stop()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	if flags&IgnoreHandshakeTime != 0 && N.NeedHandshakeForWrite(conn) {
		start = time.Now()
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, linkURL.String(), nil)
	if err != nil {
		return 0, err
	}
	tlsConfig := &tls.Config{Time: ntp.TimeFuncFromContext(ctx), RootCAs: adapter.RootPoolFromContext(ctx)}
	var transport http.RoundTripper
	if isHTTP3 {
		transport, err = newHTTP3Transport(conn, tlsConfig)
		if err != nil {
			return 0, err
		}
		defer common.Close(transport)
	} else {
		var firstConnectionUsed atomic.Bool
		transport = &http.Transport{
			DialContext: func(dialCtx context.Context, dialNetwork, _ string) (net.Conn, error) {
				if firstConnectionUsed.CompareAndSwap(false, true) {
					return conn, nil
				}
				// A HEAD response can legitimately contain Connection: close.
				// Never hand the Transport that closed socket a second time.
				next, err := detour.DialContext(dialCtx, dialNetwork, M.ParseSocksaddrHostPortStr(hostname, port))
				if err != nil {
					return nil, err
				}
				connectionAccess.Lock()
				if err := ctx.Err(); err != nil {
					connectionAccess.Unlock()
					_ = next.Close()
					return nil, err
				}
				connections = append(connections, next)
				connectionAccess.Unlock()
				if deadline, ok := ctx.Deadline(); ok {
					_ = next.SetDeadline(deadline)
				}
				return next, nil
			},
			TLSClientConfig: tlsConfig,
		}
	}
	client := &http.Client{Transport: transport, Timeout: C.TCPTimeout,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	defer client.CloseIdleConnections()
	response, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	measured := time.Since(start)
	if err = closeResponse(response); err != nil {
		return 0, err
	}
	if flags&UnifiedDelay != 0 {
		start = time.Now()
		response, err = client.Do(req)
		if err != nil {
			return 0, err
		}
		measured = time.Since(start)
		if err = closeResponse(response); err != nil {
			return 0, err
		}
	}
	if err = ctx.Err(); err != nil {
		return 0, err
	}
	milliseconds := measured / time.Millisecond
	if milliseconds > math.MaxUint16 {
		milliseconds = math.MaxUint16
	}
	return uint16(milliseconds), nil
}

func closeResponse(response *http.Response) error {
	if response == nil || response.Body == nil {
		return nil
	}
	_, readErr := io.Copy(io.Discard, response.Body)
	closeErr := response.Body.Close()
	if readErr != nil {
		return readErr
	}
	return closeErr
}
