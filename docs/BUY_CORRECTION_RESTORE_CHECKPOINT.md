# Android standalone ledger audit — BUY correction / Restore (2026-10-10)

## Source checkpoint
Based on Draft PR #9 / `feature/android-windows-v013-compat-guards-20261010`, originally at `e91fa56e585fc9d0f8d69eb6edc0aff3cdd4366f`. Never touch Windows release sources. Windows Core shared export remains read-only in Android; these changes apply **only** to locally editable Android assets/transaction history.

## Actual state reviewed
- BUY and SELL produce managed transaction IDs, `beforeAssetJson` and `afterAssetJson`, and a transaction timestamp.
- Home → Activity opens Activity Manager with `Revert Transaction` (reversal/remove) only if `canSafelyRevertTransaction` accepts the most recent managed transaction and the post-trade asset still matches.
- Home Undo/Redo use portfolio-state snapshots, but they are not a substitute for an immutable accounting audit log.
- Prior to this patch there was **no direct transaction Edit**; tapping an asset name edits holdings, not a historical BUY event.
- Shared backup restore replaces assets and saved Android histories after pre-restore recovery copy and one `SharedPreferences.commit`; manual export readback was fixed in an earlier commit. Repeated restore preserves snapshot list from backup, but can still replace later local changes (user confirmation/preview needed in future).
- A duplicate transaction ID inside an incoming JSON used to be accepted; now rejection happens before restore.

## Limited patch
- Add `BuyCorrection.calculate` (pure validation + weighted-average cost math), four synthetic unit tests.
- Show `Edit BUY transaction` only for the **latest managed BUY** with verified post-trade holdings; reject earlier, legacy, modified or ambiguous assets. Retain stable transaction ID and original timestamp.
- Recompute quantity, current quoted price, average cost and the managed post-transaction asset snapshot from **original pre-trade state**, not by applying the new buy a second time.
- Store both corrected assets and transaction history in a **single SharedPreferences commit**, preserve Undo pre-change state and do not overwrite reserved pre-Restore recovery copy. Do not touch cloud or Windows.
- Validate unique nonblank transaction IDs within imported supplemental backup and add synthetic tests.

## Important limitations — NOT a complete ledger editor
- Only latest managed BUY is editable; SELL edits, old trades, cash changes, split operations and retrospective dependency replay are intentionally unavailable.
- Existing Revert deletes an event from the local activity log rather than creating an immutable reversal entry. Real financial audit-compliant correction and fee/tax handling remain outside this Android v0.32 preview.
- This implementation still relies on the Android local holdings model and is not compatible with bidirectional Windows Core financial writes.
- Full Gradle compile/tests, lint and APK assembly must be run in Codespaces on the **new exact source commit**. No fresh APK was built in this tool session.
- Before any device restore, copy the **synthetic** exported backup to a safe folder, verify nonempty JSON and expected values, and use side-by-side preview app. Test repeated import of the same file (one transaction, expected balances), modify a BUY in Activity (cost basis recalculated), Undo/Redo, crash/restart. No real user money data, production release, merge, or Google Drive sync.
