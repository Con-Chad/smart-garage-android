package io.github.conchad.smartgarage.core.obd.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import io.github.conchad.smartgarage.core.obd.link.ObdAdapter
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.regex.Pattern
import kotlin.coroutines.resume

/** The first step of associating: the system dialog to launch, or why there is none. */
sealed interface AssociationStart {
    /** Launch with `ActivityResultContracts.StartIntentSenderForResult`, then pass the result to `onAssociationResult`. */
    data class Launch(val intentSender: IntentSender) : AssociationStart

    /** Already done without a dialog (rare). */
    data class Created(val adapter: ObdAdapter) : AssociationStart

    /** No companion device support (some ROMs strip it): use the adapter without association instead. */
    data object Unavailable : AssociationStart

    data class Failed(val message: String?) : AssociationStart
}

/**
 * CompanionDeviceManager (AOSP, no Play services; D-A80): the system's "allow Garage to manage <adapter>?" dialog,
 * and presence observation so the system starts [ObdPresenceService] when the adapter shows up. Presence uses
 * [ObservingDevicePresenceRequest] on Android 16+ and the MAC-based call before (deprecated there).
 */
class AdapterAssociation(private val context: Context) {
    private val manager: CompanionDeviceManager? = context.getSystemService(CompanionDeviceManager::class.java)

    val available: Boolean
        get() = manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    /**
     * Asks for the dialog for one adapter ([address] from our scan) or, without one, any device that looks like an
     * adapter by name. [onCreated] gets the association when Android reports it through the callback (13+).
     */
    suspend fun request(address: String?, name: String?, onCreated: (ObdAdapter) -> Unit): AssociationStart {
        val cdm = manager?.takeIf { available } ?: return AssociationStart.Unavailable
        val filter = BluetoothLeDeviceFilter.Builder().apply {
            if (address != null) setScanFilter(ScanFilter.Builder().setDeviceAddress(address).build())
            else setNamePattern(NAME_PATTERN)
        }.build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(address != null)
            .build()
        return suspendCancellableCoroutine { cont ->
            val callback = object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    if (cont.isActive) cont.resume(AssociationStart.Launch(intentSender))
                }

                @Deprecated("Android 12 path; 13+ calls onAssociationPending")
                override fun onDeviceFound(intentSender: IntentSender) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && cont.isActive) cont.resume(AssociationStart.Launch(intentSender))
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
                    val adapter = adapterOf(associationInfo, name) ?: return
                    if (cont.isActive) cont.resume(AssociationStart.Created(adapter)) else onCreated(adapter)
                }

                override fun onFailure(error: CharSequence?) {
                    if (cont.isActive) cont.resume(AssociationStart.Failed(error?.toString()))
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    cdm.associate(request, context.mainExecutor, callback)
                } else {
                    cdm.associate(request, callback, Handler(Looper.getMainLooper()))
                }
            } catch (e: RuntimeException) {
                if (cont.isActive) cont.resume(AssociationStart.Failed(e.message))
            }
        }
    }

    /** The association from the dialog's activity result; null when the user declined. */
    fun result(resultCode: Int, data: Intent?, name: String?): ObdAdapter? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            if (info != null) return adapterOf(info, name)
        }
        @Suppress("DEPRECATION")
        val scan = data.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE) ?: return null
        val address = scan.device?.address ?: return null
        return ObdAdapter(address, name ?: scan.scanRecord?.deviceName, associated = true)
    }

    /** Tells the system to start [ObdPresenceService] when the adapter appears (and again when it goes). */
    @SuppressLint("MissingPermission") // REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE is a normal permission, in the manifest.
    fun observe(adapter: ObdAdapter): Boolean {
        val cdm = manager ?: return false
        if (!adapter.usesPresence) return false
        return runCatching {
            val id = adapter.associationId
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && id != null) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(adapter.address)
            }
        }.isSuccess
    }

    @SuppressLint("MissingPermission")
    fun stopObserving(adapter: ObdAdapter) {
        val cdm = manager ?: return
        runCatching {
            val id = adapter.associationId
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && id != null) {
                cdm.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION")
                cdm.stopObservingDevicePresence(adapter.address)
            }
        }
    }

    /**
     * Ends the association. Removing the Bluetooth bond itself is only possible through CDM on Android 16+
     * ([CompanionDeviceManager.removeBond]); otherwise the user forgets it in Android's Bluetooth settings.
     * Returns whether the bond was removed.
     * TODO(CP4): say so in the "Forget adapter" text when this returns false.
     */
    fun forget(adapter: ObdAdapter): Boolean {
        val cdm = manager ?: return false
        stopObserving(adapter)
        var unbonded = false
        runCatching {
            val id = adapter.associationId ?: idFor(adapter.address)
            val canConnect = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (id != null && canConnect && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) unbonded = cdm.removeBond(id)
            if (id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.disassociate(id)
            } else {
                @Suppress("DEPRECATION")
                cdm.disassociate(adapter.address)
            }
        }
        return unbonded
    }

    /** Whether the system still has the association (the user can remove it in Android's settings). */
    fun isAssociated(adapter: ObdAdapter): Boolean {
        val cdm = manager ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.myAssociations.any { it.deviceMacAddress?.toString().equals(adapter.address, ignoreCase = true) }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations.any { it.equals(adapter.address, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    private fun idFor(address: String): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager?.myAssociations?.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }?.id
        } else {
            null
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun adapterOf(info: AssociationInfo, name: String?): ObdAdapter? {
        val mac = info.deviceMacAddress?.toString()?.uppercase() ?: return null
        return ObdAdapter(mac, name ?: info.displayName?.toString(), associated = true, associationId = info.id)
    }

    companion object {
        /** For the dialog without a chosen device: names adapters advertise. */
        private val NAME_PATTERN: Pattern = Pattern.compile("(?i).*(wican|obd|elm|vlink|v-link|vgate|veepeak|konnwei).*")
    }
}
