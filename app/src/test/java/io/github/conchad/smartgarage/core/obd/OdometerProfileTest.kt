package io.github.conchad.smartgarage.core.obd

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class OdometerProfileTest {
    // Verbatim from wican-fw main/vehicle_profiles.json.
    private val clarity = """
        {
         "car_model": "Honda: Clarity-2018",
         "init": "ATSP7;ATST96;",
         "pids": [
          {"pid": "22202A", "pid_init": "ATSHDA01F1;",
           "parameters": [{"name": "SOC", "expression": "B62", "unit": "%", "class": "battery", "min": "0", "max": "100"}]},
          {"pid": "2270229", "pid_init": "ATSHDA60F1;",
           "parameters": [{"name": "ODOMETER", "expression": "[B12:B14]", "class": "distance", "unit": "km", "min": "0"}]}
         ]
        },
    """.trimIndent()

    // Verbatim from wican-fw pro/vehicle_profiles.json: pid_init on the parameter.
    private val id3 = """{"car_model": "VW: id-3", "init": "ATSP6;ATST96;ATFCSD300000;ATFCSM1;", "pids": [{"pid": "22028C1", "parameters": [{"name": "SOC", "pid_init": "ATSHFC007B;ATCP17;ATCRA17FE007B;ATFCSH17FC007B;", "expression": "((B4*51)/115)-6.4", "unit": "%", "class": "battery"}]}, {"pid": "22295A", "parameters": [{"name": "Odometer", "pid_init": "ATSHFC0076;ATCP17;ATCRA17FE0076;ATFCSH17FC0076;", "expression": "[B4:B6]", "unit": "km", "class": "none"}]}]}"""

    @Test
    fun `the default is Honda's cluster, as WiCAN's Clarity profile`() {
        val p = OdometerProfiles.DEFAULT
        assertThat(p.kind).isEqualTo(OdometerKind.HONDA_CLUSTER)
        assertThat(p.initCommands).isEqualTo("ATSP7;ATST96;")
        assertThat(p.headerCommand).isEqualTo("ATSHDA60F1;")
        assertThat(p.request).isEqualTo("2270229")
        assertThat(p.expression).isEqualTo("[B12:B14]")
        assertThat(p.unit).isEqualTo(DistanceUnit.KM)
        assertThat(OdometerProfiles.builtIns.map { it.problem() }).containsExactly(null, null, null)
        assertThat(OdometerProfiles.STANDARD.expression).isEqualTo("[B3:B6]/10")
        assertThat(OdometerProfiles.CODES_CLEARED.request).isEqualTo("0131")
    }

    @Test
    fun `a pasted WiCAN car becomes a custom profile matching the built-in`() {
        val p = OdometerProfiles.fromWicanJson(clarity)
        assertThat(p).isEqualTo(OdometerProfiles.HONDA_CLUSTER.copy(kind = OdometerKind.CUSTOM, name = "Honda: Clarity-2018"))
    }

    @Test
    fun `whole files, lists, single pids, parameter-level pid_init and map-shaped parameters are read`() {
        val file = """{"cars": [$id3, ${clarity.trimEnd(',')}]}"""
        assertThat(OdometerProfiles.fromWicanJson(file).name).isEqualTo("VW: id-3")
        assertThat(OdometerProfiles.fromWicanJson(file, carModel = "honda: clarity-2018").request).isEqualTo("2270229")

        val vw = OdometerProfiles.fromWicanJson("[$id3]")
        assertThat(vw.initCommands).isEqualTo("ATSP6;ATST96;ATFCSD300000;ATFCSM1;")
        assertThat(vw.headerCommand).isEqualTo("ATSHFC0076;ATCP17;ATCRA17FE0076;ATFCSH17FC0076;")
        assertThat(vw.request).isEqualTo("22295A")
        assertThat(vw.expression).isEqualTo("[B4:B6]")

        val pidOnly = OdometerProfiles.fromWicanJson(
            """{"pid": "2270229", "pid_init": "ATSHDA60F1", "parameters": {"ODOMETER": {"expression": "[B12:B14]", "unit": "mi"}}}""",
        )
        assertThat(pidOnly.headerCommand).isEqualTo("ATSHDA60F1;")
        assertThat(pidOnly.unit).isEqualTo(DistanceUnit.MI)
        assertThat(pidOnly.initCommands).isEmpty()

        val bareKeys = OdometerProfiles.fromWicanJson(""""car_model": "X", "init": "ATSP6;", "pids": [{"pid": "01A6", "parameters": [{"name": "odo", "expression": "[B3:B6]/10"}]}]""")
        assertThat(bareKeys.name).isEqualTo("X")
    }

    @Test
    fun `pastes without a usable odometer are refused with a reason`() {
        fun reason(text: String) = assertThrows(WicanImportException::class.java) { OdometerProfiles.fromWicanJson(text) }.reason
        assertThat(reason("not json at all {")).isEqualTo(WicanImportException.Reason.NOT_JSON)
        assertThat(reason("""{"car_model": "Y", "pids": [{"pid": "22202A", "parameters": [{"name": "SOC", "expression": "B62"}]}]}"""))
            .isEqualTo(WicanImportException.Reason.NO_ODOMETER)
        assertThat(reason(clarity.replace("[B12:B14]", "[B12:B14")))
            .isEqualTo(WicanImportException.Reason.BAD_PROFILE)
        assertThat(reason(clarity.replace("\"unit\": \"km\"", "\"unit\": \"%\"")))
            .isEqualTo(WicanImportException.Reason.BAD_PROFILE)
        assertThat(reason(clarity.replace("2270229", "ATZ"))).isEqualTo(WicanImportException.Reason.BAD_PROFILE)
    }

    @Test
    fun `profiles serialize for storage and built-ins resolve to the current definition`() {
        val custom = OdometerProfiles.custom().copy(expression = "[B12:B14] + 0", multiplier = 1.5, unit = DistanceUnit.MI, name = "Mine")
        val json = Json.encodeToString(OdometerProfile.serializer(), custom)
        assertThat(json).contains("\"kind\":\"custom\"")
        assertThat(json).contains("\"unit\":\"mi\"")
        assertThat(Json.decodeFromString(OdometerProfile.serializer(), json)).isEqualTo(custom)
        assertThat(custom.resolved()).isEqualTo(custom)

        val stale = OdometerProfiles.HONDA_CLUSTER.copy(expression = "[B11:B13]")
        assertThat(stale.resolved()).isEqualTo(OdometerProfiles.HONDA_CLUSTER)
        assertThat(custom.copy(multiplier = 0.0).problem()).contains("multiplier")
        assertThat(custom.copy(expression = "B1 +").problem()).contains("ends with an operator")
    }
}
