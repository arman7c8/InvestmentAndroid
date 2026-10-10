# Android ↔ Windows v0.12 / v0.13 compatibility checkpoint (2026-10-10)

## Exact inspected sources

- Android baseline: `feature/v032-unified-preview` @ `5d1741f34dbca4dad1ba478c8e5cbd10b77474e0`, Draft PR #8, isolated and unmerged.
- Windows staging: `feature/v013-safe-ui-increments-20261010` @ `0ba1171a3d92ba695a9a4b31059e3bc9b26b92f6`; Windows master issue #170.
- Published Windows v0.12.0 is the preserved user baseline. This Android change does **not** modify Windows.
- Windows v0.13 staging checkpoint reports UI-only work (per-portfolio chart preferences, manual quote action, compact bank table and relocated transaction-history entry); it explicitly reports no changes to Core accounting, schema, Drive, backup or OAuth in that UI increment. Its tests have **not** all been executed.

## Compatibility matrix — actual guarantees, not aspirations

| Direction | Current status | Safe behavior / blocking issue |
|---|---|---|
| Windows v0.12 Core v11 → Android | Read-only verifier exists | `investment.core.readonly` contract v1/v2, SHA256, raw-ledger and policy checks; no Android financial mutation. Windows export availability depends on the separate Windows Draft PR #163, not on this Android branch. |
| Windows v0.13 staging → Android | Same currently supported Core v11 only | Staging UI changes do not establish new financial schema/contract. Reject incompatible future schema/contract, do not guess a migration. |
| Windows `investment.shared.portfolio` schema 1 → Android editable | **Blocked by design** | Projection is not a financial event ledger. Android detects explicit and nested Core provenance and refuses editing/restoring as ordinary Android assets. |
| Android JSON → Windows Core | **Not supported** | Windows `core_mobile_sync.py` is export-only. Windows must apply separately validated immutable financial events, not overwrite Core tables from holdings. |
| Android → Windows → Android financial round-trip | **Not supported** | No common write ledger/transaction receipts or version-safe CAS. A read-only projection is not a round trip. |
| Windows → Android → Windows financial round-trip | **Not supported** | No verified bidirectional financial apply path. Never upload Android-local synthetic data into the real Windows Drive file. |
| Android-local ↔ Android-local shared JSON | Existing code with safety limits | Shared schema 1, history identity conflict checks, pre-write copy and SAF readback. SAF does **not** offer an atomic remote revision precondition or prove Google server replication. |

## Data/accounting differences requiring a common contract

1. Windows Core SQLite schema 11 is the canonical financial source; Android local state is JSON/SharedPreferences, not an interchangeable SQLite ledger.
2. Windows Core records immutable transactions, quantity corrections, revisions, voids, opening balances, prices, source/native currency and FX references. Android local buy/sell/balance history is not guaranteed to reproduce those accounting semantics. **No phone-side Core settlement invented.**
3. Windows group targets and within-group asset targets are separate dimensions; the older Android shared projection flattens these to per-asset effective percentages. Reconstructing groups from those flattened percentages is lossy.
4. Windows Core can store policy tolerances and a cash reserve, and has one fixed/non-target `other_assets_toman` scalar in supported exports. Android v0.32 can preview this read-only; per-item fixed asset migration has no approved v0.13 schema yet.
5. Missing quotes, native USDT/FX references, historical cost basis, Tehran-time transaction ordering and crypto swaps (NEAR → USDT) must be verified with Core golden fixtures before mobile financial writes. Windows v0.13 FIN-01/02/03 are design/staging dependencies, not implemented parity.
6. Windows Core Drive backup (`Investment-core.sqlite3`, appDataFolder) and Android's user-selected `investment.shared.portfolio` document are **not** the same Drive object. Google sign-in alone does not create cross-device sync.

## This increment: provenance boundary

- Added `CoreProjectionBoundary.isWindowsCoreAsset` and wired into `PortfolioSafety.requireEditableAndroidPortfolio`.
- Detects `source_platform`/`sourcePlatform`, `id`/`sharedId` and nested source platform aliases; additionally recognizes the Windows v0.12 exporter signature `source.kind=asset` with nonblank `source.group_id` and `source.asset_id`.
- This is a **fail-closed guard**. A future Android-native asset using the same composite Core-like metadata would require explicit provenance clarification instead of being silently imported. Ordinary native Android assets remain editable.
- Added synthetic regression tests and taught the isolated preview build script to accept this branch while checking the guard's integration.
- No changes to portfolio accounting, asset values, SQLite, transaction semantics, Google credentials, Drive contents, UI layouts, Google account authentication or released artifacts.

## Verification gate

- Standalone Kotlin compiler/JVM checks for the pure origin detector: 7/7 PASS (without Android SDK).
- Android Gradle tests, lint, full APK assembly and handset verification on **this new branch**: **NOT YET RUN**. Run in a disposable Codespace using Java 17, Gradle 8.7 and Android SDK 35:
  `bash scripts/build_unified_preview.sh`
- This script creates a fresh side-by-side APK under `dist/unified-preview-<commit12>/` only after checks; it refuses an existing output directory. Do not treat the previous `5d1741f34dbc` APK as validating this branch.
- Windows Python tests/UI rendering cannot be executed here because only GitHub file access is available, not an authenticated local working tree. The Windows staging checkpoint also identifies its own outstanding test gate.

## Cross-repository dependencies (Windows conversation owns these)

1. Stabilize a versioned, lossless Core event interchange with append-only IDs and receipts, explicit source portfolio identity and schema compatibility; apply Core events idempotently on Windows only after ledger parity tests.
2. Validate atomic crypto↔crypto settlement and financial corrections in Windows v0.13 before exposing corresponding Android write controls.
3. Provide a shared Drive object/revision model with server-side conditional writes, conflict resolution and recoverable backups; Android SAF byte rereads are not remote CAS.
4. Resolve Windows Draft PR #163 (export) and #165 (worker-thread/SQLite) against current staging and release histories before using their code.
5. Add actual cross-platform golden fixtures (10+ bank accounts, missing quotes, nested targets, swaps, voids, FX, Tehran boundary times, fixed assets, repeated import and interrupted sync) **before** bidirectional writes.

**Safety gate:** This is staged Android development only. No production merge, release, real portfolio restore, credentials, OAuth, user Drive writes or automatic financial sync.
