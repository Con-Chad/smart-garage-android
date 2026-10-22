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
  - Push-triggered sync waits for CP6 (UnifiedPush).
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
