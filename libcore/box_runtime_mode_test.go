package libcore

import (
	"sync"
	"testing"

	"github.com/sagernet/sing-box/adapter"
)

// Embedded interface provides unused tracker/lifecycle methods. Only the mode
// methods are invoked by the bridge, so these tests don't start Android VPNs.
type testModeServer struct {
	adapter.ClashServer
	mode  string
	calls int
}

func (s *testModeServer) Mode() string { return s.mode }
func (s *testModeServer) SetMode(mode string) bool {
	if mode != "Rule" && mode != "Global" {
		return false
	}
	s.mode = mode
	s.calls++
	return true
}
func TestRuntimeModeBridgeLifecycle(t *testing.T) {
	s := &testModeServer{mode: "Rule"}
	b := &BoxInstance{state: 0, clashServer: s}
	if ok, e := b.SetRuntimeMode("Global"); ok || e == nil || s.mode != "Rule" {
		t.Fatal("unstarted mutation accepted")
	}
	b.state = 3
	if ok, e := b.SetRuntimeMode("Global"); ok || e == nil {
		t.Fatal("failed start mutation accepted")
	}
	b.state = 1
	for _, mode := range []string{"Global", "Rule"} {
		if ok, e := b.SetRuntimeMode(mode); !ok || e != nil || b.RuntimeMode() != mode {
			t.Fatalf("apply %s: %v %v", mode, ok, e)
		}
	}
	if ok, e := b.SetRuntimeMode("Direct"); ok || e == nil || s.calls != 2 {
		t.Fatal("unsupported mode had effects")
	}
	if e := b.Close(); e != nil {
		t.Fatal(e)
	}
	if ok, e := b.SetRuntimeMode("Global"); ok || e == nil || s.mode != "Rule" {
		t.Fatal("closed mutation accepted")
	}
	if e := b.Close(); e != nil {
		t.Fatal("double close", e)
	}
}
func TestRuntimeModeBridgeConcurrentClose(t *testing.T) {
	s := &testModeServer{mode: "Rule"}
	b := &BoxInstance{state: 1, clashServer: s}
	var wg sync.WaitGroup
	for n := 0; n < 8; n++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 100; i++ {
				b.SetRuntimeMode("Global")
				b.RuntimeMode()
			}
		}()
	}
	wg.Add(1)
	go func() { defer wg.Done(); b.Close() }()
	wg.Wait()
	if ok, e := b.SetRuntimeMode("Rule"); ok || e == nil {
		t.Fatal("mutation after concurrent close accepted")
	}
}
