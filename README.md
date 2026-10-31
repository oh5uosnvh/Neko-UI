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

Build dependencies are resolved by the repository workflow. Secrets and signing
credentials are supplied by the build environment and are not stored here.
