# Android manual Backup / Restore hardening — 2026-10-10

## Source investigation

The installed APK at commit `782e5e9390d8872a72f0083bd63c4133087e2e89` was tested on device for local holdings, read-only Windows isolation, persistence and invalid Core projection rejection. **A valid manual JSON export/restore round-trip was NOT previously proven.**

A screenshot showed a ChatGPT attachment error, "This file is empty", and a separately uploaded APK rather than the requested JSON. Those observations alone do **not** prove whether an Android-created JSON had zero bytes; do not assert an observed provider failure without inspecting actual output bytes.

Actual code audit identified two reproducible behavior gaps:

1. `MainActivity.onActivityResult(exportBackupRequestCode)` used `writeUriText(uri, createBackupJson())` and displayed a success message without validating the generated document or verifying bytes read back from the file provider. The cloud write path already used `PortfolioSafety.writeAndVerifyBackup`; now manual export uses the same readback policy.
2. `applySharedBackup` appended `snapshotsWithCurrentTotal(...)` to every restored history. Repeatedly importing an identical backup could invent multiple history points with new timestamps. Now restores store the supplied snapshot history unchanged; a file import is not a market-price observation.

## Code changes

- Validate a freshly generated manual export with `PortfolioSafety.validateBackup` before writing.
- Use the existing provider write/readback equality checker before showing export success. Empty, shortened, stale and failed provider writes must not be reported as verified.
- Preserve supplied backup snapshot history exactly during shared import instead of appending a fabricated timestamped snapshot.
- Add `ManualBackupRoundTripTest` with synthetic shared/schema v1 fixture, readback, failed short/empty writes, future-schema rejection and stable historical serialization.
- Add build-time source guards for verified manual backup export and non-mutating restore history.

## Remaining limitations and acceptance gate

- Android's Storage Access Framework does not guarantee server-side atomicity, Drive CAS or fsync on all document providers; a passed immediate readback confirms local/provider-accessible bytes only.
- Existing full-device restoration still depends on successful SharedPreferences commit and pre-restore recovery; additional crash-interruption and conflict-case device tests are required.
- Repeat import idempotence of **holdings and transactions** requires full on-device checking, not only these tests.
- A successful backup copy may contain financial information. Only use disposable synthetic assets in screenshots, uploads and smoke tests.
- **Run on the exact new SHA**: `bash scripts/build_unified_preview.sh` in Codespaces, with Java 17/Gradle 8.7/SDK 35. Full Gradle, lint, APK build, and install have **NOT YET** been executed for this patch in the current tool environment.
- No release, merge, overwrite of real Windows/Android financial data or real Drive upload.
