# pre23 selector fixes — candidate validation

Baseline: last successful experimental build `1b556257ae33bfaf8c5fbe6f6f15f17b75a00c32`, Actions 43003102734, pre22-selector-tags-r4 (Android code 340).

Changes:
- Index continuous Chinese substrings of at least two characters, covering prefixes/middle/suffix without requiring a bare canonical brand; choose common coverage then longer shared brand; ignore generic descriptors and never merge through a transitive bridge. 饿饭, 蓝猫, 飞猪 screenshot examples now have actual regressions. Existing Latin normalization/bounded matching remains.
- Top picker capsule owns its short/long gestures; decorative weighted title no longer steals taps. Red lock marker remains independent. Real MotionEvent regression checks left/icon/title/right and marker exclusion.
- Fresh locked-scope picker open prioritizes selectedGroup stable ID over the stored browsing viewport. Ordinary unlocked in-session viewport restoration is retained. Duplicate-title group-ID regression added.
- Global selector no longer aborts merely because another subscription has different DNS fields. Each compiled node receives a subscription-scoped fallback DNS resolver, including nodes without imported DNS. Application DNS stays with the active node; node-only DNS differences can hot-switch. Different application policies require an explicit manual stop/select/start; service refuses without modifying selection or restarting. Full generated-config fingerprint still rejects stale membership/policy.

Validation:
- Local subscription DNS parser: 27 tests passed.
- Local changed-source compilation and test compilation passed.
- Local classifier (11), source safety contracts (4), actual capsule touch (1): 16 passed.
- Room-backed tests cannot run on this aarch64 host because Robolectric legacy SQLite supports only x86_64. They are run by the unchanged Ubuntu GitHub Actions workflow; this is NOT treated as passing locally.
- Candidate built only after authoritative CI suites + native DNS race tests pass; legacy signature digest must be 521ea638c17fdee184998b4a3cf9b10fa6272b9806fae34b19ca3fffe5d4556a.

Not claimed: universally hot-swapping different application DNS policies, automatic reload disguised as no-reload, on-device end-to-end VPN verification of the user's private subscriptions.
