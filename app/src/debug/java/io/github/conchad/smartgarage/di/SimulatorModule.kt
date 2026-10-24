package io.github.conchad.smartgarage.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.conchad.smartgarage.core.obd.SimulatedTransport
import io.github.conchad.smartgarage.core.obd.link.SimulatorTransport
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

/** Debug builds only: fills ObdModule's optional simulator binding. Release has no such module, so no simulator code. */
@Module
@InstallIn(SingletonComponent::class)
object SimulatorModule {
    @Provides
    @Singleton
    fun simulator(): SimulatorTransport = SimulatedTransport(Dispatchers.Default)
}
