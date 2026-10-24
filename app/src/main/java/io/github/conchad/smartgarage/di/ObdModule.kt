package io.github.conchad.smartgarage.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.conchad.smartgarage.core.obd.data.ObdDatabase
import io.github.conchad.smartgarage.core.obd.data.ObdPrefs
import io.github.conchad.smartgarage.core.obd.link.SimulatorTransport
import javax.inject.Singleton

private val Context.obdDataStore: DataStore<Preferences> by preferencesDataStore(name = "obd")

/** OBD storage (D-A70). The simulator is optional: only debug builds bind it (src/debug `SimulatorModule`). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ObdModule {
    @BindsOptionalOf
    abstract fun simulator(): SimulatorTransport

    companion object {
        @Provides
        @Singleton
        @ObdPrefs
        fun obdDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.obdDataStore

        /** No destructive fallback: unsent readings live only here. A schema change ships a Migration. */
        @Provides
        @Singleton
        fun obdDatabase(@ApplicationContext context: Context): ObdDatabase =
            Room.databaseBuilder(context, ObdDatabase::class.java, ObdDatabase.FILE).build()
    }
}
