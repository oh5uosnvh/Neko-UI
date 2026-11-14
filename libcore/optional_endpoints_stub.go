//go:build !with_openvpn || !with_openconnect || !with_tailscale

package libcore

import (
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
)

func registerOptionalEndpoints(_ *endpoint.Registry)               {}
func registerOptionalCertificateProviders(_ *certificate.Registry) {}
func registerOptionalDNSTransports(_ *dns.TransportRegistry)       {}
func registerOptionalServices(_ *service.Registry)                 {}
