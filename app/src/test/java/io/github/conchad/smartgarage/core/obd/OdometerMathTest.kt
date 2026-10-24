package io.github.conchad.smartgarage.core.obd

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.model.OdometerLog
import org.junit.Test

class OdometerMathTest {
    @Test
    fun `values become whole km, rounded down`() {
        assertThat(OdometerUnits.toKm(84_130.9, DistanceUnit.KM)).isEqualTo(84_130)
        assertThat(OdometerUnits.toKm(1_000.0, DistanceUnit.MI)).isEqualTo(1_609)
        assertThat(OdometerUnits.toKm(52_000.0, DistanceUnit.MI)).isEqualTo(83_685)
        assertThat(OdometerUnits.toKm(1_000.0, DistanceUnit.KM, multiplier = 0.1)).isEqualTo(100)
        assertThat(OdometerUnits.toKm(Double.NaN, DistanceUnit.KM)).isNull()
        assertThat(OdometerUnits.toKm(-1.0, DistanceUnit.KM)).isNull()
        assertThat(OdometerUnits.toKm(1e12, DistanceUnit.KM)).isNull()
        assertThat(OdometerUnits.displayToKm(25.0, miles = true)).isWithin(1e-9).of(40.2336)
        assertThat(OdometerUnits.displayToKm(25.0, miles = false)).isEqualTo(25.0)
        assertThat(DistanceUnit.parse("Miles")).isEqualTo(DistanceUnit.MI)
        assertThat(DistanceUnit.parse(null)).isEqualTo(DistanceUnit.KM)
        assertThat(DistanceUnit.parse("%")).isNull()
    }

    @Test
    fun `upload only past the last sent value, at trip end or after the minimum distance`() {
        val miles = UploadPolicy(displayMiles = true) // 25 mi = 40.2 km
        assertThat(miles.shouldUpload(1_000, lastSentKm = null, tripEnded = false)).isTrue()
        assertThat(miles.shouldUpload(1_000, lastSentKm = 1_000, tripEnded = true)).isFalse()
        assertThat(miles.shouldUpload(990, lastSentKm = 1_000, tripEnded = true)).isFalse()
        assertThat(miles.shouldUpload(1_001, lastSentKm = 1_000, tripEnded = true)).isTrue()
        assertThat(miles.shouldUpload(1_040, lastSentKm = 1_000, tripEnded = false)).isFalse()
        assertThat(miles.shouldUpload(1_041, lastSentKm = 1_000, tripEnded = false)).isTrue()

        val km = UploadPolicy(minDistance = 25.0, displayMiles = false, uploadOnTripEnd = false)
        assertThat(km.shouldUpload(1_010, lastSentKm = 1_000, tripEnded = true)).isFalse()
        assertThat(km.shouldUpload(1_025, lastSentKm = 1_000, tripEnded = false)).isTrue()
    }

    private fun log(id: String, km: Int, at: String, source: String = "manual") =
        OdometerLog(id = id, vehicleId = "v", odometerKm = km, recordedAt = at, source = source)

    @Test
    fun `the anchor is the newest manual reading`() {
        val logs = listOf(
            log("a", 80_000, "2026-09-01T10:00:00Z"),
            log("b", 81_000, "2026-10-01T10:00:00Z", source = "receipt"),
            log("c", 82_000, "2026-10-20T10:00:00Z", source = CodesClearedOffset.OBD_SOURCE),
            // WiCAN's own server ingest (server D-046) is an adapter reading too.
            log("d", 82_500, "2026-10-21T10:00:00Z", source = CodesClearedOffset.WICAN_SOURCE),
        )
        assertThat(CodesClearedOffset.latestManual(logs)?.id).isEqualTo("b")
        assertThat(CodesClearedOffset.latestManual(logs.takeLast(2))).isNull()
    }

    @Test
    fun `codes-cleared counter adds to the manual reading and re-anchors`() {
        val manual = log("m1", 80_000, "2026-10-01T10:00:00Z")
        // No manual reading: nothing to add to.
        assertThat(CodesClearedOffset.step(null, null, 500)).isEqualTo(CodesClearedOffset.Step(null, null))

        var step = CodesClearedOffset.step(null, manual, 500)
        assertThat(step.odometerKm).isEqualTo(80_000)
        step = CodesClearedOffset.step(step.anchor, manual, 620)
        assertThat(step.odometerKm).isEqualTo(80_120)

        // Codes cleared: the counter restarts and the chain continues from the last odometer.
        step = CodesClearedOffset.step(step.anchor, manual, 3)
        assertThat(step.odometerKm).isEqualTo(80_123)
        step = CodesClearedOffset.step(step.anchor, manual, 10)
        assertThat(step.odometerKm).isEqualTo(80_130)

        // A newer manual reading takes over, counted from the counter seen with it.
        val newer = log("m2", 81_000, "2026-10-10T10:00:00Z")
        step = CodesClearedOffset.step(step.anchor, newer, 12)
        assertThat(step.odometerKm).isEqualTo(81_000)
        assertThat(step.anchor).isEqualTo(CodesClearedAnchor("m2", 81_000, 12, 81_000))
        step = CodesClearedOffset.step(step.anchor, newer, 40)
        assertThat(step.odometerKm).isEqualTo(81_028)
    }
}
