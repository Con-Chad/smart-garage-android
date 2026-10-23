package io.github.conchad.smartgarage.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class VehicleLocalPrefsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `moving a vehicle carries every per-vehicle key to the server id and leaves other vehicles alone`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("prefs.preferences_pb") }
        val prefs = VehicleLocalPrefs(dataStore)
        val temp = "tmp-00000000-0000-0000-0000-000000000001"
        prefs.setNickname(temp, "Roadster")
        prefs.setAuroraOverride(temp, AuroraChoice.Custom(0xFF7A3AB8.toInt()))
        prefs.snoozeOdometerReminder(temp, Instant.ofEpochMilli(1_000))
        prefs.setNickname("v1", "Daily")
        // A key type and prefix this class doesn't know yet still moves.
        dataStore.edit { it[booleanPreferencesKey("somethingNew.$temp")] = true }

        prefs.moveVehicle(temp, "v9")

        assertThat(prefs.nicknames.first()).containsExactly("v9", "Roadster", "v1", "Daily")
        assertThat(prefs.auroraOverrides.first()).containsExactly("v9", AuroraChoice.Custom(0xFF7A3AB8.toInt()))
        assertThat(prefs.odometerReminderSnoozedAt.first()).containsExactly("v9", Instant.ofEpochMilli(1_000))
        val raw = dataStore.data.first()
        assertThat(raw[booleanPreferencesKey("somethingNew.v9")]).isTrue()
        assertThat(raw.asMap().keys.map { it.name }.filter { it.endsWith(temp) }).isEmpty()
        assertThat(raw[stringPreferencesKey("nickname.v1")]).isEqualTo("Daily")
    }
}
