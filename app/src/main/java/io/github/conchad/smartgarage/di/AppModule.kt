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
import io.github.conchad.smartgarage.core.cache.CacheProvider
import io.github.conchad.smartgarage.core.data.ProfileDataCleaner
import io.github.conchad.smartgarage.core.data.PushSessionHook
import io.github.conchad.smartgarage.core.network.HttpClientFactory
import io.github.conchad.smartgarage.core.push.PushRegistrar
import io.github.conchad.smartgarage.core.security.KeystoreSecretCipher
import io.github.conchad.smartgarage.core.security.SecretCipher
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "app")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.appDataStore

    @Provides
    fun profileDataCleaner(cache: CacheProvider): ProfileDataCleaner = cache

    /** Sign-out and revoked tokens also end the UnifiedPush registration (D-A29). */
    @Provides
    fun pushSessionHook(registrar: PushRegistrar): PushSessionHook = registrar

    @Provides
    @Singleton
    fun secretCipher(): SecretCipher = KeystoreSecretCipher()

    /** Work that must outlive the screen that started it, such as the sync kicked off by a queued write. */
    @Provides
    @Singleton
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The server stores this per device, so the web's Mobile devices list shows app and Android version. */
    @Provides
    @Singleton
    fun httpClient(): HttpClient =
        HttpClientFactory.create(userAgent = "SmartGarageAndroid/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")
}
