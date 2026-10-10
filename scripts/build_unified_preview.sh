#!/usr/bin/env bash
# Unified developer-only Android preview: Windows read-only plus offline AI Advisor.
# Side-by-side install; never merges, applies transactions, or releases.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
EXPECTED_BRANCH="feature/v032-unified-preview"
BRANCH="$(git branch --show-current)"
[[ "$BRANCH" == "$EXPECTED_BRANCH" ]] || {
  echo "Stop: checkout $EXPECTED_BRANCH in a separate development workspace." >&2; exit 1;
}
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || {
  echo "Stop: tracked files have uncommitted changes." >&2; exit 1;
}
SHA="$(git rev-parse --verify HEAD)"
[[ "$SHA" =~ ^[a-f0-9]{40}$ ]] || { echo "Cannot verify commit." >&2; exit 1; }
SHORT="${SHA:0:12}"
SUFFIX="preview$SHORT"
PACKAGE="com.arman.investmentandroid.$SUFFIX"
FILE="InvestmentAndroid-v0.32.0-unified-$SUFFIX.apk"

command -v java >/dev/null || { echo "Java 17 required." >&2; exit 1; }
[[ "$(java -version 2>&1 | head -1)" == *'"17.'* ]] || {
  echo "Stop: use Java 17 (matches Android CI)." >&2; exit 1;
}
command -v gradle >/dev/null || { echo "Gradle 8.7 required." >&2; exit 1; }
GRADLE_VERSION="$(gradle --version | sed -n 's/^Gradle //p' | head -1)"
[[ "$GRADLE_VERSION" == "8.7" ]] || {
  echo "Stop: expected Gradle 8.7; found $GRADLE_VERSION." >&2; exit 1;
}
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$SDK" && -d "$SDK" ]] || {
  echo "Stop: ANDROID_HOME / ANDROID_SDK_ROOT must point to an installed SDK." >&2; exit 1;
}
AAPT="$SDK/build-tools/35.0.0/aapt"
APKSIGNER="$SDK/build-tools/35.0.0/apksigner"
for need in "$AAPT" "$APKSIGNER" "$SDK/platforms/android-35/android.jar"; do
  [[ -f "$need" ]] || { echo "Stop: Android SDK prerequisite missing: $need" >&2; exit 1; }
done

echo "Testing $SHA with isolated package: $PACKAGE"
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
    "-PpreviewApplicationSuffix=$SUFFIX" --stacktrace

APK="app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || { echo "Missing APK; no package created." >&2; exit 1; }
BADGING="$("$AAPT" dump badging "$APK")"
grep -Fq "name='$PACKAGE'" <<< "$BADGING"
grep -Fq "application-label:'Investment Preview'" <<< "$BADGING"
grep -Fq "versionCode='32' versionName='0.32.0'" <<< "$BADGING"
"$APKSIGNER" verify "$APK"

OUT="$ROOT/dist/unified-preview-$SHORT"
[[ ! -e "$OUT" ]] || { echo "Stop: output already exists; do not overwrite." >&2; exit 1; }
mkdir -p "$OUT"
cp "$APK" "$OUT/$FILE"
(cd "$OUT" && sha256sum "$FILE" > SHA256SUMS.txt)
printf 'Source commit: %s\nBuild branch: %s\nIsolated application id: %s\nNo production install or release.\n' \
  "$SHA" "$BRANCH" "$PACKAGE" > "$OUT/SOURCE-COMMIT.txt"
echo "PASS: unit tests, lint, APK, identity and signing verification"
echo "Developer preview ready in: $OUT"
