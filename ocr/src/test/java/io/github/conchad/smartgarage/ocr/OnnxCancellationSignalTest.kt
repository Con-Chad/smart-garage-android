// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: kotlin.test assertions rewritten for JUnit4 + Truth; PointF replaced by OcrPoint.
package io.github.conchad.smartgarage.ocr

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class OnnxCancellationSignalTest {
    @Test
    fun rejectsWorkCancelledBeforeInferenceRegistration() {
        val signal = OnnxCancellationSignal()

        signal.cancel()

        assertThat(signal.isCancelled).isTrue()
        assertThrows(CancellationException::class.java) {
            signal.ensureActive()
        }
    }

    @Test
    fun freshSignalIsActive() {
        val signal = OnnxCancellationSignal()

        assertThat(signal.isCancelled).isFalse()
        signal.ensureActive()
    }
}
