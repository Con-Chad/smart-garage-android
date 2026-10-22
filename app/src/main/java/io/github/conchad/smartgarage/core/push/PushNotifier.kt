package io.github.conchad.smartgarage.core.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.MainActivity
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.data.ProfileStore
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows a push message as a notification on the one "Garage reminders" channel (D-A29). Tapping opens the app at
 * the vehicle, the schedule or the matching section ([PushTapRouter]); "Open on web" hands the server's own URL to
 * the browser, since the app has no WebView. Nothing is logged: the text may name the user's cars.
 */
@Singleton
class PushNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileStore,
) {
    private val ids = AtomicInteger((System.currentTimeMillis() and 0xFFFF).toInt())

    /** Whether a notification would be seen: the runtime permission (API 33+) and the app/channel not blocked. */
    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.push_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.push_channel_desc)
        manager.createNotificationChannel(channel)
    }

    suspend fun show(profileId: String, payload: PushPayload) {
        if (!canNotify()) return
        ensureChannel()
        val id = ids.incrementAndGet()
        val target = PushTarget.of(profileId, payload)
        val open = PendingIntent.getActivity(
            context,
            id,
            PushTapRouter.intentFor(context, target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_garage)
            .setContentTitle(payload.title)
            .setContentText(payload.body.lineSequence().firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val base = profiles.profile(profileId)?.baseUrl?.trimEnd('/')
        if (base != null && payload.url != null) {
            val web = PendingIntent.getActivity(
                context,
                id xor 0x10000,
                Intent(Intent.ACTION_VIEW, (base + payload.url).toUri()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, context.getString(R.string.push_action_open_web), web)
        }
        // Permission checked in canNotify(); lint can't follow a check in another method.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < 33
        ) {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }
    }

    companion object {
        const val CHANNEL_ID = "garage_reminders"
    }
}
