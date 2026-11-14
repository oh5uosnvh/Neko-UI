package libcore

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"time"
)

// ProbeContext is published before native construction starts. Cancel and the
// setup deadline work without the Box's Start/Close mutex. Deadline applies only
// to construction/startup; started probes use per-request budgets separately.
type ProbeContext struct {
	access     sync.Mutex
	ctx        context.Context
	cancel     context.CancelFunc
	setupTimer *time.Timer
	timedOut   atomic.Bool
}

func NewProbeContext(setupTimeout int32) *ProbeContext {
	ctx, cancel := context.WithCancel(context.Background())
	p := &ProbeContext{ctx: ctx, cancel: cancel}
	if setupTimeout > 0 {
		p.setupTimer = time.AfterFunc(time.Duration(setupTimeout)*time.Millisecond, func() {
			p.access.Lock()
			defer p.access.Unlock()
			if p.setupTimer != nil {
				p.timedOut.Store(true)
				p.cancel()
			}
		})
	}
	return p
}

func (p *ProbeContext) Cancel() {
	if p != nil {
		p.cancel()
	}
}

// Ready disables only the setup watchdog, never owning-context cancellation.
func (p *ProbeContext) Ready() error {
	p.access.Lock()
	defer p.access.Unlock()
	if p.setupTimer != nil {
		p.setupTimer.Stop()
		p.setupTimer = nil
	}
	if p.timedOut.Load() {
		return errors.New("probe setup timeout")
	}
	return p.ctx.Err()
}

func (p *ProbeContext) TimedOut() bool { return p.timedOut.Load() }
