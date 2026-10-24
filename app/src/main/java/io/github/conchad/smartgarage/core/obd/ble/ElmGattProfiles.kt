package io.github.conchad.smartgarage.core.obd.ble

import java.util.UUID

/** A discovered characteristic: its UUID and GATT property bits ([GattProps]). Plain data so selection unit-tests. */
data class GattCharInfo(val uuid: UUID, val properties: Int) {
    val canNotify: Boolean get() = properties and GattProps.NOTIFY != 0
    val canIndicate: Boolean get() = properties and GattProps.INDICATE != 0
    val canWrite: Boolean get() = properties and GattProps.WRITE != 0
    val canWriteNoResponse: Boolean get() = properties and GattProps.WRITE_NO_RESPONSE != 0
    val listens: Boolean get() = canNotify || canIndicate
    val writable: Boolean get() = canWrite || canWriteNoResponse
}

data class GattServiceInfo(val uuid: UUID, val characteristics: List<GattCharInfo>)

/** The adapter's serial pipe: where replies arrive and where commands go. */
data class ElmUart(
    val service: UUID,
    val notify: UUID,
    val write: UUID,
    /** Indications instead of notifications (the CCCD value differs). */
    val indicate: Boolean,
    /** Write with response when the characteristic allows it: slower, but the adapter acknowledges each chunk. */
    val writeWithResponse: Boolean,
)

/** The property bits of `BluetoothGattCharacteristic`, mirrored so this file needs no Android types. */
object GattProps {
    const val WRITE_NO_RESPONSE = 0x04
    const val WRITE = 0x08
    const val NOTIFY = 0x10
    const val INDICATE = 0x20
}

/**
 * Which service of a BLE OBD adapter is the ELM327 serial pipe. Known layouts first, in this order:
 * - FFF0: FFF1 notify, FFF2 write. WiCAN Pro, OBDLink CX, most clones.
 * - FEE0: FEE1 both ways. The original WiCAN.
 * - FFE0: FFE1 both ways. HM-10-based clones.
 * - 18F0: 2AF0 notify, 2AF1 write. Seen in some clones; unconfirmed.
 * Then any other service with a listening and a writable characteristic. WiCAN's command-line service
 * (BEF0ADDE-…-C0DE000x) is never chosen: it is the adapter's own CLI, not ELM327.
 */
object ElmGattProfiles {
    val CCCD: UUID = short(0x2902)

    private data class Known(val service: UUID, val notify: UUID, val write: UUID)

    private val KNOWN = listOf(
        Known(short(0xFFF0), short(0xFFF1), short(0xFFF2)),
        Known(short(0xFEE0), short(0xFEE1), short(0xFEE1)),
        Known(short(0xFFE0), short(0xFFE1), short(0xFFE1)),
        Known(short(0x18F0), short(0x2AF0), short(0x2AF1)),
    )

    /** Services the generic fallback never takes: GAP, GATT, device information, battery. */
    private val STANDARD = setOf(short(0x1800), short(0x1801), short(0x180A), short(0x180F))

    /** Advertised service UUIDs that mark an OBD adapter in scan results. */
    val ADVERTISED: Set<UUID> = KNOWN.map { it.service }.toSet()

    fun choose(services: List<GattServiceInfo>): ElmUart? {
        val usable = services.filterNot { isWicanCli(it.uuid) }
        for (known in KNOWN) {
            val service = usable.firstOrNull { it.uuid == known.service } ?: continue
            val chars = service.characteristics.filterNot { isWicanCli(it.uuid) }
            val notify = chars.firstOrNull { it.uuid == known.notify && it.listens }
            val write = chars.firstOrNull { it.uuid == known.write && it.writable }
            if (notify != null && write != null) return uart(service.uuid, notify, write)
            generic(service.uuid, chars)?.let { return it }
        }
        for (service in usable) {
            if (service.uuid in STANDARD || KNOWN.any { it.service == service.uuid }) continue
            generic(service.uuid, service.characteristics.filterNot { isWicanCli(it.uuid) })?.let { return it }
        }
        return null
    }

    /** One characteristic doing both ways is preferred (HM-10 style), else the first listening and first writable. */
    private fun generic(service: UUID, chars: List<GattCharInfo>): ElmUart? {
        chars.firstOrNull { it.listens && it.writable }?.let { return uart(service, it, it) }
        val notify = chars.firstOrNull { it.listens } ?: return null
        val write = chars.firstOrNull { it.writable && it.uuid != notify.uuid } ?: return null
        return uart(service, notify, write)
    }

    private fun uart(service: UUID, notify: GattCharInfo, write: GattCharInfo) = ElmUart(
        service = service,
        notify = notify.uuid,
        write = write.uuid,
        indicate = !notify.canNotify && notify.canIndicate,
        writeWithResponse = write.canWrite,
    )

    fun isWicanCli(uuid: UUID): Boolean = uuid.toString().lowercase().let { text ->
        text.startsWith("bef0adde") || WICAN_CLI_SUFFIXES.any { text.endsWith(it) }
    }

    private val WICAN_CLI_SUFFIXES = listOf("c0de0001", "c0de0002", "c0de0003")

    /** A 16-bit Bluetooth SIG UUID on the base UUID. */
    fun short(value: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))
}
