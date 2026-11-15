package snell

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
)

func TestPre25VersionsDecodeConstructAndClose(t *testing.T) {
	for version := 1; version <= 6; version++ {
		t.Run(fmt.Sprint(version), func(t *testing.T) {
			var options Options
			fixture := fmt.Sprintf(`{"version":%d,"server":"127.0.0.1","server_port":443,"psk":"fixture-password"}`, version)
			if err := json.Unmarshal([]byte(fixture), &options); err != nil {
				t.Fatal(err)
			}
			ob, err := NewOutbound(context.Background(), nil, log.NewNOPFactory().Logger(), "fixture", options)
			if err != nil {
				t.Fatal(err)
			}
			scope := adapter.NewScope(context.Background(), log.NewNOPFactory().Logger())
			if err := ob.(adapter.Lifecycle).Start(adapter.StartStateInitialize, scope); err != nil {
				t.Fatal(err)
			}
			if err := scope.Close(); err != nil {
				t.Fatal(err)
			}
			raw, err := json.Marshal(options)
			if err != nil {
				t.Fatal(err)
			}
			var round Options
			if err = json.Unmarshal(raw, &round); err != nil || round.Version != version {
				t.Fatalf("roundtrip %s: %v", raw, err)
			}
		})
	}
}

func TestVersionSixKeepsPrivateQUICModeAndVersionFiveKeepsDispatch(t *testing.T) {
	var options Options
	if err := json.Unmarshal([]byte(`{"version":6,"psk":"fixture-password","mode":"unshaped","quic_proxy_mode":true}`), &options); err != nil {
		t.Fatal(err)
	}
	if !options.V6Options.QUICProxyMode || options.V6Options.Mode != "unshaped" {
		t.Fatal("lost private mode")
	}
	for _, version := range []int{0, 7, -1} {
		if json.Unmarshal([]byte(fmt.Sprintf(`{"version":%d}`, version)), &options) == nil {
			t.Fatalf("accepted invalid version %d", version)
		}
	}
}
