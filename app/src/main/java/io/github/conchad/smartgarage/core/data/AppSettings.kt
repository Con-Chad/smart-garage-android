package io.github.conchad.smartgarage.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { System, Light, Dark }

data class AppearanceSettings(
    /** Dark by default (D-A10); System and Light stay available in Settings. */
    val themeMode: ThemeMode = ThemeMode.Dark,
    /** Material You colors from the wallpaper; off falls back to the web app's blue. */
    val dynamicColor: Boolean = true,
)

data class FuelSettings(
    /**
     * Whether fill-ups ask for the odometer and the app shows consumption (D-A25). Off hides the field and the
     * figures on this device only; entries already carrying an odometer keep it, and the server is unaffected.
     */
    val trackFuelEconomy: Boolean = true,
)

/** Device-local UI settings. Units and locale come from the server instead (see [DisplayPreferences]). */
@Singleton
class AppSettings @Inject constructor(private val dataStore: DataStore<Preferences>) {

    val appearance: Flow<AppearanceSettings> = dataStore.data
        .map { prefs ->
            AppearanceSettings(
                themeMode = prefs[THEME_MODE]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                    ?: ThemeMode.Dark,
                dynamicColor = prefs[DYNAMIC_COLOR] ?: true,
            )
        }
        .distinctUntilChanged()

    val fuel: Flow<FuelSettings> = dataStore.data
        .map { prefs -> FuelSettings(trackFuelEconomy = prefs[TRACK_FUEL_ECONOMY] ?: true) }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    suspend fun setTrackFuelEconomy(enabled: Boolean) {
        dataStore.edit { it[TRACK_FUEL_ECONOMY] = enabled }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val TRACK_FUEL_ECONOMY = booleanPreferencesKey("track_fuel_economy")
    }
}
