package libcore

import (
	"context"
	"errors"
	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/option"
	"testing"
)

type delayedDNS struct{ callback chan *ExchangeContext }

func (*delayedDNS) Raw() bool            { return false }
func (*delayedDNS) NetworkHandle() int64 { return 0 }
func (d *delayedDNS) Lookup(ctx *ExchangeContext, network, domain string) error {
	d.callback <- ctx
	return nil
}
func (*delayedDNS) Exchange(*ExchangeContext, []byte) error {
	return errors.New("unexpected raw query")
}
func TestNekoLocalDNSWaitsForAsyncAndCancellationWins(t *testing.T) {
	provider := &delayedDNS{callback: make(chan *ExchangeContext, 1)}
	transport := newPlatformTransport(provider, "local", option.LocalDNSServerOptions{})
	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() {
		_, err := transport.Exchange(ctx, new(mDNS.Msg).SetQuestion("fixture.example.", mDNS.TypeA))
		result <- err
	}()
	exchange := <-provider.callback
	select {
	case err := <-result:
		t.Fatalf("returned before callback: %v", err)
	default:
	}
	cancel()
	exchange.Success("192.0.2.1")
	if err := <-result; !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled query returned success: %v", err)
	}
	exchange.Success("192.0.2.2")
}
func TestNekoLocalDNSSuccessCompletesOnce(t *testing.T) {
	ctx := &ExchangeContext{context: context.Background(), done: make(chan struct{})}
	ctx.Success("192.0.2.1")
	ctx.Success("192.0.2.2")
	if len(ctx.addresses) != 1 || ctx.addresses[0].String() != "192.0.2.1" {
		t.Fatal("late callback changed terminal DNS result")
	}
	select {
	case <-ctx.done:
	default:
		t.Fatal("Success did not wake waiter")
	}
}
