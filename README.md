# Smart Garage for Android

Native companion app for a self-hosted [Smart Garage](https://github.com/flizzy27/smart-garage)
server running the [Con-Chad fork](https://github.com/Con-Chad/smart-garage) with the
`/api/v1` mobile API. It works without Google Play Services and is distributed as an
APK through GitHub Releases and Obtainium.

Stack: Kotlin, Jetpack Compose with Material 3, Hilt, Ktor, kotlinx.serialization,
DataStore, and Android Keystore. Design decisions are in [DECISIONS.md](DECISIONS.md).

## Build

You need JDK 17 or newer (Android Studio's bundled JBR works) and the Android SDK
with platform 37.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JVM unit tests
./gradlew :app:lintDebug
./scripts/check-no-gms.sh           # run before every release
```

Debug builds install as **Garage debug** (`io.github.conchad.smartgarage.debug`)
next to the release app.

## Trying it against a local server

1. Start the fork on a scratch database. Follow the live-check recipe in the
   project handoff, which serves it on port 3111.
2. Connect a phone over adb and run `adb reverse tcp:3111 tcp:3111`.
3. In the app, use `http://localhost:3111` as the server address. Plain http is
   allowed for loopback and LAN hosts only.
