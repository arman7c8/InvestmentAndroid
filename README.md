# InvestmentAndroid

Android portfolio app (`com.arman.investmentandroid`). The shared backup format is
`investment.shared.portfolio`, `schemaVersion: 1`, with amounts in Toman. Keep this
format for Android-managed portfolio JSON. Windows v0.11.0 Core uses a separate
SQLite schema (11) and Drive app-data backup, NOT this JSON format. There is
currently no production-safe full Android/Windows two-way synchronization.
See [Interoperability and safety](docs/INTEROPERABILITY.md) before attempting any
cross-device restore. Do not select the Windows `Investment-core.sqlite3` as an
Android cloud document.

## Install a test build (safe alongside the existing app)

The [Build Android APK workflow](../../actions/workflows/build-apk.yml) runs
unit tests, lint, builds and verifies a **side-by-side debug preview** from each
`codex/**` branch commit. Download the run's
`InvestmentAndroid-0.32.0-isolated-preview-<commit>` artifact ZIP, unzip it,
and install the APK with the name `InvestmentAndroid-0.32.0-preview<sha12>.apk`.

**Do not uninstall your existing `Investment Android` app.** The preview is
installed as a **separate app** labeled `Investment Preview` with application ID
`com.arman.investmentandroid.preview<sha12>`. It has independent blank local
data and preferences. It cannot access the original installation's internal
portfolio. Use it only for UI/features evaluation; do not point its Cloud Sync
at a live shared portfolio. The SHA suffix changes on each new commit, so
different preview versions are *separate app installations*. You may remove
obsolete previews without removing the original app, but preview-local data
will then be lost.

The normal application ID `com.arman.investmentandroid` is **unchanged** for
non-preview builds. Signing a truly updatable regular release still requires
a stable private signing key managed in a secure CI secret (never committed).
A one-time release signing setup and explicitly planned migration are required
before normal in-place updates can work; no generated debug key can upgrade an
APK signed by an unrelated debug key.

The artifact includes `SOURCE-COMMIT.txt` (with the exact preview package)
and `SHA256SUMS.txt` for verifying the generated APK.

## Backup and cloud behavior

The app uses Android's document picker to select a shared portfolio file, including
one exposed by the Google Drive Android app. Drive account authentication and
offline availability depend on that document provider. The app continues to use
its local portfolio if cloud access fails. An invalid, empty, unsupported, or
malformed connected file is rejected before sync changes local data or uploads.
Before importing or loading a valid backup, the app retains a local recovery copy
under **Backup / Restore → Restore Previous Local Data**.
Before updating an existing connected cloud file, it also saves the previous
valid cloud document privately on the phone. **Recover Previous Cloud File**
can restore it when the connected file is invalid. Document-provider writes
are not atomic, so an interrupted write may require this explicit recovery.

The cloud flow compares the shared portfolio against the last successful baseline.
It asks the user to choose when both copies changed, and Smart Sync respects its
configured interval. The shared schema remains version 1; unknown shared fields
on matching assets are retained on upload. Cloud restore merges Android transaction
and snapshot history with the phone's history.

## Validation and remaining device work

CI runs `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and checks the
APK package ID and version. Phone testing is still needed for the document
provider account flow, install/update signature behavior, keyboard and scrolling,
and Persian/English display. Settings has an English/Persian switch and RTL
support. Translation is still incomplete in detailed status, transaction,
and error messages.
