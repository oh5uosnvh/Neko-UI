module libcore

go 1.25.5

toolchain go1.27.4

// v1.15.0-alpha.13 is the real core baseline, not a version-string override.
require (
	filippo.io/age v1.3.1
	filippo.io/edwards25519 v1.2.0
	filippo.io/hpke v0.4.0
	github.com/RyuaNerin/go-krypto v1.3.0
	github.com/ajg/form v1.5.1
	github.com/akutz/memconn v0.1.0
	github.com/alexbrainman/sspi v0.0.0-20231016080023-1a75b4708caa
	github.com/anchore/go-lzo v0.1.0
	github.com/andybalholm/brotli v1.1.0
	github.com/anmitsu/go-shlex v0.0.0-20200514113438-38f4b401e2be
	github.com/anthropics/anthropic-sdk-go v1.26.0
	github.com/axiomhq/hyperloglog v0.0.0-20240319100328-84253e514e02
	github.com/caddyserver/certmagic v0.25.3-0.20260421143802-60d9d8b415d6
	github.com/caddyserver/zerossl v0.1.5
	github.com/cenkalti/backoff/v4 v4.3.0
	github.com/clipperhouse/uax29/v2 v2.2.0
	github.com/coder/websocket v1.8.14
	github.com/coreos/go-iptables v0.7.1-0.20240112124308-65c67c9f46e6
	github.com/coreos/go-oidc/v3 v3.17.0
	github.com/creack/pty v1.1.24
	github.com/cretz/bine v0.2.0
	github.com/database64128/netx-go v0.1.1
	github.com/database64128/tfo-go/v2 v2.3.2
	github.com/dblohm7/wingoes v0.0.0-20240119213807-a09d6be7affa
	github.com/dgrijalva/jwt-go/v4 v4.0.0-preview1
	github.com/dgryski/go-camellia v0.0.0-20191119043421-69a8a13fb23d
	github.com/dgryski/go-metro v0.0.0-20180109044635-280f6062b5bc
	github.com/ebitengine/purego v0.10.0
	github.com/exclavenetwork/sing-juicity v0.3.1
	github.com/florianl/go-nfqueue/v2 v2.1.0
	github.com/fsnotify/fsnotify v1.9.0
	github.com/fxamacker/cbor/v2 v2.9.0
	github.com/gaissmai/bart v0.26.1
	github.com/go-chi/chi/v5 v5.2.5
	github.com/go-chi/render v1.0.3
	github.com/go-jose/go-jose/v4 v4.1.3
	github.com/go-ole/go-ole v1.3.0
	github.com/go4org/hashtriemap v0.0.0-20251130024219-545ba229f689
	github.com/gobwas/httphead v0.1.0
	github.com/gobwas/pool v0.2.1
	github.com/godbus/dbus/v5 v5.2.2
	github.com/gofrs/uuid/v5 v5.5.1
	github.com/golang/groupcache v0.0.0-20241129210726-2c02b8208cf8
	github.com/google/btree v1.1.3
	github.com/google/certificate-transparency-go v1.3.2
	github.com/google/go-cmp v0.7.0
	github.com/google/go-querystring v1.1.0
	github.com/google/gopacket v1.1.19
	github.com/google/nftables v0.2.1-0.20240414091927-5e242ec57806
	github.com/google/uuid v1.6.0
	github.com/hashicorp/yamux v0.1.2
	github.com/hdevalence/ed25519consensus v0.2.0
	github.com/huin/goupnp v1.3.0
	github.com/inconshreveable/mousetrap v1.1.0
	github.com/insomniacslk/dhcp v0.0.0-20260220084031-5adc3eb26f91
	github.com/jackpal/go-nat-pmp v1.0.2
	github.com/jsimonetti/rtnetlink v1.4.1
	github.com/keybase/go-keychain v0.0.1
	github.com/klauspost/compress v1.19.1
	github.com/klauspost/cpuid/v2 v2.3.0
	github.com/koron/go-ssdp v0.0.4
	github.com/kr/fs v0.1.0
	github.com/libdns/acmedns v0.5.0
	github.com/libdns/alidns v1.0.6
	github.com/libdns/cloudflare v0.2.2
	github.com/libdns/libdns v1.1.1
	github.com/libp2p/go-nat v1.0.1-0.20250821073202-01afc089f138
	github.com/libp2p/go-netroute v0.2.1
	github.com/logrusorgru/aurora v2.0.3+incompatible
	github.com/mattn/go-runewidth v0.0.27
	github.com/mdlayher/netlink v1.11.2
	github.com/mdlayher/socket v0.6.0
	github.com/metacubex/chacha v0.1.5
	github.com/metacubex/mihomo v1.19.13
	github.com/metacubex/randv2 v0.2.0
	github.com/metacubex/sing v0.5.5
	github.com/metacubex/tfo-go v0.0.0-20250921095601-b102db4216c0
	github.com/metacubex/utls v1.8.7
	github.com/mholt/acmez/v3 v3.1.6
	github.com/miekg/dns v1.1.72
	github.com/mitchellh/go-ps v1.0.0
	github.com/oh5uosnvh/shanlian-compat v0.0.0
	github.com/oh5uosnvh/snell-compat v0.0.0-20260808134633-c120d40d0d5b
	github.com/oh5uosnvh/viewturbo-compat v0.0.0
	github.com/openai/openai-go/v3 v3.26.0
	github.com/oschwald/maxminddb-golang v1.13.1
	github.com/philhofer/fwd v1.2.0
	github.com/pierrec/lz4/v4 v4.1.26
	github.com/pion/dtls/v3 v3.1.5
	github.com/pion/logging v0.2.4
	github.com/pion/transport/v4 v4.0.2
	github.com/pires/go-proxyproto v0.8.1
	github.com/pkg/sftp v1.13.10
	github.com/quic-go/qpack v0.6.0
	github.com/safchain/ethtool v0.3.0
	github.com/sagernet/asc-go v0.0.0-20260914163356-9e3d45a797c1
	github.com/sagernet/bbolt v0.0.0-20260915102804-500ee1e84832
	github.com/sagernet/cors v1.2.1
	github.com/sagernet/cronet-go v0.0.0-20261023060337-b3c2ed82ac97
	github.com/sagernet/cronet-go/all v0.0.0-20261023060337-b3c2ed82ac97
	github.com/sagernet/cronet-go/lib/android_386 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/android_amd64 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/android_arm v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/android_arm64 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/darwin_amd64 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/darwin_arm64 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/ios_amd64_simulator v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/ios_arm64 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/ios_arm64_simulator v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_386 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_386_musl v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_amd64 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_amd64_musl v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_arm v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_arm64 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_arm64_musl v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_arm_musl v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_loong64 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_loong64_musl v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_mips64le v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_mipsle v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_mipsle_musl v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_riscv64 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/linux_riscv64_musl v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/tvos_amd64_simulator v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/tvos_arm64 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/tvos_arm64_simulator v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/windows_amd64 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/cronet-go/lib/windows_arm64 v0.0.0-20261023055542-d5437a0df01a
	github.com/sagernet/fswatch v0.1.2
	github.com/sagernet/gliderssh v0.3.4-0.20261023092853-e0cad6d28647
	github.com/sagernet/gomobile v0.1.12
	github.com/sagernet/gvisor v0.0.0-20260727.0-sing-box-mod.1
	github.com/sagernet/netlink v0.0.0-20260814022025-64455d367bbf
	github.com/sagernet/nftables v0.3.0-mod.4
	github.com/sagernet/quic-go v0.61.0-sing-box-mod.12
	github.com/sagernet/sing v0.9.7-0.20261023115510-03a6cce64bf7
	github.com/sagernet/sing-anytls v0.0.0-20260928104022-580984e4d8cb
	github.com/sagernet/sing-box v1.15.0-alpha.13
	github.com/sagernet/sing-cloudflared v0.1.4-0.20261021152903-2d40d788fcf6
	github.com/sagernet/sing-mux v0.3.10-0.20260929204512-caf09fe32475
	github.com/sagernet/sing-openconnect v0.1.1-0.20260929151226-29757281a247
	github.com/sagernet/sing-openvpn v0.1.1-0.20261020061844-7d24ecd92837
	github.com/sagernet/sing-quic v0.7.2-0.20261020082049-7e0a5869a5f4
	github.com/sagernet/sing-shadowsocks v0.2.9-0.20260929204512-65740e0f0e3e
	github.com/sagernet/sing-shadowsocks2 v0.2.2-0.20260929152114-a69d1086332b
	github.com/sagernet/sing-shadowtls v0.2.2-0.20260928201441-a9c0127d5c99
	github.com/sagernet/sing-snell v0.0.0-20260904135315-bc5a12ac736f
	github.com/sagernet/sing-tun v0.9.7-0.20261023115453-611d83b7a57b
	github.com/sagernet/sing-usbip v0.0.0-20260817040617-28bd42667eca
	github.com/sagernet/sing-vmess v0.2.9-0.20261019223527-a0c58542bd9d
	github.com/sagernet/smux v1.5.50-sing-box-mod.1
	github.com/sagernet/tailscale v1.102.1-sing-box-1.14-mod.5.0.20261022222424-1688b52f8204
	github.com/sagernet/wireguard-go v0.0.8-0.20261022215849-fdf0ad046586
	github.com/sagernet/ws v0.0.0-20231204124109-acfe8907c854
	github.com/sirupsen/logrus v1.9.3
	github.com/smallstep/pkcs7 v0.1.1
	github.com/spf13/cobra v1.10.2
	github.com/spf13/pflag v1.0.10
	github.com/stretchr/testify v1.12.0
	github.com/tailscale/certstore v0.1.1-0.20260409135935-3638fb84b77d
	github.com/tailscale/go-winio v0.0.0-20231025203758-c4f33415bf55
	github.com/tailscale/hujson v0.0.0-20260302212456-ecc657c15afd
	github.com/tailscale/netlink v1.1.1-0.20240822203006-4d49adab4de7
	github.com/tailscale/peercred v0.0.0-20250107143737-35a0c7bd7edc
	github.com/tailscale/web-client-prebuilt v0.0.0-20250124233751-d4cd19a26976
	github.com/tidwall/gjson v1.18.0
	github.com/tidwall/match v1.1.1
	github.com/tidwall/pretty v1.2.1
	github.com/tidwall/sjson v1.2.5
	github.com/tjfoc/gmsm v1.4.1
	github.com/u-root/uio v0.0.0-20240224005618-d2acac8f3701
	github.com/ulikunitz/xz v0.5.15
	github.com/vishvananda/netns v0.0.5
	github.com/x448/float16 v0.8.4
	github.com/youmark/pkcs8 v0.0.0-20240726163527-a2c0da244d78
	github.com/zeebo/blake3 v0.2.4
	gitlab.com/go-extension/aes-ccm v0.0.0-20230221065045-e58665ef23c7
	go.uber.org/multierr v1.11.0
	go.uber.org/zap v1.27.1
	go.uber.org/zap/exp v0.3.0
	go4.org/mem v0.0.0-20240501181205-ae6ca9944745
	go4.org/netipx v0.0.0-20231129151722-fdeea329fbba
	golang.org/x/crypto v0.54.0
	golang.org/x/exp v0.0.0-20260410095643-746e56fc9e2f
	golang.org/x/mobile v0.0.0-20231108233038-35478a0c49da
	golang.org/x/mod v0.37.0
	golang.org/x/net v0.57.0
	golang.org/x/oauth2 v0.36.0
	golang.org/x/sync v0.22.0
	golang.org/x/sys v0.47.0
	golang.org/x/term v0.45.0
	golang.org/x/text v0.40.0
	golang.org/x/time v0.15.0
	golang.org/x/tools v0.47.0
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2
	golang.zx2c4.com/wireguard/wgctrl v0.0.0-20241231184526-a9ab2273dd10
	golang.zx2c4.com/wireguard/windows v0.5.3
	google.golang.org/genproto/googleapis/rpc v0.0.0-20251202230838-ff82c1b0f217
	google.golang.org/grpc v1.79.1
	google.golang.org/protobuf v1.36.11
	gopkg.in/yaml.v3 v3.0.1
	howett.net/plist v1.0.1
	lukechampine.com/blake3 v1.4.1
	zombiezen.com/go/capnproto2 v2.18.2+incompatible
)

replace github.com/matsuridayo/libneko => ../../libneko
replace github.com/sagernet/sing-box => ../../sing-box

// Pinned private MG modules are staged and renamed by stage_compat.py.
replace github.com/oh5uosnvh/shanlian-compat => ../../sing-anytls-shanlian
replace github.com/oh5uosnvh/viewturbo-compat => ../../sing-shadowsocks2-vt
replace github.com/oh5uosnvh/fastup-mod => ../../fastup-mod
replace github.com/oh5uosnvh/oppa-mod => ../../oppa-mod

// Keep modern upstream Snell for husi keep-session contexts; the old Snell
// authentication/QUIC proxy library has a separate identity.
replace github.com/oh5uosnvh/snell-compat => ../../sing-snell-pre26
replace github.com/sagernet/sing-vmess => github.com/xchacha20-poly1305/sing-vmess v0.2.9-0.20261120142420-bb1b0c7028c2

// MVS sorts the legacy dated pseudo-version above the modern custom tag.
// Force the sing-tun-compatible fork in the main module; dependency replaces
// are not inherited and otherwise WritePacketDirect is missing under with_gvisor.
replace github.com/sagernet/gvisor => github.com/sagernet/gvisor v0.0.0-20260727.0-sing-box-mod.1
