#!/usr/bin/env python3
"""Idempotent API-only adaptation of CI-staged, pinned private MG modules.
Private authentication/cipher implementations are never copied into Neko-UI.
"""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[3]
deps = root.parent
modules = [
    ("sing-anytls-shanlian", "github.com/anytls/sing-anytls", "github.com/oh5uosnvh/shanlian-compat"),
    ("sing-shadowsocks2-vt", "github.com/sagernet/sing-shadowsocks2", "github.com/oh5uosnvh/viewturbo-compat"),
    ("sing-snell-pre26", "github.com/sagernet/sing-snell", "github.com/oh5uosnvh/snell-compat"),
]
for directory, old, new in modules:
    folder = deps / directory
    if not (folder / "go.mod").is_file():
        sys.exit(f"Missing pinned protocol module: {folder}")
    for path in [folder / "go.mod", *folder.rglob("*.go")]:
        text = path.read_text()
        changed = text.replace(old, new)
        if changed != text:
            path.write_text(changed)
    if f"module {new}" not in (folder / "go.mod").read_text():
        sys.exit(f"Unexpected module identity: {folder}")

# Network reset closes sessions without permanently cancelling the client.
path = deps / "sing-anytls-shanlian/client.go"
text = path.read_text()
if "func (c *Client) Reset()" not in text:
    text += "\nfunc (c *Client) Reset() { c.sessionClient.Reset() }\n"
path.write_text(text)
path = deps / "sing-anytls-shanlian/session/client.go"
text = path.read_text()
if "func (c *Client) Reset()" not in text:
    text += """
func (c *Client) Reset() {
    c.sessionsLock.Lock()
    closing := make([]*Session, 0, len(c.sessions))
    for _, session := range c.sessions { closing = append(closing, session) }
    c.sessionsLock.Unlock()
    for _, session := range closing { session.Close() }
}
"""
path.write_text(text)

# OPPA's wire/auth code is unchanged; only v1.15 TLS logger + STDConfig API.
path = deps / "oppa-mod/oppa/outbound.go"
if not path.is_file():
    sys.exit(f"Missing pinned OPPA module: {path}")
text = path.read_text()
text = text.replace("tls.NewSTDClient(ctx, options.Server,", "tls.NewSTDClient(ctx, logger, options.Server,")
text = text.replace("tlsConfig.Config()", "tlsConfig.STDConfig()")
path.write_text(text)
if not (deps / "fastup-mod/fastup/outbound.go").is_file():
    sys.exit("Missing pinned FastUP module")
print("Protocol module identities and v1.15 TLS APIs staged")
