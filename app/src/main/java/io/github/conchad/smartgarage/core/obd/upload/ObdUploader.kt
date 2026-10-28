package io.github.conchad.smartgarage.core.obd.upload

import io.github.conchad.smartgarage.core.data.GarageWrites
import io.github.conchad.smartgarage.core.data.OdometerInput
import io.github.conchad.smartgarage.core.data.WriteDenied
import io.github.conchad.smartgarage.core.data.WriteResult
import io.github.conchad.smartgarage.core.obd.CodesClearedOffset
import io.github.conchad.smartgarage.core.obd.data.ObdReading
import io.github.conchad.smartgarage.core.obd.data.ObdReadingDao
import io.github.conchad.smartgarage.core.obd.data.ReadingState
import io.github.conchad.smartgarage.core.sync.SyncOutcome
import java.time.Instant

/**
 * The OBD upload worker's job (D-A80), Android-free. It only runs on a network the user allows (WorkManager:
 * unmetered when "cellular uploads" is off), and there it hands the newest QUEUED reading of each vehicle to the
 * outbox as an odometer log with source "obd", marks older queued ones SUPERSEDED, and syncs. If that sync can't
 * reach the server and cellular uploads are off, the entries it just queued are taken back out of the outbox
 * (still unsent) and their readings are QUEUED again, so the app's ordinary syncs can't carry them over mobile data.
 */
class ObdUploader(
    private val readings: ObdReadingDao,
    private val writes: (profileId: String) -> GarageWrites,
    private val withdraw: suspend (profileId: String, logId: String) -> Boolean,
    private val sync: suspend (profileId: String) -> SyncOutcome,
    private val cellularAllowed: suspend (profileId: String) -> Boolean,
    private val onSynced: suspend (profileId: String, atMillis: Long) -> Unit,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Result { DONE, RETRY }

    suspend fun run(profileId: String): Result {
        val handed = handOff(profileId)
        when (val outcome = sync(profileId)) {
            is SyncOutcome.Success -> {
                onSynced(profileId, clock())
                if (handed.isNotEmpty()) log("Uploaded ${handed.joinToString { "${it.km} km" }}")
                return Result.DONE
            }
            is SyncOutcome.Failed -> {
                log("Upload failed: ${outcome.problem}")
                if (!cellularAllowed(profileId)) {
                    val back = handed.filter { withdraw(profileId, it.outboxId!!) }
                    if (back.isNotEmpty()) readings.setState(back.map { it.id }, ReadingState.QUEUED, null)
                }
                return Result.RETRY
            }
            SyncOutcome.SignedOut, SyncOutcome.NotSignedIn -> return Result.DONE
        }
    }

    /** The readings now in the outbox, with [ObdReading.outboxId] set. */
    private suspend fun handOff(profileId: String): List<ObdReading> {
        val queued = readings.inState(profileId, ReadingState.QUEUED).filter { it.km != null }
        if (queued.isEmpty()) return emptyList()
        val w = writes(profileId)
        val handed = mutableListOf<ObdReading>()
        for ((vehicleId, list) in queued.groupBy { it.vehicleId }) {
            val (send, rest) = pick(list)
            val input = OdometerInput(
                odometerKm = send.km!!,
                recordedAt = Instant.ofEpochMilli(send.readAt).toString(),
                source = CodesClearedOffset.OBD_SOURCE,
            )
            when (val result = w.addOdometerLog(vehicleId, input)) {
                is WriteResult.Queued -> {
                    readings.setState(listOf(send.id), ReadingState.SENT, result.id)
                    if (rest.isNotEmpty()) readings.setState(rest.map { it.id }, ReadingState.SUPERSEDED, null)
                    handed += send.copy(state = ReadingState.SENT, outboxId = result.id)
                }
                is WriteResult.Denied -> when (result.reason) {
                    WriteDenied.ViewOnly, WriteDenied.OwnerOnly -> {
                        readings.setState(list.map { it.id }, ReadingState.REJECTED, null)
                        log("Readings for $vehicleId rejected: ${result.reason}")
                    }
                    // Not signed in, or the vehicle isn't cached yet: they stay QUEUED for the next run.
                    WriteDenied.NotSignedIn, WriteDenied.Unknown -> log("Readings for $vehicleId wait: ${result.reason}")
                }
            }
        }
        return handed
    }

    companion object {
        /** The highest reading goes (the latest of equal ones); the others are superseded by it. */
        fun pick(list: List<ObdReading>): Pair<ObdReading, List<ObdReading>> {
            val send = list.maxWith(compareBy<ObdReading> { it.km }.thenBy { it.readAt })
            return send to list.filter { it.id != send.id }
        }
    }
}
