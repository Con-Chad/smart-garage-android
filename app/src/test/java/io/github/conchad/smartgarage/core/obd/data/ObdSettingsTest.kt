package io.github.conchad.smartgarage.core.obd.data

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.obd.CodesClearedAnchor
import io.github.conchad.smartgarage.core.obd.OdometerKind
import io.github.conchad.smartgarage.core.obd.OdometerProfiles
import io.github.conchad.smartgarage.core.obd.link.ObdAdapter
import io.github.conchad.smartgarage.core.obd.link.ObdTransportKind
import io.github.conchad.smartgarage.core.obd.upload.ObdUploadWorker
import androidx.work.NetworkType
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class ObdSettingsTest {
    @Test
    fun `settings survive a round trip, defaults stay out of the stored text`() {
        val settings = ObdSettings(
            adapter = ObdAdapter("AA:BB:CC:DD:EE:FF", "WiCAN_PRO", associated = true, associationId = 7),
            vehicleId = "v1",
            profile = OdometerProfiles.custom().copy(expression = "[B12:B14]*2", name = "Mine"),
            pollMinutes = 10,
            minUploadDistance = 40.0,
            uploadOnTripEnd = false,
            cellularUploads = false,
            anchor = CodesClearedAnchor("o1", 84_000, 1_200, 84_100),
            lastSyncAt = 1_700_000_000_000,
            lastSentKm = 84_512,
            simulator = SimulatorSettings(speedKmh = 100.0),
        )
        assertThat(ObdSettings.decode(settings.encode())).isEqualTo(settings)
        assertThat(ObdSettings().encode()).isEqualTo("{}")
        assertThat(ObdSettings(adapter = ObdAdapter.simulated()).encode()).contains("\"transport\":\"simulated\"")
    }

    @Test
    fun `unknown keys, missing keys and garbage fall back to defaults`() {
        val decoded = ObdSettings.decode("""{"vehicleId":"v1","futureField":{"x":1},"pollMinutes":3}""")
        assertThat(decoded.vehicleId).isEqualTo("v1")
        assertThat(decoded.pollMinutes).isEqualTo(3)
        assertThat(decoded.profile).isEqualTo(OdometerProfiles.DEFAULT)
        assertThat(decoded.cellularUploads).isTrue()
        assertThat(ObdSettings.decode("not json")).isEqualTo(ObdSettings())
        assertThat(ObdSettings.decode(null)).isEqualTo(ObdSettings())
        assertThat(ObdSettings.decode("""{"adapter":{"address":"AA","transport":"carrier-pigeon"}}""").adapter?.transport)
            .isEqualTo(ObdTransportKind.BLE)
    }

    @Test
    fun `another vehicle or profile kind resets what belonged to the old one`() {
        val anchor = CodesClearedAnchor("o1", 1, 2, 3)
        val s = ObdSettings(vehicleId = "v1", lastSentKm = 100, anchor = anchor, profile = OdometerProfiles.CODES_CLEARED)
        assertThat(s.withVehicle("v1")).isEqualTo(s)
        assertThat(s.withVehicle("v2")).isEqualTo(s.copy(vehicleId = "v2", lastSentKm = null, anchor = null))
        assertThat(s.withProfile(OdometerProfiles.CODES_CLEARED.copy(kind = OdometerKind.CODES_CLEARED)).anchor).isEqualTo(anchor)
        assertThat(s.withProfile(OdometerProfiles.STANDARD).anchor).isNull()
        assertThat(s.withProfile(OdometerProfiles.STANDARD).lastSentKm).isEqualTo(100)
    }

    @Test
    fun `poll interval is clamped and the policy uses the display unit`() {
        assertThat(ObdSettings(pollMinutes = 0).pollInterval).isEqualTo(1.minutes)
        assertThat(ObdSettings(pollMinutes = 500).pollInterval).isEqualTo(60.minutes)
        assertThat(ObdSettings().pollInterval).isEqualTo(5.minutes)
        assertThat(ObdSettings().policy(displayMiles = true).minDistanceKm).isWithin(1e-6).of(40.2336)
        assertThat(ObdSettings(minUploadDistance = 10.0, uploadOnTripEnd = false).policy(displayMiles = false))
            .isEqualTo(io.github.conchad.smartgarage.core.obd.UploadPolicy(10.0, displayMiles = false, uploadOnTripEnd = false))
    }

    @Test
    fun `cellular off means the upload job waits for an unmetered network`() {
        assertThat(ObdUploadWorker.constraints(cellular = false).requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(ObdUploadWorker.constraints(cellular = true).requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }
}
