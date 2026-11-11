# Global selector progress

- Milestone 1: Added persisted `globalSelector` setting and global-preferences entry; per-group `isSelector` remains unchanged.
- Milestone 2: ConfigBuilder now compiles selector members across all groups, preserves each member's `resolveChain()` and rejects mixed subscription DNS policies instead of silently borrowing the active group's policy.
- Milestone 3: Added selector membership/chain fingerprinting and BaseService validation; cross-group selection uses the same running Box and rejects stale membership/configs.
- Milestone 4: Native SelectOutbound now serializes against Box lifecycle and returns the native bool; callback membership is checked before updating host state.
- Milestone 5: Moved fingerprint canonicalization into `SelectorFingerprint`; focused tests cover cross-group order independence, persisted edits, and exclusion of runtime selection/ports.
- Milestone 6: Added `ISagerNetService.selectSelectorNode(profileId): String`. The synchronous JSON contract is `{ok,ack,profileId,actualId,tag}` on success and `{ok:false,ack:false,error,message}` on rejection. The binder persists `selectedProxy` and `currentProfile` only after native acknowledgement and verifies the same `ProxyInstance` is still running.

The callback path validates the compiled tag, membership, and fingerprint before updating traffic/UI state. A successful switch resets active connections (`Libcore.resetAllConnections(true)`), so this is no reload but can interrupt existing flows. Same-node selection may have no native callback; the binder ACK is authoritative.

## Support limits

Global live switching supports ordinary built-in profiles whose groups share the exact subscription DNS policy. It rejects mixed DNS policies, missing groups/members, empty selectors, custom/raw configs and any changed membership/chain/tag fingerprint. External plugin processes and custom JSON may still require a normal restart. UI placement is limited to global preferences; ConfigurationFragment integration is intentionally left to the main session (it must call the binder ACK before persisting/UI selection). No group/tag/search UI files were changed here.
