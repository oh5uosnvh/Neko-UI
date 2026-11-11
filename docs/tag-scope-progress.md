# Tag-scoped configuration progress

- Branch: `feat/pre22-global-selector-tags` (pre22 base)
- Added a tag-scope bridge/rules in `GroupPickerTags.kt`; the Members header title supports long press while its short click remains the existing back action.
- `ConfigurationFragment` now captures a tag ID, persists it through rotation, filters pager tabs to tag membership, validates deleted tags, guards stale async reload generations, and passes the captured tag ID to new-group/subscription settings/import flows.
- Existing `GroupManager.createGroup(group, tagId, profiles)` is the atomic root + membership path; `GroupSettingsActivity` already consumes `EXTRA_TAG_ID` and calls it.
- Targeted verification still pending: compile and tests for picker header/scope rules, rotation/deleted-tag fallback, and tagged imports.

## Frozen verification

- `git diff --check`: passed.
- Targeted Gradle command (no concurrent Gradle, no native task):
  `ANDROID_HOME=/opt/android-sdk ./gradlew :app:testOssDebugUnitTest --tests io.nekohasekai.sagernet.group.GroupPickerTagsTest --tests io.nekohasekai.sagernet.group.GroupPickerTagScopeTest --no-daemon -x :app:buildHevTun`
- Result: blocked before tests by another agent's existing `ConfigBuilder.kt:622:44` (latest run reported line 622): `Argument type mismatch: actual type is 'io.nekohasekai.sagernet.database.ProxyGroup?', but 'io.nekohasekai.sagernet.database.ProxyGroup' was expected.` No selector/runtime files were changed here.
- Earlier compile attempt reached the same unrelated `ConfigBuilder.kt` error. Native `buildHevTun` was excluded as requested.

Frozen implementation includes: header long-press bridge and pure scope rules; captured tag state with rotation persistence; tag membership filtering with stale generation/deleted-tag fallback; scoped new-group and subscription import propagation via `EXTRA_TAG_ID`/`importSubscription`; validation in `GroupSettingsActivity`; focused `GroupPickerTagScopeTest`.

## Review follow-up: three scoped corrections (frozen, no Gradle run)

1. Successful `enterTagScope` now dismisses the picker immediately. The Configuration overflow menu exposes `返回全部分组` / `Show all groups` whenever a tag scope is active; selecting it clears the scope and refreshes tabs. Exit also closes any picker.
2. Clipboard imports capture `capturedTagId` before parsing. File imports capture `pendingFileTagId` before launching GetContent, persist that ID and picker-busy state in saved instance state, and consume that ID in the result callback. All node/subscription/settings continuations use the initiating ID. Missing captured tags produce an explicit import failure rather than being changed to null. GroupSettingsActivity passes the original EXTRA_TAG_ID unchanged to the atomic GroupManager create path, whose transaction rejects deleted tags.
3. Scoped node imports choose only BASIC groups belonging to the captured tag (selected scoped manual group first, then first scoped manual group). If none exists, create a BASIC group with GroupManager.createGroup(group, capturedTagId). Both ordinary and streaming node appends validate tag existence, group membership, BASIC type, and pending-delete status inside the same outer DB transaction as node writes. UI callbacks check view/adapter identity and the initiating scope before selecting imported groups.

Targeted tests added to GroupPickerTagScopeTest: scoped manual-target preference, outside/subscription exclusion, empty-tag fallback indication, pending-delete exclusion, and source contracts for async capture/rotation persistence/deleted-tag no-downgrade. Source-contract tests are not runtime rotation tests.

Verification of this follow-up: git diff --check and static source assertions only. Gradle was deliberately NOT run as requested; no new local.properties, commit, push, or changes to selector/runtime/other agents' owned files. Earlier Gradle blocker remains historical, not rechecked in this pass.

Limitations: when a scope has no BASIC target, a tagged empty group is created before parsing/appending streamed nodes completes; a later malformed-file failure can leave that empty tagged group, but cannot write nodes into another tag. ScannerActivity/MainActivity external intent flows are not altered; the corrections cover Configuration clipboard/file import and new-group/settings paths. Exit label is inline bilingual (zh/en) to avoid editing shared resource files.
