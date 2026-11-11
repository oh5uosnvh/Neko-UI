package libcore

import (
	"errors"
	"libcore/device"
)

// Optional structural interface lets this file build against an unpatched core:
// capability probe returns 0 and update returns an explicit unsupported error.
type runtimeRoutingUpdater interface {
	RuntimeRoutingVersion() int
	UpdateRouting(routeJSON, dnsJSON string) (uint64, error)
}

func (b *BoxInstance) RuntimeRoutingVersion() int {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 || b.Box == nil {
		return 0
	}
	updater, ok := b.Box.Router().(runtimeRoutingUpdater)
	if !ok {
		return 0
	}
	return updater.RuntimeRoutingVersion()
}

// UpdateRouting accepts the COMPLETE route and dns objects, not a whole Box
// config. Only an acknowledged, already-loaded routing snapshot is hot applied.
// Existing TCP/UDP sessions keep their original outbound. New sessions use the
// new generation. No Box/transport/TUN is recreated in this method.
func (b *BoxInstance) UpdateRouting(routeJSON, dnsJSON string) (generation int64, err error) {
	b.access.Lock()
	defer b.access.Unlock()
	defer device.DeferPanicToError("box.UpdateRouting", func(e error) { err = e })
	if b.state != 1 || b.Box == nil {
		return 0, errors.New("ROUTING_NOT_RUNNING")
	}
	updater, ok := b.Box.Router().(runtimeRoutingUpdater)
	if !ok || updater.RuntimeRoutingVersion() != 1 {
		return 0, errors.New("ROUTING_API_UNAVAILABLE")
	}
	if len(routeJSON) > 8<<20 || len(dnsJSON) > 8<<20 {
		return 0, errors.New("ROUTING_PAYLOAD_TOO_LARGE")
	}
	v, err := updater.UpdateRouting(routeJSON, dnsJSON)
	return int64(v), err
}
