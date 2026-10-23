package io.github.conchad.smartgarage.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
 * odometer reminder was last snoozed, and the aurora color chosen for it (D-A44). Keyed by the server's vehicle
 * id, in the same DataStore as [AppSettings]; every key is `<prefix>.<vehicleId>` (see [moveVehicle]).
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

    /** The owner's name as shown on this phone (D-A43), by vehicle id; absent means the server's owner name. */
    val ownerNames: Flow<Map<String, String>> = dataStore.data
        .map { prefs ->
            prefs.asMap().entries
                .filter { it.key.name.startsWith(OWNER_NAME) }
                .mapNotNull { (key, value) -> (value as? String)?.takeIf { it.isNotBlank() }?.let { key.name.removePrefix(OWNER_NAME) to it } }
                .toMap()
        }
        .distinctUntilChanged()

    /** Blank clears the override. */
    suspend fun setOwnerName(vehicleId: String, name: String?) {
        val key = stringPreferencesKey(OWNER_NAME + vehicleId)
        val trimmed = name?.trim().orEmpty()
        dataStore.edit { if (trimmed.isEmpty()) it.remove(key) else it[key] = trimmed }
    }

    /**
     * Each vehicle's aurora override (D-A44), by vehicle id. Absent means "follow the app-wide choice in Settings";
     * [AuroraChoice.MatchVehicle] means this car keeps its own color even when Settings picks one.
     */
    val auroraOverrides: Flow<Map<String, AuroraChoice>> = dataStore.data
        .map { prefs ->
            prefs.asMap().entries
                .filter { it.key.name.startsWith(AURORA) }
                .mapNotNull { (key, value) -> AuroraChoice.decode(value as? String)?.let { key.name.removePrefix(AURORA) to it } }
                .toMap()
        }
        .distinctUntilChanged()

    /** Null clears the override, so the vehicle follows Settings again. */
    suspend fun setAuroraOverride(vehicleId: String, choice: AuroraChoice?) {
        val key = stringPreferencesKey(AURORA + vehicleId)
        dataStore.edit { if (choice == null) it.remove(key) else it[key] = choice.encode() }
    }

    /**
     * Moves everything stored for [from] to [to] (D-A44): called by the outbox replay when a vehicle created offline
     * gets its server id. Generic on purpose: every key named `<prefix>.<from>` moves, whatever the prefix and type,
     * so a per-vehicle pref added later survives the id swap without touching this.
     */
    suspend fun moveVehicle(from: String, to: String) {
        if (from == to || from.isEmpty()) return
        dataStore.edit { prefs ->
            val suffix = ".$from"
            // Snapshot first: asMap() is a live view of the prefs being edited.
            val moving = prefs.asMap().entries.filter { it.key.name.endsWith(suffix) }.map { it.key to it.value }
            moving.forEach { (key, value) ->
                val target = key.name.removeSuffix(from) + to
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is String -> prefs[stringPreferencesKey(target)] = value
                    is Long -> prefs[longPreferencesKey(target)] = value
                    is Int -> prefs[intPreferencesKey(target)] = value
                    is Boolean -> prefs[booleanPreferencesKey(target)] = value
                    is Float -> prefs[floatPreferencesKey(target)] = value
                    is Double -> prefs[doublePreferencesKey(target)] = value
                    is Set<*> -> prefs[stringSetPreferencesKey(target)] = value as Set<String>
                    else -> return@forEach // A type DataStore added later: left where it is rather than lost.
                }
                prefs.remove(key)
            }
        }
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
        const val AURORA = "auroraColor."
        const val OWNER_NAME = "ownerName."
    }
}
