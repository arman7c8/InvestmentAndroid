# Android standalone release candidate gate — 2026-10-10

## User-approved scope
Complete and test Android features independently of Windows bidirectional financial synchronization. Keep Windows v0.12 and its user data untouched. This staging branch is **not a production release**.

## Existing on-device evidence
The previously built isolated APK at commit `782e5e9390d8872a72f0083bd63c4133087e2e89` passed Gradle unit tests, lint, debug build, signing and package verification in Codespaces. User screenshots verified: Android-local read/write portfolio calculations, read-only Windows Core preview, no mixing of local and Core financial data, persistence after reopening, and refusal to import a disguised Windows Core projection. This does **not** validate any newer commit.

## This incremental standalone feature
- Portfolio History now renders a read-only value-over-time line from its actual stored `Snapshot(timestamp,totalValue)` data; it does not generate synthetic points, infer cash-flow-adjusted performance or modify holdings.
- `PortfolioTrend` isolates chronological scaling, filters nonfinite/negative samples, and handles one or multiple same-time samples. Five synthetic JVM tests added.
- UI keeps the old history list below the trend, so no existing history details are removed.

## Outstanding before labeling an APK final
1. Run the **full** Gradle unit tests, lint and assemble on the current exact source commit in isolated Codespaces: `bash scripts/build_unified_preview.sh`. The local tool container has Kotlin but **no Android SDK** and cannot run full Android build. Do not use a previous APK checksum for this source.
2. Verify the portfolio history UI on the handset with at least two disposable snapshots, and verify dark-mode labels in FA and EN. No real financial information.
3. Test backup export -> JSON readback -> restore with synthetic history and repeat import; report failures, not fabricated successes.
4. Thoroughly exercise local transactions, corrections, app lock, undo/redo, price source fallbacks, missing quotations, app restart and crash recovery. The Windows v0.13 parity and synchronized financial writes remain excluded.
5. For a *production* Android app rather than a disposable side-by-side preview: stable release application ID, persistent secure signing key/keystore, update continuity and rollback protection, permission/security review, consent for replacing any earlier application, and explicit user release approval. None is implemented or asserted here.

## Safety
No production merge, release, user Google Drive upload, Windows repository changes, OAuth credentials, or real portfolio mutation.
