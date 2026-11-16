package libcore

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestProbeHTTPUsesSingleHEADAndAcceptsResponseStatus(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.Method != http.MethodHead {
			t.Errorf("method = %s, want HEAD", r.Method)
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()

	if _, err := probeHTTP(context.Background(), server.Client(), server.URL, 500); err != nil {
		t.Fatalf("non-redirect response should complete the probe: %v", err)
	}
	if got := requests.Load(); got != 1 {
		t.Fatalf("requests = %d, want one request", got)
	}
}

func TestProbeHTTPDoesNotFollowRedirect(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		http.Redirect(w, r, "/redirected", http.StatusTemporaryRedirect)
	}))
	defer server.Close()

	if _, err := probeHTTP(context.Background(), server.Client(), server.URL, 500); err != nil {
		t.Fatalf("redirect response should complete the probe: %v", err)
	}
	if got := requests.Load(); got != 1 {
		t.Fatalf("requests = %d, redirect was followed", got)
	}
}

func TestProbeHTTPUsesOneEndToEndDeadline(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer server.Close()

	started := time.Now()
	_, err := probeHTTP(context.Background(), server.Client(), server.URL, 60)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("error = %v, want deadline exceeded", err)
	}
	if elapsed := time.Since(started); elapsed > 500*time.Millisecond {
		t.Fatalf("probe exceeded its end-to-end deadline: %s", elapsed)
	}
}

func TestProbeHTTPDoesNotMutateStageTimers(t *testing.T) {
	transport := &http.Transport{ResponseHeaderTimeout: 20 * time.Millisecond}
	client := &http.Client{Transport: transport}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(80 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	if _, err := probeHTTP(context.Background(), client, server.URL, 400); err != nil {
		t.Fatalf("user deadline was overridden by a stage timer: %v", err)
	}
	if transport.ResponseHeaderTimeout != 20*time.Millisecond {
		t.Fatalf("shared transport was mutated: %s", transport.ResponseHeaderTimeout)
	}
}
