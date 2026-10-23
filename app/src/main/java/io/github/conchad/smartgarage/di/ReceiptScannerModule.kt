package io.github.conchad.smartgarage.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.conchad.smartgarage.core.network.ApiReceiptScanner
import io.github.conchad.smartgarage.core.network.ReceiptScanner

/**
 * Which [ReceiptScanner] the forms get (D-A27): the real multipart client since D-A53. Whether a form shows "Scan
 * receipt" at all is decided by `/me.features` (`ServerFeatures.scansFuel` / `scansService`), not here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReceiptScannerModule {
    @Binds
    abstract fun receiptScanner(client: ApiReceiptScanner): ReceiptScanner
}
