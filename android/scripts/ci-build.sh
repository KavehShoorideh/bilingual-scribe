#!/usr/bin/env bash
# Full verification, same steps as .github/workflows/android.yml.
# Works on any machine with JDK 17+ and the Android SDK (ANDROID_HOME set).
#
# On machines WITHOUT the Android SDK (e.g. restricted sandboxes), the
# pure-JVM slice still runs:   ./gradlew :core:test
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE=./gradlew
[ -f gradle/wrapper/gradle-wrapper.jar ] || GRADLE=gradle

"$GRADLE" --no-daemon :core:test testDebugUnitTest :app:assembleDebug
bash scripts/check-gms-free.sh

echo
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
