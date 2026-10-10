# InvestmentAndroid: Windows v0.13-aware standalone safety checkpoint — 2026-10-10

> Scope: isolated Android-only development; **no production merge/release, user data manipulation, Google Drive finance sync, or Windows repository edits**. The user has no laptop access and will request device builds/testing later.

## Source-of-truth check

- Android branch: `feature/android-windows-v013-compat-guards-20261010`, Draft PR #9. Previous independently built preview: `e91fa56e585fc9d0f8d69eb6edc0aff3cdd4366f`.
- Windows branch inspected: `arman7c8/Investment` `feature/v013-safe-ui-increments-20261010`, Windows v0.13 source version `0.13.0`. `core_mobile_sync.py` explicitly remains **export-only**. Windows staged financial UI added target category/asset editors, cash reserve controls, time-period/Jalali *display* selection, quote health and charts, but **does not establish Android financial write interoperability**. Windows Core schema 11 read-only verifier stays authoritative for immutable ledger observations.
- Android app's `investment.shared.portfolio` schema 1 plus `androidBackup` v3 is a **separate Android-local editable format**. A Windows-derived shared projection (or a Core interchange) is never a writable Android transaction ledger. No attempt to mirror Windows UI-only state into accounting records.

## Completed Android-only code increments in this checkpoint

1. **Verified latest managed BUY correction** (since initial PR): `BuyCorrection.calculate`, stable UUID/timestamp and before/after snapshots; edit only if current holdings still match that transaction and no later managed transaction exists. Old transactions and SELL remain deliberately noneditable. Existing guarded Revert remains.
2. **Duplicate transaction IDs rejected at backup validation**; repeated import preserves imported timestamped valuation history rather than fabricating a new point.
3. **Manual JSON export validates input and checks provider readback** before success notification, addressing false-positive empty/truncated SAF documents.
4. **Manual Restore confirmation** for nonempty local state, fully rechecks holdings + transactions + chart snapshots + relevant prefs while a document provider reads and again at confirmation, and refuses to pair incoming holdings with unavailable source history if the phone already has transaction/snapshot data. It keeps the original pre-restore recovery copy.
5. **Managed local BUY, SELL, cash final balance, BUY correction and Revert** now share one `SharedPreferences.commit()` containing assets + history + portfolio snapshot, rather than three independently asynchronous financial writes. The before-change Undo checkpoint remains.
6. **Stale editor and ambiguous identity safeguards**: existing trade/cash/asset edit dialogs cannot quietly operate on replaced or same-named assets. Financial Revert checks provenance as well as quantity, price and cost. Editing already-managed quantities, purchase basis, category or name is disallowed outside managed event corrections rather than silently rewriting historical trades.
7. **Visible Edit Asset control** instead of undocumented tap-on-name-only gesture.
8. **Lossless editable number formatting** via `BigDecimal.valueOf` removes previous `Double.toLong()` and six-fraction-digit conversions from quantity, price, cost and target editor defaults. Small crypto holdings and fractional quotes must survive open/close and no-op saves.
9. **Tehran-local Gregorian historical timestamps**, presentation only, independent of device timezone and compatible with Windows v0.13's canonical Gregorian/UTC records. Android **does not yet** implement Windows v0.13's optional Jalali view.

## Synthetic checkpoint scenarios

- Baseline: TEST-BACKUP-01, quantity 2, current price 10,000 Toman, average cost 8,000 Toman => value 20,000, invested 16,000.
- BUY 2 @ 15,000 => quantity 4, average cost 11,500, current value 60,000 and unrealized P/L 14,000.
- Correct same latest BUY to 1 @ 12,000 => quantity 3, average cost 28,000/3, value 36,000 and unrealized P/L 8,000. The transaction ID must remain unchanged; no second BUY is added.
- Export full shared/Android backup; import twice into disposable preview. Each import must preserve **one** synthetic BUY identity and its exact before/after state; no added valuation events due to import alone; user must explicitly confirm replacement when local data already exists.
- A holdings-only backup must never silently reuse an unrelated local transaction log. A concurrent history-only modification must abort import.
- Undo once restores the pre-correction synthetic state; Redo restores the correction. Revert latest validated BUY should return to quantity 2 at average cost 8,000 only if portfolio matches the post-trade snapshot; otherwise it must fail closed.
- 0.123456789123456 quantity and fractional quote/average-cost fields must not be rounded on opening editors; use only synthetic values.

## Verification classification

- **PASS on prior APK** (`e91fa56e585f`): Gradle tests, lint, APK assemble, SHA256 identity/signature, and user confirmed manual import of disposable JSON and correct BUY calculations. The successful local backup JSON with managed before/after transaction records was reviewed in a separate conversation.
- **PASS standalone Kotlin in the current tool container**: isolated non-Android calculator/precision/trend and timezone checks; these are **not** a full repository build.
- **PASS GitHub readback static checks**: manual import confirmation; unchanged-state check; non-lossy history guard; one-commit financial save; matching Buy correction; precision; provenance; explicit Edit Asset.
- **NOT VERIFIED on current source**: full Android Gradle compilation, unit tests, lint, built APK, device import/repeated-restore/recovery/Undo/Redo, crash/power-loss recovery and Windows Core v0.13 golden fixture comparison. No Android SDK or authenticated Codespace build access in current tool runtime, so DO NOT label a new APK successfully built.

## Known limits / next gates

1. In Codespaces, at user's return: `git pull --ff-only && bash scripts/build_unified_preview.sh` on the current branch; **do not assume any previous APK represents this source**. Address build or lint failures first and run synthetic tests.
2. Device smoke test with a newly identified side-by-side APK, no real Google Drive. Verify restore confirmation, two repeated imports, stable transaction ID/quantity/cost, edit/revert/undo/redo, Persian labels and exact fractional input, Tehran date.
3. Interrupted restoration and Undo/Redo durability require dedicated failure-injection tests. Current local JSON/SharedPreferences model is **not** Windows Core's immutable ACID SQLite ledger; Revert removes a local record instead of recording an immutable void.
4. Windows v0.13 may pass tests on the user's laptop, but this tool session cannot independently certify the full v0.13 release or a cross-platform financial round-trip from GitHub documentation alone.
5. Do not claim Windows↔Android live sync until a versioned event/receipt protocol, server-side CAS, FX, swaps, cash accounts, policy nesting and Core reconciliation are designed/tested. Only the read-only Windows Core bridge is in scope here.
