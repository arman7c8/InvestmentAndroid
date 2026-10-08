# Windows Core ↔ Android synchronization: compatibility and safety checkpoint

Verified against Windows `arman7c8/Investment@release/windows` (`a61885ad40b81c0cc3a10134422376f89ff3276e`, v0.11.0) and Android development v0.32.0.

## Current reality: NOT interoperable for full read/write sync

| | Windows Core | Android v0.32 |
|---|---|---|
| Canonical store | SQLite Core database, `core/repository.py`, schema 11 | SharedPreferences JSON assets, transactions and snapshots |
| Google backup | `Investment-core.sqlite3` under Drive `appDataFolder` | `investment.shared.portfolio` JSON chosen with Android Storage Access Framework |
| Trading | Immutable `transactions` with cash amount, account source/destination, asset id, quantity and occurrence time; quantity corrections, revisions and voids | Mutable asset holdings with phone-local transaction/snapshot history |
| Quotes | Time-stamped quotes, native currency, reference asset, source settings | Toman unit price and a limited market source |
| Policy | Account balances, groups/assets, allocation tolerances and pricing currencies | Per-asset target and global tolerance |

These are not two copies of the same ledger, and signing in to the same Google account does not make them synchronize. The Windows appDataFolder file is not a normal Drive document exposed by the Android picker.

## Safety policy implemented in Android

1. Do not import Windows SQLite bytes as JSON. Do not accept a purported shared JSON with an embedded `coreLedger` or `windowsCore` that Android would drop on restore.
2. For `androidBackup.transactions` and `snapshots`, preserve distinct identities, deduplicate identical records (even when JSON key order differs), and **fail closed** if the same identity has conflicting content. Never arbitrarily prefer the phone copy.
3. Preserve unknown `androidBackup` fields during cloud uploads; unknown root and shared fields are preserved where current code supports them.
4. Reject unsupported cash quantities rather than silently rewriting quantity to one and changing portfolio value.
5. Continue validating before import, keep pre-restore recovery data, and verify readback after SAF writes. SAF readback does not prove eventual Google Drive server replication.

**Do not connect the production Windows Core backup to Android's JSON sync or enable production two-way synchronization yet.** The current Android JSON sync is Android-to-Android only, subject to actual device/provider validation.

## Proposed interoperable design (not yet implemented)

**Phase A: snapshot-only and read-only.** Windows provides a versioned canonical Core export that includes complete ledger and provenance metadata plus a read-only valuation/holdings projection. Android validates schema version, source portfolio ID, ledger revision/hash, currency basis, numeric finiteness, asset/group/account identity and opening baseline, then renders the projection without replacing its local data or uploading anything. Verify totals vs Windows fixture snapshots.

**Phase B: lossless round trips.** Define a canonical interchange with all original event IDs, occurred/created timestamps, cash accounts, trade amounts and quantities, quantity corrections, voids/revisions, opening balances, asset/group definitions, manual/native-currency quote history, FX reference metadata, target allocation and source priorities. Every round trip must preserve unknown required data; unsupported events must cause a clear conflict/rejection rather than reinterpretation. Use fixtures exported by Windows and independent invariant tests on both platforms.

**Phase C: synchronized writes.** Use a single shared transport and stable portfolio ID with explicit origin and revision; implement compare-and-swap / ETag preconditions or provider-native revision enforcement, immutable event-ID deduplication, conflict UI, atomic local backup/restore and remote checksum verification. Never treat a last-read snapshot as atomic remote locking. Only enable write actions after concurrency, offline, cross-device and rollback tests with disposable data pass.

The Windows repository is unchanged by this checkpoint. A Windows Core exporter/reader and/or agreed shared bridge service are necessary for actual dual-platform synchronization.
