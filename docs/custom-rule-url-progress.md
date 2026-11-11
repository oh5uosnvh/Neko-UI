# Custom route URL integration

- Added routeRuleUrl editor field directly below custom config, draft restored through DataStore and persisted as existing RuleEntity.sourceUrl. HTTP(S) URL validated before save.
- Update rules action now updates every stored nonblank sourceUrl (catalog and custom). Waiting custom cards can retry by ID.
- SafeConstructor YAML parser supports classical/domain/ipcidr payload entries; max 4MiB, bounded connect/read/call timeouts, no YAML aliases. Unsupported rule types counted and shown, malformed/zero-useful payload never replaces old cache.
- Custom downloaded domains/IPs stored separately in remoteDomains/remoteIps. DB13 additive migration preserves manual fields, source/outbound and old catalog cache. Compiler merges cache into effective route without altering editor fields; pending source without conditions is omitted rather than catch-all.
- Fresh-row transaction changes only cache/state and rejects changed URL/builtin identity; source deletion discards results, errors retain last good cache. Serial update mutex prevents same-process out-of-order publications.
- User Microsoft URL fetched successfully: 668 useful domain entries, 2 PROCESS-NAME entries skipped explicitly. Exact offline fixture kept under app/src/test/resources/routes/Microsoft.yaml (upstream authorship/license applies).
- Main compilation and local selected tests: 30 tests passed incl parser, URL/menu contract, tag header, scope and name clustering. DB migration/storage and real builder tests added for Ubuntu (ARM host SQLite native library unavailable).
- No assertion of Android VPN live-route update: explicit Apply/reconnect is prompted after rule updates.
