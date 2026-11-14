package probe

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestHealthyRepeatedRunsUseCurrentWarmSample(t *testing.T) {
	var calls atomic.Int32
	var connections atomic.Int32
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		time.Sleep(12 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	server.Config.ConnState = func(_ net.Conn, state http.ConnState) {
		if state == http.StateNew {
			connections.Add(1)
		}
	}
	server.Start()
	defer server.Close()
	for i := 0; i < 12; i++ {
		result, err := RTT(context.Background(), server.Client(), server.URL, time.Second)
		if err != nil {
			t.Fatalf("run %d unexpectedly failed: %v", i, err)
		}
		if result < 8 || result > 300 {
			t.Fatalf("unexpected real sample %dms", result)
		}
	}
	if calls.Load() != 12 {
		t.Fatalf("unexpected extra probes sent: %d", calls.Load())
	}
	if connections.Load() != 12 {
		t.Fatalf("connection was not reused inside each run: %d", connections.Load())
	}
}

func TestColdPrimeBudgetDoesNotConsumeWarmSampleBudget(t *testing.T) {
	var count atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if count.Add(1) == 1 {
			time.Sleep(150 * time.Millisecond)
		} else {
			time.Sleep(75 * time.Millisecond)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	result, err := RTT(context.Background(), server.Client(), server.URL, 190*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	if result < 120 || result > 185 {
		t.Fatalf("wanted the single real sample: %dms", result)
	}
}

func TestSuccessfulPrimeIsNotOverwrittenByFailedWarmRequest(t *testing.T) {
	var count atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if count.Add(1) == 2 {
			<-r.Context().Done()
			return
		}
		time.Sleep(20 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	started := time.Now()
	result, err := RTT(context.Background(), server.Client(), server.URL, 120*time.Millisecond)
	if err != nil {
		t.Fatalf("valid current-run first sample discarded: %v", err)
	}
	if result < 12 || result > 90 {
		t.Fatalf("unexpected single-sample RTT: %dms", result)
	}
	if count.Load() != 1 {
		t.Fatalf("quick test issued extra warm requests: %d", count.Load())
	}
	if time.Since(started) > 600*time.Millisecond {
		t.Fatal("single sample exceeded bounded budget")
	}
}

func TestOwningCoreCancellationInterruptsReadAndNeverReturnsCachedSuccess(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	entered := make(chan struct{})
	var count atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		count.Add(1)
		close(entered)
		<-r.Context().Done()
	}))
	defer server.Close()
	done := make(chan error, 1)
	go func() { _, err := RTT(ctx, server.Client(), server.URL, 5*time.Second); done <- err }()
	<-entered
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("cancellation returned success/wrong error: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("core cancellation did not interrupt HTTP request")
	}
}

func TestDeadNodeIsRealFailureAndNotRetriedInsideSample(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		<-r.Context().Done()
	}))
	defer server.Close()
	_, err := RTT(context.Background(), server.Client(), server.URL, 60*time.Millisecond)
	if err == nil {
		t.Fatal("dead endpoint reported healthy")
	}
	if calls.Load() != 1 {
		t.Fatal("sample duplicated failed cold prime")
	}
}

func TestHTTPErrorsAreNotHealthyButRedirectResponseIsValid(t *testing.T) {
	for _, status := range []int{204, 302, 401, 403, 500} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(status)
			_, _ = io.WriteString(w, "small")
		}))
		_, err := RTT(context.Background(), server.Client(), server.URL, time.Second)
		server.Close()
		if (err == nil) != (status < 400) {
			t.Fatalf("HTTP %d wrong result: %v", status, err)
		}
	}
}

func TestConcurrentProbeStressKeepsHealthyResponsesStable(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(3 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	var group sync.WaitGroup
	errors := make(chan error, 120)
	for n := 0; n < 120; n++ {
		group.Add(1)
		go func() {
			defer group.Done()
			client := &http.Client{Transport: &http.Transport{}}
			value, err := RTT(context.Background(), client, server.URL, 3*time.Second)
			if err == nil && value < 1 {
				err = io.ErrUnexpectedEOF
			}
			if err != nil {
				errors <- err
			}
		}()
	}
	group.Wait()
	close(errors)
	for err := range errors {
		t.Error(err)
	}
}
