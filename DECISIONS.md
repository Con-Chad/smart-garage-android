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

- **D-A34: the vehicle card glides to the vehicle page; the page's cards
  slide up under it as one drawer; Quick info slides open.**
  - **Why it snapped.** The Home card sits under the "Vehicles" group header
    (`GlassGroupHeader`, 24 + 12 + title + 12 + 8 dp, about 80 dp) and is
    taller (plate, chip, footer, maybe Quick info); the page's photo card is
    the first list item straight under the top bar. Same width (16 dp margin
    + 12 dp inset), same 16:9 photo and corner (`radiusMedium`), so the jump
    was the y offset plus the height change, shown on frame 1 with nav
    transitions `None`.
  - **Hero.** `SharedTransitionLayout` wraps the shell's NavHost; Home,
    Vehicles and the page get the entry's `AnimatedContentScope` through
    `LocalNavAnimatedScope` (ui/glass/VehicleHero.kt). Home's `VehicleCard`
    and the page's `PhotoCard` carry `Modifier.vehicleHero(id)`: a
    `sharedBounds` keyed `vehicle-card-<id>`, `tween(320 ms,
    FastOutSlowInEasing)` (no overshoot), `scaleToBounds(FillWidth,
    TopCenter)` so content keeps its own size (the photo never stretches),
    clipped in the overlay to the plate (16 dp side margin, `radiusLarge`),
    so as the bounds shrink Home's footer slides up under the card's edge.
    The arriving card's content is at full alpha from the first frame
    (`enter = None`); the leaving card's content fades in 120 ms. The two
    photos are in the same place, so the photo never reads as transparent
    and never scales. Off under reduced motion.
  - **Drawer.** The `popIn` scale bubble is gone from the page. Every item
    under the photo (owner line, drop-downs, headers, cards) takes
    `Modifier.drawerSlide`: translationY from where the Home card's bottom
    edge was (handed over by the tap through `VehicleHeroOrigins`) to its
    place, driven by `transition.animateFloat` on the nav entry's own
    transition with the same 320 ms tween, so card and drawer start on the
    same frame and end together; opaque by 40 % of the way. Opened any other
    way (Vehicles tab, a section, a push) it rises 48 dp. Going back it runs
    in reverse. Under reduced motion everything is simply in place.
  - **Nav.** The screen the page opens over now fades out in 120 ms
    (`NAV_POP_OUT_MS`) rather than `None`: the drawer keeps the transition
    running for 320 ms, and without the fade the old screen would show
    through the transparent page. Every other transition is unchanged.
  - **Quick info** on Home and Vehicles cards is `AnimatedVisibility(expand
    Vertically + fadeIn / shrinkVertically + fadeOut)`. The cards'
    `animateContentSize` went with it (it lagged a frame behind the
    expanding child).
  - The Vehicles tab cards keep their D-A32 pop-in and are not a hero (a
    scale pop on a shared element would break full-scale-throughout).

- **D-A35: the vehicle page opens on its content in its first frame.**
  - **Why.** The hero glide and the drawer (D-A34) need `detail` when the
    page first composes. The page's ViewModel started empty and combined
    eleven cache flows (decoded on `Default`), so the vehicle landed a frame
    or more late: the page was blank (D-A33's 300 ms hold), the hero didn't
    glide, and the cards appeared in place. `syncInfo` also started at the
    default `SyncInfo()`, whose `firstLoad` is true, which could put a
    spinner frame in front.
  - **Seeding.** `VehicleDetailSource` (feature/vehicles) combines everything
    the page is built from (vehicles, schedules, templates, records, fuel,
    expenses, papers, custom fields and values, modifications, formatter,
    track-fuel-economy, account name) once and shares it like the resources
    (`WhileSubscribed(5 s)`, replay dropped on stop). Home and the Vehicles
    tab fold its `keepWarm` (emits once at once, never re-emits) into their
    state, so while they are on screen the page's inputs are live in memory.
    `VehicleDetailViewModel.state` is `stateIn(initialValue =` the state
    built from `source.latest`, the replay read synchronously`)`; the live
    flow replays the same inputs, yields an equal state and `StateFlow`
    drops it, so the seeded frame is never replaced and `LoadedContent`
    never cross-fades. Later changes are rebuilt on `Default` (the build
    used to run on the main thread). The build is a pure
    `vehicleDetailState()`, unit-tested. `SyncedViewModel.syncInfo` starts
    from `SyncControl.latest` the same way, for every screen.
  - **Unseeded opens** (a deep link, a cold start, nothing observing) get
    `latest == null`, start unloaded and keep D-A33's behaviour exactly:
    empty while the cache answers, the spinner after `SPINNER_HOLD_MS`
    (kept, now only reachable on that path).
  - **Cost.** Frame 1 builds the state once on the main thread: list
    filters, the due-items pass and two sorts over one garage's rows,
    well under 1 ms for a few hundred rows (estimated, not profiled).
    While Home or Vehicles is on screen, five more resources (records,
    expenses, custom fields, values, modifications) stay observed and
    decoded on `Default`. The photo comes from the same `PhotoLoader` /
    Coil memory cache as the home card's, unchanged.

- **D-A36: the bottom bar is liquid glass; "View all" is a two-line pill.**
  - **Bottom bar** (`MorphingBottomNav`): the opaque `navSurface` block is
    replaced by the cards' plate (`glassPlate`, now `internal` with a `fill`
    parameter): a translucent panel tint at ~72 % (`navSurface` dark
    `#B8141622`, light `#B8FFFFFF`; a little denser than the cards' 42 / 55 %
    so the inactive icons keep >= 3:1 over a bright aurora or pale car glow),
    the aurora-tint plate glow, the 24 dp inset top highlight and 1 dp top
    line, and the panel's 1 dp border (`navBorder` = `panelBorder`). Dark
    `navInactive` lifted `#8A91A8` -> `#A3AABF` for the same reason. The blob
    gains a 1 dp `panelHighlight` edge; it stays an opaque gradient, so the
    selected icon / label on it keep their contrast whatever is behind. No
    backdrop blur (none in the project, see `GlassPanel`). Floating pill
    shape, blob spring (snaps under reduced motion), insets, layout and
    TalkBack semantics unchanged; MainShell untouched. Content does not
    scroll under the bar (Scaffold padding), so the glass shows the aurora.
  - **"View all"** (`VehicleCardFooter`, home card and Vehicles-tab cards —
    one shared footer, so both): new `GlassStackedPillButton` in
    `GlassMenu.kt` — "View" over "all ›" (de "Alles" / "anzeigen ›";
    `card_view_all_line1/2`), labelLarge at 1.15 em line height, 14 dp
    rounded rect, min 48 x 48 dp, same pill fill / border / sheen / ripple;
    TalkBack reads `card_view_all` once. Outer padding 8 -> 4 dp, the Quick
    info bubble's side padding 10 -> 4 dp (still >= 48 dp), its label
    `softWrap = false`, mileage `maxLines = 1`, so mileage and "Quick info" /
    "Kurzinfo" each fit one line at 1.3x on a 360 dp phone (estimated, not
    screenshot-verified). Home card's Confirm haptic is in the caller,
    unchanged.

- **D-A38: car-card footer pills share one height; mileage above the status chip; silver, slower title sheen.**
  - **Footer** (`VehicleCardFooter`, home card and Vehicles-tab cards): the
    mileage moved out of the footer into the details column, directly above
    `VehicleStatusChip` (bodyMedium `MetallicText`, one line). The footer is now
    a custom `Layout` holding just "View all" and "Quick info": both pinned to
    the taller pill's intrinsic height (~54 dp at 1.0x), "Quick info" at the
    end, "View all" centred unless that would leave < 8 dp to "Quick info", in
    which case it slides left (large fonts / German), so both stay on one line
    at 1.3x on a 360 dp phone (estimated, not screenshot-verified). Padding
    16 / 12 dp sides, 4 dp top, 12 dp bottom (visible pills need room).
  - **"View all"** (`GlassStackedPillButton`): sides 12 -> 16 dp, min width
    48 -> 80 dp, inner surface `fillMaxHeight()` so it takes the pinned height.
  - **"Quick info"** (`QuickInfoToggle`): now on the glass pill surface (same
    pill fill, 1 dp pill border, 14 dp corners as "View all"; ripple clipped),
    side padding 4 -> 12 dp, chevron 28 -> 24 dp. New shared
    `Modifier.glassPillSurface(tokens, shape)` in `GlassMenu.kt`.
  - **Title sheen** (`MetallicSheenHost` / `metallicSheen`, every animated
    `MetallicText`): sweep 2952 -> 3247 ms (+10 %), hold 648 -> 1510 ms; the
    band's off-text time between glints 3.18 -> 4.29 s (+35 %), loop 3.6 ->
    4.76 s. Band colour is a new `sheenBand` token (muted cool silver: dark
    `#DDE1E8`, highlight `#FFFFFF`; light `#7E8594`, highlight `#A4ABB8`),
    replacing the gold `sheen` / `sheenHighlight` in the band. `sheen` stays
    gold for pill labels, icons and focus rings.

- **D-A37: the vehicle page's cards pop in one after another; the hero ramps up.**
  - **Staggered pop** (`PopIn.kt`, `rememberPopIn(staggered = true)`, vehicle
    page only): every item under the photo card (owner line, drop-downs,
    group headers, cards) pops top to bottom, each starting 150 ms after the
    one above *started* (the literal reading of "0.2 s expand, 0.15 s delay";
    checked frame by frame on the Pixel 10 Pro and kept; the
    gap-after-finish variant, ~350 ms per card, was not tried). Each: invisible until its slot,
    scale 0.8 -> 1.03 in 200 ms (`CubicBezierEasing(0.2, 0.6, 0.35, 1)`),
    back to 1.0 in a 90 ms FastOutSlowIn settle; alpha 0 -> 1 in 120 ms.
    Slots are claimed in composition order (the visible items compose top to
    bottom in the first pass), never in the past: an item scrolled in during
    the 600 ms window queues behind the others instead of waiting for a slot
    far down the list; after the window items appear at once. Off-screen
    items are not composed, so they hold nothing up. Runs on top of the
    D-A34 drawer slide (separate `graphicsLayer`s, alphas multiply). The
    Vehicles tab keeps the D-A32 all-at-once spring (default `staggered =
    false`).
  - **Hero easing** (`VehicleHero.kt`): `FastOutSlowInEasing` ->
    `CubicBezierEasing(0.6, 0, 0.2, 1)` — a visible ease-in ramp from rest,
    then the same soft landing; shared by the `sharedBounds` glide and
    `drawerSlide`, so both stay in sync. HERO_MS stays 320; MainShell
    untouched.
  - Reduced motion: no pop, no glide, no slide (unchanged paths).
  - *Superseded in part by D-A40 (user-set):* stagger 150 -> 100 ms; on
    opening, the hero now starts 400 ms after the drawer. Scale, alpha,
    window and easing values unchanged.

- **D-A39: Compose stability config; list screens compute off the main thread.**
  - `app/compose-stability.conf` (wired in `composeCompiler {}`) marks the
    immutable `java.time` value types and the read-only `kotlin.collections`
    interfaces stable, so models holding them (Vehicle, DueItem, TimelineEvent,
    the UiStates) compare by `equals` and an equal re-emission skips instead of
    recomposing. Safe because UI state is always rebuilt, never mutated in place.
  - Compiler reports are opt-in: `./gradlew :app:compileReleaseKotlin
    -PcomposeReports=true` -> `app/build/compose_compiler/`.
  - Dashboard, Vehicles and Fuel state, Maintenance's filter-independent prep,
    and History's timeline merge run under `flowOn(Dispatchers.Default)`.
    Anything that combines the search query stays on the main thread (the
    query is bound to a TextField; an async round trip drops keystrokes), so
    only the cheap filter/search step runs there.

- **D-A40: content under the glass tray; one motion system; outside-only glass shadows.**
  - **Under the tray** (`MainShell`, `AppScaffold`): the NavHost draws full
    height; the shell's bottom-bar height (nav bar included) goes down as
    `LocalBottomBarInset`, which `AppScaffold` unions into its
    `contentWindowInsets`. Tab lists take `padding.calculateBottomPadding()`
    (+ `FAB_CLEARANCE`) as `contentPadding`; FABs and snackbars sit above the
    tray; Home's fan is padded by it; empty states stop above it. The shell
    no longer consumes the inset: Scaffold *excludes* consumed insets from
    its own, which cancelled the tray back out. Top bars unchanged; pushed
    screens (no tray) get the plain nav-bar inset as before.
  - **Motion tokens** (`ui/glass/Motion.kt`, `object Motion`): durations
    (QUICK 120, SHORT 200, MEDIUM 260, STANDARD 280, HERO 320, NAV_FADE 700,
    TINT 800, SPIN 900), the D-A37 pop values (stagger now 100, window 600,
    expand 200, settle 90, fade 120), `HERO_DELAY_MS` 400, easings
    (`EmphasizedEasing` = (0.6, 0, 0.2, 1) for hero + drawer, `PopExpandEasing`,
    `SettleEasing`) and springs (bouncy 0.55/300 nav, pop 0.6/MediumLow
    Vehicles pop + fan core, petal 0.62/380, gentle 0.75/MediumLow menu +
    drop-down size, standard chevrons, press 600). Used by the hero, drawer,
    pop-ins, FanFab, bottom nav, GlassMenu, GlassDropdown, Quick info,
    DisconnectedPopup, tilt settle, aurora tint, nav transitions. Small
    drift: fan core damping 0.65 -> 0.6, popup 220/300 ms -> 200/320 ms.
  - **One reduced-motion check**: `rememberReducedMotion()` now reads the
    setting synchronously (right on frame 1, so the once-only pop/hero
    decisions use it too) and again on resume; `isReducedMotion` is private.
    `orSnap` / `orNone` switch specs and transitions off. Now also honoured
    by nav transitions, the tray's show/hide, tab tint/lift/label and the
    disconnected popup.
  - **Press feedback** (`Press.kt`): `rememberPressFeedback(interaction)` +
    `pressScale` + `pressGlow` (white wash, 10 %), read in layer/draw only.
    Pills (`GlassPillButton`, View all, Quick info via `glassPillSurface(...,
    press)`) shrink to 0.96; fan core/petals 0.92; tab icon+label 0.9;
    tappable `GlassPanel`s keep the D-A24 lift (1.02, now also for
    long-press-only panels) and gain the wash. Reduced motion: wash only.
  - **Vehicle page opening** (user-set): the drawer slides up first
    (`Motion.HERO_MS`, no delay); the hero card starts 400 ms later and rises
    off the cards it covered (720 ms total). The pop stagger runs from the
    first frame with the drawer, so the cards below the card are already
    popping while it waits and those under it are revealed settled; starting
    the pops with the hero would leave the drawer invisible for 400 ms. The
    delay is chosen in a direction-aware `BoundsTransform` on both hero
    sides, so Back stays simultaneous (card and drawer down together in
    320 ms; checked frame by frame on the Pixel).
    *Superseded in part by D-A41 (user-set):* 400 ms was far too long; the
    card now starts 80 ms before the drawer's slide ends
    (`HERO_DELAY_MS = HERO_MS - HERO_OVERLAP_MS` = 240 ms, 560 ms total).
    Easing, stagger and pop values unchanged.
  - **Green boxes removed**: the lighter rectangle behind card text was the
    platform elevation shadow's penumbra seen through the translucent glass.
    `GlassPanel` and the bottom-bar pill now use `glassShadow` (a
    BlurMaskFilter copy of the shape, offset down half the elevation, drawn
    only outside the shape). Opaque surfaces keep `Modifier.shadow`.
- **D-A41: the remaining screens on the glass kit; skeleton loading; card pills beside the mileage; shorter hero delay.**
  - **Scope**: Service, Fuel, the More sections (`SectionScaffold`), More,
    Settings, Profiles, setup, every form (`FormScreen` / `FormSheet`) and
    every detail sheet now use the glass kit instead of tonal Material
    surfaces. Dark theme only was reviewed; the light tokens apply unchecked.
    No ViewModel changed (D-A39 stands); behaviour is the same.
  - **New kit pieces** (`ui/glass/GlassControls.kt`): `GlassIconBadge` (pill
    fill + border, sheen icon; `IconBadge` delegates to it, its
    container/content params became one `tint`), `GlassBottomSheet` (page
    colour under the panel plate, top highlight, aurora glow, panel border,
    no shadow: a sheet covers content, so like menus it is opaque),
    `GlassButton` (sheen fill, `onPale` ink, >= 7:1; disabled = pill),
    `GlassOutlinedButton` (pill fill/border, sheen label), `GlassFilterChip`
    (pill; selected = sheen tint + border + check), and colour sets for
    switches, segmented buttons, list items and the date picker. All press
    via `rememberPressFeedback` + `pressScale`. `glassTextFieldColors` now
    also covers icons, placeholder, supporting and disabled, and every form,
    search and setup field uses it. `GlassAlertDialog.title` is nullable.
  - **Replaced**: section rows' tonal badge -> the vehicle page's 22 dp sheen
    icon; Fuel's opaque primaryContainer average -> `GlassPanel` with a
    metallic figure; expense category bars -> pill track + sheen fill;
    `SyncBanner` opaque strip -> a glass strip with tone glow and tinted icon
    (Home shows it too); setup `StatusPanel` / server cards -> tone wash +
    border or the pill surface (they sit inside the form panel); Service tabs
    -> sheen indicator and labels; Profiles' DropdownMenu -> `GlassMenu`,
    its extended FAB -> `AddFab`; every AlertDialog in scope ->
    `GlassAlertDialog` (`ConfirmDialog` too, so the vehicle page's confirms
    match). Labels (`SectionLabel`, `FormSection`, `FormDisclosure`) take the
    sheen instead of the scheme primary.
  - **Empty states**: icon in a glass circle + one line (the existing title)
    + an action only where no FAB already offers the verb (D-A20: one "Add"
    per screen): Add schedule (Reminders, Service upcoming) and "Clear
    search" when a search / tag found nothing. The action is `EmptyAction`,
    the solid `GlassButton`: a translucent pill's sheen label failed contrast
    over a bright aurora on the Pixel. The old explanatory bodies were
    dropped (several still said "on the web") and their strings deleted in
    en + de; `EmptyState.body` is optional (Home and Vehicles still pass one).
  - **Also fixed on the device**: More, Settings and Profiles scrolled their
    rows under the see-through top bar (text through the title); their lists
    now start below it. Settings' unit rows no longer run label into value.
    Charts default to the sheen and glass greys (they were the scheme blue
    beside sheen category bars). The search field has a frosted fill so its
    hint reads over a bright aurora. `GlassBottomSheet` draws its glass and
    drag handle inside the sheet content: the sheet's own modifier sits on a
    full-screen wrapper and clipped the sheet.
  - **Skeleton loading** (`ui/glass/Skeleton.kt`): `SkeletonList` (group
    header bars over glass cards of icon / two-line / value rows, with the
    real rows' paddings) for the list tabs and sections via
    `LoadedContent(skeleton = true)`; `FormSkeleton` (field-shaped bones)
    for every editor that loads its record. One infinite transition per
    skeleton, read only in the bones' draw lambdas; static under reduced
    motion or off screen. `Motion.SHIMMER_MS` = `SPIN_MS` (no new timing).
    The first sync's caption stays above the bones. Home, Vehicles and the
    vehicle page keep `LoadingState` (out of scope; the vehicle page opens
    on content anyway).
  - **Car-card pills** (user-set): `VehicleCardFooter` takes the card's
    details as a slot: mileage over the status chip on the left, "View all"
    and "Quick info" beside them at the end, top-aligned, one height; the
    plate (and "Shared by") stay above. The row under the details is gone,
    so both the Home and Vehicles cards are shorter.
  - **Hero delay** (user-set): see the D-A40 note; `Motion.HERO_OVERLAP_MS`.

- **D-A42: code-health split of the three largest screens (no behaviour or visual change).**
  - **Vehicle page** (`feature/vehicles`): `VehicleDetailScreen.kt` (1024 -> 492
    lines) keeps `VehicleActions`, the screen and the list; `VehicleDetailCards.kt`
    (photo card, owner line, "At a glance" tiles), `VehicleDetailSections.kt`
    (the "Vehicle details" drop-downs, `detailSections`, modification rows),
    `VehicleDetailRows.kt` (`StatStrip`, `RecordRow`, `PaperRow`, `EmptyGroup`),
    `VehicleDetailSheets.kt` (the detail-sheet `when`, now
    `VehicleDetailSheets(target, onClose, …)`; it owns `rememberAddToCalendar`).
  - **List keys**: every fixed `item {}` on the vehicle page has a stable string
    key and a `DetailItem` content type; the details drop-downs are keyed
    `"section_<key>"`. Order is unchanged, and the D-A37/D-A40 stagger is claimed
    in first-composition order (`PopInState.claimDelay`), which is still top to
    bottom, so the 100 ms pop-in sequence is the same.
  - **Sections** (`feature/sections`): `SectionScreens.kt` (847 -> 35) keeps
    `SectionActions` and the `SectionScreen` switch; one file per section
    (`RemindersScreen`, `HistoryScreen`, `OdometerScreen`, `ExpensesScreen`,
    `DocumentsScreen`, `NotesScreen`, `WishlistScreen`); `byMonth` moved to
    `SectionParts.kt`.
  - **Home** (`feature/dashboard`): `DashboardScreen.kt` (644 -> 413) keeps the
    screen and content; `DashboardVehicleCard.kt` (card + pager chevron),
    `DashboardParts.kt` (odometer reminder row, all-caught-up card, `Avatar`).
  - Moved symbols went `private` -> `internal` only where another file needs
    them. Dead code removed: `GlassTokens.metalText`, `relativeDays` (both
    unused), and the unused imports in the touched files. Kit consolidation:
    the sheen text buttons (Home's odometer "Update", `GlassGroupHeader`,
    the disconnected popup's Retry) use `glassTextButtonColors()`;
    `GlassIconBadge` draws with `glassPillSurface`. No string was unused (en
    and de match). `Motion.kt` untouched.

- **D-A43: vehicle-page motion retune, reverse pop on Back, header pill, seamless sheen, owner name, pill ink.**
  - **Opening (user-set)**: the hero starts rising `Motion.HERO_DELAY_MS` = 60 ms
    after the drawer (was 240, D-A41; 380 ms in all). Opened from a Home card the
    cards no longer ride the drawer: each pops at its final spot, the first at
    `POP_WITH_HERO_MS` (= the hero's start), then the 100 ms stagger, and is drawn in
    the shared-transition overlay above the hero (`renderInSharedTransitionScopeOverlay`,
    z 1, only while arriving), so the hero rises into place above them and never hides
    them. Opened any other way, the 48 dp drawer slide stays.
  - **Back**: the page's cards pop out together (`rememberPopOut`): 1 -> 1.03 in
    `POP_OUT_BULGE_MS` 70, -> 0 in `POP_OUT_SHRINK_MS` 150, fading below 0.35 scale,
    while the hero glides home; the drawer stays put; the page's top bar and banner fade
    with them; the NavHost pop-exit for the vehicle route is `None`. Only on a pop
    (`isPopped` from the shell), not when a form is pushed over the page. Reduced motion:
    none of it.
  - **Hero "shadow flash"**: the hero was scaled to bounds under an overlay clip, which
    cut its outside-only shadow for the glide and let it snap back at the end. Now
    `ResizeMode.RemeasureToBounds` (plate, border and shadow drawn at the gliding bounds
    every frame), content at natural height via `HeroContent` and clipped by the plate;
    the page card leaves at once on Back (no doubled fill), and the Home card's shadow is
    off while it fades out on open (`heroShadowOff`). `CardPlateClip` is gone.
  - **Home header pill**: "Swipe to view more ›" (taps to the next vehicle, wrapping)
    with 2+ vehicles, "Add vehicle +" (the vehicle form) with one; `GlassGroupHeader`
    takes a `trailing` slot, `GlassPillButton` a `textStyle` and `contentColor`;
    labelMedium so it fits beside the title at font scale 1.3.
  - **Sheen**: the gradient tiles (`TileMode.Repeated`, both ends dim steel) and one loop
    slides it exactly one period (gradient length / cos 20°), so the loop's last frame is
    its first; timing (3247 + 1510 ms) and the silver band unchanged, centred as before.
  - **Owner name**: long-press the owner pill (haptic, TalkBack long-click action "Change
    owner's name") opens a glass dialog; stored on the phone only
    (`VehicleLocalPrefs` `ownerName.<id>`), blank or Reset shows the server's name. A tap
    still does nothing.
  - **Pill ink**: "View all", "Quick info", the header pill and every group-header
    action are white (`onPhoto`) instead of the gold sheen, switching to `onPale` when
    white would fall under 3:1 on the estimated backdrop (`pillInk` in ui/glass/GlassInk:
    the resolved aurora tint's main ribbon at the ribbons' mean opacity over the page,
    under the pill fill; follows a user-chosen theme colour). 3:1 rather than 4.5:1 by
    request, so white stays on mid-tone cars (green estimates ~3.4:1).

- **D-A44: user-chosen aurora (background) color, and a local greeting name.**
  - **Storage** (device-only, no API or Room change): `AuroraChoice` (`MatchVehicle`
    | `Custom(argb)`, stored as "match" / "#RRGGBB"). App-wide default in
    `AppSettings.aurora` (key `aurora_color`, default match). Per-vehicle
    override in `VehicleLocalPrefs` (`auroraColor.<vehicleId>`, next to the
    nickname); absent = follow Settings, explicit "match" = keep the car's color
    even when Settings picked one.
  - **Resolution** (`AuroraTintResolver.resolve`, unit-tested): vehicle override ->
    Settings choice -> the car's color (`VehicleTint`) -> theme primary. Picked
    colors are clamped to HSL lightness 0.28..0.70 (0.70 = the steel white cars
    already get), so the aurora never blinds or vanishes behind text.
  - **Where**: the shell provides `LocalAuroraChoices` (from `ShellViewModel`); Home,
    Vehicles and the vehicle page call `AuroraTint(vehicleId, vehicleColor)`. A
    Settings pick also replaces the per-tab fallback tints (one background app-wide);
    "match" keeps the D-A30 per-tab rooms. The vehicle form route now honours a
    screen tint and previews the pick live. Card glows still use the car's color.
  - **UI**: `AuroraColorSetting` + `GlassColorPicker` (ui/glass): 12 preset swatches
    (`AuroraPresets` in GlassTokens), a Canvas hue/saturation wheel with TalkBack
    custom actions, a brightness slider. Settings debounces drags (250 ms). The
    form's "Use app setting" drops the override; an edit where only the aurora
    changed writes no server patch. The aurora tint cross-fade snaps under reduced
    motion.
  - **Temp ids**: a vehicle created offline stores its prefs under the temp id;
    `OutboxReplay.adopt` calls `onVehicleAdopted` (wired by `SyncEngine` to
    `VehicleLocalPrefs.moveVehicle`), which moves every `<prefix>.<tempId>` key,
    any prefix and type, to the server id (nickname, owner name, snooze, aurora).
  - **Greeting name**: `ServerProfile.localName` (Settings > Account, "Your name"),
    applied by `Account.withLocalName` in `MainShell` and the Settings header, so
    Home's greeting and avatar initial use it with no dashboard edit. Blank falls
    back to the server display name, then the username.

- **D-A45: the Home card's drawer collapses instead of blinking out; first pop 200 ms after the hero starts.**
  - **Cause** (frame-stepped screenrecord, 120 Hz): opening a vehicle, the page's
    hero card was drawn above the Home card at full alpha, and the Home card (whose
    footer is the "drawer" under the photo) left on a 120 ms `sharedBounds` exit fade
    from frame 0. The footer was gone by ~80 ms, while the bounds (60 ms delay, slow
    ease-in) had barely moved, leaving an empty plate that collapsed afterwards.
  - **Fix**: the leaving Home card draws over the page's card (`zIndexInOverlay`
    0.5; the page's cards stay above at 1) with no plate, border or shadow of its
    own (`heroPlateOff()`, new `plate` flag on `GlassPanel`/`GlassCard`). The page
    card underneath supplies the single fill and shadow (D-A43 no-flash holds); the
    Home photo and footer ride on top at full alpha, clipped by the gliding bounds,
    so the drawer visibly collapses on the hero's own curve. The Home card fades only
    over the last `QUICK_MS` of the glide (`Motion.HERO_END_MS` = 380 ms), onto the
    page's identical photo. Back is unchanged (Home card entering, page card snaps out,
    reverse pop).
  - **Timing**: `POP_AFTER_HERO_MS` 100 -> 200 (user-set): first pop at 260 ms. On
    device the last sliver of the drawer clears the frame before the owner line first
    shows (~280 ms), and the hero is within a pixel or two of rest, so there is no
    overlap and no dead gap; kept at 200.
  - Reduced motion: no shared element, so `heroPlateOff()` is false and nothing changes.
  - **Title placement shared**: the Home, Vehicles-tab and vehicle-page photo cards now
    all draw the name through `VehiclePhotoTitle` (feature/vehicles), the Home/Vehicles
    placement (16 dp sides, 28 top, 12 bottom). The page's card was the odd one out
    (20/48/16), which put its "Tessa" ~4 dp off the Home card's and showed it doubled for
    2–3 frames at the end of the cross-fade; verified gone frame by frame on the Pixel.
  - **What the hero leaves behind fades with it**: opening the vehicle page, the screen
    beneath (Home's greeting, headers, other cards, the fan FAB) and the bottom tray
    fade out over the whole glide on its curve (`Motion.UNDER_HERO_FADE_MS` =
    `HERO_END_MS`, `underHeroFadeSpec()`), and fade back in over the same span on Back
    while the page's cards pop out. Before, the screen faded in `QUICK_MS` and the tray
    shrank away. The tray fades as one layer (AnimatedVisibility alpha, no shrink or
    expand for this route) and keeps the last tab selected while it is off a tab, so the
    selection bubble no longer drops on frame 1 and re-morphs on return. Reduced motion:
    none of it animates.
- **D-A46 (2026-10-23) — No photo shine; pop-in only on the hero path; plain slide for every other navigation; adaptive ink on the aurora.**
  - **Photo shine removed**: the gyro-following radial shine over the car photo
    (`tiltShine`, `CardTilt.shine`, tokens `shine`/`shineBlend`) is gone; the gyro tilt and
    parallax stay, and the header text sheen (`MetallicSheenHost`) is unchanged.
  - **Pop-in only from Home**: the vehicle page's staggered pop runs only when the page is
    opened from a Home card (tap or "View all", `VehicleHeroOrigins.isLaunching`/`take`).
    Opened any other way (Vehicles list, push, section) or returned to by Back, nothing
    pops and the cards don't slide (the 48 dp fallback drawer is gone). The Vehicles tab
    keeps its D-A32 bubble on a tab arrival but not on a Back return (`NavArrivals`: the
    new top is the entry that sat just below the old one). Back to Home is still the hero
    reverse (pop-out + glide, D-A43); Back anywhere else slides.
  - **Plain transitions (Motion)**: push = slide in from the right by `NAV_SLIDE_FRACTION`
    0.3 of the width + fade in, `NAV_PUSH_MS` 300 on `SlideInEasing` (emphasized
    decelerate), the page beneath fades out in `NAV_UNDER_FADE_MS` 200; Back = slide out to
    the right + fade, `NAV_POP_MS` 260 on `SlideOutEasing` (emphasized accelerate), the page
    beneath fades in over the same 260. Tab to tab keeps the `NAV_FADE_MS` cross-fade.
    Reduced motion: no transition (instant).
  - **Adaptive ink** (`ui/glass/AuroraInk.kt`): titles of transparent top bars (incl. the
    greeting and its sync line), forms' titles, group headers and their "See all",
    `SectionLabel`, and Home's pills ("Add vehicle", "View all", "Quick info") take their
    colour from the aurora behind them. Text on cards keeps its card colour.
    - The aurora renders its own scene (same code path) into a 12×96 software bitmap on
      `Dispatchers.Default`, averaged over 4 drift phases, once per palette/size/theme
      (`AuroraLumaHolder`, `LocalAuroraLuma`). No GPU readback.
    - Each ink node (`Modifier.auroraInk`, a Modifier.Node) learns its root position in
      `onGloballyPositioned`, samples top and bottom bilinearly (pills composite their
      fill, and the card panel when inside a card; a scrolled top bar its panel), and
      eases toward the result with a first-order low-pass (`Motion.INK_SMOOTH_MS` 140 ms
      time constant) on a frame loop that runs only while settling. Draw phase only: an
      offscreen layer, glyphs drawn, then one rect `SrcIn` with a reused light→dark
      `LinearGradient` re-aimed by a local matrix (a tall block shades across the
      gradient; a flat colour when top ≈ bottom). No recomposition, relayout or
      allocation per frame. Animated metallic titles draw their sheen band `SrcAtop`
      over the ink.
    - Mapping (`AdaptiveInk`, unit-tested): light ink `onPhoto`, dark ink `onPale`. *(The band,
      contrast targets and hysteresis below are superseded by D-A48: one continuous mapping,
      grey span 0.32–0.50 centred on L 0.40, no hysteresis.)*
      Text: the 4.5:1 limits (light up to L 0.166, dark from 0.243) leave a gap no ink
      bridges, so the white→grey→black band is 0.024 wide around the crossover
      (L ≈ 0.20, where both contrast equally; ≥ 4:1 at its edges, ≥ 4.5:1 outside the
      gap). Pills: 3:1 with white preferred (D-A43), band ending where white stops
      clearing 3:1. Smoothstep on ln(L+0.05).
    - **Grey is passed through, never rested on**: on the Pixel a mid-green aurora sat
      right at the crossover and left a whole line grey on green. So each edge settles on
      light or dark with hysteresis (`settle`, switching 30 % into the band), and the
      low-pass carries the ink through grey as the backdrop crosses over; blocks shorter
      than 56 dp take one decision from their centre. Verified frame by frame scrolling
      the vehicle page ("Vehicle details" white → grey → dark, eased).
- **D-A47 (2026-10-23) — Slower header sheen (user).** `SHEEN_SWEEP_MS` 3247 → 5412 (−40 % speed), `SHEEN_HOLD_MS` 1510 → 2265 (+50 %), loop 7.68 s (b90fa6c).
- **D-A48 (2026-10-23) — Adaptive ink: continuous grey, crossover raised to L 0.40; cheaper placement path.**
  - **Look (user-approved, replaces D-A46's band + hysteresis)**: one mapping for text and pills,
    `AdaptiveInk.mix`: light ink up to backdrop L `BAND_LO` 0.32, dark from `BAND_HI` 0.50,
    smoothstep on ln(L+0.05) between (centre `CENTER` 0.40, ≈ ×1.22 either side on the contrast
    axis), so the ink fades white → grey → dark continuously and may rest on grey in mid tones;
    no `settle`/hysteresis. The 140 ms low-pass, first-sample-lands-at-once and the single value for
    blocks under 56 dp stay. Chosen from the Pixel's green aurora (luma column 0.02–0.54): all of
    Home's aurora text is white; the vehicle page's "Vehicle details" goes dark only in the lightest
    band (~40 % down) and fades through grey to white below it (screenrecord frame-step). Contrast
    targets (4.5:1 / 3:1) no longer drive the ink; `AuroraInkSpec` lost `minContrast`. Mid-grey ink
    on a mid-tone backdrop is low-contrast by design (e.g. "Add vehicle" pill reads light grey).
  - **Perf**: the ink node re-reads its observed inputs (map, theme, overlay, density) and the band
    only when they change (`refreshInputs`), not on every placement; a scroll only samples. The
    frame loop repaints (re-rendering the node's offscreen layer) only once the eased ink moves half
    an 8-bit step (`SNAP`). The top bar's overlay reads `overlappedFraction` through a
    `derivedStateOf` threshold flag, so scrolling under it no longer notifies the title's ink every
    frame. Measured (release, Pixel, framestats over 12 scripted swipes): the ink costs ~0.1 ms of
    UI-thread draw recording per scroll frame on Home and ~0.08 ms on the vehicle page, nothing on
    RenderThread/GPU; janky ≤ 0.1 %, p99 10–11 ms on Home / vehicle / Service, same as ink off.
- **D-A49 (2026-10-23) — Dark-grey adaptive ink; vehicle page bar title fades, bar has no bottom edge.**
  - **Ink (user)**: the adaptive ink's dark end (text and pills alike, `auroraInk` and the composed `pillInk`) is a
    new shared token `GlassTokens.inkDark` #474B54 (L 0.070) instead of `onPale` #1B2130, which keeps its other uses
    (FAB on a pale car, switches, picker). D-A48's mapping is unchanged (white ≤ L 0.32, grey between, dark ≥ 0.50,
    centre 0.40). #474B54 clears 4.6:1 at L 0.50 (where the ink first turns fully dark), 4.9:1 on the Pixel's lightest
    green aurora (L 0.54), 7.7:1 on the light theme's page; on the composed pill estimate for a white car (L ≈ 0.24)
    neither ink reaches 3:1 (white 2.86, grey 2.82). Pixel: "Vehicle details" in the lightest band renders ≈ #4E525B.
  - **Bar title (vehicle page)**: the name still switches on `firstVisibleItemIndex >= 1` (derivedStateOf), but now
    fades over `Motion.SHORT_MS` (200 ms, FastOutSlowIn) via animateFloatAsState read in a graphicsLayer (draw phase);
    composed only while alpha > 0; reduced motion snaps. Screenrecord: 0 → 1 over ~12 frames at 60 fps, and back.
  - **No bar edge (vehicle page only)**: `AppScaffold(softBarEdge = true)` turns off the bar's own scrolled colour and
    draws the glass panel itself, feathering out over `GlassTokens.barFeather` (28 dp) below the bar (eased stops,
    alpha animated on the overlapped flag, draw phase). The list's straight clip under the bar is hidden too:
    `Modifier.fadingTopEdge` erases (DstOut) its top 28 dp once scrolled; offscreen layer only while scrolled.
    Other screens keep the plain bar.
  - **No flip back to white near the top (user follow-up)**: the cause was the aurora map itself: the luma map darkens
    toward the top (ribbon 0 plus the dark top rows), so content text sampled its own row and went white again as it
    rose, not because of the bar glass, the feather or the edge fade (content specs carry no overlay). Now
    `AppScaffold` provides its content an `AuroraInkCeiling`: content scrolled under the bar samples at the bar's lower
    edge, and `AuroraLumaMap.holdY` makes content above a column's lightest band take the lightest row between it and
    the band (precomputed per map, 12 × 96). The held value only grows as text rises, so there is no jitter. Bar titles
    still ink by what is behind them. Screenrecord: "Vehicle details" stays dark grey all the way up and under the bar.
- **D-A50 (2026-10-23) — Insurer autocomplete in the insurance form.**
  - **List**: `ui/links/InsuranceProviders.names`, 62 built-in names (52 US incl. a generic "Farm Bureau" for the
    state bureaus besides Texas Farm Bureau; 10 German: HUK-COBURG, Allianz, ERGO, DEVK, R+V, AXA, Generali, VHV,
    Signal Iduna, HDI), brand-cased. It is the one list for suggestions; icons still come from
    `InsuranceApps.packageFor` on the same name (a test checks every `InsuranceApps` entry resolves from a listed
    name). No new packages/manifest `<queries>` were added, so icons show only for the 14 apps already in the table
    and only when installed; others get the Shield glyph so names line up.
  - **Filter** (`InsuranceProviders.suggest`, unit-tested): lower-case, punctuation folded to spaces, a leading
    "The" ignored on both sides (but "the"/"t" alone still reach The General/Hanover/Hartford as word-start hits).
    Tier 0 = the folded name starts with the query (also with spaces dropped: "rv" → R+V, "hukcoburg"); tier 1 =
    a later word starts it ("farm" → State Farm). Alphabetical by folded name within a tier. Mid-word never matches.
  - **UI**: new `FormAutocompleteField` (ui/components/forms/FormAutocomplete.kt): the field plus an inline glass
    panel under it (in the form's column, so it scrolls with the form and is brought into view above the keyboard;
    page colour at 94 % under the frosted fill, like the menus). ~6.5 rows (48 dp each, growing with font scale)
    then scrolls; "Other… / Keep “typed”" pinned at the bottom keeps the free text. Opens while focused with
    non-blank text and ≥ 1 match; closes on pick, Other, IME Done (field now uses Done, which also hides the
    keyboard), Back, focus loss; typing reopens. Expand + fade on `Motion.gentleSpring`/`SHORT_MS`, none under
    reduced motion. TalkBack: the panel is a pane titled "N suggestions" with collection info; rows are buttons.
    Saved values not on the list load and save unchanged (the ViewModel still holds a plain string).
- **D-A51 (2026-10-23) — Tab switches: quick fade-through, no pop-in; loading never cross-fades over fresh content.**
  - **Cause** (120 Hz screenrecord, stepped frame by frame, Home↔Vehicles, Home↔Service, Vehicles↔Fuel): the
    D-A46 700 ms cross-fade left two busy glass pages half-visible together for most of a second (Home's cards
    under Service's list, Fuel's empty state under the Vehicles card); into Vehicles the old page vanished in one
    frame (enter/exit None) and the cards bubbled up from an empty aurora (D-A32 replayed on every tab arrival);
    and the first visit to a tab cross-faded a "Loading…" spinner under the content for ~300 ms (ViewModel not
    seeded yet, `LoadedContent`'s Crossfade). The hero (sharedBounds) does not engage on tab switches (no matching
    key; the Home card fades with its page), and the aurora tint already eases (`TINT_MS` 800); ink low-passes.
  - **Fix (Motion)**: tab to tab (and Back from a tab to Home) is a fade-through, `navTabExit`/`navTabEnter`: the
    old page fades out in `NAV_TAB_OUT_MS` 80 (decelerating, so it drops fastest at once); the new one waits
    `NAV_TAB_IN_DELAY_MS` 60 (old page ≈ 5 % left) and fades in over `NAV_TAB_IN_MS` 180 while scaling from
    `NAV_TAB_SCALE_FROM` 0.98, LinearOutSlowIn; 240 ms in all. `NAV_FADE_MS` removed. Reduced motion: instant.
  - **No pop-in on tabs**: the Vehicles tab's bubble (D-A32) is gone; its cards arrive with the page.
    `NavArrivals` and `VehiclesScreen(popIn)` removed. The vehicle page's staggered pop on the hero path, the hero
    open/back (D-A43/45) and the pushed-screen slides (D-A46) are unchanged (re-checked on device).
  - **Loading grace**: `LoadedContent` swaps straight to the content (snap) when the cache answers within
    `Motion.LOADING_GRACE_MS` 250 of the screen appearing; a longer wait still cross-fades. Applies to every
    cache-backed screen. (Seeding the tab ViewModels' initial state would remove the loading frame entirely; that's
    a data-layer change, not done.)
  - Verified on the Pixel (release, screenrecord frame-step): Home↔Vehicles, Home↔Service, Vehicles↔Fuel each
    settle in ~150–270 ms with no frame showing both pages at strength, no bubble, no "Loading…" on a first visit.
- **D-A52 (2026-10-23) — Legible aurora ink, no sticky Save bar, colour swatches, plate on the photo, raised glass.**
  Unattended round; built, unit-tested and linted, not yet checked on the Pixel (no device attached).
  - **Adaptive ink by contrast** (`AdaptiveInk`, AuroraInk.kt): the white → dark-grey cross-fade is now centred on the
    equal-contrast point of `onPhoto` and `inkDark` (L ≈ 0.292, both ≈ 2.85:1) and narrowed to ±12 % on the contrast
    axis (`BAND_LO` 0.255, `BAND_HI` 0.333; was 0.32–0.50 around 0.40). D-A48's wide grey span left text resting at
    ~1.1:1 on bright bands ("Inspection and insurance" over the purple/pink band); D-A48's 0.40 is now fully dark.
    Outside the span the ink is whichever end contrasts more (unit-tested). Still continuous (no hysteresis), still
    the 140 ms low-pass, darkest ink still #474B54, the D-A49 bar-edge ceiling / holdY unchanged.
  - **Halo where 4.5:1 isn't reachable**: between L ≈ 0.17 and 0.50 neither ink reaches 4.5:1 with a grey dark end,
    so the ink node now also eases the backdrop luminance and, when the drawn ink falls short of
    `AdaptiveInk.MIN_CONTRAST` 4.5, draws a soft halo under the glyphs: the content recorded once into a
    GraphicsLayer, redrawn blurred (`HALO_BLUR_DP` 2.5) and tinted `DstOver` in the opposite tone (new tokens
    `inkHaloDark` black / `inkHaloLight` white, blended by the ink mix), opacity `0.7 × haloStrength`
    (0 at ≥ 4.5:1, full at ≤ 2:1, linear). No halo, no extra layer, where the ink already reads. Halo is clipped to
    the text's own bounds (the node's offscreen layer).
  - **Frosted quick actions** (`ActionButton`, the vehicle page's four quick actions and Add inspection / Add policy):
    new `frost` token fill (dark #262A3A @ 78 %, light white @ 85 %) with a top gloss, a 1.5 dp bevelled rim
    (`rimLight` → `rimShade`, new `ui/glass/Bevel.kt`: `bevelRim`, `bevelFill`) and a 4 dp `glassShadow`; label in
    `textPrimary` (was the stock outlined button's `primary`, the grey labels in the screenshot), icon in the sheen;
    press shrink + glow; disabled at 50 %.
  - **Owner pill**: same raised treatment — panel fill under the gloss, 1.5 dp bevelled rim, 6 dp `glassShadow`.
  - **"Add vehicle" pill** (Home header, `VehiclesHeaderPill(emphasized = true)`): fixed white label/icon (no adaptive
    ink), fill `emphasisPillFill(tint)` = the aurora tint with HSV value capped at 0.34 at 88 % (≥ 4.5:1 for white over
    the lightest bands, a visibly stronger tint than the glass pill), 1.5 dp `onPhoto` @ 60 % rim. `GlassPillButton` /
    `glassPillSurface` take an optional `fill` and `border`. "Swipe to view more" unchanged.
  - **No sticky Save bar** (`FormScreen`, every full-screen editor): the frosted bottom bar that rode up with the IME is
    gone. Save is a compact sheen pill (check + "Save", spinner while saving) at the end of the top bar, always
    reachable; the full `SaveBar` (validation line + big button) sits inline at the end of the form's panel. The
    scrolling column now does `consumeWindowInsets(padding).imePadding()`, so focused fields and the autocomplete panel
    come into view above the keyboard. Setup steps (`StepScaffold`) likewise: their action row moved inline at the end
    of the panel. `FormSheet` (odometer) unchanged — its save already scrolls inline.
  - **Vehicle colour** (`CarColorPicker`): a single-select grid of 13 paint swatches with names (radio semantics,
    `selectableGroup`; tap the selected one to clear). Stores the swatch's English name ("Blue"), which `VehicleTint`
    already parses; localised names also match on load. A saved value that is none of them is kept and shown first as
    its own selected swatch. Paint colours in `CarPaints` (GlassTokens.kt); strings `car_color_*` (EN/DE).
  - **Plate on the photo**: `VehiclePhotoTitle(plate = …)` puts a `PlateBadge` (light plate, dark 1.5 dp rim, bold
    spaced caps, small shadow, ≤ 160 dp, TalkBack "License plate, …") bottom-right beside the name on all three photo
    cards (Home, Vehicles tab, vehicle page) so the hero cross-fade lines up. Removed: the plate line under the Home and
    Vehicles-tab photos and the plate tile in the vehicle page's "At a glance" drop-down (now three tiles, the last full
    width). The "Registration" group under Vehicle details still lists it.

- **D-A53 (2026-10-23) — Real receipt scanner: fuel and service receipts prefill their forms.**
  Unattended round; built, unit-tested and linted, not yet checked on the Pixel or against a live scanner server.
  Contract: server `docs/mobile/RECEIPT-SCANNER.md` (D-042 fuel, D-044 service).
  - **Client** (`ApiReceiptScanner`, `core/network/ReceiptScanner.kt`, bound in `di/ReceiptScannerModule.kt` in place of
    the D-A27 stub): `scan` → `POST /api/v1/fuel/receipt-scan`, `scanService` → `POST /api/v1/records/receipt-scan`,
    multipart `vehicleId` + `file` (the `CameraCapture` JPEG) through `ApiClient.postMultipartJson`, i.e. the active
    profile's connection with the device token, Cloudflare Access headers, no redirects, a fresh `Idempotency-Key`.
    `postMultipartJson` returns the JSON body for any status, because the 502 carries the saved photo's top-level
    `documentId` that `ApiResult.HttpError` would drop. Per-request timeout 45 s (above the server's 25 s model
    timeout plus upload). Status mapping in the pure `ApiReceiptScanner.interpret` (401/403/404/413/415/422/429 with
    `Retry-After`/502 with `documentId`; anything else or a malformed 200 → `Unreachable`). Nothing is logged; the AI
    key stays on the server.
  - **Features**: `MeFeatures.receiptScannerKinds` (nullable) → `scannerKinds()`: the listed known kinds, `["fuel"]`
    when an older server omits the field but says `receiptScanner: true`, empty when off. Stored in
    `ServerFeatures.receiptScannerKinds`; `scansFuel` (null kinds = a profile stored before D-A53 → fuel) gates the
    fill-up button, `scansService` the service form's.
  - **Fuel form**: `FuelFormState.withFuelDraft` fills date, volume, price/unit (or total ÷ volume), total, station,
    currency; grade and address go into the note; null draft fields keep the form's value; odometer keeps its prefill.
    `filledAt` uses the printed local time in the phone's zone while the form's date still matches it.
    `receiptDocumentId` is kept (also after a 502) and sent on save; switching vehicle drops it (the server would 404).
  - **Service form** (`RecordFormScreen`): "Scan receipt" (same button/photo row as fuel, shared in
    `feature/receipts/`) on new records only, when `scansService`. `withServiceDraft` fills date/time, odometer, total
    cost, vendor, note, currency and the parts (receipt items replace the schedule defaults), and preselects
    `suggestedScheduleId` unless the form is locked to a schedule. No match → the user picks in the existing picker
    (note "Pick the service schedule yourself"). Other recognised service types are listed ("Also on this invoice: …,
    log those separately with no cost"); we don't create those records. `receiptDocumentId` goes on `POST /records`;
    no `POST /expenses` (the server books the cost itself). A `fuel_receipt` answer drops the photo and points to the
    fill-up form.
  - **Expense hand-off**: after a service scan, "Save as expense instead" replaces the record form with the expense
    form via `ExpenseFormRoute(category, date, amountCents, currency, description ≤ 500, receiptDocumentId)`;
    `suggestedTarget == "expense"` adds a note saying so. `ExpenseInput.receiptDocumentId` is sent on save.
  - **UX**: spinner + "Reading the receipt…" while scanning (Save disabled meanwhile); a short info note lists fields
    with confidence < 0.8 ("Please check: Date, Total.") and the warnings in words (unknown codes ignored); a foreign
    receipt currency is named and sent with the entry. Snackbars: 429 "today's scan limit", 502 "couldn't be read;
    the photo is saved and will be attached", 404 "not available on this server", 403 view-only.
  - **Not done**: offline queueing of unscanned photos, gallery pick (the fuel flow is camera-only, kept so), creating
    missing schedules from `serviceTypes[].templateSlug`, and per-field highlighting (the note covers it).
- **D-A54 (2026-10-23) — Home's "Your vehicles" header on a glass bar, title without halo.**
  - The header row (title + "Swipe to view more" / "Add vehicle" pill) sits on a full-width rounded `GlassPanel`
    strip (card margin 16 dp, `radiusLarge`, panel fill/rim/shadow, min height 56 dp) via
    `GlassGroupHeader(onGlassBar = true)`; other group headers are unchanged.
  - On the bar the title is plain `Text` in `textPrimary` (the cards' text colour, 4.5:1 on the panel), not the
    adaptive aurora ink, so D-A52's opposite-tone halo is not drawn behind it. The halo stays for text straight on
    the aurora. The non-emphasised pill's adaptive ink now accounts for the bar's panel (`inCard = true`).
- **D-A55 (2026-10-23) — Real-looking plate badge, glass "Add vehicle" on the Vehicles tab, soft shadows while panels
  pop in, drawers that glide shut.**
  - **Plate badge** (`PlateBadge`, `VehiclePhotoTitle.kt`): light plate face inside a soft blue 1.5 dp outline, 3 dp in a
    thin inner border in the plate ink (60 %), a 4 dp blue band along the top, characters in the new `PlateTextStyle`
    (`ui/theme/Type.kt`: system `sans-serif-condensed` bold, 16 sp, tight, font padding trimmed; nothing bundled), ink
    ≥ 14:1 on the face, 4 dp shadow. 64–160 dp wide (≈ 2:1 to 2.6:1 for common plates). New shared tokens
    `plateFace` / `plateRim` / `plateBand` / `plateInk` in `GlassTokens`.
  - **Vehicles tab "Add vehicle"**: the Material extended FAB is replaced by `GlassEmphasisPill` (new, `GlassMenu.kt`):
    the D-A52 call-to-action pill (white label on `emphasisPillFill` of the aurora tint, 1.5 dp light rim), full 48 dp,
    with the glass lift shadow when `floating`. Home's header "Add vehicle" now uses the same component (`compact`).
    Other screens' `AddFab`s are unchanged.
  - **Soft shadows during pop-ins**: `PopIn.kt` fades now use `CompositingStrategy.ModulateAlpha`; the default offscreen
    layer was the card's bounds, so the glass shadow outside them was cut to a square-cornered rectangle while fading.
    Home's per-vehicle switcher (`AnimatedContent`, attention / fill-ups) no longer clips (`SizeTransform(clip = false)`)
    and its content carries 16 dp `SHADOW_ROOM` below the last card (taken off the page's bottom padding), so the last
    card's shadow is whole during and after the slide.
  - **Drawers** (`GlassDropdown`: the vehicle page's Registration / Colour / Specifications, At a glance, Quick
    actions): the body is an `AnimatedVisibility` inside the glass with new `drawerExpand()` / `drawerCollapse()`
    (`Motion.kt`: expand / shrink vertically on `EmphasizedEasing` over `HERO_MS`, fade over `SHORT_MS`; none under
    reduced motion), replacing the card's outer `animateContentSize`, under which the glass snapped shut on close.
- **D-A56 (2026-10-23) — Receipt scan from the gallery too; bills scanned on the expense form.**
  Built, unit-tested and linted; not run against a live scanner server.
  - **Two buttons everywhere a scan is offered** (fill-up, service record, expense): one shared composable,
    `ReceiptScanButtons` (`feature/receipts/ReceiptScanUi.kt`, replaces `ScanReceiptButton`): "Scan receipt" (camera,
    as before) and "Pick from gallery", side by side, equal width, 48 dp; while scanning both are disabled and the
    hint line becomes a spinner + "Reading the receipt…". Not split: the Home fan's "Scan receipt" petal (dashboard
    file, owned by the UI agent at the time); it still opens the fill-up form and starts the camera.
  - **Gallery**: `rememberReceiptGallery` = `ActivityResultContracts.PickVisualMedia` (ImageOnly): no storage
    permission, no Play Services (androidx falls back to the document picker on phones without the system picker;
    `check-no-gms.sh` stays clean). `ReceiptCapture.fromGallery` copies the picked image into `cache/captures/`
    (refused above 64 MiB), then the same `finish` as the camera: long edge ≤ 1600 px, JPEG 80, EXIF dropped, then a
    size check against the contract's limit (≤ 7 MB and ≤ `MAX_IMAGE_SIZE_MB` → `MAX_UPLOAD_BYTES` = 7 × 10⁶);
    upload, document, prefill and `receiptDocumentId` on save are the camera flow's. All three view models share one
    `scan(source)` path for camera and gallery; a second scan can't start while one runs.
  - **Expense form**: gated on `scansService` (`/me receiptScannerKinds` contains `service`) and edit rights. Uses
    `POST /records/receipt-scan`; `ExpenseFormState.withServiceScan` maps date (+ printed time for `occurredAt`),
    category from `expenseCategory` (unknown → form keeps its own), `totalCostCents`, currency, and a description of
    what the user typed + vendor + the server's note (no repeats, ≤ 500). `expenseReview` checks date/total/vendor
    and drops record-only warnings (schedule, odometer, item truncation). A fuel receipt is dropped with the
    existing "use the fill-up form" message; a 502 keeps the `documentId`. Switching vehicle drops the scan.
  - **Reverse hand-off**: when `suggestedTarget == "record"`, "Log as service record instead" replaces the expense
    form with `RecordFormRoute(vehicleId, scan = <response JSON>)` (`encodeScanHandoff`, model/quota stripped); the
    record form decodes it after loading and applies `withServiceDraft`, so it fills exactly as after its own scan
    (schedule match, parts, `receiptDocumentId`) and survives process death. No local photo there; a line says the
    scanned receipt is attached.
  - Strings (en + de) added; the two camera-only hints (`fuel_scan_hint`, `receipt_scan_service_hint`) were
    replaced by `*_hint_both` and removed as unused. Tests: `ExpenseScanTest`.
- **D-A57 (2026-10-23) — "Theme color" petal in the Home fan opens the vehicle's aurora picker in a sheet.**
  - The aurora picker (D-A44) was only in Settings and deep in the vehicle edit form; users couldn't find it. The fan
    (D-A31/D-A32) gets a fifth petal at the **bottom** of the arc: palette icon, caption "Theme color" (de
    "Designfarbe"), TalkBack "Change theme color". Same bead, connector, stagger and spring as the others.
  - Geometry: five slots, `ANGLES` 180/162/144/124/100°, `RADIUS` 124 → 160 dp, `EXTENT` 184 → 212 dp, elbow on the
    top petal only. Chords ≥ 50 dp; captions clear the petal below by ≥ 5 dp at font scale 1.3 (checked on device).
    Fewer actions take the lowest slots.
  - The petal opens `ThemeColorSheet` (feature/dashboard): `DetailSheet` + the shared `AuroraColorSetting` for the
    vehicle in view, with the vehicle form's rules (switch on = no override if Settings matches, explicit "match"
    otherwise; "Use app setting" drops the override). Applies live: switch/presets save at once, wheel/slider drags
    debounce 250 ms (as Settings); a pending drag is saved on close. Writes `VehicleLocalPrefs.setAuroraOverride` via
    `DashboardViewModel.setAuroraOverride` (thin pass-through).
  - View-only shared vehicles: the override is phone-only, so the fan now shows for them too, with only the Theme
    color petal (core label "Quick actions"); the logging petals still need edit rights.
- **D-A58 (2026-10-23) — Home fan: evenly spaced petals on a tighter arc.**
  - Feedback: the bottom petals sat ~40 % closer together than the top ones and the fan spread over half the screen.
  - `ANGLES` 180/162/144/124/100° → **190/163.75/137.5/111.25/85°** (equal 26.25° steps, every neighbour chord
    50.9 dp: ≈ 5 dp between 46 dp petals); `RADIUS` 160 → **112 dp**. Petals now span ≈ 47 % of a 376 dp Pixel width
    (was ≈ 60 %); with captions ≈ 72 % at font scale 1.0 / ≈ 78 % at 1.3 (was ≈ 91 % at 1.3).
  - The arc dips ≈ 15 dp below and ≈ 6 dp right of the core's box: the fan's box grows by `OVERHANG` (16 dp) below /
    right of the core and the whole fan is shifted back by it, so the core doesn't move and every target stays
    hit-testable. `EXTENT` 212 → 164 dp (+ overhang).
  - Captions stay to the left of their petal; connectors are per-slot (`RISES` / `ELBOW_ANGLES`): straight for the
    bottom three, 12 dp at 20° for "Fuel", 32 dp at 45° for "Scan Receipt", so each caption clears the petal it runs
    past and its neighbours by ≥ 5 dp at font scale 1.3 (checked on device at 1.0 and 1.3).
  - Limit: captions can't fit under half the width with five equally spaced 46 dp petals — the bottom "Theme color"
    caption must sit left of the leftmost petal (no room below it above the nav bar, or inside the arc). Halving that
    would need shorter / two-line captions or fewer petals.
- **D-A60 (2026-10-23) — Denser fan scrim; complementary divider under the shared header.**
  - Fan scrim: new `GlassTokens.fanScrim`, the page colour at 72 % (was `veil`, 60 %), dark `#B808090D`, light
    `#B8F2F4F8`. `veil` itself (the aurora's bottom veil) is unchanged.
  - Header divider: `ui/glass/HeaderDivider.kt`. A 1 dp (whole-pixel) opaque line drawn over the bottom edge of the
    top bar in `AppScaffold` (every tab and pushed screen) and `FormFrame`. Colour = `complementaryAccent(tint, dark)`:
    the aurora's effective tint (`LocalAuroraTint.effective`, else primary) with hue + `HeaderDivider.HUE_OFFSET`
    (180°), saturation clamped 0.65–0.85 (so steel still gets an accent), lightness 0.64 dark / 0.42 light; all
    knobs are constants on `HeaderDivider`. Cross-fades over `Motion.MEDIUM_MS` (snaps under reduced motion), read
    in the draw phase only, so no recomposition per frame; drawn in the pinned bar's own bounds, so it doesn't move
    on scroll. Not on the vehicle page (`softBarEdge`, D-A49 keeps its feathered edge) or the setup flows.
    Tests: `HeaderDividerTest`.
  - Follow-up: the divider is hidden (alpha 0) at the top of a page and fades in over `Motion.SHORT_MS` once content
    scrolls under the bar (`AppScaffold`: the existing `overlapped` derived flag; `FormFrame`: hoisted scroll
    state's `canScrollBackward` as a derived flag), or while Home's fan is open (`AppScaffold(showHeaderDivider)`).
    The alpha is read in the draw phase only.
  - The car card's buttons are title case in English: "Quick Info", "View All" (`card_view_all_line2` "All").
    German is unchanged ("Kurzinfo", "Alles anzeigen" follow German casing). Other Home labels ("Add vehicle", "See
    all") aren't on the car's card and stay sentence case.
- **D-A59 (2026-10-23) — "Scan document": an on-device document scanner on the Upload document form.**
  - A third button, "Scan document" (de "Dokument scannen"), sits beside "Choose file" and "Take photo" in
    `FilePickerField` (`onScan`); with three, each takes a third of the row with the icon stacked over the label
    (72 dp tall). With a file chosen, a scan icon sits next to the camera icon. Only the document form passes it.
  - **GMS-free, no new dependency.** Not the ML Kit Document Scanner (Play Services). No CameraX either (not a
    dependency): photos come from the system camera through `TakePicture` into `cache/captures/` as on the document
    form (D-A23), no CAMERA permission. No OpenCV/Tesseract. **No OCR**: there is no small GMS-free on-device text
    recogniser (Tesseract would add ~20 MB of native code plus language data), and the server's AI scan is
    deliberately not used — the scan is only an image/PDF.
  - Flow (`feature/documents/scan/`): the photo is made upright (EXIF), long edge 2400 px, kept in `cache/scans/`
    (swept after a day). The **corner screen** shows it with four draggable handles (nearest within 48 dp; outside
    the page shaded); start corners come from a cheap guess on a 256 px greyscale copy — Otsu threshold, the largest
    bright blob, its extreme points along the diagonals — accepted only if convex and ≥ 10 % of the photo, else 8 %
    inset. "Whole photo" / "Detect edges" toggles, "Retake" replaces the page. The **page list** shows each page
    flattened and filtered, with adjust corners, rotate 90°, move up/down, delete; "Add page" (≤ 20 pages); the
    filter chips Original / Greyscale (default; `ColorMatrix` saturation 0 + ×1.2 contrast) / B&W document
    (Bradley adaptive threshold, window ⅛ of the short edge, bias 12 %, integral image); and the **document name**
    (prefilled from the form's title, else "Scan yyyy-mm-dd"; ≤ 120, required).
  - Flattening: corners ordered TL/TR/BR/BL by angle round the centroid (so crossed drags still work), rotated by
    shifting the order, output size = the longer of each pair of opposite edges, long edge ≤ 2200 px, warped with
    `Matrix.setPolyToPoly` + bitmap filtering onto white.
  - Export: one page → JPEG (q 85); several → one PDF from a ~100-line writer (`JpegPdf`) that embeds each page's
    JPEG as-is (`/DCTDecode`), page box A4-wide (595 pt short edge, aspect kept), title in `/Info`.
    `PdfDocument` was not used: it re-encodes bitmaps losslessly, 5–10× larger. The file must fit the server's
    document rules (`lib/storage/paths.ts`: JPEG/PDF allowed, `MAX_UPLOAD_SIZE_MB` 25 → `FileRules`); if it
    doesn't, it is rebuilt at q 75 / 1700 px, then q 65 / 1300 px, else "couldn't be saved".
  - "Use scan" puts the file (named from the document name, unsafe characters → "-") into the form as the picked
    file and the name into the title; the user then taps Upload, so it goes through the same `addDocument`
    outbox/sync path as the other two buttons (the file sits in `cache/captures/` and is deleted once queued).
    The scan photos are deleted on export or discard ("Discard this scan?" on close).
  - The session (pages, corners, turns, filter, name, open page) is written to the form's `SavedStateHandle`
    (`ScanSession.encode`), so a multi-page scan survives the process being killed behind the camera app.
  - Tests: `ScanGeometryTest`, `ScanFiltersTest`, `JpegPdfTest`, `ScanSessionTest`.
