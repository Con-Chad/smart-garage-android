#!/usr/bin/env bash
# Fails if any Google Play Services or Firebase artifact reaches the release APK.
# Run before every release (and in CI from CP13): the app must work on GrapheneOS without GMS.
set -euo pipefail
cd "$(dirname "$0")/.."

# Resolved release runtime classpath (what ships), not just the build files. Hilt and Truth are Google but GMS-free.
deps="$(./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath)"
pattern='com\.google\.android\.(gms|play|libraries)|com\.google\.(firebase|mlkit|ads)|play-services|firebase-|embedded_fcm_distributor'
if grep -nE "$pattern" <<<"$deps"; then
  echo "check-no-gms: found Google Play Services / Firebase dependencies (above)" >&2
  exit 1
fi
echo "check-no-gms: release runtime classpath is free of GMS and Firebase"
