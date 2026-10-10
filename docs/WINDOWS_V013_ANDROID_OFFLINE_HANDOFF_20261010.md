# InvestmentAndroid ↔ Windows v0.13 — offline work checkpoint

Date: 2026-10-10. User has no laptop and requested autonomous Android-only implementation until returning. This is an **unmerged, developer-only Android checkpoint**, not a v0.13 Windows release or a production mobile financial sync.

## Source-of-truth verification

**Windows:** `arman7c8/Investment`, `feature/v013-safe-ui-increments-20261010`, inspected branch SHA `4113e2cd9534a6b1cd5eb9ad2ab2ae4cee92546d`. The Windows checkpoint confirms user-reported *776/776 passing tests* at `b5829e9ab5e84731f8d4def52d31a7d230e7861f`; 16 later commits involved version/build/preview workflow. This does not establish an Android APK, installed Windows EXE, full live Drive test or source-level full regression after those 16 commits.

**Android:** `arman7c8/InvestmentAndroid`, `feature/android-windows-v013-compat-guards-20261010`, Draft PR #9 stacked on Draft PR #8. Previous user-tested APK comes from `e91fa56e585fc9d0f8d69eb6edc0aff3cdd4366f`. Later commits require their **own** full Gradle build and device test. Do not reuse older APK SHA as new-build evidence.

**Windows contracts:**
- `core_mobile_sync.py`: `investment.shared.portfolio` schemaVersion 1, explicitly **export-only** from Windows SQLite Core. Its holdings are derived `windows:` identities with `source_platform=windows-core`, not an editable Android financial ledger. `average_cost_toman` uses current quote as a non-accounting placeholder. Do NOT use it to reconstruct BUY/SELL P/L or overwrite cost basis.
- Checksum-verified Windows Core read-only interchange contract versions 1/2 remain supported for Core schema 11 on Android. Do not coerce future unknown schema into local editable holdings.
- Windows v0.13 policy covers category and in-category asset targets, global/group/asset tolerance overrides and an absolute `reserve_target_toman` goal. Reserve and fixed non-target assets are **not** Android buying-power cash or ordinary target allocation percentages. Do not include a reserve goal as a holding.
- Windows v0.13 GUI includes optional Jalali **display** (not mutation of canonical event timestamps) and Tehran-local semantic Today/7-day/Month/Year windows. Android aligns summary display boundaries while keeping stored epoch milliseconds unchanged.
- Core crypto swaps (e.g., NEAR→USDT) and fixed-asset event-date migration are not yet represented as fully interoperable Android transaction types. Do NOT enable two-way financial syncing or fake swaps as unrelated buys.

## Implemented in Android during this offline coding session

1. `WindowsReserveStatus.kt`: pure bounded, BigDecimal-based cash reserve goal gap (surplus/shortfall/exact), no trade action. Core read-only `WindowsCoreHome.inspect` now derives the reserve and global tolerance **only from already-verified policy**. Windows read-only home displays reserve target, cash available and difference; existing fixed assets remain solely in net-worth display. V1 Core exports have no invented reserve policy. FA translations and JVM regression tests added.
2. `LocalHistorySafety.kt`: fail-closed transaction ID/row/JSON checks before Android-local history overwrite and verified manual backup payload creation. Existing malformed data bytes are left intact instead of being overwritten by an empty fallback. Legacy transactions without IDs remain accepted on read; duplicate declared IDs are rejected on write.
3. `SnapshotHistorySafety.kt`: fail-closed validation before overwriting local value-history snapshots, including trades and price updates, to avoid turning a corrupted saved history into `[]`. No attempt at silent reconstruction.
4. The same feature branch has an incremental `commitManagedPortfolioChange` integration that commits Android-local assets, transaction history and valuation snapshots together on BUY/SELL/guarded correction/Revert. **Undo checkpoint remains a separate stored write and is NOT proven atomic with that commit.** No Windows or Google Drive writes occur.
5. `TehranPeriodWindow.kt`: pure timezone-aware local-calendar Day (today), Week (current plus previous 6 Tehran days), Gregorian Month and Gregorian Year boundaries, plus tests. The mobile month summary was previously rolling ~30d; changing to calendar-month is an intentional visible semantic change. Ledger timestamps remain epoch milliseconds; `formatDate` now displays Tehran clock in both UI languages.
6. `scripts/build_unified_preview.sh` has source-level guards for preserved v0.13 read-only and local-safety wiring before Gradle, lint, APK and signing verification.

## Independent validation and blockers

- Pure Kotlin `WindowsReserveStatus` and `TehranPeriodWindow` logic were run in an isolated local Kotlin/JVM harness: **5/5 reserve** and **5/5 Tehran** behavioral checks passed. These were independent reproductions of the pure logic, NOT Android Gradle/JUnit or UI tests.
- GitHub contents/readback inspections verified source presence and intended guards; see Draft PR #9 for specific head source and changes.
- New committed JUnit modules `WindowsReserveStatusTest`, `LocalHistorySafetyTest`, `SnapshotHistorySafetyTest`, `TehranPeriodWindowTest` and updated `WindowsCoreHomeTest` are **NOT executed** in Android SDK on this commit.
- **Important remaining gates:** Run `bash scripts/build_unified_preview.sh` on the exact then-current branch HEAD in Codespaces (Java 17, Gradle 8.7, Android 35 installed); fix compile/test/lint failures before any APK. Separately test only synthetic data on phone: full backup export-readback, repeat import, BUY edit, Undo/Redo, guarded Revert, corrupt backup rejection, Windows Core v1/v2 read-only reserve and Tehran day boundary.
- Keep draft PR unmerged; do not publish or install on real portfolio. Do not change Windows repo, release/windows, signed installer, user Drive, SQLite data or mobile cloud synchronization.

## Proposed next development steps after the verified build

Prioritize an explicit read-only safe recovery screen for damaged Android-local JSON; rejection currently prevents writes but certain UI flows may show errors/crash rather than guide restore. Test crash-interruption persistence and Undo/Redo stack atomics separately. Do not advertise data-loss-proof storage before those gates pass.

## Additional verified static-source checks

- GitHub blob-level parity against Windows `release/windows` on the same date: `core_mobile_sync.py` `f9eb0731130a281ef0424d0ed4adbfba2980ea4a`, `core/repository.py` `7eb4c5c529f30efa09b7d9048f5ca905236f33b8`, `core_sync.py` `3e569ac5c0a3ec43362690a6e264d8e1cc8cec96`, and `core_drive_backup.py` `237895ce1bc538c62b591b28a3ac2509adc4fdcf` are **identical** to the active v0.13 UI-staging branch. Hence the current Windows v0.13 increment has **not changed those four financial/export/Drive modules**. This does not prove every GUI behavior unchanged.
- Added a read-only **Local data protection** screen before displaying an editable Android portfolio if stored local assets, transaction history or valuation snapshots cannot be verified. Background price refresh and smart cloud sync are disabled in that state. The original bytes are left intact and there is no automatic reset, overwrite or inferred recovery.
- The recovery screen is deliberately *not yet* a full guided import or repair flow. The correct recovery procedure for damaged preferences requires a separately validated, recoverable pre-restore backup; do not misrepresent a static warning as tested disaster recovery.
- Current commit after this addendum requires new full Gradle/JUnit, lint, assembly and isolated Android on-device acceptance. Local readback/source checks are not an APK acceptance test.
