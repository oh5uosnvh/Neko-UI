# pre13: endpoint single-line shaping and group undo viewport

Based on the verified pre12 candidate (`a0fe96d`).

## Address screenshot regression

The user's Android15 screenshot showed a dotted Shadowsocks hostname and port `19273` rendering in two lines with the final `3` at the left of line two. Pre12 passed API28 tests but allocated StaticLayout width using `ceil(Layout.getDesiredWidth())`. Shaping at that tight width can exceed the estimate on another font/backend; Android wraps the last digit, and the vertical-height fallback compresses both lines into the fixed 24dp row.

Pre13 separates the shaping canvas from the display width. A wide single-line LTR StaticLayout is used to obtain actual shaped line width and ink bounds; host-only fitting uses those measurements. The display is then drawn in the real padded row without tight-width line breaking. Original endpoint text remains unchanged; port/list/range protection and SS/SSR labels remain.

Native tests now cover API28 AND API35, screenshot-style dotted hostnames, ports 19273/14561/65535/474/484, a one-pixel width sweep and font-scale variants. They require exactly one line and disallow the previous two-line-height compression. Output screenshots are included in `reports/ui-pre13`.

## Group deletion undo

The separate group mutation repair preserves stable group identity, selected-page intent and the horizontal tab viewport across TabLayoutMediator tab recreation. Its details and regression tests are in the corresponding helper/test source files.

## Boundaries

No protocol transport, subscription mapping, DNS database version, serialized node address or signing certificate changes. Existing checksum-pinned build dependency recovery remains. A passing emulator/native test suite is not a claim of direct installation and verification on the user's phone.
