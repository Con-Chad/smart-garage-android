package io.github.conchad.smartgarage.core.obd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.conchad.smartgarage.MainActivity
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.obd.ObdController
import io.github.conchad.smartgarage.core.obd.trip.ObdError
import io.github.conchad.smartgarage.core.obd.trip.ObdStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive while the phone waits for or talks to the adapter (D-A70); type connectedDevice. The work
 * itself is [ObdController]'s session manager; this only starts it, mirrors its status in a small low-importance
 * notification ("Connected to HR-V · 84,512 mi") and stops when the manager stops (presence lost, user disconnect,
 * a fatal error). Being stopped by the system or from the notification ends the trip.
 */
@AndroidEntryPoint
class ObdForegroundService : Service() {
    @Inject lateinit var controller: ObdController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                controller.disconnect()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (!started) {
            ensureChannel(this)
            val ok = runCatching {
                startForeground(NOTIFICATION_ID, notification(ObdStatus.Connecting(null, waiting = true), null), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
            if (ok.isFailure) {
                // No BLUETOOTH_CONNECT yet, or a start Android doesn't allow from here: run without the service.
                controller.log("Foreground service refused: ${ok.exceptionOrNull()?.javaClass?.simpleName}")
                controller.startWithoutService()
                stopSelf()
                return START_NOT_STICKY
            }
            started = true
            observe()
        }
        scope.launch {
            controller.startStandby()
            // Nothing to wait for (no adapter or vehicle set, or it already failed): don't linger.
            if (!controller.running.value) stopSelf()
        }
        // Not sticky: CDM presence or the boot receiver start it again; a restart without them would only wait.
        return START_NOT_STICKY
    }

    private fun observe() {
        val manager = getSystemService(NotificationManager::class.java)
        scope.launch {
            combine(controller.status, controller.notificationTitle) { status, title -> status to title }
                .distinctUntilChanged()
                .collect { (status, title) -> manager.notify(NOTIFICATION_ID, notification(status, title)) }
        }
        scope.launch {
            // Wind down once the session manager has run and stopped.
            controller.running.dropWhile { !it }.first { !it }
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (started) controller.onServiceStopped()
        super.onDestroy()
    }

    private fun notification(status: ObdStatus, title: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ObdForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_garage)
            .setContentTitle(text(status, title))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.obd_notification_stop), stop)
            .build()
    }

    /** [title] is "<vehicle> · 84,512 mi" (or just the vehicle) from [ObdController.notificationTitle]. */
    private fun text(status: ObdStatus, title: String?): String = when (status) {
        is ObdStatus.Connected, is ObdStatus.Reading ->
            title?.let { getString(R.string.obd_notification_connected, it) } ?: getString(R.string.obd_notification_connected_plain)
        is ObdStatus.Pairing -> getString(R.string.obd_notification_pairing)
        is ObdStatus.Connecting -> if (status.waiting) getString(R.string.obd_notification_waiting) else getString(R.string.obd_notification_connecting)
        is ObdStatus.Error -> getString(if (status.reason == ObdError.PAIRING_FAILED) R.string.obd_notification_pairing_failed else R.string.obd_notification_error)
        ObdStatus.Disconnected, ObdStatus.Scanning -> getString(R.string.obd_notification_waiting)
    }

    companion object {
        const val CHANNEL_ID = "obd"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "io.github.conchad.smartgarage.obd.STOP"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.obd_channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = context.getString(R.string.obd_channel_desc)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }

        /** False when Android refused the start (background restrictions); the caller then runs without it. */
        fun start(context: Context): Boolean = runCatching {
            context.startForegroundService(Intent(context, ObdForegroundService::class.java))
        }.isSuccess
    }
}
