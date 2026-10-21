package io.github.conchad.smartgarage.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.conchad.smartgarage.BuildConfig
import io.github.conchad.smartgarage.core.network.HttpClientFactory
import io.github.conchad.smartgarage.core.security.KeystoreSecretCipher
import io.github.conchad.smartgarage.core.security.SecretCipher
import io.ktor.client.HttpClient
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "app")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.appDataStore

    @Provides
    @Singleton
    fun secretCipher(): SecretCipher = KeystoreSecretCipher()

    /** The server stores this per device, so the web's Mobile devices list shows app and Android version. */
    @Provides
    @Singleton
    fun httpClient(): HttpClient =
        HttpClientFactory.create(userAgent = "SmartGarageAndroid/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")
}
