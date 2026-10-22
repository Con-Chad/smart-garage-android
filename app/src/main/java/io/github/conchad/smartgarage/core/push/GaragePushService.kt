package io.github.conchad.smartgarage.core.push

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.conchad.smartgarage.core.network.ServerIds
import io.github.conchad.smartgarage.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives UnifiedPush events from the connector (D-A29). The connector's own receiver decrypts the Web Push body
 * with the instance's keys and binds to this service for about a second per event, so every callback hands off to
 * the application scope at once. The `instance` is a profile id; anything that doesn't look like one is ignored.
 *
 * Like [io.github.conchad.smartgarage.core.sync.SyncWorker], this uses a Hilt entry point rather than making the
 * service an Android entry point: the connector instantiates it, and one accessor is all it needs.
 */
class GaragePushService : PushService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun registrar(): PushRegistrar
        fun notifier(): PushNotifier
        fun syncEngine(): SyncEngine
        fun scope(): CoroutineScope
    }

    private val deps: Deps by lazy { EntryPointAccessors.fromApplication(applicationContext, Deps::class.java) }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val profileId = profileId(instance) ?: return
        deps.scope().launch {
            deps.registrar().onNewEndpoint(profileId, endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth)
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val profileId = profileId(instance) ?: return
        // Not decrypted means the connector couldn't open it (keys gone?): nothing to show, but the server did send
        // something, so a sync still runs and the next start re-registers.
        val payload = if (message.decrypted) PushPayload.parse(message.content) else null
        deps.scope().launch {
            if (payload != null) deps.notifier().show(profileId, payload)
            deps.syncEngine().syncSoon(profileId)
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        val profileId = profileId(instance) ?: return
        deps.scope().launch { deps.registrar().onRegistrationFailed(profileId, reason) }
    }

    override fun onUnregistered(instance: String) {
        val profileId = profileId(instance) ?: return
        deps.scope().launch { deps.registrar().onUnregistered(profileId) }
    }

    private fun profileId(instance: String): String? = instance.takeIf(ServerIds::isValid)
}
