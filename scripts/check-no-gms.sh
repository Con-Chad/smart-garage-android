#!/usr/bin/env bash
# Fails if any Google Play Services or Firebase artifact reaches the release APK.
# Run before every release (and in CI from CP13): the app must work on GrapheneOS without GMS.
set -euo pipefail
cd "$(dirname "$0")/.."

deps="$(./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath)"
if grep -nE 'com\.google\.android\.gms|com\.google\.firebase|play-services|embedded_fcm_distributor' <<<"$deps"; then
  echo "check-no-gms: found Google Play Services / Firebase dependencies (above)" >&2
  exit 1
fi
echo "check-no-gms: release runtime classpath is free of GMS and Firebase"
