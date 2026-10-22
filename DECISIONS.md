# Decisions: Smart Garage Android app

Server-side decisions (D-001 to D-035) live in the fork's `DECISIONS.md`.
App decisions use the `D-A` prefix.

## Foundation (CP7)

- **D-A01: minSdk 31, target/compile SDK 37.**
  - Android 12 gives dynamic color and the current permission and
    exact-alarm model without compat shims.
  - The only device is a recent Pixel on GrapheneOS.
- **D-A05: One Gradle module, Ktor (OkHttp engine), Hilt, Compose
  Material 3.**
  - Ktor over Retrofit: one HTTP stack covers JSON, multipart uploads (CP10)
    and a `MockEngine` for tests. It's also what the other apps on this
    machine use.
  - The client never follows redirects, so the bearer token and the
    Cloudflare secret can't be forwarded to another host.
  - No logging plugin.
  - Modules can be split later if build times call for it.
- **D-A08: Debug builds install as `io.github.conchad.smartgarage.debug`
  ("Garage debug").**
  - They sit next to the signed release build instead of failing on a
    signature mismatch.
- **D-A09: Bottom bar Home · Vehicles · Service · Fuel · More.**
  - These are the four things done standing next to the car. Fuel is
    one tap away at the pump.
  - Everything else from the web sidebar (Reminders, History, Odometer,
    Expenses, Documents, Notes, Wishlist) and Settings sit under More, in
    the web's order.
  - Tab labels are short so they fit at large font sizes. Screen titles
    keep the web's names (Dashboard, Maintenance).
- **D-A10: Appearance is per device; units are per server.**
  - Wallpaper (Material You) colors are on by default. Turning them off
    gives a palette built on the web app's accent, `#2563EB`.
  - Theme: Dark by default (the user's choice, 2026-10-21); System and Light
    stay available in Settings.
  - Distance, volume, currency and time zone come from the server's
    preferences (`/me`). The app shows them but doesn't edit them, so the
    web and the app never disagree.

## Auth and server profiles (CP8)

- **D-A11: The server address starts blank.** The app isn't tied to one
  person's server, so anyone can point it at their own instance.

- **D-A02: Independent server profiles.**
  - Each profile has a URL, an optional Cloudflare Access service token,
    and its own device token from its own sign-in. A LAN profile and a
    tunnel profile for the same server are simply two devices on the web's
    Mobile devices page.
  - *Why not one account with several URLs:* simpler and explicit. Each
    profile's credentials are tested on their own.
  - **CP9 consequence:** the offline cache is per profile, and switching
    profiles rebuilds the UI.
- **D-A03: Secrets are AES-256-GCM encrypted with a non-exportable Android
  Keystore key, then stored in DataStore.**
  - This covers the device token and the CF client ID and secret.
  - Not `androidx.security:security-crypto`, which is deprecated.
  - The key isn't bound to the lock screen, so CP9 background sync can run
    while the phone is locked.
  - `allowBackup=false` and the extraction rules exclude everything: a
    restored backup would hold ciphertext with no key. Signing in again is
    the recovery path.
- **D-A04: Plain http only for hosts that can't be public.**
  - Allowed: RFC 1918, loopback, link-local, Tailscale's 100.64/10,
    `localhost`, `.local`, `.lan`, `.home.arpa`, `.internal`, `.ts.net`.
  - The platform's network security config can't scope cleartext to IP
    ranges, so it allows cleartext and `ServerUrl` enforces the rule.
- **D-A06: The connection test tells failures apart.** This uses
  `/api/v1/health` (D-025):
  - **No response:** DNS, refused, timeout or TLS.
  - **Cloudflare Access:** `cf-ray` or `server: cloudflare` with a 401 or
    403, or a redirect to `*.cloudflareaccess.com` or `/cdn-cgi/access/`.
  - **Cloudflare challenge:** `cf-mitigated: challenge`.
  - **Tunnel or proxy up, origin down:** 502/503/504, or Cloudflare 52x/530.
  - **Any other redirect:** shown with its target.
  - **Not Smart Garage:** anything without `service: "smart-garage"`.
  - **Smart Garage problems:** wrong `apiVersion`, or the database is down.
  - Sign-in separates wrong credentials (401) and the rate limit (429,
    shown in minutes) from all of the above.
- **D-A07: Session lifecycle.**
  - Nothing is saved until sign-in succeeds, so an abandoned setup leaves
    no half-made profile.
  - Each time a profile becomes active, the app calls `/me`. A 401 means
    the token was revoked on the web: the app clears the token but keeps
    the URL, CF credentials and username, so signing in again only needs
    the password. Being offline changes nothing.
  - Sign-out and removal revoke the device server-side (5 s timeout); the
    local session is cleared either way.
- **D-A12: The cache is one Room database per profile, rows kept as JSON.**
  - Each row is (resource, id, vehicleId, updatedAt, json), decoded into
    typed models when read. A new server field can't break the schema, and
    a resource the app doesn't show yet is still cached for later.
  - All pages of a sync session are collected first, then applied in one
    transaction. A connection that drops halfway leaves the old cache
    intact. A row deleted later in the same session is never written.
  - Sign-out, a revoked token and removing a profile wipe its cache and
    downloaded photos. Tables are emptied rather than the file deleted,
    because screens may still be observing it. Files of profiles that no
    longer exist are deleted at startup.
  - A schema change drops the cache; the next sync refills it.
- **D-A13: When the app syncs.**
  - Every time the app comes to the foreground, and on pull-to-refresh.
    Incremental sync (D-028) makes this cheap when nothing changed.
  - Hourly in the background through WorkManager, only with a network.
    It retries only when the tunnel or proxy answers but the origin is
    down; other failures wait for the next run.
  - ~~Push-triggered sync waits for CP6 (UnifiedPush).~~ Done in CP12
    (D-A29): a push message calls `SyncEngine.syncSoon(profileId)` after
    the notification is posted, so the cache is fresh by the time the user
    taps it.
  - 2026-10-22 (battery fix owed since CP11): the hourly worker now skips a
    profile that synced successfully within the last 15 minutes and has an
    empty outbox (`SyncEngine.syncIfStale`): the foreground sync on every
    return to the app usually has the cache fresh, and an empty incremental
    pull still cost a request, a Keystore decrypt and a database pass. A
    queued write always syncs, so the worker remains the path that sends
    writes made offline. Its retry backs off exponentially from 10 min
    instead of WorkManager's 30 s (an origin that is down stays down for a
    while; the next hourly run comes anyway). Scheduling uses
    `ExistingPeriodicWorkPolicy.UPDATE`, which applies spec changes without
    resetting the period or next run time; it is called once per process
    start only, never on profile switch. The network constraint
    (`CONNECTED`) is unchanged. The foreground path is deliberately not
    throttled: "every time the app comes to the foreground" stays true.
- **D-A14: Documents open in place for images, in another app for everything else.**
  - Files download through the authenticated API into the cache (the same
    store as vehicle photos), so a file opened once also works offline.
  - Images show in a full-screen viewer inside the app. PDFs and other
    types go to whatever viewer the phone has, through a FileProvider
    limited to `cache/documents/` with a read-only grant. Writing our own
    PDF renderer isn't worth it when every phone already has one.
  - The More sections (reminders, history, odometer, expenses, documents,
    notes, wishlist) are read-only for now. Editing arrives with the offline
    write queue, after the conflict rule is agreed (before CP11).
- **D-A15: Offline edits use last write wins (user sign-off 2026-10-21).**
  - Edits made offline queue locally and replay in order when the server is
    reachable. Each request carries an Idempotency-Key (D-033), so a retry
    after a dropped connection can't apply twice.
  - Whichever write reaches the server last wins; an earlier change to the
    same record is overwritten without a warning. Fine for one person; two
    people editing the same record at once can lose an edit.
  - Revisit (e.g. compare `updatedAt` and ask) if that ever bites.

## Offline writes (CP11)

- **D-A16: The outbox is a table in the profile's cache database.**
  - Every write the app makes is first a row in `outbox` (schema version 2):
    an `Idempotency-Key` (D-033), a monotonic `seq`, the resource, the
    operation, the target id, the request body, and the optimistic row. It
    is replayed in `seq` order, one entry at a time, with its key as the
    header, so a retry after a dropped connection can never apply twice.
  - It sits in the same database as the cache so sign-out, a revoked token
    and removing a profile wipe it with everything else (D-A12). A revoked
    phone may be a lost phone; queued edits are not worth keeping.
  - The change shows at once: a created row goes into the cache under a
    temp id, an edit is merged onto the cached row, a delete removes it. The
    row carries `pendingKey` (the entry's key) until the server answers, so
    the UI can mark it "waiting to sync". A pull never overwrites a row
    with a `pendingKey`, and a full resync keeps such rows; the replay that
    precedes the next pull settles them with the server's answer.
  - Rights are checked locally from the cached vehicle's `access` (D-030):
    viewers can't write, only the owner deletes a vehicle. The server checks
    again, so a stale cache can only cost a FAILED entry, never a wrong write.
  - Not queued: modifications, custom fields, sharing, preferences and
    notification settings. They are outside the handoff's scope and most of
    them need the server's answer to be useful.
- **D-A17: Temp ids are `tmp-<uuid>` and are remapped when the create replays.**
  - Rows created offline can be edited, deleted and referenced (a fill-up on
    a vehicle that doesn't exist on the server yet) before they are sent.
    When the create succeeds, the server's id replaces the temp id in the
    cached row, in cached rows that point at it (`vehicleId` and JSON), and
    in every later outbox entry. The server row replaces the optimistic one,
    unless a later edit to the same row is still queued; then the local
    version stays until that edit is answered.
  - Deleting a row that is still a pending create cancels the create instead
    of sending both.
- **D-A18: Transient answers stop the replay; final answers mark one entry
  FAILED and move on.**
  - Unreachable, 5xx, 429 and "request still in progress" (409) leave the
    entry PENDING and stop the run, so order is preserved. The next attempt
    follows the usual sync timing (D-A13): foreground, pull-to-refresh, the
    hourly worker. No tight retry loop. When the server is unreachable the
    pull is skipped too; it would only time out again.
  - Any other 4xx is final (the server replays it for the same key anyway).
    The entry keeps the server's status, code and message for the UI; the
    cache converges back to the server: a failed create disappears, together
    with every later entry that built on its temp id (`dependency_failed`);
    a failed edit or delete re-fetches the row by id (`GET /<resource>/<id>`,
    404 removes it), and if that fetch doesn't settle it the next pull is a
    full resync. A delete answered 404 counts as done: the goal state is
    reached.
  - Retry gives the entry a fresh key (a used key would just replay the
    rejection) and re-applies its optimistic change; dismiss drops it. An
    entry that depends on a failed create can only be dismissed.
  - 401 takes the existing revoked-token path: session and cache (and so
    the outbox) are cleared.
- **D-A19: Replay runs before the pull, inside the same sync.**
  - Writes have server-side effects beyond the row they touch (a service
    record updates its schedule's due status and the vehicle's odometer, and
    may create an expense). Pulling right after the replay brings those back
    in the same session, so the screen is consistent after one sync.
  - Uploads (documents, vehicle photos) are staged under
    `files/outbox/<profile>/<key>` so they survive a restart and the cache
    being cleared; a copy is published to the documents cache under the temp
    id, so the file opens offline immediately, and is renamed to the server's
    id on success. Last write wins throughout (D-A15): no conflict detection.
- **D-A20: Forms convert at the edge; stored units never reach a text field.**
  - A form's text fields hold what the user typed, in the server's display
    units (mi/gal/USD per its preferences, D-A05), with the unit as a suffix.
    The ViewModel converts at save time through `Formatter` (`kmFromDisplay`,
    `litersFromDisplay`, `centsFromDisplay`) and prefills the other way
    (`distanceInput`, `volumeInput`, `moneyInput`): no grouping, the locale's
    decimal mark. `parseNumber` takes "." or "," as the decimal mark whatever
    the keyboard offers; a single separator is always the decimal mark.
  - Dates: the picker works on calendar days in the server's time zone.
    Today is stored as the current instant (what the web and the server
    default to); any other day as noon in that zone, so neither app shows
    the day before. Future dates are not selectable.
  - Odometer fields are prefilled with the vehicle's current reading. A lower
    value is allowed (a late entry) but asks first, in a dialog that names
    both numbers; the server accepts either. Editing an existing record
    skips the question, since its reading is historical by nature.
  - Fill-up: volume × price per unit = total. The money field touched last
    stays, the other follows; with no volume yet, total ÷ price gives the
    volume (the web's quick add). The price is prefilled from the vehicle's
    most recent fill-up. The server has no "full tank" field, so the form
    has no toggle; adding one is a server change first.
  - Service records open with the schedule's `itemDefaults` as editable
    rows (name, quantity, cost); the fields the form doesn't show (brand,
    part number, unit) ride along unchanged. Blank rows are dropped.
  - Where things open: list tabs (Fuel, Service) get one extended FAB with a
    verb ("Log fill-up"). Detail screens use inline buttons below the
    heading, never a FAB. Edit and Delete live in the row's detail sheet, so
    a list row stays a single tap target; delete confirms first. Short forms
    (odometer) are bottom sheets; anything with more than three fields is a
    full-screen form with a pinned Save bar, whose disabled state always
    says why, and a discard confirmation on back.
  - Viewers (`access.canEdit == false`) see "View only · shared by <owner>"
    where an action would be, and no FAB for a garage with nothing editable.
    A `Denied` from the writer still shows a snackbar, so a stale cache
    never fails silently. Rows waiting to sync carry an icon-plus-text mark;
    the sync banner counts queued changes while offline and lists the
    rejected ones with Retry/Dismiss.
  - Phase B (the remaining editors): a setting rather than a log entry
    (a service schedule) is added from the top bar's "+" and the empty
    state, so each list tab keeps its one FAB verb. Vehicle-level verbs
    (edit, change photo, delete) sit in the detail screen's overflow menu;
    "add" buttons for papers sit inside their card. Deleting a vehicle asks
    twice, the second time naming the vehicle and the server. "Deleting" a
    schedule is the web's deactivate: the copy says reminders stop and the
    history stays. An inspection is one per vehicle and type; the form says
    when saving would replace the existing one. Patch bodies that the server
    takes as plain strings (schedule name, notes) are sent as "" rather than
    cleared. Notes and wishlist items are the user's own, so their forms
    skip the view-only gate and offer every vehicle, shared ones included.
- **D-A21: Files are picked with the system pickers and checked against the
  server's rules before anything is queued.**
  - Photos use the Android photo picker (`PickVisualMedia`, images only);
    documents use the Storage Access Framework (`OpenDocument`) limited to
    the server's `ALLOWED_DOCUMENT_MIME_TYPES` (JPEG, PNG, WebP, GIF, PDF).
    Neither needs a runtime permission or a Google library; the app never
    reads the media store itself.
  - The file's name and size come from the provider's `OpenableColumns`
    (falling back to the URI's last segment and a byte count); the MIME
    type is what the first KiB says (`FileRules.sniffMimeType`, the same
    signatures as `lib/storage/sniff.ts`), else the provider's declared type
    when the server accepts it, else the extension's. The type sent is
    therefore the one the server will see, so a provider reporting a
    wildcard or a wrong type doesn't earn a 415 later.
  - `FileRules.check` applies the server's defaults before queuing: 10 MiB
    for a vehicle photo (`MAX_IMAGE_SIZE_MB`), 25 MiB for a document
    (`MAX_UPLOAD_SIZE_MB`), type first, then size. A file that fails is
    named in the form's file slot (or a snackbar for the photo) and nothing
    enters the outbox; a server configured with a smaller limit still
    answers 413, which the failed-writes list shows as before.
  - The stream is opened at save time and handed to the writer, which
    stages a copy (D-A19); the URI itself is not persisted, so no
    persistable permission is taken.
- **D-A22: Cache flows are decoded once and shared at the repository.**
  - Room re-runs every query on the `rows` table whenever any row changes,
    so a queued write or a sync page used to re-decode every observed
    resource, once per ViewModel observing it. `GarageRepository` now drops
    re-emissions of unchanged rows (`distinctUntilChanged` on the raw rows,
    which compares strings, not decoded models) and shares each decoded
    resource with `shareIn` on the application scope; `GarageWriter`'s
    outbox and pending-id flows and `SyncControl.info` are shared the same
    way. Decoded models stay immutable.
  - Sharing stops five seconds after the last subscriber leaves (the same
    timeout as the ViewModels' `stateIn`) and the replay is dropped then,
    so a screen opened later never shows a stale list before the fresh one.
- **D-A23: Parity gap-fill: charts drawn in-house, search in the ViewModel,
  camera capture downscaled, custom fields and modifications read-only.**
  - Charts are a `Canvas` line chart and bar chart in
    `ui/components/charts/` (no charting dependency). Series come from
    `ChartMath` (pure Kotlin, unit-tested) in stored units and are converted
    for display through `Formatter`, the same way the rows are, so a chart
    never disagrees with the list under it. Theme colors, sp text; TalkBack
    reads one spoken summary (span, latest, lowest, highest) in place of the
    drawing. Fuel shows the web's consumption and price-per-unit trends in a
    collapsible "Trends" group above the log; Odometer shows readings over
    time; Expenses shows the web's cost overview (last 12 months, by
    category). The fuel and odometer charts are per vehicle and only drawn
    when one vehicle is in view; under "All vehicles" the card says to pick
    one, since lines of different cars mean nothing together.
  - Search (Service → History, the History section, Notes) is a plain
    outlined field with a clear button; the matching is `SearchMatch` in the
    ViewModel (words ANDed, case- and accent-folded), never in composition.
    Records match on name, vendor, notes and parts; notes on title, content
    and tags, with a tag chip row (one tag at a time) and Pin/Unpin as the
    note sheet's secondary action (`NotePatch(isPinned)` only). "No matches"
    is told apart from "nothing yet".
  - Camera capture uses `ActivityResultContracts.TakePicture` into
    `cache/captures/` through the existing FileProvider (`captures` path
    added); no CAMERA permission, no Google libraries. The photo is
    re-encoded in place before anything is queued: long edge ≤ 1600 px, JPEG
    80 %, EXIF rotation applied and EXIF dropped (`ImageMath` sizing is
    unit-tested), then it is a `PickedFile` like any other and goes through
    `FileRules` → `addDocument`. "Take photo" sits next to "Choose file" in
    the document form; a service record's sheet has "Attach receipt", which
    opens the form with the record pre-linked (vehicle fixed, category
    Invoice) and the camera already open. Captures older than a day are
    swept when the form opens; the one uploaded is deleted once queued.
  - `customFields`, `customFieldValues` and `modifications` are in `/sync`
    (`resources.ts`) and were already cached, so typed models were added
    (`CustomField`, `CustomFieldValue`, `Modification`; all fields optional)
    and `GarageRepository` exposes them. The vehicle detail shows custom
    fields as rows at the end of Details (before Notes, the web's order;
    only fields with a value) formatted like the web's
    `formatCustomFieldValue`, and a read-only Modifications group. Writing
    either stays out of scope (D-A16).
  - `PhotoLoader` remembers resolved files per document id and
    `VehiclePhoto` starts from that, so a card scrolled back into view no
    longer flashes the placeholder.

- **D-A24: A glass design system for the home and vehicles pages; nickname
  and odometer reminder stay on the phone; insurer deep links by package.**
  - `ui/glass/` holds the look, translated from the user's reference
    snippets into Compose with no new dependency: `AuroraBackground` (three
    soft ribbons drawn as radial gradients with transparent edges rather
    than a full-screen blur, screen-blended on dark, 9/11/13 s drifts),
    `GlassPanel`/`GlassCard` (translucent fill + 1 dp border + inset top
    highlight + lift shadow — a real backdrop blur of drawn content is not
    expressible in Compose without a library, so this approximates it),
    `MetallicText` (two tiers: an animated gold sheen sweep for titles and
    headers, a static steel gradient for body text so contrast stays
    ≥ 4.5:1), `MetallicFrame` + `rememberTilt` (chrome border, tilt-driven
    sheen, tiled grain, ≤ 12° 3D tilt), `MorphingBottomNav` (floating pill,
    spring blob, no auto-cycle) and `DisconnectedPopup`/`RefreshIcon`.
    Tokens live in `GlassTokens` with dark and light sets chosen from the
    resolved theme (Light/Dark/System); with dynamic color on, only the nav
    blob takes the wallpaper's primary → tertiary. Every ambient effect
    shares two guards: composed without animation when the lifecycle isn't
    RESUMED, and one static frame when the system animator scale is 0 (the
    snippets' `visibilitychange` and `prefers-reduced-motion`).
  - The aurora is drawn once by the shell behind a transparent `Scaffold`,
    so it also runs behind the floating bottom bar; its tint comes from
    `LocalAuroraTint`, set by the screen in view (the Dashboard sets the
    current vehicle's `VehicleTint`, cross-faded over 800 ms). Only the Home
    and Vehicles routes compose it; the other tabs keep plain surfaces and
    pay nothing. `VehicleTint` maps the server's free-text `color` (hex, EN
    and DE words, compounds like "Dunkelrot", dark/light/metallic modifiers)
    to a color; black, white and greys give a cool steel so a dark car still
    has a visible aurora. Unit-tested.
  - Sensor rules: the tilt registers `TYPE_GAME_ROTATION_VECTOR` (fallback
    `TYPE_ROTATION_VECTOR`) at `SENSOR_DELAY_UI` only while the frame is in
    the tree, the lifecycle is RESUMED and the page is the settled one; it
    unregisters on dispose. Without a rotation sensor the frame follows the
    finger and drifts idly. No permission is involved.
  - The home card's dropdown shows insurance, mileage and inspection per
    vehicle, so `Dashboard` gained `inspectionByVehicle`, `policyByVehicle`
    and `recentFuelByVehicle` (additive, defaulted) and the "Coming up"
    tiles are gone. "Needs attention" and "Recent fill-ups" are filtered to
    the vehicle in the pager and slide with it.
  - Renaming a vehicle is device-local (`VehicleLocalPrefs`, same DataStore
    as `AppSettings`): the server has no nickname field and
    `vehiclePatchSchema` cannot change make/model/year, so a nickname that
    only this phone shows is the honest option. The server's name stays
    visible under it. Photo change/upload reuses `setVehiclePhoto` and the
    D-A21 checks, gated on `access.canEdit`.
  - Odometer reminder: `OdometerReminder` (pure Kotlin, unit-tested) takes
    the newest odometer log or fill-up with a reading (falling back to the
    vehicle's `updatedAt`), reminds when that is > 30 days old, and "Later"
    snoozes for 30 days per vehicle in `VehicleLocalPrefs`. Shown only to
    people who can edit the vehicle.
  - Insurer deep links: `InsuranceApps` matches `InsurancePolicy.provider`
    by keyword to a short table of package names and uses
    `getLaunchIntentForPackage`, which needs `<queries><package>` entries in
    the manifest. Not installed or no match: the row is plain text. Several
    package names are marked unverified in the table; a wrong one degrades
    to plain text.
  - The disconnect popup covers `SyncInfo.stale` (offline or server error
    after an earlier success); dismissing it collapses it into a refresh
    icon in the top bar until a sync succeeds, after which a new failure
    shows the popup again. The home screen's `WriteQueueBanner` keeps only
    the failed-writes message (`syncStates = false`) so nothing is said
    twice; the never-synced case keeps the full-screen `CantReachState`.
- **D-A25: Calendar reminders via `ACTION_INSERT`; the vehicle page leads
  with the car, not its specs; fuel economy is a device-side switch.**
  - "Add to calendar" hands an all-day event to whatever calendar app the
    user has: `Intent(ACTION_INSERT)` on `CalendarContract.Events` with
    title, description, `EXTRA_EVENT_ALL_DAY` and begin/end at midnight UTC
    of the day (`CalendarLinks`, pure and unit-tested). Nothing is written
    until the user saves in their app, so no calendar permission, no
    account access and no Google dependency; a phone without a calendar app
    gets a snackbar. A due date is a day, not a time, hence all-day. The
    button (`AddToCalendarButton`, 48 dp, label carries the date) appears
    wherever a due day is known: the schedule form (baseline + months, else
    the server's `nextDueAt`; a new schedule without a baseline has none
    until saved), the record form (date + the schedule's month interval),
    the inspection form (`nextDueAt`), the insurance form (`endDate`,
    titled "… renewal"), and the saved-item sheets for schedules,
    inspections and policies. Viewers get it too: a reminder is not an edit.
    Titles follow "Oil change due — 2019 Mazda MX-5"; the description names
    the vehicle, the interval or coverage and the notes.
  - The vehicle page (notes 13–15) now reads top-down as the car, who it
    belongs to, the four figures one asks about first, the quick actions,
    and everything else behind one header. The make/model sits inside the
    16:9 photo card, bottom-left over a dark bottom gradient (fixed light-on-
    dark pair, not theme colours, since it sits on a photo); the top bar
    still takes the name over on scroll. "Owner: …" names the signed-in
    account for own vehicles (from `ProfileStore`, the same source Settings
    uses) and the sharing owner for shared ones. The 2×2 immediate-info grid
    is mileage, the insurer in force (`GarageMath.currentPolicy`, the
    dashboard's pick), the plate and this year's spend (fuel + records +
    expenses, server-zone calendar year, deduplicated as before). "Vehicle
    details" is a collapsible, closed by default and remembered across
    rotation, holding registration (plate, HSN/TSN, inspection sticker,
    VIN), colour and purchase, specs, tyres, modifications, custom fields
    and notes — nothing about service, which keeps its own groups below.
    Tyres are a row only when the owner keeps a tyre custom field or
    modification (`GarageMath.isAboutTires`, English and German whole
    words); the server has no tyre model and the app does not invent one.
    The average consumption moved from the old stat strip to the top of the
    Fuel card, so the grid stays the four figures asked for.
  - "Track fuel economy" (`AppSettings.trackFuelEconomy`, default on) is a
    setting of this phone, not of the server or the entry: off hides the
    odometer field on the fill-up form (no validation, no regression prompt,
    no `odometerKm` sent) and hides consumption everywhere it is derived
    (Fuel summary card, consumption chart, rows, sheets, the vehicle page).
    Prices per unit and totals stay, since they need no odometer. Entries
    that already carry a reading keep it, the server is unaffected, and
    other screens take the flag with a default of `true` so the dashboard
    is untouched.
- **D-A26: The vehicle page joins the glass screens; every group is its own
  drop-down; the photo card shares the home card's menu.**
  - `MainShell` now treats `VehicleDetailRoute` as a glass route, so the
    shell's one aurora runs behind it with the same guards as Home and
    Vehicles (static when the lifecycle isn't RESUMED or the animator scale
    is 0). The screen sets `AuroraTint` from the vehicle's `color` via
    `VehicleTint`, so the aurora cross-fades into the car's colour when the
    page opens and back when it closes. The top bar is transparent and its
    title is the animated metallic tier; the whole screen sits in one
    `MetallicSheenHost` so every header glints together.
  - Notes 11–12: `GlassDropdown` (new in `ui/glass/`) is a `GlassCard` with
    a bold animated-metallic header row (≥ 56 dp, two lines allowed, so it
    holds at font scale 1.3), a turning chevron and `animateContentSize`, so
    the cards under it slide as it opens; under reduced motion both snap.
    The header is a button with its state for TalkBack. On the page the 2×2
    "At a glance" figures and the "Quick actions" start open; under one
    plain "Vehicle details" header each group is its own drop-down, closed by
    default and remembered across rotation: registration (plate, HSN/TSN,
    sticker), VIN, colour (with a swatch in the aurora tint when the text
    names a colour), purchase, specs, tyres (still only with a tyre custom
    field or modification, D-A25), modifications, custom fields, notes.
    Groups with nothing in them aren't shown; with nothing at all the one
    "nothing recorded" card stays. Service, papers, fuel and history keep
    their headers (they carry actions) on `GlassGroupHeader` + `GlassCard`.
    The figure tiles are glass pills with static steel values.
  - Note 10: the photo card is a `MetallicFrame` (tilt on, under the D-A24
    sensor rules) with `VehicleCardMenu` on tap or long-press and the same
    TalkBack custom actions as the home card, hosted by the shared
    `VehicleCardActionsViewModel` / `rememberVehicleCardActions`. The name in
    the photo and in the scrolled top bar is the device-local nickname when
    set, with the server's name beneath, as on the home card; the delete
    dialogs keep the server's name, since that is what is deleted. The top
    bar's overflow offers the same choices for anyone who doesn't find the
    long-press, and is now shown to viewers too because renaming is local;
    edit, photo and delete stay gated on `access.canEdit`. The detail
    screen's own picker path (`VehicleDetailViewModel.setPhoto` /
    `photoPick`) is no longer called from the UI. 2026-10-22: removed, with
    the ViewModel's `Context` dependency that only it used.
  - The scrim under a name in a photo (transparent → 70 % → 85 % black) and
    its fixed light pair moved from three screens into `GlassTokens`
    (`photoScrim`, `onPhoto`, `onPhotoSecondary`), the same in both token
    sets since a photo has no theme. The dashboard's schedule sheet gained
    the D-A25 "Add to calendar" hook like the maintenance screen's. The
    unused `vehicle_details` and `vehicle_no_modifications` strings are gone
    from both locales; new labels (`vehicle_at_a_glance`,
    `vehicle_quick_actions`, `vehicle_purchase`, `vehicle_custom_fields`,
    `vehicle_photo_options`) are in EN and DE.
  - Visual pass (Pixel 10 Pro, font scale 1.3, dark): the page's aurora read
    grey over a green car because `AuroraTint`'s dispose on the outgoing
    Dashboard ran after the incoming vehicle page had set its tint and wiped
    it back to theme primary. `LocalAuroraTint` is now an `AuroraTintHolder`
    that remembers which `AuroraTint` set it and only clears on that owner's
    dispose, so the handover in either direction keeps the newer screen's
    tint. `VehicleTint` itself is unchanged. "Spent this year" is labelled in
    the formatter's (server-preference) currency, since the sum is of entries
    stored in it (D-A20), not the vehicle's `purchaseCurrency`; the owner
    line sits on a panel-fill pill so it reads over the aurora's bright spot;
    `ActionButton` stacks icon over a centred two-line label; the fill-up
    Price/Total row uses one text size so the outlined fields align; the home
    subtitle uses the abbreviated relative time ("5 min. ago").
- **D-A27: The receipt scanner is a hook, not a feature: everything the
  real call needs is wired, the scanner itself is a stub.**
  - The server has `POST /api/v1/fuel/receipt-scan` and announces it via
    `GET /api/v1/me` → `features.receiptScanner` (docs/mobile/RECEIPT-
    SCANNER.md). The user isn't enabling it yet, so the app gets the seams
    and none of the behaviour. `MeResponse.features` (`MeFeatures`, every
    flag `false` by default, so an older server's `/me` without the key still
    parses) is mirrored into `ServerProfile.features` (`ServerFeatures`) by
    `AuthRepository.applyMe`, so any screen can observe it through
    `ProfileStore.state`, the same way Settings reads the account.
  - `core/network/ReceiptScanner.kt`: the `ReceiptScanner` interface
    (`scan(vehicleId, photo: File)`), the contract's shapes
    (`ReceiptScanResponse`, `ReceiptDraft` with `confidence` and `source`,
    the `warnings` codes as strings) and `ReceiptScanError` with one case per
    error row (401/403/404/413/415/422/429 + `Retry-After`/502 with its
    `documentId`) plus `Unreachable`. `StubReceiptScanner` answers
    `Unavailable`; `di/ReceiptScannerModule` binds it, so the real multipart
    client (through `ApiClient`, 25 s timeout, `Idempotency-Key`) is a
    one-binding swap. Its KDoc lists what the real client must do and the
    next steps in order: prefill, confidence highlighting, the attach-anyway
    path, the offline "unscanned" queue. None of those exist yet.
  - The fill-up form (fuel note 1) shows "Scan receipt" — a full-width
    48 dp outlined button with a hint beneath, between the vehicle and the
    date — **only when** the active server's `features.receiptScanner` is
    true and the vehicle is editable, so nothing changes tonight. Tapping it
    reuses the document form's camera path (`CameraCapture`, ≤ 1600 px JPEG
    80 in `cache/captures/`, D-A23); while the photo is prepared and sent the
    button shows a spinner and "Reading the receipt…" and Save is held.
    The answer is a snackbar (en + de): the stub's and a 404's "Receipt
    scanning isn't available on this server yet", or the matching line for
    view-only, not a receipt, daily limit, rejected photo, no answer, or
    failure. The photo stays on the form as a "Receipt photo" row with a
    remove control so a later attach-anyway path has it; it isn't uploaded
    on its own, since the document link only exists through the scan's
    `documentId`. `FuelEntryInput.receiptDocumentId` (optional) is sent
    when non-null (a successful scan or a 502 both carry one); the stub never
    sets it.
- **D-A28: Security patch from the 2026-10-22 audit (A-1 … A-8): server ids
  are validated before they become file names or path segments, and
  plain-http profiles don't background-sync unless the user opts in.**
  - A-1 (Medium). `core/network/ServerIds` is the one definition of what a
    server id may look like: `^[A-Za-z0-9_-]{1,64}$` (cuids and the app's
    `tmp-<uuid>` both pass). It is applied at the trust boundary —
    `ServerRows.toCachedRow` drops a row whose `id` or `vehicleId` fails it,
    the pull counts those in `SyncOutcome.Success.rejected` and otherwise
    succeeds — and again in the file helpers: `PhotoStore.file` and
    `OutboxFiles.publish`/`rename` throw `InvalidServerIdException` (which
    carries the id's length, never the id) before touching the disk.
    `PhotoLoader` maps that to "no photo" (the glyph shows);
    `DocumentsViewModel` maps it to `DocumentOpen.InvalidId` → the
    "server sent an invalid id" dialog (en + de). `OutboxReplay.complete`
    checks `imageDocumentId` from a write response, which doesn't pass
    through `toCachedRow`, before renaming the published copy. Ids in URLs
    (`WriteRoutes.path`/`itemPath`, `ApiClient.documentContent`) are
    percent-encoded as one path segment with Ktor's `encodeURLPathPart()`.
    A hostile or hijacked server for profile X can no longer write outside
    `cache/documents/<X>/`, which restores the D-A02 isolation guarantee.
  - A-2 (Medium). `ServerProfile.backgroundSyncOnCleartext: Boolean =
    false` (serializable default, so stored profiles parse unchanged) plus
    the derived `isCleartext` / `syncsInBackground`. `SyncWorker.doWork`
    skips a profile with `syncsInBackground == false` before the
    `syncIfStale` check (D-A13 stays). **Default off** for http profiles:
    an http address names a box on the home network, and on another network
    whatever answers at that name or IP would receive the device token in
    clear; the user is not present to notice a 401/404 being logged as
    "unreachable". Foreground syncs are unchanged — the user opened the app
    and chose that profile. The Settings → Account card shows a "Sync hourly
    in the background" switch with a one-line explanation **only** for http
    profiles (`profile.isCleartext`); https profiles see nothing new.
  - A-3 (Low) **deferred, deliberately.** `setUnlockedDeviceRequired(true)`
    on the Keystore key would make `SecretCipher.decrypt` fail while the
    phone is locked, so the hourly worker (which runs mostly while locked)
    could never read the token: background sync would silently stop for
    every profile, https included. Key properties are fixed at generation,
    so a per-profile or "only when background sync is off" variant means a
    second key, re-encrypting every stored secret and a migration — not a
    one-liner. The current trade-off (key usable while locked, not
    exportable, `allowBackup=false`) stands as documented in D-A03; revisit
    if background sync ever becomes a global opt-out.
  - A-4 (Info). `scripts/check-no-gms.sh` already resolved the release
    runtime classpath through Gradle; its pattern now also catches
    `com.google.android.play`, `com.google.android.libraries`,
    `com.google.mlkit`, `com.google.ads` and `firebase-*` artefacts. Still
    exits 0 on this tree.
  - A-5 (Info). `HttpClientFactory` now turns off OkHttp's own
    `followRedirects`/`followSslRedirects` explicitly in addition to Ktor's
    `followRedirects = false` (Ktor's engine already did, but a dependency
    bump must not be able to change that quietly). `ApiClientTest` gained a
    `MockEngine` test: a 302 to another host yields
    `ServerProblem.Redirected`, exactly one request is made, and the bearer
    token and Cloudflare headers went to the origin only.
  - A-6 (Info) no change: profile metadata in plaintext DataStore is the
    documented design (secrets are encrypted). A-7 (Info): `ReceiptScanner`
    KDoc now states the interface must never grow a host/URL parameter and
    must not log the response. A-8 (Info) confirmed as-is: `FileProvider`
    not exported, paths scoped to `cache/documents/` and `cache/captures/`,
    per-intent `FLAG_GRANT_READ_URI_PERMISSION` only.
  - Tests: `ServerIdsTest` (accept cuid/uuid/temp ids; reject `../x`, `..`,
    `/abs`, empty, 65 chars, unicode, separators, NUL), `WriteRoutesTest`
    (encoding, boundary drops), `ServerProfileTest` (http default off, opt-in,
    https always, legacy JSON parses), `SyncPullTest` (bad ids dropped and
    counted, good rows still applied), `ApiClientTest` (redirect to another
    host). 102 → 112 unit tests.
- **D-A29: Push notifications through UnifiedPush, connector only (CP12;
  server contract docs/mobile/PUSH.md, CP6).**
  - **Dependency.** `org.unifiedpush.android:connector` 3.3.5 is the one new
    artefact. Not `connector-ui` (it drags in appcompat for a dialog the app
    can draw itself) and never the embedded FCM distributor
    (`scripts/check-no-gms.sh` flags it). The connector brings
    `com.google.crypto.tink:tink` with protobuf-java and gson: Google-authored
    but GMS-free, like Hilt and Truth; the script's pattern does not match it
    and it exits 0.
  - **Decryption is the connector's job.** The distributor hands over the raw
    RFC 8291 body; the connector's `DefaultKeyManager` generates the P-256
    pair and the 16-byte `auth` secret per instance, keeps the private key
    AES-GCM-encrypted under its own Keystore key, decrypts with Tink's
    `WebPushHybridDecrypt` and calls `PushService.onMessage` with plaintext
    and a `decrypted` flag. The app therefore has no crypto of its own, never
    sees a private key and has nothing to store with `SecretCipher` beyond
    what the server was given: `PushSubscription` (endpoint, `p256dh`,
    `auth`) lives encrypted under `secret.<profile>.push_subscription` so the
    stale re-PUT can repeat it without asking the distributor. A message that
    arrives with `decrypted == false` shows nothing but still triggers a sync.
  - **One instance per profile.** The UnifiedPush `instance` is the profile
    id (a UUID, which passes `ServerIds`), so two servers on one phone are two
    registrations with the one distributor the connector keeps; the service
    maps `instance` back to the profile and ignores anything that isn't a
    valid id. `PushSettings` on the profile (`enabled`, `registeredAt`,
    `distributor`, `vapidKey`, `problem`, `problemDetail`; every field
    defaulted so stored profiles parse) is what Settings shows.
  - **State machine** (`core/push/PushRegistrar`): *enable* saves the
    distributor (the saved one, the only one installed, or the user's pick
    from a list of installed ones), fetches `GET /push/vapid`, marks
    `enabled`, calls `UnifiedPush.register(instance, vapid)`; *onNewEndpoint*
    does `PUT /devices/{id}/push` and records `registeredAt`; *refresh* runs
    from `AppViewModel` after `auth.refresh` on every start / profile switch /
    re-login and re-PUTs when `registeredAt` is a day old (`needsReregister`,
    pure, tested), re-registers with the distributor when the VAPID key
    changed or no subscription exists, and retries after Unreachable /
    ServerRejected / Unregistered / NoDistributor; *disable* (switch off)
    DELETEs, unregisters with the distributor and forgets the subscription;
    *forget* (`PushSessionHook`, called from `AuthRepository.sessionRejected`
    and `removeProfile`) only unregisters with the distributor, since logout
    already revoked the device, and keeps `enabled` so signing in again
    re-registers (contract §2). A 409 from the test endpoint forces a
    refresh; a `push_subscription_gone` 502 clears the subscription and
    re-registers.
  - **Cleartext rule.** A push wakes the app to sync while the user isn't
    looking, which is exactly what D-A28 keeps plain-http profiles from doing.
    `ServerProfile.pushAllowed == syncsInBackground`: the switch is disabled
    with a one-line explanation until "Sync hourly in the background" is on
    for that profile, and `refresh` skips such profiles.
  - **Notifications.** One channel, "Garage reminders" (en + de). The
    `POST_NOTIFICATIONS` runtime permission is requested from the Settings
    push row when the user turns the switch on, never on launch; a denied
    permission shows as "blocked" under the switch. The tap intent carries
    profile, vehicle and schedule ids plus the web path; `MainActivity`
    (`singleTask`) hands it to `PushTapRouter`, which re-validates every id,
    makes the profile active and parks a `PushTarget` that the profile's
    shell consumes: vehicle → `VehicleDetailRoute`, a lone schedule or
    `/maintenance` → Service tab, `/odometer` / `/expenses` / `/reminders` →
    that section, else Home. The payload's `url` is also offered as an "Open
    on web" action (`ACTION_VIEW` on the profile's base URL) only when it is
    a plain site-relative path; there is no WebView. Nothing about the
    message, endpoint or keys is logged.
  - **Deferred.** `push_subscription_gone` surfaces only through the test
    endpoint today: `/sync` doesn't report it and the hourly worker doesn't
    look for it. `PATCH /notification-settings { mobilePushEnabled }` and the
    device list's `pushSubscribed` are not shown in the app (the web does
    that). `onTempUnavailable` is left at the connector's default. No
    per-type channels or quiet hours on the phone: delivery timing is the
    server's.
  - Tests: `PushPayloadTest` (full payload, missing ids, invalid id dropped
    while the text is kept, non-path `url` dropped, garbage and blank text
    rejected, intent target validation), `PushStalenessTest` (the one-day
    rule), `PushApiTest` (PUT/DELETE/POST/GET shapes against `MockEngine`,
    bearer header, JSON body, `endpoint_rejected`, `push_subscription_gone`,
    `Retry-After`, a bad device id never becomes a path). 112 → 128 unit
    tests.
- **D-A30: The cards tilt in 3D toward a pointer and shine where it is; the
  aurora runs behind every screen.**
  - **What replaced what.** The holographic sheen band, the tiled grain and the
    frame's own 3D rotation in `MetallicFrame` are gone, with their tokens
    (`frameSheen`, `frameSheenBlend`, `frameGrain`); the frame is now just the
    chrome border + glow + clip around a photo. In their place the user's
    "3D Tilt Parallax" reference, translated to Compose in `ui/glass/Tilt.kt`
    and `ui/glass/TiltParallax.kt`: `TiltParallaxCard` (a `GlassPanel` that
    rotates, glows and shines) with `TiltLayer(depth)` / `Modifier.tiltLayer`
    for the layers inside it. Applied to the home vehicle card, the vehicles-
    list card, the vehicle page's photo card (now a card around the framed
    photo, like the home card) and the four "At a glance" tiles. `GlassCard`
    (every content card, `GlassDropdown`, and `GroupCard`, see below) gets the
    plate glow and the shine but **no rotation and no parallax** (content-
    dense), and its shine reacts to touch only. `MetallicText` stays on
    titles, headers and the plate · mileage line: it is text, it reads well
    over the new plate, and nothing else sweeps any more, so the one glint
    per screen is the headers'.
  - **The pointer** is a point 0..1 × 0..1 over the card, (0.5, 0.5) level.
    (a) The gyroscope: `rememberTilt` (one per screen, provided through
    `LocalTilt`) keeps the D-A24 sensor rules — game rotation vector at
    `SENSOR_DELAY_UI`, only while in the tree and RESUMED, the slowly tracking
    resting pose, the 0.015° deadband — and maps ±12° of roll/pitch to the
    full 0..1 range. (b) The finger: `Modifier.tiltPointer` observes the
    card's touches in the final pass without consuming anything; while
    pressed, touch wins. A press (consumed by a clickable or not) starts
    tracking; a move the list or pager has consumed, or that left the card,
    ends it, so scrolling a list doesn't tilt every card the finger crosses.
    No idle auto-rotation (the reference's demo loop): the gyro gives the
    life, and a phone lying flat is level. The no-sensor idle drift of D-A24
    is gone too; such a phone's cards react to touch only.
  - **Mapping.** `rotationX = (0.5 − py)·14°·2`, `rotationY = (px − 0.5)·14°·2`
    (max 14° at the card's edge), `cameraDistance = 7` (the platform camera
    unit is View's px ÷ dpi, so 8 is the classic 1280 dp; 7 ≈ the reference's
    `perspective: 1100px`). The pointer eases with a soft ease-out over
    280 ms, or tracks the finger with a 90 ms linear tween while pressed, and
    eases back to the gyro value on release. Depths (`TiltDepths`, scaled
    from the reference's 40–95 px to a phone card): photo **−24 dp** (behind
    the plate, so it slides the other way inside its frame like a view
    through a window, scaled up just enough that its farthest shift never
    shows an edge), plate · mileage line 16, server name 24, title 32,
    status chip 40, a tile's figure 28. Compose has no `translateZ`, so a
    layer at depth d is shifted by `d·sin(angle)` on each axis — where a
    point at that depth lands under the card's rotation — and scaled by
    `1 + d / 1100 dp`. The shine is `radialGradient(center = pointer, shine →
    transparent)` with a radius of 45 % of the card's diagonal, screen-
    blended on dark (`shine` 0x47FFFFFF) and plain over on light
    (0x8CFFFFFF; screen does nothing on a near-white panel); strength 1 while
    pressed, 0.45 while only the gyro moves the card, 0 when static, all
    faded over 280 ms. The plate is the glass panel fill plus
    `radialGradient(at 30 % 20 %, glow → transparent 55 %)` in the **vehicle's
    `VehicleTint`** (the cards) or the current aurora tint (content cards),
    at `plateGlowAlpha` 0.22 dark / 0.14 light — not the reference's pink —
    with the existing 1 dp `panelBorder` and lift shadow. The home card eases
    flat while the pager moves or for a page that isn't the settled one.
  - **Perf measures kept.** Nothing in a frame allocates: the rotation is
    read inside `graphicsLayer`, the parallax inside each layer's
    `graphicsLayer`, the shine and the plate brushes are built once per size
    in `drawWithCache` and a frame only translates the canvas. The shine is
    its own layer, so a frame of shine repaints a circle, not the photo and
    text under it; a rotation frame changes layer properties and redraws
    nothing. The pointer animates on `Animatable`s driven from a
    `snapshotFlow`, so a sensor tick or a touch recomposes nothing (D-A24's
    `animateFloatAsState` recomposed the card on every tick). Touch-only
    cards cost nothing while idle: strength 0 skips the draw and no
    animation runs. Reduced motion or not RESUMED → `TiltState.motion`
    false: no rotation, no parallax, no shine, no pointer input, exactly
    the static frame the other ambient effects show.
  - **Aurora everywhere.** `MainShell` draws its one `AuroraBackground`
    behind every route (same RESUMED / animator-scale guards, same ¼-scale
    layer, same 30 Hz clock), and `AppRoot` draws one behind the first-run
    and re-sign-in setup flows, which live outside the shell. The
    `AuroraTintHolder` still lets Home, Vehicles and the vehicle page name
    the car's color; where no screen does, the tab chooses: Service →
    `colorScheme.tertiary`, Fuel → the glass gold (`sheen`), More and all it
    opens (sections, Settings, servers, add a server) → `colorScheme.
    secondary`, else primary. With dynamic color on these follow the
    wallpaper. `AppScaffold` is transparent by default now, so Settings,
    Servers, Service, Fuel, More and the seven sections' `SectionScaffold`
    lost their opaque surfaces without touching each screen; `FormScreen`
    (all ten editors) and the setup `StepScaffold` draw no surface and put
    their fields on one `GlassPanel`, with the save / action bar on the
    frosted panel fill; the Welcome step's three features sit on a panel.
    `GroupCard` is now `GlassCard` and `GroupHeader` is `GlassGroupHeader`,
    so every row list in the app is a frosted card under a metallic header;
    Service, Fuel and the sections run in a `MetallicSheenHost` so those
    headers glint like Home's. Service's `PrimaryTabRow` is transparent.
    Sheets, dialogs and menus stay opaque.
  - **Contrast.** Rows keep `onSurface` / `onSurfaceVariant` text, which on
    the frosted fill over the page color clears 4.5:1 in both themes (dark:
    E3E2E9 / C5C6D0 on ≈ #14161F; light: 1A1B21 / 44464F on ≈ #E8EAF0, about
    7:1). Text that sits directly on the aurora rather than on a panel —
    section labels, empty / can't-reach / loading states, the Welcome
    title — gets `TextStyle.onAurora`: a 12 px halo in the page color
    (`textHalo`, 70 % dark page / 70 % white) behind the glyphs, so a bright
    ribbon under them can't take the contrast away. Metallic headers are
    unchanged (the halo would be tinted by their `SrcIn` gradient); they
    were already accepted over the aurora on Home. No new strings.
- **D-A31: Only the photo tilts, and only to the gyroscope; the home card gets
  a labelled chevron, a "View all" pill, Add / Update buttons, the insurer's
  icon and a radial fan FAB; the long-press menu moves onto glass.**
  - **Tilt, 60 % of D-A30, photo only, gyro only.** `MAX_TILT_DEGREES` is
    **8.4°** (was 14°), the depths are `TiltDepths.Photo −14 dp`, `Subtitle
    14 dp`, `Title 19 dp` (were −24 / 24 / 32; `Line`, `Chip` and `Value` are
    gone with the layers that used them), and the shine's centre travels
    **60 %** of the frame (`SHINE_TRAVEL`) for the full pointer range, with
    the same 45 % radius and 0.45 gyro strength. The tilting element is now
    `TiltPhotoFrame` (`ui/glass/TiltParallax.kt`): a `MetallicFrame` that
    rotates, shines and provides `LocalCardTilt` to the photo and the name
    inside it. It is used on the home card, the vehicles-list card and the
    vehicle page's photo card; the card around each is a flat `GlassCard` /
    `GlassPanel` in the vehicle's tint, so the rows, buttons and the drop-down
    never rotate. The "At a glance" tiles are plain `GlassPanel`s with the
    aurora-tint plate glow. `TiltParallaxCard` / `TiltParallaxListCard` are
    deleted. The finger is no longer a pointer source: `Modifier.tiltPointer`,
    the pressed fast-track (90 ms linear) and `CardTilt.press / release` are
    removed, `GlassPanel` lost its `shine` parameter and `GlassCard` /
    `GlassDropdown` keep the plate glow only. A phone without a rotation
    sensor has static frames. Kept unchanged: the 0.015° deadband, the slowly
    tracking resting pose, registration only while RESUMED and in the tree,
    `motion = false` under reduced motion (flat, no shine), the 280 ms
    ease-out, `cameraDistance 7`, the home pager's "settled page only" rule
    and the `Animatable` + `snapshotFlow` path that recomposes nothing per
    tick.
  - **"Quick info" toggle** (`QuickInfoToggle`, home card): a full-width
    48 dp row with the text "Quick info" / "Kurzinfo" in `labelLarge` and a
    **28 dp** `ExpandCircleDown` ring chevron, both in `tokens.sheen`,
    right-aligned under the plate line; the chevron turns 180° on a
    `spring(StiffnessMediumLow)`, `snap()` under reduced motion. TalkBack:
    one button, state from `form_expanded / form_collapsed`, click label
    "Show / Hide details". The old 24 dp `IconButton` is gone.
  - **Radial fan FAB** (`ui/glass/FanFab.kt`, bottom-left of Home at 16 dp
    from the left and from the bottom-bar padding): a **56 dp** glass core
    (panel fill over 35 % page colour, 1 dp `panelBorder`, `shadow` lift) with
    a 28 dp "+" in the sheen that turns **135°** to an ×; open, the core takes
    the nav blob's `navBlobStart → navBlobEnd` gradient, `navBorder` and a
    14 dp `navGlow` shadow, the glyph `onAccent`. Three **46 dp** petals
    (48 dp touch targets) spring out to **76 dp** from the centre at **80°,
    40°, 0°** (counter-clockwise from "right": up, up-right, right), each
    scaling from 40 % and fading in, `spring(dampingRatio 0.62, stiffness
    380)`, staggered **45 ms** opening (first petal first) and **30 ms** closing
    (last petal first). 76 dp rather than the asked-for ~72 so the 48 dp
    targets sit 52 dp apart and don't touch. Reduced motion: everything
    snaps, no fade. Petals: **Log service** → `RecordFormRoute(vehicleId)`,
    **Log expense** → `ExpenseFormRoute(vehicleId)`, **Log fill-up** →
    `FuelEntryRoute(vehicleId)`, all for the vehicle in the pager, wired in
    `MainShell` through the new `DashboardActions`. Core label "Log quick
    expense" / "Schnell erfassen" with expanded / collapsed state; each petal
    is a button named after its action and is only in the tree while
    visible. A `FanScrim` (`tokens.veil`, tap = cancel) sits between the
    content and the fan; Back closes it. The fan fades out (scale 0.6) and
    closes while the pager scrolls, and is not shown at all when the vehicle
    in view can't be edited or there is no vehicle. The home column's bottom
    padding grew 24 → 96 dp so the last card scrolls clear of the core.
    Nothing in a frame recomposes: petal positions are read in `offset {}`
    and their scale / alpha in `graphicsLayer {}`.
  - **"View all" pill + glass menu.** `GlassPillButton` (`ui/glass/
    GlassMenu.kt`): the panel's `pill` fill and `pillBorder`, label in the
    sheen, optional trailing icon; 48 dp tall, or `compact` (36 dp pill in a
    48 dp `minimumInteractiveComponentSize` target, ripple clipped to the
    pill). The home card shows it right of the plate · mileage line as
    **"View all" / "Alles anzeigen"** with a `ChevronRight`; it fires
    `HapticFeedbackType.Confirm` (the shortest distinct platform pattern; a
    `LongPress` buzz would read as "held") and opens the vehicle page.
    Tapping the photo opens the page too; long-press keeps the menu. The
    menu is now `GlassMenu`: a centred `Popup` (focusable, Back and outside
    tap dismiss) over the photo holding a `GlassPanel` on 94 % page colour
    (D-A30 kept menus opaque for contrast, and the panel alone over a bright
    photo would not clear 4.5:1), a `MetallicText` header with the car's
    name as shown (nickname when set), a divider and 48 dp `GlassMenuItem`
    rows with 22 dp sheen icons: change / upload photo, rename, open vehicle
    — same actions and gates as before. It springs in from 90 % (snap under
    reduced motion). The rename dialog is a `GlassAlertDialog` (page colour
    container, `panelBorder`, `radiusLarge`, metallic title) with
    `glassTextButtonColors` (sheen) and `glassTextFieldColors` (sheen focus
    ring and cursor, panel border at rest). The vehicles list and the
    vehicle page get the glass menu through the shared `VehicleCardMenu`.
  - **Insurer app icon.** `rememberInsurerIcon(provider)` (`ui/links/
    InsurerIcon.kt`) resolves the package through `InsuranceApps.packageFor`,
    rasterises `PackageManager.getApplicationIcon` at **24 dp** on
    `Dispatchers.IO` once per package into the process-wide `InsurerIcons`
    cache, and returns null when there is no match or the app isn't
    installed. The home card's insurance row shows it left of the provider
    name only when the launch intent exists (so no load for an app that
    isn't there); the vehicle page's insurer tile shows the same badge. No
    icon: the rows are exactly as before.
  - **Add / Update.** Every `DetailLine` of the drop-down ends in a compact
    `GlassPillButton` for people with `canEdit`: **"Update"** when the row
    has an entry, **"Add"** when it hasn't (`action_update` /
    `action_add`, already in both languages). Insurance → `InsuranceFormRoute
    (vehicleId, policyId?)`, inspection → `InspectionFormRoute(vehicleId,
    inspectionId?)`, mileage → `OdometerSheet` ("Add" only when there is
    neither an odometer log / fill-up date nor a reading above 0). Viewers
    see no button. The row's own tap (insurer app, odometer sheet) is kept,
    but the clickable and the merged TalkBack description now sit on the
    icon + text part, so the button is its own accessibility node rather
    than being swallowed by the row.
  - New strings (en + de): `card_quick_info`, `card_view_all`,
    `fab_quick_log`, `fab_log_expense`. No new dependencies; `core/`, `di/`
    and the build files are untouched.
  - **2026-10-22, after the phone test: gyro response cut to 20 %, "View all"
    moves down.** `MAX_TILT_DEGREES` **8.4° → 1.68°**. A layer's parallax is
    `depth · sin(angle)`, so that cut alone takes the parallax to 20 %; the
    depths were still trimmed to 40 % rather than 20 % (`TiltDepths.Photo
    −14 → −6 dp`, `Subtitle 14 → 6 dp`, `Title 19 → 8 dp`) because at 20 %
    the shift would be ≈ 0.08 dp, i.e. nothing, while 40 % lands at ≈ 0.18 dp,
    still under the old 20 % mark. `SHINE_TRAVEL` **0.6 → 0.24** (40 %, not
    20 %: the shine follows the pointer, not the angle, and at 0.12 the
    45 %-radius blob would barely be seen to move). Deadband, resting pose,
    RESUMED-only registration, reduced-motion flattening, 280 ms ease-out and
    the "settled page only" rule are unchanged. The **"View all"** pill left
    the plate · mileage row and is now its own **centred** row between the
    details (plate · mileage line and status chip) and the "Quick info"
    toggle, with 8 dp above and below; the plate / mileage column now spans
    the card's width, the Quick info row is still full-width beneath. Same
    48 dp target, `Confirm` haptic and vehicle-page navigation. No new strings.
  - **2026-10-22, after the phone test: the fan FAB moves to the bottom-right
    and goes opaque in the car's colour.** `FanFab` is aligned `BottomEnd` at
    16 dp from the right and the bottom (was `BottomStart`, 16 dp from the
    left); the petals' angles are mirrored to **180°, 140°, 100°** (left,
    up-left, up), same 76 dp radius, stagger and spring, so the fan opens
    toward the upper left and every 48 dp target stays inside the 160 dp
    square (and the screen) at any font scale. The frosted `panel` fill is
    gone from these controls: core and petals are **solid**, derived from the
    aurora tint in effect (`LocalAuroraTint.current.color`, i.e. the car in
    view; theme primary when nothing has set one) by `fanPalette`. Core =
    tint made opaque over the page, blended **35 %** toward `pageBackground`
    on dark / **25 %** toward white on light; petals a step the other way
    (15 % / 45 %). Each fill is pushed further the same way in 10 % steps
    until the light ink (`onPhoto`) or the new dark ink **`onPale`**
    (`0xFF1B2130`, shared by both token sets like `onPhoto`) clears
    **4.5:1** on it, whichever is better — a mid-tone car colour clears it
    with neither on its own. The 1 dp border is solid too, the fill 30 %
    toward its ink. Fills and inks cross-fade with `tween(800)`, matching the
    aurora, and snap under reduced motion. Open state (135° turn, nav-blob
    gradient and `navGlow`, `onAccent` glyph), the `FanScrim`, Back, the
    hide-while-scrolling and edit-only rules, actions, labels and strings are
    unchanged.
- **D-A32: UX round 3 — cards bubble in, the Vehicles tab gets "Quick info",
  the aurora's colour is decided by the destination, no boxes inside glass,
  and the fan FAB gets captions, a fourth petal and depth.**
  - **Bubble pop-in** (`ui/glass/PopIn.kt`): `rememberPopIn()` once per page
    (called inside the loaded branch, so the window starts with the cards)
    and `Modifier.popIn(state)` on every card. A card composed within the
    first **600 ms** grows from **60 %** of its size at its own centre on
    `spring(dampingRatio 0.6, StiffnessMediumLow)` with alpha reaching 1 at
    40 % of the way; all at once, no stagger, no slide. Scale and alpha are
    read in `graphicsLayer {}` only (nothing recomposes per frame); a card
    composed later (a lazy item scrolled in) and every card under reduced
    motion (read synchronously through the new `isReducedMotion(context)`,
    since `rememberReducedMotion()`'s first frame is always `false`) appears
    at once and takes no layer. Applied to the vehicle page (photo card,
    owner line, every `GlassDropdown`, the service / papers / fuel / history
    cards and the empty-group cards; headers are text and don't pop) and to
    the Vehicles tab's list cards. The **nav transitions** in `MainShell`
    make room for it: entering `VehicleDetailRoute` or `VehiclesRoute` is
    `EnterTransition.None` and the screen being replaced exits with
    `ExitTransition.None` (the page is simply there, the cards pop); leaving
    one of them by back or tab, it fades out in **120 ms** over the screen
    beneath, which is there at once. Every other navigation keeps NavHost's
    700 ms cross-fade. The aurora's own 800 ms colour cross-fade is untouched
    (it is the background, not a card).
  - **"Quick info" on the Vehicles tab.** `QuickInfoToggle`, `QuickInfoDetails`
    and `DetailLine` moved from `DashboardScreen` into `feature/vehicles/
    QuickInfo.kt`, with `QuickInfoActions` (add / edit insurance and
    inspection) that `DashboardActions.quickInfo` adapts to and the shell
    wires for the Vehicles tab. Each list card has the toggle under the
    plate · mileage line and status chip, the same rows (insurer icon,
    inspection, mileage, Add / Update pills) and the odometer sheet. The
    per-vehicle policy, inspection and last reading come from a
    `DashboardViewModel` scoped to the tab's entry (`hiltViewModel()` in
    `VehiclesScreen`), so the two cards share one source without touching
    `VehiclesViewModel`; a `VehiclesUiState` field would be the tidier home
    if the second `GarageMath.dashboard` ever shows up in a profile. The
    drop-down keeps `animateContentSize` on both screens — it drops down,
    it does not pop.
  - **Aurora colour per destination.** Bug: Service / Fuel / More sometimes
    stayed in the Home car's colour. `AuroraTintHolder`'s owner-token clear
    (D-A26) is the only thing that took a screen's tint away, and it runs in
    the outgoing screen's `DisposableEffect`, i.e. only once NavHost's
    `AnimatedContent` drops that entry — deferred to the end of the 700 ms
    cross-fade and skipped altogether when the entry is kept composed (an
    interrupted transition, predictive back, a tab's `restoreState`
    bringing the same entry back mid-fade). The tint then outlived Home.
    Now `MainShell` decides from the destination: `auroraTint.color` counts
    only on Home, Vehicles and `VehicleDetailRoute` (`honoursScreenTint()`);
    every other destination draws `fallbackTint(destination)` — Service
    tertiary, Fuel the glass gold, More and what it opens secondary — the
    moment `currentBackStackEntryAsState` changes, through the same 800 ms
    cross-fade. What is drawn is written to the new `AuroraTintHolder.shown`
    (`SideEffect`), and `effective` (`shown ?: color`) is what `GlassCard`'s
    default plate glow and the fan read, so plates, fan and aurora agree on
    every tab. Outside the shell (setup flows) `shown` is null and nothing
    changes.
  - **No boxes inside glass cards.** Gone: the four "At a glance"
    `GlassPanel` tiles (now icon + label over the value straight on the
    card, two per row with a divider between the rows); the `pill` icon
    badges on the vehicle page's paper and modification rows (plain 22 dp
    sheen icons, as the quick-info rows have); the `surfaceContainer`
    surfaces around the fill-up form's kept receipt photo, the document
    form's chosen file (plain rows, 20 dp icon) and the service form's part
    editor (a divider above each part instead); and `StatStrip` (Fuel's
    "This year") is a frosted `GlassPanel` with the figures straight on it
    rather than an opaque Material box. Kept: `GlassPillButton`s (View all,
    Add / Update — they are buttons), the owner-line pill (it sits on the
    aurora, not in a card, D-A26), the colour swatch, the status chips and
    dots, and the Material `IconBadge`s in Settings / More / section rows
    (icon avatars, not boxes around values).
  - **Fan FAB.** Still bottom-right. Four petals at **180° / 157° / 133° /
    106°** on a **124 dp** radius (was three at 180/140/100 on 76): the
    arc stays inside the upper-left quadrant, neighbouring 48 dp targets
    are 49 / 52 / 58 dp apart, and the petal centres are 48.5 / 42 / 28.5 dp
    apart vertically so the captions never share a line at font scale 1.3
    (a `labelMedium` line is ≈ 21 dp; equal 30° steps would put the top two
    13 dp apart). The touch square is 184 dp; captions may draw beyond it.
    Each petal has a **1 dp connector** running **12 dp** left from its edge
    and a caption 6 dp further left — "Service", "Expense", "Fuel", "Scan
    Receipt" (de "Service", "Ausgabe", "Tanken", "Beleg scannen") in
    `labelMedium`, glass text colour with the on-aurora halo, the line that
    colour at 60 % — which scales and fades with the petal from its right
    edge on the same stagger; TalkBack keeps the petal as the one node with
    the full verb. The **fourth petal** (`DocumentScanner`) navigates to
    `FuelEntryRoute(vehicleId, scan = true)`; `FuelEntryScreen(scanOnOpen)`
    reads the flag from the entry and, once the form has loaded, starts the
    D-A27 scan when `canScan`, else shows the existing "not available" (or
    view-only) snackbar — once, saved across rotation. **Depth:** core and
    petals draw a linear gradient **10 % lighter** top-left → fill → **10 %
    darker** bottom-right plus a white radial highlight peaking at **16 %**
    just up-left of centre (`Modifier.bead`, brushes in `drawWithCache`,
    rebuilt only while the 800 ms tint fade runs); the open core's accent
    gradient sits between the two. `fanPalette`'s 4.5:1 search now tests
    the ink against the disc's lightest point (light end + highlight) and
    its darkest, not the flat fill.
  - New strings (en + de): `fab_petal_service`, `fab_petal_expense`,
    `fab_petal_fuel`, `fab_petal_scan`. No new dependencies; `core/`, `di/`,
    `scripts/` and the build files are untouched.
- **D-A33: vehicle-card footer on one line, a stacked "Quick info" bubble,
  and the tapped card never pops.**
  - **Footer** (`VehicleCardFooter` in `feature/vehicles/QuickInfo.kt`, on the
    home card and the Vehicles-tab cards): one `Row` — the mileage on the
    left, the "View all" pill exactly centred (the two sides take an equal
    `weight(1f)`), the "Quick info" bubble on the right, all vertically
    centred. The plate moved to its own line above the status chip (it no
    longer shares a line with the mileage). The Vehicles cards gain the same
    "View all" pill (opens the vehicle, as the card tap does) so both cards
    read alike.
  - **"Quick info" bubble** (`QuickInfoToggle`): the label stacked over the
    28 dp ring chevron; the whole bubble (label, chevron and padding, at
    least 48 × 48 dp) is one clickable, its ripple clipped to a
    `radiusMedium` rounded shape. No longer full-width.
  - **No pop on the tapped card.** The vehicle page's photo card no longer
    takes `Modifier.popIn`: it is at full scale and alpha from its first
    frame, and only the cards beneath it grow in. The ~¼ s blink also came
    from `LoadedContent`'s Crossfade: the page's state starts unloaded, so
    the spinner showed for a frame or two and then cross-faded into the
    content. The page now draws nothing while `!loaded && !firstLoad` for up
    to **300 ms** (`SPINNER_HOLD_MS`; a slower load still shows the
    spinner), so the content opens with no cross-fade. Nav transitions are
    unchanged (already `None` in both directions into the page). A true
    frame-1 hero would need the detail ViewModel to seed its initial state
    from the cached vehicle — left for a logic change.
