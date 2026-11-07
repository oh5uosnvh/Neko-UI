# Compact profile card / live traffic preference

Base: Build UI candidate #322, cde98105f2aa5d2f50db8c87c82e698f1f957ef8.

## Behavior

- Address off: three physical text rows. Address on: four rows.
- Title occupies up to two rows; no extra traffic/spacer row.
- Two columns: one overflow button, no edit/share/delete strip, no profile traffic regardless of preference.
- One column: original edit/share/delete actions. When statistics are enabled, title uses line one and traffic uses line two; otherwise title can use two lines.
- Preference changes trigger visible and cached page rebinding; zero statistics display when enabled. Hiding does not require clearing historical counters.
- The traffic loop reads the preference per tick. Live rate counters are independent from profile counters, so disabled intervals do not inflate profile totals when enabled again.
- Existing endpoint width fitting and status-priority rendering are preserved.

## Follow-up visual acceptance after #323

Device screenshots exposed three presentation defects not caught by the initial geometry test:

- Single-column traffic must align to the card's right content edge, not the left name column. It stays on line two and remains absent in two-column mode.
- The name's first text line must share the horizontal visual centerline of the edit/share/delete icons (single column) or overflow icon (two columns). Align actual glyph/font metrics, not just the center of the two-line name container.
- Endpoint and final protocol/status text should be separated by a small normal interline gap, not a blank-line-sized gap from oversized address/status rows.

Follow-up tests must measure absolute card-relative positions, right-edge alignment and first-line glyph center; merely asserting a positive name width or unchanged card height is insufficient.

## Local validation

Environment: Android SDK /opt/android-sdk, JDK 17, Alpine aarch64.
Native NDK build is unsupported on this host. Unit tests skip buildHevTun and reuse the unchanged libcore artifact from build #322; full APK/native validation must run on GitHub Actions.

Focused suite: 11 tests passed (ProfileCard*, AddressDisplayRefreshTest, EndpointTextViewTest, TrafficUpdaterTest).
Full group suite plus traffic tests: 406 tests, 56 failures.
Unmodified #322 control: 403 tests, 56 failures on the same local environment.
The full suite is therefore not claimed passing locally. Existing failures concern title bars, control bars, settings geometry, popups, etc.; CI is authoritative for its runner environment.

Layout measurement tests manually apply presentation states and verify XML geometry; they are not an end-to-end device test of ConfigurationHolder or service IPC. Final visual and runtime acceptance on an Android device remains required.
