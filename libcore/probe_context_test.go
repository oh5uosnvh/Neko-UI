package libcore

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

func TestProbeContextStartupBlockedUntilIndependentCancellation(t *testing.T) {
	p := NewProbeContext(5000)
	defer p.Cancel()
	entered := make(chan struct{})
	stopped := make(chan struct{})
	go func() { close(entered); <-p.ctx.Done(); close(stopped) }()
	<-entered
	p.Cancel()
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("blocking native startup could not be cancelled")
	}
	if !errors.Is(p.Ready(), context.Canceled) {
		t.Fatal("cancelled startup became ready")
	}
}

func TestProbeContextSetupWatchdogDoesNotBecomeHTTPDeadline(t *testing.T) {
	p := NewProbeContext(100)
	defer p.Cancel()
	if err := p.Ready(); err != nil {
		t.Fatal(err)
	}
	time.Sleep(160 * time.Millisecond)
	if p.ctx.Err() != nil {
		t.Fatal("setup timeout continued into healthy HTTP probes")
	}
	p.Cancel()
	if p.ctx.Err() == nil {
		t.Fatal("Ready disabled owner cancellation")
	}
}

func TestProbeContextDeadStartupHasBoundedBudget(t *testing.T) {
	p := NewProbeContext(30)
	defer p.Cancel()
	select {
	case <-p.ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("setup watchdog did not stop dead startup")
	}
	if p.Ready() == nil {
		t.Fatal("timed out startup reported ready")
	}
}

// Required under -race: timer construction publishes its handle while an
// immediate watchdog callback can already be scheduled on another goroutine.
func TestProbeContextConcurrentReadyCancelAndTimerPublication(t *testing.T) {
	var workers sync.WaitGroup
	for n := 0; n < 200; n++ {
		p := NewProbeContext(1)
		workers.Add(2)
		go func() { defer workers.Done(); _ = p.Ready() }()
		go func() { defer workers.Done(); p.Cancel() }()
	}
	workers.Wait()
}
