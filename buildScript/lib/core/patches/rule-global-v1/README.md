# Rule/Global live-mode prototype v1

## Provenance / integration

- Production source pin is unchanged: starifly/sing-box `0b7a2fc2f2c33f754e5fe5a1bb3e0a4f610b6aa9`.
- `series.json` records the exact baseline and SHA-256 of every patch, in application order. Do not describe this as an unmodified upstream core.
- `get_source.sh` applies the series once; a second run verifies the exact expected Git tree without resetting/stashing/cleaning. Unknown tracked, staged, untracked or ignored edits are rejected, not overwritten.
- `build.sh` verifies the patched tree before invoking gomobile. The harness verifies it independently.
- Current patch SHA-256: `3901831d7d65bc4082383f369e288df5c0aaa76c5108d7ef053eb4b6abf0ec11`.
- Verified patched Git tree: `6b13118b70956c8a250305c385af5a1bb3bd6387`.

## Contract

`BoxInstance.SetRuntimeMode(string) (bool,error)` is serialized with Start/Close by `b.access`, only permits Rule/Global, and fails closed for unstarted, failed-start, closed, unsupported and uncompiled modes. There is no reload/rebuild/reset fallback. Start marks running only after successful core startup; failures are terminal until Close. RuntimeMode returns the core mode under the same lifecycle lock. The ClashServer handle is captured using `service.FromContext` after box construction.

Core mode/status/hook reads and writes are protected by an RWMutex; ModeList returns a defensive copy. HTTP `/configs` also uses synchronized getters. Explicit `default_mode` wins over saved core cache; configs omitting it retain legacy cache restoration. Setter publication, optional persistence and notifications share one writer lock. No platform DNS-cache callback runs under that lock.

Routing captures mode once into per-operation metadata. DNS resolution nested in that route inherits it. Independent DNS operations capture current mode. Hijacked stream/packet and legacy DNS outbound handlers clear socket route tokens per DNS message, so a long-lived DNS socket is not pinned to an obsolete mode. This is a narrow mode-only snapshot, **not a general cross-resource transaction**: no live transport/rule/ruleset/outbound replacement, no revision/CAS or multi-resource rollback protocol is provided.

PreMatch conservatively returns nil: no early policy reject or DNS resolve can become stale before the real route operation. The actual routing rule still performs reject/hijack/route decisions. This also defers static rejects, with possible extra work/connection establishment before a later reject; it is intentional for the prototype.

## Cache policy and limitations

For DNS Router calls with a ClashServer, `options.DisableCache=true` is forced at Exchange/Lookup entry, retained in each copied `dnsOptions`, and no rule path assigns false (rule actions only assign true). The stock DNS Client gates response-cache lookup/write, question-cache lookup, singleflight, RDRC lookup and async RDRC save on that flag. Thus old in-flight response completion cannot refill or satisfy the new mode's policy cache. This is a conservative **cache bypass**, not revision-namespaced caching.

Performance cost: every uncached logical DNS query reaches its upstream; concurrent identical queries are not coalesced; repeated negative/rejected responses are not reused. DNS volume, latency, upstream load and battery use can increase substantially. Existing transport connection pools are unchanged. Future production work should add mode/generation namespaces to response/singleflight/RDRC keys instead of keeping this fallback indefinitely.

Reverse mapping remains a shared compatibility facility: real DNS answers still populate it and IP-only routing may still use prior mappings, preserving normal Rule-domain matching semantics. FakeIP mappings/storage are unchanged and not cleared. Reverse mappings/FakeIP are not advertised as transactionally mode-isolated; different DNS policies returning conflicting names for one IP retain upstream shared-map semantics. This limitation is explicit rather than silently disabling domain rules for IP connections.

Already connected TCP/tunnels and UDP associations keep their selected route. This does not migrate existing sessions to another outbound. The harness covers localhost mixed HTTP/CONNECT, UDP DNS and hijacked DNS TCP, not Android TUN/platform callbacks, every proxy protocol, real Internet reachability or APK/device lifecycle.

## Reproduce

```sh
python3 buildScript/lib/core/test_patches.py
bash buildScript/lib/core/get_source.sh
bash buildScript/lib/core/get_source.sh
bash buildScript/lib/core/test_runtime_mode.sh
(cd libcore && go test -tags with_clash_api -race -count=1 -run '^TestRuntimeModeBridge' .)
```

`test_runtime_mode.sh` runs both normal and `-race` tests in a temporary module with checked-in go.mod/go.sum, refusing a mismatched source tree. CI runs these before building libcore, plus bridge lifecycle race tests and Android `fmt.*` tests. A race runtime failure is not converted into success.

Local evidence (Linux/arm64 Android PRoot, Go 1.26.8):

1. Mixed Rule/Global/Rule actual endpoints PASS; one box and listener, endpoint hits Rule=6 / Global=1; original CONNECT tunnel stayed on Rule across both switches.
2. 8 workers x 1000 mode/status/ModeList-copy/PreMatch operations PASS in normal test execution.
3. Real UDP DNS Rule/Global/Rule and old blocked in-flight completion PASS; same qname queried on both modes, later Global query still returned Global, upstream hits a=3 / b=3. Thus TTL=300 answer was not reused after old completion.
4. One persistent mixed CONNECT DNS TCP socket returned Rule/Global/Rule answers PASS.
5. Explicit initial Rule beat cached Global; omitted-default raw config restored cached Global PASS.

Five normal tests passed against the exact patched Git tree (`ok nekobox-mode-harness 0.452s`). Core adapter/clashapi/route/rule/dns package compilation also passed. Hermetic patch application/digest/idempotence/unknown-dirty tests passed.

**Race execution is not locally passed:** the detector compiled, but runtime exited before tests with `FATAL: ThreadSanitizer: unsupported VMA range; Found 39 - Supported 48`. Ubuntu-24.04 CI is the intended supported race runtime; CI success is required, not presumed. Full libcore/AAR build and bridge tests require the private sibling protocol modules and were not run on this device.
