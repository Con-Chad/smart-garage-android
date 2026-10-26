package io.github.conchad.smartgarage.core.obd.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** The "obd" preferences DataStore, apart from the app's (D-A73). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ObdPrefs

/** [ObdSettings] per server profile, each one JSON value under `settings.<profileId>`. */
@Singleton
class ObdSettingsStore @Inject constructor(@param:ObdPrefs private val dataStore: DataStore<Preferences>) {

    fun settings(profileId: String): Flow<ObdSettings> =
        dataStore.data.map { ObdSettings.decode(it[key(profileId)]) }.distinctUntilChanged()

    suspend fun get(profileId: String): ObdSettings = settings(profileId).first()

    /** Every profile that has OBD settings stored. */
    suspend fun all(): Map<String, ObdSettings> = dataStore.data.first().asMap().entries
        .filter { it.key.name.startsWith(PREFIX) }
        .associate { (key, value) -> key.name.removePrefix(PREFIX) to ObdSettings.decode(value as? String) }

    suspend fun update(profileId: String, transform: (ObdSettings) -> ObdSettings): ObdSettings {
        var result = ObdSettings()
        dataStore.edit { prefs ->
            result = transform(ObdSettings.decode(prefs[key(profileId)]))
            prefs[key(profileId)] = result.encode()
        }
        return result
    }

    suspend fun remove(profileId: String) {
        dataStore.edit { it.remove(key(profileId)) }
    }

    private fun key(profileId: String) = stringPreferencesKey(PREFIX + profileId)

    private companion object {
        const val PREFIX = "settings."
    }
}
