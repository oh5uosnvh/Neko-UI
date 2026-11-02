# NekoBox UI source

This repository contains the Android application source tree and its integrated
UI, profile-management, selection, undo, and protocol wiring changes.

The source includes:

- official service button and traffic statistics surface;
- compact group-tab menu with edit, jump, clear, and delete actions;
- shared undo for clearing, deleting, duplicate cleanup, and unavailable-profile cleanup;
- transactional bulk import/removal paths for large profile collections;
- selection-mode group picker for chain-proxy front and landing profiles;
- suffix-driven X365, ViewTurbo, and FastUP integration;
- OPPA and Shanlian protocol integration;
- source and APK regression checks.

## Subscription DNS (pre2)

This iteration starts at the published **1.4.2-mg-pre1** commit `53f1f18`.
It adds group-scoped direct DNS, private domain policies and domain/IP hosts
mapping without changing node TLS identities. See [usage, behavior and tests](docs/subscription-dns.md).
The candidate default is `1.4.2-mg-pre2`, base code 50 (Android 250).
Parser, pinned-core DNS/TLS and Android JVM tests are mandatory build gates.

## Candidate build

`Build UI candidate` is a manually dispatched workflow; it uploads artifacts only
and never creates a release or pushes. Enter a safe version name and a **base**
version code in 49..420000000. The app multiplies that code by five; 49 yields
245, above the old APK's 240. Use a higher code for each later update.

The default candidate is **unsigned**, and cannot be installed as an update.
An explicitly selected signed build needs repository secrets `GH_PAT` (read-only
access to the private Nekobox-MG repository), `KEYSTORE_PASS`, `ALIAS_NAME`,
and `ALIAS_PASS`. The workflow checks out only `keystore/release.keystore` at
commit `a2267e3e51ff4aa851db4a247b2749dd36f6dfcb`, checks the certificate
SHA-256 against `521EA638C17FDEE184998B4A3CF9B10FA6272B9806FAE34B19CA3FFFE5D4556A`,
and verifies every signed APK before upload. Failure aborts; credentials are
not copied into artifacts. Protect workflow access and the private repository.

Local prerequisites: JDK 21, Go 1.24.9, Android SDK 35/build-tools 35.0.1,
NDK r25c and Linux x86_64; initialize recursive submodules. The Go replacements
in `libcore/go.mod` resolve to directories **beside** this repository. Stage
Neko-shanlian, Neko-viewTurbo, Neko-fastup and Neko-oppa from Nekobox-MG commit
`c91b33856ce5827a9a39e8d5d83188c505a15f32` respectively as sibling directories
`sing-anytls-shanlian`, `sing-shadowsocks2-vt`, `fastup-mod`, `oppa-mod`.
`./run lib core` fetches the pinned sing-box and libneko siblings and builds
the AAR; it refuses to overwrite dirty checkouts. Then run
`./run init action gradle` and `APP_VERSION_NAME=1.4.2-mg-pre2 APP_PRE_VERSION_NAME=1.4.2-mg-pre2 APP_VERSION_CODE=50 ./gradlew app:assemblePreviewRelease`.

These pins make source selection repeatable, not a claim of byte-identical APKs:
external action tags, Maven/Go dependencies and hosted runner images are not
all content-locked (geo assets are pinned and SHA-256 checked). Verify artifacts independently before distribution.
