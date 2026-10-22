package io.github.conchad.smartgarage.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.conchad.smartgarage.core.network.ReceiptScanner
import io.github.conchad.smartgarage.core.network.StubReceiptScanner

/**
 * Which [ReceiptScanner] the fill-up form gets (D-A27). Swapping the stub for the real multipart client is this one
 * binding; nothing on the screen changes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReceiptScannerModule {
    @Binds
    abstract fun receiptScanner(stub: StubReceiptScanner): ReceiptScanner
}
