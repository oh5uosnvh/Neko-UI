# Snell compatibility source

This wrapper preserves the behavior shipped in Neko-UI `1.4.2-mg-pre25`.

- Wrapper/outbound reference: `starifly/sing-box` commit
  `0b7a2fc2f2c33f754e5fe5a1bb3e0a4f610b6aa9`, `protocol/snell/outbound.go`.
- `legacywire/`: `reF1nd/sing-snell` commit
  `c120d40d0d5b` (`v0.0.0-20260808134633-c120d40d0d5b`), the exact pre25 replacement.
  Its `LICENSE` is preserved in that directory. Only local Go import prefixes
  change, so the old crypto, record framing, multi-user and QUIC proxy formats
  do not depend on the newer official Snell module's narrower API.
- Adaptations: local option discriminator accepts versions 1–6 and v6
  `quic_proxy_mode`; core 1.14 `Scope` cleanup and context-aware network update
  notification replace legacy lifecycle integration.
- The official updated Snell module is still pinned for upstream libbox/include
  compilation; Neko's outbound registry deliberately selects this compatible
  implementation so existing profiles do not silently lose v1–3/v5 or QUIC mode.

Tests distinguish constructor/schema acceptance from wire tests. No test here
claims availability of an arbitrary remote service.
