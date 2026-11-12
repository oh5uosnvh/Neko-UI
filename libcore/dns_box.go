// libbox/dns.go — raw query plumbing only; the platform DNS transport lives in
// libbox since 1.14 (registered via PlatformInterface.LocalDNSTransport).

package libcore

import (
	libbox "github.com/sagernet/sing-box/experimental/libbox"
)

var rawQueryFunc func(networkHandle int64, request []byte) ([]byte, error)

var gLocalDNSTransport libbox.LocalDNSTransport = nil

type Func interface {
	Invoke() error
}
