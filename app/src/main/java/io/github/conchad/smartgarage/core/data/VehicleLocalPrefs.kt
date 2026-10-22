package io.github.conchad.smartgarage.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-vehicle settings that live only on this phone (D-A24): a nickname for the home and vehicles cards (the
 * server has no nickname field, and `vehiclePatchSchema` can't rename a make/model/year), and when the monthly
 * odometer reminder was last snoozed. Keyed by the server's vehicle id, in the same DataStore as [AppSettings].
 */
@Singleton
class VehicleLocalPrefs @Inject constructor(private val dataStore: DataStore<Preferences>) {

    /** Every nickname set on this phone, by vehicle id; blank ones were cleared and are absent. */
    val nicknames: Flow<Map<String, String>> = dataStore.data
        .map { prefs ->
            prefs.asMap().entries
                .filter { it.key.name.startsWith(NICKNAME) }
                .mapNotNull { (key, value) -> (value as? String)?.takeIf { it.isNotBlank() }?.let { key.name.removePrefix(NICKNAME) to it } }
                .toMap()
        }
        .distinctUntilChanged()

    fun nickname(vehicleId: String): Flow<String?> = nicknames.map { it[vehicleId] }.distinctUntilChanged()

    /** Blank clears the nickname. */
    suspend fun setNickname(vehicleId: String, nickname: String?) {
        val key = stringPreferencesKey(NICKNAME + vehicleId)
        val trimmed = nickname?.trim().orEmpty()
        dataStore.edit { if (trimmed.isEmpty()) it.remove(key) else it[key] = trimmed }
    }

    /** When the odometer reminder was last snoozed, by vehicle id. */
    val odometerReminderSnoozedAt: Flow<Map<String, Instant>> = dataStore.data
        .map { prefs ->
            prefs.asMap().entries
                .filter { it.key.name.startsWith(SNOOZED) }
                .mapNotNull { (key, value) -> (value as? Long)?.let { key.name.removePrefix(SNOOZED) to Instant.ofEpochMilli(it) } }
                .toMap()
        }
        .distinctUntilChanged()

    suspend fun snoozeOdometerReminder(vehicleId: String, at: Instant = Instant.now()) {
        dataStore.edit { it[longPreferencesKey(SNOOZED + vehicleId)] = at.toEpochMilli() }
    }

    private companion object {
        const val NICKNAME = "nickname."
        const val SNOOZED = "odometerReminderSnoozedAt."
    }
}
