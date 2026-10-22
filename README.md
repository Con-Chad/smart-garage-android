# Smart Garage for Android

Native Android companion for a self-hosted [Smart Garage](https://github.com/flizzy27/smart-garage)
server running the [Con-Chad fork](https://github.com/Con-Chad/smart-garage), which adds the
`/api/v1` mobile API. The app keeps a full offline copy of your garage, lets you log fill-ups,
service and expenses from the phone, and syncs back when a connection is there. It is built for
phones without Google services (GrapheneOS and similar) and is distributed as a sideloaded APK,
not through an app store. Design decisions are recorded in [DECISIONS.md](DECISIONS.md).

## Features

- Several server profiles; sign in with a device token issued by the server.
- Offline-first: one Room cache per profile, filled from `/api/v1/sync` (keyset paging), synced
  on foreground, pull-to-refresh and hourly in the background. Edits go into an outbox that is
  replayed before every pull; conflicts resolve last-write-wins.
- Dashboard and vehicles on a glass UI: aurora background tinted by the car's colour, metallic
  text, gyroscope tilt on the vehicle frame, a morphing bottom nav.
- Vehicle page with the photo, owner, the four key figures, quick actions and collapsible groups
  for registration, specs, service and fuel.
- Maintenance records and schedules, inspections, insurance policies, fuel entries, expenses,
  notes, wishlist and documents (images open in-app, PDFs and others via FileProvider).
- Optional fuel-economy tracking (a per-device switch that hides the odometer field and all
  derived consumption figures).
- "Add to calendar" for anything with a due date, handed to your calendar app via
  `ACTION_INSERT`; no calendar permission.
- Deep links into installed insurer apps; device-local vehicle nicknames and odometer reminders.
- English and German; dark theme by default with System and Light available; Material You
  colours optional; every ambient animation respects the system's reduced-motion setting.
- Receipt scanning: the hook is in place and appears only when the server advertises
  `features.receiptScanner`; the client that actually sends the photo is not wired yet.
- Push notifications for maintenance due, odometer reminders and (if enabled on the server)
  logged services and expenses, via UnifiedPush; tapping opens the vehicle or section and syncs.

## Privacy

- No Google Play Services, no Firebase, no analytics. `scripts/check-no-gms.sh` fails the build
  if any such artifact reaches the release classpath.
- Permissions: `INTERNET` and, only if you turn push on, `POST_NOTIFICATIONS`. Camera and file
  access go through the system pickers.
- Push notifications arrive via [UnifiedPush](https://unifiedpush.org) (Web Push, RFC 8291
  encrypted with VAPID). Your server encrypts each message for this phone; the distributor you
  choose (ntfy, for example) only relays ciphertext. Needs a UnifiedPush distributor app installed.
- Remote access is meant to go through a Cloudflare Tunnel with Access service tokens; the app
  sends `CF-Access-Client-Id` / `CF-Access-Client-Secret` headers when a profile has them. Do not
  port-forward your server.
- Plain `http://` is accepted only for hosts that cannot be public (loopback, RFC 1918,
  link-local, Tailscale, `.local`, `.lan`, `.home.arpa`, `.internal`, `.ts.net`).
- Tokens and Access secrets are AES-256-GCM encrypted with a non-exportable Android Keystore key
  and stored in DataStore.

## Requirements

- Android 12 or newer (`minSdk 31`).
- A Smart Garage server running the Con-Chad fork with `/api/v1` enabled, reachable from the
  phone over HTTPS (or plain HTTP on a private network).

## Install

Download the APK from the Releases page and sideload it, or add the repository to
[Obtainium](https://github.com/ImranR98/Obtainium) so updates are picked up automatically:

    <<https://github.com/Con-Chad/smart-garage-android/releases>>

There are no signed release builds yet (see Status). Until then, build a debug APK from source.
Debug builds install as **Garage debug** (`io.github.conchad.smartgarage.debug`) next to the
release app (`io.github.conchad.smartgarage`).

## First run

1. Enter your server address. The field starts blank; any instance of the fork works.
   For a Cloudflare Access protected server, add the service-token id and secret on the same
   screen. The connection test tells you whether the tunnel, the proxy or the origin failed.
2. Sign in with your Smart Garage account. The server issues a device token, which the app
   stores encrypted; your password is never kept.
3. The first sync pulls everything. After that, pull to refresh or let the hourly background
   sync run.

Units, currency and time zone come from the server's preferences. Theme and wallpaper colours
are per device.

## Building from source

There is no system Java required; use Android Studio's bundled JBR and SDK platform 37.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JVM unit tests (128)
./gradlew :app:lintDebug
./scripts/check-no-gms.sh           # run before every release
```

Stack: AGP 9.x, Kotlin 2.4, Hilt, Jetpack Compose (BOM 2026.09) with Material 3, type-safe
Navigation Compose, Ktor 3 on OkHttp, kotlinx.serialization, Room (rows stored as the server's
JSON), WorkManager, DataStore, Coil 3. One Gradle module.

To test against a local server, run the fork on a scratch database, `adb reverse` its port, and
use `http://localhost:<<port>>` as the server address.

## Project layout

`app/src/main/java/io/github/conchad/smartgarage/`

| Package | What lives there |
| --- | --- |
| `core/network` | Ktor `ApiClient`, DTOs, `ServerUrl` rules, Cloudflare Access headers, `ReceiptScanner` interface + stub |
| `core/cache` | Room database per profile; synced rows kept as JSON |
| `core/sync` | `SyncEngine` (replay outbox, then pull `/sync`) and the hourly `SyncWorker` |
| `core/push` | UnifiedPush registration (`PushRegistrar`), the connector service, notifications, tap routing |
| `core/outbox` | Queued writes, temp-id remapping, staged files, write routes |
| `core/data` | Repositories, `ProfileStore`, `AppSettings`, device-local vehicle prefs |
| `core/model` | Domain models decoded from the cache |
| `core/format` | Units, currency and date formatting |
| `core/security` | `SecretCipher` (Android Keystore, AES-256-GCM) |
| `di` | Hilt modules |
| `feature/*` | One package per screen: `setup`, `profiles`, `dashboard`, `vehicles`, `maintenance`, `schedules`, `fuel`, `expenses`, `notes`, `wishlist`, `documents`, `sections`, `settings`, `more` |
| `ui/glass` | Aurora, glass panels, metallic text, tilt frame, morphing nav, drop-downs |
| `ui/components` | Shared widgets, forms and in-house charts |
| `ui/files` | Camera capture, file picker, size/type rules |
| `ui/links` | Calendar intents and insurer app lookup |
| `ui/navigation`, `ui/sync`, `ui/theme` | Routes and shell, sync status UI, Material theme |

## Status

Working today: everything under Features except the two items below.

Push notifications (CP12) are done: Settings → Notifications turns them on per server, picks the
UnifiedPush distributor, asks for the notification permission and offers a test push. A distributor
such as [ntfy](https://ntfy.sh) (F-Droid) or another UnifiedPush app must be installed on the phone;
without one the switch explains what to install. Plain-http servers need "Sync hourly in the
background" on first. Contract: `docs/mobile/PUSH.md` in the fork.

- Real receipt-scanner client (multipart `POST /fuel/receipt-scan`, prefilled fill-up form,
  offline "unscanned" queue). Contract: `docs/mobile/RECEIPT-SCANNER.md` in the fork.
- Signed release builds, a CI workflow and setup docs (planned as CP13). No CI exists yet.

## Licence

This repository has no LICENSE file yet. The upstream project and the fork are MIT licensed;
the intent is to release this app under the same terms. <<Add a LICENSE file before the first
release.>>
