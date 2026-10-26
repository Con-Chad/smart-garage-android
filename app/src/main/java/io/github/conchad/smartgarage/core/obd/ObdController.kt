package io.github.conchad.smartgarage.core.obd

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.BuildConfig
import io.github.conchad.smartgarage.core.cache.CacheProvider
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.data.VehicleLocalPrefs
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.network.ApiJson
import io.github.conchad.smartgarage.core.obd.ble.BleScanner
import io.github.conchad.smartgarage.core.obd.ble.BleTransport
import io.github.conchad.smartgarage.core.obd.ble.ScannedAdapter
import io.github.conchad.smartgarage.core.obd.data.ConnectionEvent
import io.github.conchad.smartgarage.core.obd.data.ObdDatabase
import io.github.conchad.smartgarage.core.obd.data.ObdDiagnostics
import io.github.conchad.smartgarage.core.obd.data.ObdReading
import io.github.conchad.smartgarage.core.obd.data.ObdSettings
import io.github.conchad.smartgarage.core.obd.data.ObdSettingsStore
import io.github.conchad.smartgarage.core.obd.data.ReadingState
import io.github.conchad.smartgarage.core.obd.data.RoomTripStore
import io.github.conchad.smartgarage.core.obd.data.SimulatorSettings
import io.github.conchad.smartgarage.core.obd.device.AdapterAssociation
import io.github.conchad.smartgarage.core.obd.device.AssociationStart
import io.github.conchad.smartgarage.core.obd.link.ObdAdapter
import io.github.conchad.smartgarage.core.obd.link.ObdTransportKind
import io.github.conchad.smartgarage.core.obd.link.SimulatorTransport
import io.github.conchad.smartgarage.core.obd.service.ObdForegroundService
import io.github.conchad.smartgarage.core.obd.trip.ObdError
import io.github.conchad.smartgarage.core.obd.trip.ObdSessionManager
import io.github.conchad.smartgarage.core.obd.trip.ObdSetup
import io.github.conchad.smartgarage.core.obd.trip.ObdStatus
import io.github.conchad.smartgarage.core.obd.trip.RawResult
import io.github.conchad.smartgarage.core.obd.trip.ReadNowResult
import io.github.conchad.smartgarage.core.obd.trip.TripStore
import io.github.conchad.smartgarage.core.obd.upload.ObdUploadWorker
import io.github.conchad.smartgarage.core.obd.upload.ObdUploader
import io.github.conchad.smartgarage.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The OBD feature's one entry point (D-A73), for the CP4 settings screens and for the services. Settings flows and
 * setters act on the active server profile; the background side (presence, service, uploads) uses the profile that
 * has an adapter set ([obdProfileId]: the active one when it has, else the first that does). Everything works with
 * or without [ObdForegroundService] running: the session manager lives on the application scope, the service only
 * keeps the process alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ObdController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileStore,
    private val settingsStore: ObdSettingsStore,
    private val database: ObdDatabase,
    private val cache: CacheProvider,
    private val writer: GarageWriter,
    private val sync: SyncEngine,
    private val vehiclePrefs: VehicleLocalPrefs,
    private val simulator: Optional<SimulatorTransport>,
    private val scope: CoroutineScope,
) {
    private val diagnostics = ObdDiagnostics()
    private val ble = BleTransport(context, diagnostics::log)
    private val scanner = BleScanner(context)
    private val association = AdapterAssociation(context)
    private val readings = database.readings()

    private val manager = ObdSessionManager(
        scope = scope,
        transports = { kind ->
            when (kind) {
                ObdTransportKind.BLE -> ble
                ObdTransportKind.SIMULATED -> simulator.orElse(null)
            }
        },
        setup = ::currentSetup,
        tripStore = ::tripStore,
        diagnostics = diagnostics,
    )

    // ── Status ───────────────────────────────────────────────────────────────────────────────────────────────

    /** Disconnected / Scanning / Connecting / Pairing / Connected / Reading / Error. */
    val status: StateFlow<ObdStatus> = combine(manager.status, scanner.scanning) { status, scanning ->
        if (scanning && (status == ObdStatus.Disconnected || status is ObdStatus.Error)) ObdStatus.Scanning else status
    }.stateIn(scope, SharingStarted.Eagerly, ObdStatus.Disconnected)

    /** Whether the connect/standby loop runs (the notification shows while it does). */
    val running: StateFlow<Boolean> = manager.running

    fun permissions(): ObdPermissionState = ObdPermissions.state(context)

    // ── Scanning and choosing an adapter ─────────────────────────────────────────────────────────────────────

    /** Adapters first, then by signal. */
    val scanResults: StateFlow<List<ScannedAdapter>> = scanner.results

    /** Null when scanning started, else why not (permission, Bluetooth off). Stops by itself after 30 s. */
    fun startScan(): ObdError? = scanner.start()

    fun stopScan() = scanner.stop()

    /** Whether CompanionDeviceManager is there; without it, [useWithoutAssociation]. */
    val companionAvailable: Boolean get() = association.available

    /**
     * Step 1 of choosing [adapter] (null = let the system dialog find one by name): launch the returned
     * [AssociationStart.Launch.intentSender], then pass the activity result to [onAssociationResult].
     * [AssociationStart.Unavailable] / a declined dialog: offer [useWithoutAssociation].
     */
    suspend fun associate(adapter: ScannedAdapter?): AssociationStart {
        stopScan()
        val start = association.request(adapter?.address, adapter?.name) { created -> scope.launch { saveAdapter(created) } }
        if (start is AssociationStart.Created) saveAdapter(start.adapter)
        return start
    }

    /** Step 2: the dialog's result. Returns the saved adapter, or null when the user declined. */
    suspend fun onAssociationResult(resultCode: Int, data: Intent?, name: String? = null): ObdAdapter? {
        val adapter = association.result(resultCode, data, name) ?: return null
        saveAdapter(adapter)
        return adapter
    }

    /** The fallback: no association, a foreground service waits for the adapter with a BLE autoConnect. */
    suspend fun useWithoutAssociation(adapter: ScannedAdapter) {
        stopScan()
        saveAdapter(ObdAdapter(adapter.address.uppercase(), adapter.name))
    }

    /** Debug builds: the pretend car instead of an adapter. False in release (no simulator compiled in). */
    val simulatorAvailable: Boolean get() = simulator.isPresent

    suspend fun useSimulator(): Boolean {
        if (!simulator.isPresent) return false
        saveAdapter(ObdAdapter.simulated())
        return true
    }

    /**
     * Disconnects, ends the association and presence observation, and clears the adapter (readings stay). Returns
     * whether the Bluetooth bond went too: only CDM on Android 16+ can remove it; otherwise the UI should tell the
     * user to forget the adapter in Android's Bluetooth settings (TODO(CP4): that text).
     */
    suspend fun forget(): Boolean {
        val profileId = activeProfileId() ?: return false
        val adapter = settingsStore.get(profileId).adapter ?: return false
        manager.stop()
        val unbonded = if (adapter.transport == ObdTransportKind.BLE && adapter.associated) association.forget(adapter) else false
        settingsStore.update(profileId) { it.copy(adapter = null) }
        diagnostics.log("Forgot adapter ${adapter.name ?: adapter.address}")
        return unbonded
    }

    private suspend fun saveAdapter(adapter: ObdAdapter) {
        val profileId = activeProfileId() ?: return
        val old = settingsStore.get(profileId).adapter
        if (old != null && old.address != adapter.address) {
            manager.stop()
            if (old.associated) association.stopObserving(old)
        }
        settingsStore.update(profileId) { it.copy(adapter = adapter) }
        diagnostics.log("Adapter set: ${adapter.name ?: adapter.address} (${if (adapter.associated) "companion" else adapter.transport.name.lowercase()})")
        if (adapter.usesPresence && !association.observe(adapter)) diagnostics.log("Presence observation refused")
    }

    // ── Connecting ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Connects now and keeps waiting for the adapter between trips, under the foreground service when Android lets
     * it start (it does while the app is open), else on the app's scope only.
     */
    suspend fun connect() {
        if (!ObdForegroundService.start(context)) startStandby()
    }

    /** Ends the trip (with its trip-end upload decision) and stops waiting. */
    suspend fun disconnect() {
        presenceWatch?.cancel()
        manager.stop()
    }

    /** Read the odometer once, through the running session or a short one-off connection; stores nothing. */
    suspend fun readNow(): ReadNowResult = manager.readNow()

    /** One command as typed, e.g. "ATRV" or "0100". */
    suspend fun sendRaw(command: String): RawResult = manager.sendRaw(command)

    // ── Settings (active profile) ────────────────────────────────────────────────────────────────────────────

    private val activeId: Flow<String?> = profiles.state.map { it.active?.id }.distinctUntilChanged()

    val settings: Flow<ObdSettings> = activeId.flatMapLatest { id -> id?.let(settingsStore::settings) ?: flowOf(ObdSettings()) }

    /** The server's distance preference: [ObdSettings.minUploadDistance] is in this unit. */
    val displayMiles: Flow<Boolean> = profiles.state.map { Formatter(it.active?.preferences).miles }.distinctUntilChanged()

    /** A different vehicle clears "last sent" and the codes-cleared anchor. */
    suspend fun setVehicle(vehicleId: String?) = updateActive { it.withVehicle(vehicleId) }

    /** A built-in ([OdometerProfiles]) or a custom / imported one (check [OdometerProfile.problem] first). */
    suspend fun setProfile(profile: OdometerProfile) = updateActive { it.withProfile(profile) }

    suspend fun setPollMinutes(minutes: Int) =
        updateActive { it.copy(pollMinutes = minutes.coerceIn(ObdSettings.MIN_POLL_MINUTES, ObdSettings.MAX_POLL_MINUTES)) }

    /** In the display unit ([displayMiles]). */
    suspend fun setMinUploadDistance(distance: Double) = updateActive { it.copy(minUploadDistance = distance.coerceAtLeast(0.0)) }

    suspend fun setUploadOnTripEnd(enabled: Boolean) = updateActive { it.copy(uploadOnTripEnd = enabled) }

    /** Off: queued readings wait for an unmetered network. Re-schedules a pending upload with the new constraint. */
    suspend fun setCellularUploads(enabled: Boolean) {
        val profileId = activeProfileId() ?: return
        val updated = settingsStore.update(profileId) { it.copy(cellularUploads = enabled) }
        if (readings.inState(profileId, ReadingState.QUEUED).isNotEmpty()) {
            ObdUploadWorker.schedule(context, profileId, updated.cellularUploads)
        }
    }

    /** Debug simulator: speed and time scale, applied at the next connection. */
    suspend fun setSimulator(settings: SimulatorSettings) = updateActive { it.copy(simulator = settings) }

    private suspend fun updateActive(transform: (ObdSettings) -> ObdSettings) {
        val profileId = activeProfileId() ?: return
        settingsStore.update(profileId, transform)
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────────────────────────────────────

    /** Last 100 commands and replies, oldest first. */
    val transcript: StateFlow<List<ElmTranscriptEntry>> = diagnostics.transcript

    /** Connects, disconnects, pairing, errors, uploads. */
    val connectionLog: StateFlow<List<ConnectionEvent>> = diagnostics.connectionLog

    /** Readings picked for upload and still waiting for a network. */
    val queuedReadings: Flow<Int> = activeId.flatMapLatest { id -> id?.let(readings::observeQueued) ?: flowOf(0) }

    /** The newest readings, newest first. */
    fun recentReadings(limit: Int = 50): Flow<List<ObdReading>> =
        activeId.flatMapLatest { id -> id?.let { readings.observeRecent(it, limit) } ?: flowOf(emptyList()) }

    /** Writes the diagnostics to a text file and returns a share chooser for it (FileProvider, D-A14). */
    suspend fun exportDiagnostics(): Intent = withContext(Dispatchers.IO) {
        val profileId = obdProfileId()
        val settings = profileId?.let { settingsStore.get(it) }
        val queued = profileId?.let { readings.inState(it, ReadingState.QUEUED).size } ?: 0
        val recent = profileId?.let { readings.recent(it, 20) }.orEmpty()
        val header = buildList {
            add("App ${BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            add("Exported ${Instant.now()}")
            add("Status: ${status.value}")
            add("Adapter: ${settings?.adapter}")
            add("Profile: ${settings?.profile?.resolved()}")
            add("Poll ${settings?.pollMinutes} min, upload every ${settings?.minUploadDistance} (display unit), trip end ${settings?.uploadOnTripEnd}, cellular ${settings?.cellularUploads}")
            add("Last sent ${settings?.lastSentKm} km, last sync ${settings?.lastSyncAt?.let(Instant::ofEpochMilli)}, queued readings $queued")
            add("Recent readings:")
            recent.forEach { add("  ${Instant.ofEpochMilli(it.readAt)}  ${it.km} km  ${it.state}  trip ${it.tripId.take(8)}") }
        }
        val dir = File(context.cacheDir, "obd").apply { mkdirs() }
        val file = File(dir, "obd-diagnostics.txt")
        file.writeText(diagnostics.report(header))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun log(message: String) = diagnostics.log(message)

    // ── Services, presence, uploads (internal to the feature) ────────────────────────────────────────────────

    /** "<vehicle> · 84,512 mi" for the notification, or the vehicle alone before the first reading. */
    val notificationTitle: Flow<String?> = status.mapLatest { s ->
        val profileId = obdProfileId() ?: return@mapLatest null
        val vehicleId = settingsStore.get(profileId).vehicleId ?: return@mapLatest null
        val name = vehicleName(profileId, vehicleId) ?: return@mapLatest null
        val km = when (s) {
            is ObdStatus.Connected -> s.lastKm
            is ObdStatus.Reading -> s.lastKm
            else -> null
        } ?: return@mapLatest name
        "$name · ${Formatter(profiles.profile(profileId)?.preferences).distance(km)}"
    }.distinctUntilChanged()

    suspend fun startStandby() = manager.start(keepWaiting = true)

    /** The foreground service couldn't start: wait for the adapter on the app's scope while the process lives. */
    fun startWithoutService() {
        scope.launch { startStandby() }
    }

    private var presenceWatch: Job? = null

    /** From [io.github.conchad.smartgarage.core.obd.device.ObdPresenceService]. */
    fun onPresence(appeared: Boolean) {
        diagnostics.log(if (appeared) "Adapter nearby" else "Adapter gone")
        presenceWatch?.cancel()
        if (appeared) {
            scope.launch { connect() }
            return
        }
        // Gone: once no trip is running, stop waiting (the next appearance starts it again).
        presenceWatch = scope.launch {
            status.first { it !is ObdStatus.Connected && it !is ObdStatus.Reading && it !is ObdStatus.Pairing }
            manager.stop()
        }
    }

    /** The foreground service was destroyed (stopped by the user or the system): the trip ends. */
    fun onServiceStopped() {
        scope.launch { manager.stop() }
    }

    fun onBoot(done: () -> Unit) {
        scope.launch {
            try {
                for ((profileId, settings) in settingsStore.all()) {
                    val adapter = settings.adapter ?: continue
                    if (profiles.profile(profileId) == null) continue
                    when {
                        adapter.usesPresence -> association.observe(adapter)
                        adapter.transport == ObdTransportKind.BLE && settings.vehicleId != null -> ObdForegroundService.start(context)
                    }
                }
            } finally {
                done()
            }
        }
    }

    /** [ObdUploadWorker]'s job. */
    suspend fun upload(profileId: String): ObdUploader.Result = ObdUploader(
        readings = readings,
        writes = writer::writesFor,
        withdraw = { pid, id -> writer.withdraw(pid, "odometerLogs", id) },
        sync = sync::sync,
        cellularAllowed = { settingsStore.get(it).cellularUploads },
        onSynced = { pid, at -> settingsStore.update(pid) { it.copy(lastSyncAt = at) } },
        log = diagnostics::log,
    ).run(profileId)

    // ── Plumbing ─────────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun activeProfileId(): String? = profiles.current().active?.id

    /** The active profile when it has an adapter, else the first profile that does. */
    private suspend fun obdProfileId(): String? {
        val state = profiles.current()
        val all = settingsStore.all()
        val active = state.active?.id
        if (active != null && all[active]?.adapter != null) return active
        return all.entries.firstOrNull { (id, s) -> s.adapter != null && state.profiles.any { it.id == id } }?.key
    }

    private suspend fun currentSetup(): ObdSetup? {
        val profileId = obdProfileId() ?: return null
        val settings = settingsStore.get(profileId)
        simulator.ifPresent { it.settings = settings.simulator }
        return ObdSetup(profileId, settings, Formatter(profiles.profile(profileId)?.preferences).miles)
    }

    private fun tripStore(profileId: String): TripStore = RoomTripStore(
        profileId = profileId,
        readings = readings,
        settings = settingsStore,
        cache = { cache.dao(profileId) },
        onQueued = { settings -> ObdUploadWorker.schedule(context, profileId, settings.cellularUploads) },
    )

    private suspend fun vehicleName(profileId: String, vehicleId: String): String? {
        vehiclePrefs.nickname(vehicleId).first()?.let { return it }
        val row = cache.dao(profileId).row("vehicles", vehicleId) ?: return null
        return runCatching { ApiJson.decodeFromString(Vehicle.serializer(), row.json).displayName }.getOrNull()
    }
}
