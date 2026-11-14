//go:build !with_cloudflared

package libcore

import "github.com/sagernet/sing-box/adapter/inbound"

func registerCloudflaredInbound(_ *inbound.Registry) {}
