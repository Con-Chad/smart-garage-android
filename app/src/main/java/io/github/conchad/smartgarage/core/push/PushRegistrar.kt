package io.github.conchad.smartgarage.core.push

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.data.PushProblem
import io.github.conchad.smartgarage.core.data.PushSessionHook
import io.github.conchad.smartgarage.core.data.PushSettings
import io.github.conchad.smartgarage.core.data.PushSubscription
import io.github.conchad.smartgarage.core.data.ServerProfile
import io.github.conchad.smartgarage.core.network.ApiClient
import io.github.conchad.smartgarage.core.network.ApiResult
import io.github.conchad.smartgarage.core.network.PushKeysDto
import io.github.conchad.smartgarage.core.network.PushSubscriptionRequest
import io.github.conchad.smartgarage.core.network.ServerConnection
import io.github.conchad.smartgarage.core.network.ServerIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A UnifiedPush distributor installed on the phone, for the picker in Settings. */
data class Distributor(val packageName: String, val label: String)

sealed interface PushEnableOutcome {
    /** The distributor was asked; the endpoint arrives in `onNewEndpoint` and the server PUT follows. */
    data object Requested : PushEnableOutcome

    /** No distributor installed, or none chosen yet while several are. */
    data class NeedsDistributor(val installed: List<Distributor>) : PushEnableOutcome

    /** Plain-http profile without the background opt-in (D-A28 via D-A29). */
    data object CleartextBlocked : PushEnableOutcome

    data object NotSignedIn : PushEnableOutcome
}

sealed interface PushTestOutcome {
    data object Sent : PushTestOutcome
    data object NotRegistered : PushTestOutcome
    data class RateLimited(val retryAfterSeconds: Long?) : PushTestOutcome

    /** The distributor no longer knows the endpoint; a fresh registration was requested. */
    data object SubscriptionGone : PushTestOutcome
    data class Failed(val message: String?) : PushTestOutcome
    data object Unreachable : PushTestOutcome
}

/**
 * Push registration for every profile (D-A29, docs/mobile/PUSH.md): the UnifiedPush `instance` is the profile id,
 * so a phone with two servers holds two registrations with the one distributor the connector keeps.
 *
 * State lives in [PushSettings] on the profile (visible to Settings) and the subscription keys in the encrypted
 * secret store. The connector owns the Web Push private key (AES-GCM under a Keystore key, Tink does the RFC 8291
 * decryption) and only ever hands out the public `p256dh` + `auth`; the app never sees or stores a private key.
 *
 * Transitions, in order of a normal life:
 * 1. [enable]: save the distributor, fetch the VAPID key, mark `enabled`, ask the distributor to register.
 * 2. [onNewEndpoint]: `PUT /devices/{id}/push`; success stores the subscription and `registeredAt`.
 * 3. [refresh] on every app start: nothing while the registration is younger than a day; else PUT the stored
 *    subscription again, or re-register with the distributor when the VAPID key changed or no subscription exists.
 * 4. [disable] (switch off): DELETE on the server, unregister with the distributor, forget everything.
 * 5. [forget] (sign-out, revoked token, profile removed): unregister with the distributor only; the server's logout
 *    already ended the device and its push. `enabled` stays, so signing back in re-registers (contract §2).
 */
@Singleton
class PushRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiClient,
    private val profiles: ProfileStore,
) : PushSessionHook {

    /** Installed distributors with their launcher labels, for the picker. */
    fun distributors(): List<Distributor> = UnifiedPush.getDistributors(context).map { Distributor(it, label(it)) }

    fun savedDistributor(): String? = UnifiedPush.getSavedDistributor(context)

    fun label(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /**
     * Turns push on for [profileId]. [distributor] is the user's pick; null means "the saved one, or the only one
     * installed". The registration itself completes in [onNewEndpoint].
     */
    suspend fun enable(profileId: String, distributor: String? = null): PushEnableOutcome = withContext(Dispatchers.Default) {
        val profile = profiles.profile(profileId) ?: return@withContext PushEnableOutcome.NotSignedIn
        if (!profile.pushAllowed) return@withContext PushEnableOutcome.CleartextBlocked
        val connection = profiles.connection(profileId)?.takeIf { it.token != null } ?: return@withContext PushEnableOutcome.NotSignedIn

        val installed = distributors()
        val chosen = distributor?.takeIf { pkg -> installed.any { it.packageName == pkg } }
            ?: savedDistributor()?.takeIf { pkg -> installed.any { it.packageName == pkg } }
            ?: installed.singleOrNull()?.packageName
            ?: run {
                profiles.updatePush(profileId) { it.copy(enabled = true, problem = PushProblem.NoDistributor, problemDetail = null) }
                return@withContext PushEnableOutcome.NeedsDistributor(installed)
            }
        if (chosen != savedDistributor()) UnifiedPush.saveDistributor(context, chosen)

        val vapid = when (val result = api.pushVapid(connection)) {
            is ApiResult.Ok -> result.value.publicKey
            is ApiResult.HttpError -> {
                profiles.updatePush(profileId) { it.copy(enabled = true, distributor = chosen, problem = PushProblem.ServerRejected, problemDetail = null) }
                return@withContext PushEnableOutcome.Requested
            }
            is ApiResult.Failed -> {
                profiles.updatePush(profileId) { it.copy(enabled = true, distributor = chosen, problem = PushProblem.Unreachable, problemDetail = null) }
                return@withContext PushEnableOutcome.Requested
            }
        }
        profiles.updatePush(profileId) {
            it.copy(enabled = true, distributor = chosen, vapidKey = vapid, problem = null, problemDetail = null)
        }
        registerWithDistributor(profileId, profile, vapid)
        PushEnableOutcome.Requested
    }

    /** Switch off: server first (best effort), then the distributor, then local state. */
    suspend fun disable(profileId: String) = withContext(Dispatchers.Default) {
        deleteQuietly(profileId)
        UnifiedPush.unregister(context, profileId)
        profiles.clearPushSubscription(profileId) { PushSettings(enabled = false, distributor = it.distributor, vapidKey = it.vapidKey) }
    }

    /** Session ended (sign-out, revoked token) or profile removed: the distributor registration must not outlive it. */
    override suspend fun forget(profileId: String) {
        withContext(Dispatchers.Default) {
            runCatching { UnifiedPush.unregister(context, profileId) }
            if (profiles.profile(profileId) != null) {
                profiles.clearPushSubscription(profileId) { it.copy(registeredAt = null, problem = null, problemDetail = null) }
            }
        }
    }

    /**
     * App-start check (contract §2): re-registers when the stored registration is older than a day, when the server's
     * VAPID key changed, when the distributor was lost, or when push is on but nothing is registered yet.
     */
    suspend fun refresh(profileId: String, now: Instant = Instant.now(), force: Boolean = false) = withContext(Dispatchers.Default) {
        val profile = profiles.profile(profileId) ?: return@withContext
        val push = profile.push
        if (!push.enabled || !profile.pushAllowed) return@withContext
        val connection = profiles.connection(profileId)?.takeIf { it.token != null } ?: return@withContext

        if (savedDistributor() == null) {
            profiles.updatePush(profileId) { it.copy(problem = PushProblem.NoDistributor, problemDetail = null) }
            return@withContext
        }
        val subscription = profiles.pushSubscription(profileId)
        val retryable = push.problem in setOf(PushProblem.Unreachable, PushProblem.ServerRejected, PushProblem.Unregistered, PushProblem.NoDistributor)
        if (subscription != null && !force && !retryable && !needsReregister(push.registeredAt, now)) return@withContext

        val vapid = when (val result = api.pushVapid(connection)) {
            is ApiResult.Ok -> result.value.publicKey
            // Offline or a server hiccup: keep what we have and try again on the next start.
            else -> return@withContext
        }
        if (subscription == null || vapid != push.vapidKey) {
            profiles.updatePush(profileId) { it.copy(vapidKey = vapid, problem = null, problemDetail = null) }
            registerWithDistributor(profileId, profile, vapid)
        } else {
            putSubscription(profileId, connection, profile, subscription)
        }
    }

    /** The distributor delivered (or renewed) an endpoint for this instance: tell the server (contract §1 step 3). */
    suspend fun onNewEndpoint(profileId: String, endpoint: String, p256dh: String?, auth: String?) = withContext(Dispatchers.Default) {
        val profile = profiles.profile(profileId) ?: return@withContext
        if (!profile.push.enabled) {
            // Switched off meanwhile: make sure the distributor forgets us too.
            UnifiedPush.unregister(context, profileId)
            return@withContext
        }
        if (p256dh == null || auth == null) {
            profiles.updatePush(profileId) { it.copy(problem = PushProblem.NoKeys, problemDetail = null) }
            return@withContext
        }
        val connection = profiles.connection(profileId)?.takeIf { it.token != null } ?: return@withContext
        putSubscription(profileId, connection, profile, PushSubscription(endpoint, p256dh, auth))
    }

    suspend fun onRegistrationFailed(profileId: String, reason: FailedReason) {
        val problem = when (reason) {
            FailedReason.NETWORK -> PushProblem.NetworkRequired
            FailedReason.ACTION_REQUIRED -> PushProblem.ActionRequired
            FailedReason.INTERNAL_ERROR, FailedReason.VAPID_REQUIRED -> PushProblem.DistributorFailed
        }
        profiles.updatePush(profileId) { it.copy(problem = problem, problemDetail = null) }
    }

    /** The distributor dropped the registration: the server must stop sending (contract §2). */
    suspend fun onUnregistered(profileId: String) = withContext(Dispatchers.Default) {
        if (profiles.profile(profileId) == null) return@withContext
        deleteQuietly(profileId)
        profiles.clearPushSubscription(profileId) { it.copy(registeredAt = null, problem = PushProblem.Unregistered, problemDetail = null) }
    }

    suspend fun sendTest(profileId: String): PushTestOutcome = withContext(Dispatchers.Default) {
        val profile = profiles.profile(profileId) ?: return@withContext PushTestOutcome.NotRegistered
        val deviceId = profile.validDeviceId() ?: return@withContext PushTestOutcome.NotRegistered
        val connection = profiles.connection(profileId)?.takeIf { it.token != null } ?: return@withContext PushTestOutcome.NotRegistered
        when (val result = api.testPush(connection, deviceId)) {
            is ApiResult.Ok -> PushTestOutcome.Sent
            is ApiResult.HttpError -> when {
                result.status == 409 -> {
                    refresh(profileId, force = true)
                    PushTestOutcome.NotRegistered
                }
                result.status == 429 -> PushTestOutcome.RateLimited(result.retryAfterSeconds)
                result.code == "push_subscription_gone" -> {
                    profiles.clearPushSubscription(profileId) { it.copy(registeredAt = null, problem = PushProblem.Unregistered, problemDetail = null) }
                    profile.push.vapidKey?.let { registerWithDistributor(profileId, profile, it) }
                    PushTestOutcome.SubscriptionGone
                }
                else -> PushTestOutcome.Failed(result.message)
            }
            is ApiResult.Failed -> PushTestOutcome.Unreachable
        }
    }

    private suspend fun registerWithDistributor(profileId: String, profile: ServerProfile, vapid: String) {
        try {
            UnifiedPush.register(
                context,
                instance = ServerIds.require(profileId),
                messageForDistributor = context.getString(R.string.push_distributor_message, profile.name),
                vapid = vapid,
            )
        } catch (_: UnifiedPush.VapidNotValidException) {
            // The connector checks the key's shape; a server handing out something else can't be registered with.
            profiles.updatePush(profileId) { it.copy(problem = PushProblem.ServerRejected, problemDetail = null) }
        }
    }

    private suspend fun putSubscription(profileId: String, connection: ServerConnection, profile: ServerProfile, subscription: PushSubscription) {
        val deviceId = profile.validDeviceId() ?: run {
            profiles.updatePush(profileId) { it.copy(problem = PushProblem.ServerRejected, problemDetail = null) }
            return
        }
        val request = PushSubscriptionRequest(subscription.endpoint, PushKeysDto(subscription.p256dh, subscription.auth))
        when (val result = api.registerPush(connection, deviceId, request)) {
            is ApiResult.Ok -> profiles.savePushSubscription(profileId, subscription) {
                it.copy(registeredAt = Instant.now().toString(), problem = null, problemDetail = null)
            }
            is ApiResult.HttpError -> when {
                result.status == 401 -> Unit // the session layer handles a revoked token
                result.code == "endpoint_rejected" -> profiles.savePushSubscription(profileId, subscription) {
                    it.copy(registeredAt = null, problem = PushProblem.EndpointRejected, problemDetail = result.message)
                }
                else -> profiles.savePushSubscription(profileId, subscription) {
                    it.copy(registeredAt = null, problem = PushProblem.ServerRejected, problemDetail = null)
                }
            }
            // Keep the subscription: refresh() retries the PUT on the next start.
            is ApiResult.Failed -> profiles.savePushSubscription(profileId, subscription) {
                it.copy(registeredAt = null, problem = PushProblem.Unreachable, problemDetail = null)
            }
        }
    }

    private suspend fun deleteQuietly(profileId: String) {
        val profile = profiles.profile(profileId) ?: return
        val deviceId = profile.validDeviceId() ?: return
        val connection = profiles.connection(profileId)?.takeIf { it.token != null } ?: return
        withTimeoutOrNull(DELETE_TIMEOUT_MS) { api.unregisterPush(connection, deviceId) }
    }

    /** The device id came from the server at login; it is checked like every other server id before use (D-A28). */
    private fun ServerProfile.validDeviceId(): String? = account?.deviceId?.takeIf(ServerIds::isValid)

    companion object {
        private const val DELETE_TIMEOUT_MS = 5_000L

        /** Contract §2: a registration older than this is PUT again at app start, defensively. */
        val REREGISTER_AFTER: Duration = Duration.ofDays(1)

        /** The stale-registration decision: never registered, unparseable, a clock that went backwards, or too old. */
        fun needsReregister(registeredAt: String?, now: Instant, maxAge: Duration = REREGISTER_AFTER): Boolean {
            val at = registeredAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
            val age = Duration.between(at, now)
            return age.isNegative || age >= maxAge
        }
    }
}
