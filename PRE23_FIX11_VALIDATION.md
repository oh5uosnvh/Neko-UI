# pre23 deep fix11 — configuration edge + stable bounded latency probes

Baseline: `55d10259289167485d417301d67d9cd69a0db20a` (latest successful fix10 build).

## Configuration cards: Groups page is the reference

The configuration title is a visible custom row beside a `GONE` native Toolbar.
Previously `TitleBarLayout.updateTitleBoundary()` treated that hidden Toolbar as
"no title" and fell back to the full AppBar bottom, including `group_tab`.
The pager and every page ancestor were consequently clipped at the tabs bottom;
changing a gradient height could not reveal pixels already clipped away.

- Resolve visible title ink independently of native Toolbar visibility.
- Place the complete pager viewport at the measured title-ink bottom.
- Keep the stationary first row at complete header bottom + original 4dp gap.
- Configuration uses the actual **same `TitleBarEdgeRenderer`** as Groups, with
  its measured title-to-tabs interval. Groups retains its original 48dp cap.
- Title, pills, latency strip, fixed scrollbar and bottom controls stay outside
  card blur recording. No new blur of labels, adapter data or scroll offsets.
- Refresh page padding on tab visibility/height changes without accumulating it.

`ConfigurationNodeEdgeTest` exercises actual inflated hierarchy, ViewPager2,
page ancestors, hidden native Toolbar/custom title, pixel masks versus canonical
Groups renderer, night mode, large fonts, resizing, and header touch priority.

## Latency probes

No historical RTT cache is used as a successful current result.

- Bound **whole live-probe lifecycles** to configured concurrency (1..16).
  Default for an unset preference is 12; explicit existing settings are retained.
- Bound native initialization to two slots to avoid a burst of cold Box creation.
- Successful nodes complete in the first pass. Retry transient failures once in
  a separate two-worker verification pass after the first pass has drained.
  Publish exactly one final result; no provisional timeout flicker.
- Authentication/certificate/configuration/missing-host failures are not blindly
  retried. User cancellation is never converted to a node failure or retried.
- Replace unowned `GlobalScope` per-instance work with structured worker/cleanup.
  Initialization and close are ordered, and cancellation joins native/plugin
  cleanup before closing or reusing the test executor.
- Native HTTP request contexts inherit the owning Box cancellation.
- Prime and warm RTT samples get separate bounded budgets. Count request-header
  write to first response byte, not queueing/Box startup/TLS setup.
- If a valid first response exists but optional warm sampling fails, return that
  saved **current-run measured** RTT, not a timeout or historical value. The
  failed wait is not added to RTT. Owning-core cancellation still returns error.
- Keep GET probe URL behavior, status validation and small-body draining for
  connection reuse. TLS and proxy transport identity remain unchanged.
- Add per-node wall-time/RTT instrumentation without logging node credentials.

External plugins retain their existing 500ms readiness allowance. This revision
is not a complete FlClash-style shared-core/outbound rewrite.

## Validation

- Complete Android application AND complete unit-test sources: compiled locally.
- JVM cancellation/scheduling/retry tests: 13 passed.
- HTTP probe tests: 7 passed; repeated five rounds and 120 concurrent probes.
- Local native drawing tests cannot run: Robolectric reports Linux aarch64 native
  runtime unsupported. Their actual pixel execution is gated in GitHub x86_64 CI.
- Local ThreadSanitizer cannot run on this Android kernel's 39-bit VMA; race tests
  are required in GitHub CI (`go test -race ./probe`).
- These are deterministic local test endpoints. Real subscription nodes,
  device/VPN/kernel load and network variation still require on-device checking.
  No claim of identical FlClash speed or guaranteed network stability is made.

## On-device acceptance

1. Compare Groups and Configuration pages while scrolling slowly: no hard clip
   at tabs bottom; node pixels continue underneath group names and disappear at
   Neko text lower edge; pills/title stay crisp.
2. Verify latency strip still anchors to tabs and does not change card positions.
3. Test a large group with concurrency 12, then repeat five rounds from a cold
   app start. Record total time, false-timeout count and RTT spread.
4. Cancel during testing, immediately restart; verify no old results/cores leak.
5. Include permanently dead nodes and bad certificates: they remain failures.
