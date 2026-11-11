# pre22 Rule / Global live-mode prototype

Base: `1.4.2-mg-pre22` (`f8b036f2fc0485fe232230bceda977a4ed1977b3`).
Candidate name: `1.4.2-mg-pre22-global-live-p0`.

This is an experimental **mode toggle**, not arbitrary configuration hot reload.
The generated configuration contains both Rule and Global routing branches,
including mode-guarded DNS rules derived from user routes. The app calls the
same running Go Box through Binder/gomobile. A failed mode request must not
invoke service reload, reset existing connections, or silently persist success.

## Scope

- Rule ↔ Global for generated configurations; no new listener or VPN interface.
- Global means traffic already captured by VPN/mixed, not apps excluded by the
  Android VPN capture rules. Existing LAN bypass and bootstrap protections stay.
- Existing TCP/UDP associations retain their original outbound. New connections
  use the new mode. A browser may reuse old HTTP/2/QUIC connections: use a fresh
  connection when checking whether the new policy is applied.
- Node editing, DNS upstream changes, sniff settings, MTU, app capture lists and
  stack changes are **not** included.
- Opaque raw/global/node/rule custom JSON is not promised live-mode compatibility.
- Stop/Connecting/Stopping and unavailable mode branches must return failure,
  never start/reload the service as a fallback.

## Manual Android checks

1. Back up the app data before installing the experimental candidate. The
   candidate is intended to use the existing application ID and audited signer.
2. Create a recognizable Rule-mode bypass/reject rule for a test destination.
3. Start the VPN once in Rule mode. Record the service/core/TUN startup logs.
4. Establish a long-lived connection and leave it open.
5. Toggle Global. Verify success acknowledgement, fresh connections use the
   main proxy, and no service restart/TUN establish/core startup is logged.
6. Verify the old connection remains usable. Do not infer migration from this.
7. Toggle back to Rule. Fresh connections must match the original test rule.
8. Toggle during Connecting/Stopping and with unsupported custom configuration:
   it must report failure without changing the stored preference.
9. Stop and start normally; the last acknowledged app mode must be restored.

A process ID remaining unchanged alone is not proof of no core reload. Android
VPN continuity needs on-device logs/counters in addition to the host harness.
No Android runtime result is claimed until that check is actually performed.

## Evidence

The checked-in core patch series records the upstream commit and SHA-256 of each
patch. The CI harness exercises actual loopback mixed-inbound routing and runs
with the Go race detector. The normal Android build also runs mode compiler and
UI/service contract tests. See the candidate's build report for exact results;
this document describes the acceptance target, not a pre-recorded pass.
