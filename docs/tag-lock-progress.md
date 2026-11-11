# Tag picker lock UI progress

- Updated `ConfigurationFragment` to expose its persisted `scopedTagId` as the current locked tag for the picker; existing rotation persistence and deleted-tag fallback remain authoritative.
- Updated `TopBarController` so a fresh picker open reconciles to the locked tag's Members page. If the locked tag was deleted, the existing scope-exit fallback clears the lock and returns to Configuration.
- Updated `GroupPickerTags.kt` Members header with a right-side red circular `!` marker only for the locked tag. Tapping it shows `已锁定当前标签组▶长按解除锁定` (localized fallback included); the title retains the existing short-click back action and supports long-press lock/unlock through the existing scope bridge.
- Added `tag_picker_locked_hint` to base and Simplified Chinese picker resources.
- Clean-up verification: no duplicate `lockIndicator` declaration remains; `Toast` import and both resource definitions are present; `git diff --check` passes.
- Added Robolectric coverage in `GroupPickerTagsTest`: matching locked Members header shows the red `!`, tapping reports the lock hint, title long-press calls the existing lock bridge, short back remains a back-only action, reopening the pure navigation model starts at Members, and clearing/deletion makes the tag invalid.
- Static verification only (`rg`, diff review, `git diff --check`); Gradle/CI deliberately not run. No commit or push.
