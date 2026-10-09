# Android v0.32 — Windows Core v0.12 read-only home (development)

## Purpose

Display a verified Windows v0.12 Core v11 portfolio directly on the Android
home screen without turning it into Android's editable holdings or modifying
the original Core event history.

This is **not live sync** or a Windows/Core financial-command importer.

## User flow

1. Windows: create a backup from the current Windows v0.12 Core app.
2. Export a compatible versioned Windows Core JSON using the offline Core
   exporter (Windows Draft PR #163); the Android device has already validated
   the same interchange contract with real backup data.
3. Android: Backup / Restore → View Windows Core Snapshot (Read-only).
4. After validation, choose **Show on home (Windows read-only)**.
5. The home shows estimated value, holdings (with quantities and available
   prices), Core group labels / effective target percentages, cash accounts,
   event count and abbreviated SHA-256.
6. **Load newer Windows snapshot** manually when the Windows file changes.
   **Switch to Android local portfolio** returns to the original editable
   Android assets. The two stores are never merged by this feature.

No automatic Drive or SAF update takes place. Windows snapshot is stored using
Android AtomicFile under noBackupFilesDir, private to the app and excluded from
Android Auto Backup. It is read back and revalidated against the complete Core
ledger and SHA-256 before the home can display it.

The cached Windows snapshot may include **full private financial history**.
Do not upload or publish it, or include it as a sample test asset.

## Safeguards

- Malformed checksum, unsupported contract, missing tables, unexpected
  financial event, invalid ledger/quantities/accounts/policy: fail closed
  before saving the home snapshot; original Android assets and transactions
  are untouched.
- Cache is optional and remains separate from Android SharedPreferences asset
  rows, transactions, undo stack and Android cloud JSON.
- Automatic Android-local price update and Smart Cloud Sync are suspended
  while the read-only Windows home is selected, and resume upon switching
  back to Android-local mode.
- If a saved Windows cache becomes inaccessible or invalid, revert to the
  separate Android-local screen and warn the user. Never auto-import the
  broken Windows contents as a portfolio.
- If a **nonzero holding** has no quote, show a *partial* subtotal with an
  explicit missing-price warning. No invented cost basis, realized P/L or
  automatic transaction operation is displayed.
- Offline Android Core policy and financial request exporters remain
  unapplied drafts; no buy/sell/cash command is accepted on Windows yet.

## Market-price and USDT valuation parity

An additional integrity gate independently reconstructs the **latest quote**
for each Core asset by (observed_at, SQLite rowid), then recursively values
native-currency quotes against the latest reference price, exactly like
Windows Core latest_price. It compares each displayed holding value against
(quantity × current quote), including zero-quantity and missing-price cases.
A forged amount is rejected even if the JSON SHA-256 is recomputed to match
it. Circular/unknown FX references and non-finite valuations are rejected.

Windows exporter Draft PR #163 has been updated to match this quote policy.
The previously delivered October 8 and October 9 JSON previews were checked
independently against this rule and both matched. Newly exported snapshots
need another on-device acceptance after the updated APK is built.

## Validation and next steps

Real Android v0.32 installed preview of the October 9 Core v11 snapshot
successfully displayed: 15 tables, 28 events, 9 assets, 4 cash accounts,
1 correction / 1 revision / 0 voids. The device has **not yet tested**
this new home screen or its installation/restart/corruption flows.

Added WindowsCoreHomeTest JVM fixtures (synthetic only) for sums, null quotes,
zero quantities, SHA tampering and ledger forgery. These tests and the Android
Gradle lint / APK assembly have **not** run in this session; do not claim
production readiness. Use a side-by-side isolated preview package to protect
the existing installed app while testing.

Before financial two-way synchronization: authoritative Windows Core apply API,
same-operation ID deduplication, compare-and-swap revision enforcement,
reconciliation screen, pre-apply backups, accounting invariant tests,
interrupted-write recovery and dual-device conflict acceptance.

## Reproducible local/Codespaces build (no GitHub Actions)

A guarded developer script lives at
`scripts/build_isolated_windows_core_preview.sh`. It **never** merges,
releases, installs, or touches the production app data.

Prerequisites: Java 17, Gradle 8.7, an installed Android SDK with
platforms/android-35 and build-tools/35.0.0, and a clean checkout of
`feature/v032-windows-core-home-readonly`.

Run:

```bash
bash scripts/build_isolated_windows_core_preview.sh
```

It runs **testDebugUnitTest, lintDebug, assembleDebug** with a preview
applicationId suffix derived from the exact 12-char Git commit ID, then checks
the APK label/identity/version/debug signature, and writes a package with
`SHA256SUMS.txt` and `SOURCE-COMMIT.txt` into an isolated `dist/` directory.
This preview must be installed *side by side* with the normal app and must
never prompt a user to uninstall their existing installation.

If prerequisites are unavailable, the script fails before any APK is built.
A successful script result is **not** yet a physical-device acceptance pass:
validate the read-only Windows home, restart, mode-switch, quote mismatch
failure and absence of unwanted changes in the user's separate installed app.

