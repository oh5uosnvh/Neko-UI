# pre12 card endpoint / protocol display repair

Base: `1.4.2-mg-pre11` (`7eae676affefcb876236506a5216c55b5230a6ee`).

## Root cause

* pre11 rewrote `TextView.text` to fit only from measure/layout hooks. Same-width RecyclerView rebinds can replace it with the full endpoint without a new child layout; the platform then physically clips the port.
* Its separate Paint and heuristic character-width floor did not describe the actual rendered font.
* The two-column protocol helper ignored its layout-mode argument and returned Shadowsocks/ShadowsocksR unchanged.
* Old tests checked `endsWith(port)` even at 1px; they did not prove glyph visibility.

## Change boundaries

Only display code, card address layout, tests and screenshot-artifact collection change. No proxy beans, protocol transport, DNS, subscription mapping, serialization or connection parameters change. Original host spelling (including hyphens) is preserved.

## Implementation

`EndpointTextView.text` remains the full address for binding/accessibility. A cached StaticLayout is generated at draw time from the final compound-padded width and actual TextPaint. Host-only truncation reserves the complete numeric port/list/range suffix. Bracketed IPv6 stays bracketed, UTF-16 surrogate pairs are not split. Ink bounds protect italic overhang, and controls are normalized only in the one-line display. No recursive setText or layout invalidation loops.

Normal endpoints retain the configured font. Exceptionally, if the complete port specification itself is wider than the entire row, the port-only fallback is scaled horizontally instead of clipped. Oversized text is constrained vertically to the fixed address-row height. These exceptional fallbacks preserve contents but extremely long lists may be small; full source remains accessible.

Two-column display maps only exact names (case-insensitive): Shadowsocks => SS; ShadowsocksR => SSR. Single-column/other labels and protocol colors remain unchanged. Accessibility retains the full protocol name.

## Verification

* Deterministic Kotlin/JUnit fitter and protocol tests.
* Android app and all test sources compiled locally using pre11's verified libcore artifact (local JVM tests exclude native HEV rebuilding).
* CI executes the entire `io.nekohasekai.sagernet.group.*` test set before assembling APKs.
* Endpoint tests use Robolectric NATIVE graphics: same-size long/identical rebind without layout, final width changes, host spelling, IPv6, lists/ranges, spacing/typeface/padding/RTL, control chars, italic final digits, source restoration and real card SS/SSR screenshots.
* Actual glyph pixels are compared to an unclipped reference; layout caret/advance boundaries are checked too.
* CI screenshots are uploaded under `android-test-results/reports/ui-pre12/`.
* Signing uses the existing verified legacy certificate; no key or token is committed.

Native drawing cannot run inside this ARM64 Alpine JVM because Robolectric's bundled Linux JNI runtime is x86_64. CI on Ubuntu supplies that verification; local success alone is not claimed as full graphics validation.
