// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: kotlin.test assertions rewritten for JUnit4 + Truth; PointF replaced by OcrPoint.
package io.github.conchad.smartgarage.ocr.internal

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.ocr.OcrPoint
import io.github.conchad.smartgarage.ocr.TextBox
import org.junit.Test

class CharacterBoxGeometryTest {
    private val verticalBox = TextBox(
        listOf(
            OcrPoint(10f, 10f),
            OcrPoint(30f, 10f),
            OcrPoint(30f, 110f),
            OcrPoint(10f, 110f)
        )
    )
    private val spans = listOf(
        CharacterSpan("first", 1f, 0f, 0.5f),
        CharacterSpan("second", 1f, 0.5f, 1f)
    )

    @Test
    fun reversesVerticalSpansAfterTheFixedClockwiseCropRotation() {
        val characters = CharacterBoxGeometry.build(
            verticalBox,
            spans,
            rotated = false
        )

        assertThat(characters[0].points.minOf { it.y }).isEqualTo(60f)
        assertThat(characters[1].points.minOf { it.y }).isEqualTo(10f)
    }

    @Test
    fun keepsVerticalSpansAfterAnAdditionalClassifierRotation() {
        val characters = CharacterBoxGeometry.build(
            verticalBox,
            spans,
            rotated = true
        )

        assertThat(characters[0].points.minOf { it.y }).isEqualTo(10f)
        assertThat(characters[1].points.minOf { it.y }).isEqualTo(60f)
    }
}
