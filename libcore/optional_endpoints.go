//go:build with_openvpn && with_openconnect && with_tailscale

package libcore

import (
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/protocol/openconnect"
	"github.com/sagernet/sing-box/protocol/openvpn"
	"github.com/sagernet/sing-box/protocol/tailscale"
	"github.com/sagernet/sing-box/service/derp"
)

func registerOptionalEndpoints(registry *endpoint.Registry) {
	openconnect.RegisterEndpoint(registry)
	openvpn.RegisterEndpoint(registry)
	tailscale.RegisterEndpoint(registry)
}

func registerOptionalCertificateProviders(registry *certificate.Registry) {
	tailscale.RegisterCertificateProvider(registry)
}

func registerOptionalDNSTransports(registry *dns.TransportRegistry) {
	openconnect.RegisterDNSTransport(registry)
	openvpn.RegisterDNSTransport(registry)
	tailscale.RegistryTransport(registry)
}

func registerOptionalServices(registry *service.Registry) {
	derp.Register(registry)
}
