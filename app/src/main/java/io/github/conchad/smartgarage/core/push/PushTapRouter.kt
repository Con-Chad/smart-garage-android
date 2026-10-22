package io.github.conchad.smartgarage.core.push

import android.content.Context
import android.content.Intent
import io.github.conchad.smartgarage.MainActivity
import io.github.conchad.smartgarage.core.data.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries a tapped notification's [PushTarget] from [MainActivity] to the shell (D-A29). The notification may be
 * for a profile other than the active one, so the profile is made active first; `AppRoot` then rebuilds the shell
 * for it, and that shell consumes the target. Extras come from an exported activity's Intent, so they are
 * re-validated here (`PushTarget.validated`) before anything navigates.
 */
@Singleton
class PushTapRouter @Inject constructor(
    private val profiles: ProfileStore,
    private val scope: CoroutineScope,
) {
    private val _pending = MutableStateFlow<PushTarget?>(null)
    val pending: StateFlow<PushTarget?> = _pending.asStateFlow()

    fun open(intent: Intent?) {
        val target = targetOf(intent) ?: return
        scope.launch {
            if (profiles.profile(target.profileId) == null) return@launch
            profiles.setActive(target.profileId)
            _pending.value = target
        }
    }

    fun consumed(target: PushTarget) {
        _pending.compareAndSet(target, null)
    }

    companion object {
        private const val ACTION_OPEN = "io.github.conchad.smartgarage.OPEN_PUSH"
        private const val EXTRA_PROFILE = "profileId"
        private const val EXTRA_VEHICLE = "vehicleId"
        private const val EXTRA_SCHEDULE = "scheduleId"
        private const val EXTRA_URL = "url"

        fun intentFor(context: Context, target: PushTarget): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_PROFILE, target.profileId)
                target.vehicleId?.let { putExtra(EXTRA_VEHICLE, it) }
                target.scheduleId?.let { putExtra(EXTRA_SCHEDULE, it) }
                target.url?.let { putExtra(EXTRA_URL, it) }
            }

        fun targetOf(intent: Intent?): PushTarget? {
            if (intent?.action != ACTION_OPEN) return null
            return PushTarget.validated(
                profileId = intent.getStringExtra(EXTRA_PROFILE),
                vehicleId = intent.getStringExtra(EXTRA_VEHICLE),
                scheduleId = intent.getStringExtra(EXTRA_SCHEDULE),
                url = intent.getStringExtra(EXTRA_URL),
            )
        }
    }
}
