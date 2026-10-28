package io.github.conchad.smartgarage.core.obd.device

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.conchad.smartgarage.core.obd.ObdController

/**
 * Bound by the system while an associated adapter is near (D-A80). Appearing starts the foreground service (an app
 * with a companion association may start one from the background); disappearing lets it wind down once the trip is
 * over. Android 16+ reports [DevicePresenceEvent]s, 13–15 the [AssociationInfo] callbacks, 12 the MAC ones; each
 * handler is idempotent, so a release that calls more than one does no harm.
 */
class ObdPresenceService : CompanionDeviceService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun obdController(): ObdController
    }

    private val controller: ObdController
        get() = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).obdController()

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> controller.onPresence(appeared = true)
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED, DevicePresenceEvent.EVENT_BT_DISCONNECTED -> controller.onPresence(appeared = false)
            else -> Unit
        }
    }

    @Deprecated("Android 13–15 path")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = controller.onPresence(appeared = true)

    @Deprecated("Android 13–15 path")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = controller.onPresence(appeared = false)

    @Deprecated("Android 12 path")
    override fun onDeviceAppeared(address: String) = controller.onPresence(appeared = true)

    @Deprecated("Android 12 path")
    override fun onDeviceDisappeared(address: String) = controller.onPresence(appeared = false)
}
