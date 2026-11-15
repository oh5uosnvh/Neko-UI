package snell

import (
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/common/json/badjson"
)

// Preserve the entire pre25 discriminator, including v5 and v6 QUIC mode.
// Upstream 1.14's option.SnellOutboundOptions only accepts versions 4 and 6.
type rawOptions struct {
	Version int `json:"version"`
	option.AbstractSnellOutboundOptions
	ObfsOptions option.SnellObfsClientOptions `json:"-"`
	V6Options   V6Options                     `json:"-"`
}
type Options rawOptions
type V6Options struct {
	Mode          string `json:"mode,omitempty"`
	QUICProxyMode bool   `json:"quic_proxy_mode,omitempty"`
}

func (o *Options) UnmarshalJSON(content []byte) error {
	if err := json.Unmarshal(content, (*rawOptions)(o)); err != nil {
		return err
	}
	var versionOptions any
	switch o.Version {
	case 1, 2, 3, 4, 5:
		versionOptions = &o.ObfsOptions
	case 6:
		versionOptions = &o.V6Options
	case 0:
		return E.New("snell: missing version")
	default:
		return E.New("snell: unsupported version: ", o.Version)
	}
	return badjson.UnmarshallExcluded(content, (*rawOptions)(o), versionOptions)
}
func (o Options) MarshalJSON() ([]byte, error) {
	var versionOptions any
	switch o.Version {
	case 1, 2, 3, 4, 5:
		versionOptions = o.ObfsOptions
	case 6:
		versionOptions = o.V6Options
	default:
		return nil, E.New("snell: unsupported version: ", o.Version)
	}
	return badjson.MarshallObjects(rawOptions(o), versionOptions)
}
