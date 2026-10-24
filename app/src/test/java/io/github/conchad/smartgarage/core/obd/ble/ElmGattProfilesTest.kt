package io.github.conchad.smartgarage.core.obd.ble

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.obd.ble.ElmGattProfiles.short
import io.github.conchad.smartgarage.core.obd.ble.GattProps.INDICATE
import io.github.conchad.smartgarage.core.obd.ble.GattProps.NOTIFY
import io.github.conchad.smartgarage.core.obd.ble.GattProps.WRITE
import io.github.conchad.smartgarage.core.obd.ble.GattProps.WRITE_NO_RESPONSE
import org.junit.Test
import java.util.UUID

class ElmGattProfilesTest {
    private val gap = GattServiceInfo(short(0x1800), listOf(GattCharInfo(short(0x2A00), 0x02)))
    private val deviceInfo = GattServiceInfo(short(0x180A), listOf(GattCharInfo(short(0x2A29), 0x02)))
    private val wicanCli = GattServiceInfo(
        UUID.fromString("bef0adde-0000-1000-8000-00c0de000001"),
        listOf(
            GattCharInfo(UUID.fromString("bef0adde-0000-1000-8000-00c0de000002"), NOTIFY),
            GattCharInfo(UUID.fromString("bef0adde-0000-1000-8000-00c0de000003"), WRITE),
        ),
    )

    private fun fff0(notifyProps: Int = NOTIFY, writeProps: Int = WRITE or WRITE_NO_RESPONSE) = GattServiceInfo(
        short(0xFFF0),
        listOf(GattCharInfo(short(0xFFF1), notifyProps), GattCharInfo(short(0xFFF2), writeProps)),
    )

    @Test
    fun `WiCAN Pro and OBDLink CX use FFF0 with FFF1 notify and FFF2 write, never the WiCAN CLI`() {
        val uart = ElmGattProfiles.choose(listOf(gap, wicanCli, deviceInfo, fff0()))!!
        assertThat(uart.service).isEqualTo(short(0xFFF0))
        assertThat(uart.notify).isEqualTo(short(0xFFF1))
        assertThat(uart.write).isEqualTo(short(0xFFF2))
        assertThat(uart.writeWithResponse).isTrue()
        assertThat(uart.indicate).isFalse()
    }

    @Test
    fun `known services win in order FFF0, FEE0, FFE0, 18F0 whatever the discovery order`() {
        val fee0 = GattServiceInfo(short(0xFEE0), listOf(GattCharInfo(short(0xFEE1), NOTIFY or WRITE_NO_RESPONSE)))
        val ffe0 = GattServiceInfo(short(0xFFE0), listOf(GattCharInfo(short(0xFFE1), NOTIFY or WRITE_NO_RESPONSE)))
        val f18 = GattServiceInfo(short(0x18F0), listOf(GattCharInfo(short(0x2AF0), NOTIFY), GattCharInfo(short(0x2AF1), WRITE)))

        assertThat(ElmGattProfiles.choose(listOf(f18, ffe0, fee0, fff0()))!!.service).isEqualTo(short(0xFFF0))
        assertThat(ElmGattProfiles.choose(listOf(f18, ffe0, fee0))!!.service).isEqualTo(short(0xFEE0))
        with(ElmGattProfiles.choose(listOf(f18, ffe0))!!) {
            assertThat(service).isEqualTo(short(0xFFE0))
            assertThat(notify).isEqualTo(write)
            assertThat(writeWithResponse).isFalse()
        }
        with(ElmGattProfiles.choose(listOf(gap, f18))!!) {
            assertThat(notify).isEqualTo(short(0x2AF0))
            assertThat(write).isEqualTo(short(0x2AF1))
        }
    }

    @Test
    fun `an unknown service with notify and write is the fallback, standard services never`() {
        val custom = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val nus = GattServiceInfo(
            custom,
            listOf(
                GattCharInfo(UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"), WRITE_NO_RESPONSE),
                GattCharInfo(UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"), INDICATE),
            ),
        )
        val battery = GattServiceInfo(short(0x180F), listOf(GattCharInfo(short(0x2A19), NOTIFY or WRITE)))

        with(ElmGattProfiles.choose(listOf(gap, battery, nus))!!) {
            assertThat(service).isEqualTo(custom)
            assertThat(indicate).isTrue()
            assertThat(writeWithResponse).isFalse()
        }
        assertThat(ElmGattProfiles.choose(listOf(gap, battery, deviceInfo, wicanCli))).isNull()
    }

    @Test
    fun `a known service with odd properties falls back to what it does offer`() {
        // FFF1 can't notify here, but FFF2 does both: take it.
        val odd = fff0(notifyProps = 0x02, writeProps = NOTIFY or WRITE)
        with(ElmGattProfiles.choose(listOf(odd))!!) {
            assertThat(notify).isEqualTo(short(0xFFF2))
            assertThat(write).isEqualTo(short(0xFFF2))
        }
        assertThat(ElmGattProfiles.choose(listOf(fff0(notifyProps = 0x02, writeProps = 0x02)))).isNull()
    }

    @Test
    fun `scan results look like an adapter by name or advertised service`() {
        assertThat(ObdScanMatch.likelyObd("WiCAN_PRO_1A2B", emptyList())).isTrue()
        assertThat(ObdScanMatch.likelyObd("OBDLink CX", emptyList())).isTrue()
        assertThat(ObdScanMatch.likelyObd("IOS-Vlink", emptyList())).isTrue()
        assertThat(ObdScanMatch.likelyObd(null, listOf(short(0xFFF0)))).isTrue()
        assertThat(ObdScanMatch.likelyObd("Pixel Buds", listOf(short(0x180F)))).isFalse()

        val a = ScannedAdapter("AA", "Buds", -40, likelyObd = false, lastSeenMillis = 1)
        val b = ScannedAdapter("BB", "WiCAN", -80, likelyObd = true, lastSeenMillis = 1)
        val list = ObdScanMatch.merge(ObdScanMatch.merge(emptyList(), a), b)
        assertThat(list.map { it.address }).containsExactly("BB", "AA").inOrder()
        val again = ObdScanMatch.merge(list, b.copy(name = null, rssi = -60))
        assertThat(again.single { it.address == "BB" }.name).isEqualTo("WiCAN")
        assertThat(again).hasSize(2)
    }
}
