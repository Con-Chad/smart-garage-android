package io.github.conchad.smartgarage.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.conchad.smartgarage.ocr.OcrEngine
import io.github.conchad.smartgarage.ocr.OnnxOcrEngine
import javax.inject.Singleton

/**
 * The on-device [OcrEngine] (PP-OCRv5, models bundled in the APK). One instance for the process so the ONNX
 * sessions are loaded once; loading is lazy (first recognise), never at startup.
 */
@Module
@InstallIn(SingletonComponent::class)
object OcrModule {
    @Provides
    @Singleton
    fun ocrEngine(@ApplicationContext context: Context): OcrEngine = OnnxOcrEngine(context)
}
