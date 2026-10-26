package io.github.conchad.smartgarage.core.obd

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** What the OBD feature can do on this phone right now, for CP4's explanation screen (D-A73). */
data class ObdPermissionState(
    /** Runtime permissions still to ask for, in [ObdPermissions.RUNTIME] order. */
    val missing: List<String>,
    val bluetoothLe: Boolean,
    val bluetoothOn: Boolean,
    /** CompanionDeviceManager is there (AOSP; a ROM may strip it): without it, the autoConnect fallback. */
    val companionDevices: Boolean,
) {
    val canScan: Boolean get() = bluetoothLe && Manifest.permission.BLUETOOTH_SCAN !in missing
    val canConnect: Boolean get() = bluetoothLe && Manifest.permission.BLUETOOTH_CONNECT !in missing
    val canNotify: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Manifest.permission.POST_NOTIFICATIONS !in missing
}

object ObdPermissions {
    /**
     * Scan (declared neverForLocation, so no location), connect, and notifications for the connected-device
     * service's notification (Android 13+; without it the service still runs, its notification is just hidden).
     */
    val RUNTIME: List<String> = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun missing(context: Context): List<String> =
        RUNTIME.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun state(context: Context): ObdPermissionState {
        val pm = context.packageManager
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
        return ObdPermissionState(
            missing = missing(context),
            bluetoothLe = bluetooth != null && pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            bluetoothOn = bluetooth?.isEnabled == true,
            companionDevices = pm.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP),
        )
    }
}
