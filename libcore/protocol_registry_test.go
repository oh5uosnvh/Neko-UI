//go:build with_naive_outbound && with_openvpn && with_openconnect && with_tailscale && with_cloudflared

package libcore

import "testing"

func TestNekoBoxProtocolRegistriesKeepCustomAndOfficialProtocols(t *testing.T) {
	outbound := nekoboxAndroidOutboundRegistry().OptionTypes()
	wantOutbound := []string{"bridge", "fastup", "juicity", "oppa", "x365", "xhttp", "naive"}
	for _, typ := range wantOutbound {
		found := false
		for _, got := range outbound {
			if got == typ {
				found = true
				break
			}
		}
		if !found {
			t.Fatalf("outbound registry lost %q: %v", typ, outbound)
		}
	}
	inbound := nekoboxAndroidInboundRegistry().OptionTypes()
	for _, typ := range []string{"naive", "cloudflared"} {
		if !containsProtocol(inbound, typ) {
			t.Fatalf("inbound registry lost %q: %v", typ, inbound)
		}
	}
	endpoints := nekoboxAndroidEndpointRegistry().OptionTypes()
	for _, typ := range []string{"wireguard", "openvpn-client", "openconnect", "tailscale"} {
		if !containsProtocol(endpoints, typ) {
			t.Fatalf("endpoint registry lost %q: %v", typ, endpoints)
		}
	}
}

func containsProtocol(values []string, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}
