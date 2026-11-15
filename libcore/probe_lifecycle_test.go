package libcore

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// Exercise the actual Android-facing factory, not a hand-built box that omits
// PlatformLogWriter and thus cannot reproduce its implicit cache-file lock.
func TestProbeCoresStartTogetherWhileNormalCoreOwnsCache(t *testing.T) {
	t.Chdir(t.TempDir())
	const config = `{"log":{"disabled":true},"outbounds":[{"type":"direct","tag":"proxy"}]}`
	normal, err := NewSingBoxInstance(config, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer normal.Close()
	if err = normal.Start(); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat("cache.db"); err != nil {
		t.Fatalf("normal core did not acquire real cache: %v", err)
	}
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer target.Close()
	const count = 5
	started := make(chan struct{}, count)
	release := make(chan struct{})
	failures := make(chan error, count)
	var workers sync.WaitGroup
	for n := 0; n < count; n++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			p := NewProbeContext(2000)
			defer p.Cancel()
			b, err := NewSingBoxProbe(config, nil, p)
			if err != nil {
				failures <- err
				return
			}
			defer b.Close()
			if err = b.Start(); err != nil {
				failures <- err
				return
			}
			if err = p.Ready(); err != nil {
				failures <- err
				return
			}
			started <- struct{}{}
			<-release // all temporary cores must be running at the same time
			_, err = UrlTest(b, target.URL, 1000)
			if err != nil {
				failures <- err
			}
		}()
	}
	deadline := time.NewTimer(3 * time.Second)
	defer deadline.Stop()
	for n := 0; n < count; n++ {
		select {
		case <-started:
		case err := <-failures:
			close(release)
			workers.Wait()
			t.Fatal(err)
		case <-deadline.C:
			close(release)
			workers.Wait()
			t.Fatal("parallel probes blocked on normal/shared cache.db")
		}
	}
	close(release)
	workers.Wait()
	close(failures)
	for err := range failures {
		t.Error(err)
	}
}

func TestProbeFactoryDoesNotCreatePersistentState(t *testing.T) {
	dir := t.TempDir()
	t.Chdir(dir)
	p := NewProbeContext(1000)
	defer p.Cancel()
	b, err := NewSingBoxProbe(`{"log":{"disabled":true},"outbounds":[{"type":"direct","tag":"proxy"}],"experimental":{"cache_file":{"enabled":true,"path":"must-not-exist.db"}}}`, nil, p)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	if err = p.Ready(); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"cache.db", "must-not-exist.db"} {
		if _, err = os.Stat(filepath.Join(dir, name)); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("probe created persistent file %s: %v", name, err)
		}
	}
}

func TestProbeHTTPTimeoutAndOwnerCancellationComplete(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done() }))
	defer target.Close()
	t.Run("deadline", func(t *testing.T) {
		began := time.Now()
		_, err := probeHTTP(context.Background(), target.Client(), target.URL, 60)
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("wanted deadline: %v", err)
		}
		if time.Since(began) > time.Second {
			t.Fatal("HTTP deadline did not release worker")
		}
	})
	t.Run("owner", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		time.AfterFunc(50*time.Millisecond, cancel)
		began := time.Now()
		_, err := probeHTTP(ctx, target.Client(), target.URL, 5000)
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("wanted cancellation: %v", err)
		}
		if time.Since(began) > time.Second {
			t.Fatal("owner cancellation did not release worker")
		}
	})
}
