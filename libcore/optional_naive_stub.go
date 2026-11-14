//go:build !with_naive_outbound

package libcore

import (
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
)

func registerNaiveInbound(_ *inbound.Registry)   {}
func registerNaiveOutbound(_ *outbound.Registry) {}
