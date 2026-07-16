#!/usr/bin/env bash
# Fails if any Google Play Services / Firebase artifact sneaks into the app's
# runtime classpath. /e/OS has no GMS; a transitive dependency on it would
# break users silently (and violate the privacy promise).
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE=./gradlew
[ -f gradle/wrapper/gradle-wrapper.jar ] || GRADLE=gradle

deps=$("$GRADLE" -q :app:dependencies --configuration debugRuntimeClasspath)

if grep -E 'com\.google\.(android\.gms|firebase)' <<<"$deps"; then
    echo "ERROR: GMS/Firebase dependency found on the runtime classpath." >&2
    exit 1
fi

echo "OK: runtime classpath is GMS/Firebase-free."
