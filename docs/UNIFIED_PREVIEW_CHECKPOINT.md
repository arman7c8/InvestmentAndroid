# InvestmentAndroid v0.32 — unified developer preview (Draft only)

**Source branch:** `feature/v032-unified-preview`, based on `feature/v032-windows-core-home-readonly` (Draft PR #7, stacked on Draft PR #5).

**NOT production ready.** No merge, release, financial event apply, automatic financial migration, or Windows↔Android bidirectional sync.

## Integrated code

- Existing Android-local portfolio, transaction/quantity/history, backup/restore and cloud conflict guards from PR #7's ancestry are preserved.
- Windows Core v11 read-only home and offline verified snapshot under `noBackupFilesDir` remain physically separate from Android editable assets, activity and cloud JSON.
- Windows Core `portfolio_settings.other_assets_toman` is independently read from the verified raw-table payload as *non-target fixed assets*, included in the displayed offline Net Worth but never in investment allocation/holdings or Android financial events. Reject invalid or negative amount. No invented per-item fixed asset labels.
- PR #6 AI Advisor privacy contract, schema-validated recommendation, AI journal, Atlas context library and **dormant** ChatGPT plan transport code were staged.
- Integrated **offline/manual** AI Advisor: share only percentages with a user-chosen app; paste/review structured suggestions; strict asset-key/current-% parity before saving; manually accept/reject for tracking; no financial mutation. The Android-local vs Windows-read-only mode chooses its *own* asset-percentage source, and the Windows fixed-asset total is intentionally excluded from target allocation.
- AI journal remains in a separate Android no-backup storage file, now atomically replaced. Terminal decisions are idempotent and cannot flip; remote conflicts fail closed. An explicit manual export/merge UI permits backup/recovery. **Cloud AI-journal auto-merge is not enabled**, because its shared-backup/conflict semantics need separate validation.
- Live SIWC/ChatGPT OAuth and API requests are **not connected to any active UI** until authorized app eligibility and end-to-end device sign-in are established. No paid API fallback or token requirement.

## Windows repo relationship (no Windows change here)

- Windows Draft PR #163: verified Core export for Android.
- Windows Draft PR #165: UI/worker SQLite-thread correction for Android Sync, not merged/released and **not** a guarantee of financial two-way sync.
- Windows Draft PR #167: aggregate non-target fixed asset amount edit; schema v11 still stores only one fixed-asset scalar (not individual items). Android reads this scalar only when included in a newly exported Core snapshot.
- No Windows repo merges/releases performed from this Android branch.

## Synthetic verification matrix

After checking out this branch in a disposable Codespace, **run**:

```bash
bash scripts/build_unified_preview.sh
```

The script requires Java 17, Gradle 8.7, SDK 35 and checks:
1. All Gradle/JVM unit tests, including original ledger parity, no double-apply, policy request, offline snapshot, shared safety/restore and AI contract/journal tests;
2. Lint;
3. Debug APK assembly with a commit-specific `applicationIdSuffix`;
4. APK package label/identity and signature;
5. File name and SHA256 evidence in a new `dist/unified-preview-<12sha>/` directory.

**Validation checkpoint (2026-10-10):** The initial unified preview at commit `ed295aca764f` passed full Gradle unit tests, lint, APK assembly, debug signing and package verification in Codespaces. Its artifact SHA256 was `260c48268f4ad28b83527ab3b87019c8e72f5779593a38f72b801004b34d50c9`. User installed the isolated APK: Android-local home started empty (0 Toman, expected without imported data); AI Advisor entry was present. **Device screenshot exposed a real UI bug:** the AI Advisor dialog showed its privacy message and Close, but hid all five actions because Android AlertDialog.setMessage and setItems compete. The action menu was repaired in a later commit and a source invariant was added to the build script. **The repaired commit is NOT YET built or device verified**; do not reuse the old APK checksum as validation for the new source.

Required device acceptance (fake accounts/assets, never real write):
- Android local portfolio unchanged after switching Windows read-only home and back, offline restart, invalid/old source, conflict while in-flight and recoveries.
- Windows Core values match independently replayed source including `other_assets_toman` and missing quote behavior; no duplicate cash or imported Android events.
- AI Advisor entry must visibly show **Share privacy-safe percentages**, **Paste recommendation JSON**, **Recommendation history**, **Export AI journal**, **Restore AI journal**, **Privacy and safety information** (not just a message + Close). Check on Android 16 as well as synthetic JVM checks.
- AI snapshot has no money, prices, quantities, bank/account IDs or raw transactions; reject stale/unknown/duplicated recommendation targets; journal backup/restore conflict leaves previous data intact.
- Trial Windows sync with disposable SQLite only; PR #165 does not activate financial event apply.

## APK handling

Produce exactly **one** unified APK from the final verified commit. Do not install over the existing Android app: unique application ID `com.arman.investmentandroid.preview<sha12>`. Never generate a final release or merge before the user's explicit approval.
