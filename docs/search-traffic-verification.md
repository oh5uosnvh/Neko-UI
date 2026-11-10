# Search and zero-traffic correction — local verification

Baseline: latest successful candidate `6a1642d64185f34899efd74e861e3dcac2a382e3`, build 42766619051, branch `fix/pre19-group-search-classify`. The default main branch was not used as the UI baseline.

## Changes
- Replaced GroupFragment's collapsing menu SearchView with a full-width AppBar search row, using the configuration page's SearchView appearance, transparent search plate, page theme text/hint/icon colours, and one permanent exit X. No navigation-back arrow or group/global scope control.
- Right-hand order: classify pill, exit X, confirm. Confirm is INVISIBLE (not GONE) before custom selection, keeping its measured far-right position. Disabled until at least one custom selection exists.
- Nonblank queries matching at least two visible groups reveal the classify pill leftwards from X using width/translation animation. X and confirm do not move. Custom selection hides the pill and reveals confirm. Editing the query clears manual selection; data reloads prune removed selections.
- Closing search restores the normal toolbar and removes the search back callback; destroy cancels animation and input references.
- Full profile binds and incremental traffic binds use one shared visibility/text/translation binding. All-zero cumulative tx/rx is hidden; any nonzero direction is shown if statistics are enabled and the existing single-column policy allows it. Recycling/reset clears stale text and translation. Persisted and live cumulative snapshots are not added twice.

## Passed before push
- `git diff --check`
- `bash -n run gradlew`
- Android resources, ViewBinding, KSP, complete PreviewDebug Kotlin and Java compilation, unit-test compilation.
- 22 tests, zero failures: GroupSearchBarTest (4), ProfileTrafficVisibilityTest (3), GroupClassificationTest (2), GroupJumpHighlightTest (4), CardLatencyBindingTest SDK28 (4), GroupOrderStoreTest (5).
- Search layout tests inflate the actual `layout_appbar`/MaterialToolbar with theme overlay, measuring positions and testing rapid animation reversal.

Command: `ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew --no-daemon -I ../local-sdk28.gradle :app:testPreviewDebugUnitTest --tests '*GroupSearchBarTest' --tests '*ProfileTrafficVisibilityTest' --tests '*GroupClassificationTest' --tests '*GroupJumpHighlightTest' --tests '*CardLatencyBindingTest' --tests '*GroupOrderStoreTest' -x :app:buildHevTun`

## Limitations
The local sandbox is Alpine Linux/aarch64. Native HEV build is excluded; compilation uses an existing libcore AAR. SDK28 Robolectric legacy graphics tests run normally. An exploratory GroupOrderPersistenceTest run cannot load Robolectric native SQLite on Linux/aarch64; SDK35 native graphics likewise cannot load. These are not recorded as passing. CI's x86_64 runner performs the repository's complete group test suite and native/APK build. No claim of installed-app manual/device regression testing is made.
