package io.github.conchad.smartgarage.ocr.internal

import com.google.common.truth.Correspondence
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CtcDecoderTest {
    // Index 0 = blank, 1 = "a", 2 = "b", 3 = "c", 4 = trailing " ".
    private val dict = CtcDecoder.characterDictionary(listOf("a", "b", "c"))

    private fun decode(indices: IntArray, probs: FloatArray = FloatArray(indices.size) { 1f }) =
        CtcDecoder.decode(indices, probs, scale = 1f, characterDict = dict)

    @Test
    fun dictionaryPutsBlankFirstAndAppendsSpace() {
        assertThat(dict).containsExactly("blank", "a", "b", "c", " ").inOrder()
    }

    @Test
    fun removesBlanks() {
        assertThat(decode(intArrayOf(0, 1, 0, 0, 2, 0)).text).isEqualTo("ab")
    }

    @Test
    fun mergesConsecutiveRepeats() {
        assertThat(decode(intArrayOf(1, 1, 1, 2, 2)).text).isEqualTo("ab")
    }

    @Test
    fun keepsRepeatsSeparatedByBlank() {
        assertThat(decode(intArrayOf(1, 0, 1, 1, 0, 1)).text).isEqualTo("aaa")
    }

    @Test
    fun averagesFrameConfidencePerCharacterThenAcrossCharacters() {
        val result = decode(intArrayOf(1, 1, 0, 2), floatArrayOf(0.9f, 0.7f, 0.99f, 0.5f))

        assertThat(result.text).isEqualTo("ab")
        assertThat(result.characterSpans.map { it.confidence })
            .comparingElementsUsing(Correspondence.tolerance(1e-6))
            .containsExactly(0.8, 0.5).inOrder()
        assertThat(result.confidence.toDouble()).isWithin(1e-6).of(0.65)
    }

    @Test
    fun decodesTrailingSpaceEntry() {
        assertThat(decode(intArrayOf(1, 4, 0, 2)).text).isEqualTo("a b")
    }

    @Test
    fun dropsIndicesOutsideDictionary() {
        val result = decode(intArrayOf(1, 9, 2), floatArrayOf(1f, 0.1f, 0.5f))

        assertThat(result.text).isEqualTo("ab")
        assertThat(result.confidence).isWithin(1e-6f).of(0.75f)
    }

    @Test
    fun allBlankOrEmptyGivesEmptyTextAndZeroConfidence() {
        assertThat(decode(intArrayOf(0, 0, 0))).isEqualTo(RecognitionResult("", 0f, emptyList()))
        assertThat(decode(intArrayOf())).isEqualTo(RecognitionResult("", 0f, emptyList()))
    }

    @Test
    fun spansAreFramePositionRatios() {
        val result = decode(intArrayOf(1, 1, 0, 2))

        assertThat(result.characterSpans.map { it.startRatio to it.endRatio })
            .containsExactly(0f to 0.5f, 0.75f to 1f).inOrder()
    }

    @Test
    fun spansScaleToUnpaddedContentAndKeepMinimumWidthAtTheEdge() {
        val result = CtcDecoder.decode(intArrayOf(1, 0, 2, 3), FloatArray(4) { 1f }, scale = 2f, characterDict = dict)

        assertThat(result.characterSpans.map { it.startRatio to it.endRatio })
            .containsExactly(0f to 0.5f, 0.5f to 1f, 0.5f to 1f).inOrder()
    }

    @Test
    fun decodeBatchTakesArgmaxPerFrameAndScalesByContentWidth() {
        val vocab = dict.size
        val seqLen = 4
        // batch 0 -> "ab" (full width), batch 1 -> "c" (content is half the padded width).
        val frames = listOf(
            listOf(1, 0, 2, 0),
            listOf(3, 0, 0, 0)
        )
        val output = FloatArray(2 * seqLen * vocab)
        frames.forEachIndexed { b, indices ->
            indices.forEachIndexed { t, index ->
                val offset = (b * seqLen + t) * vocab
                for (c in 0 until vocab) output[offset + c] = 0.01f
                output[offset + index] = 0.9f
            }
        }

        val results = CtcDecoder.decodeBatch(
            output = output,
            batchSize = 2,
            seqLen = seqLen,
            vocabSize = vocab,
            contentWidths = intArrayOf(100, 50),
            targetWidth = 100,
            characterDict = dict
        )

        assertThat(results.map { it.text }).containsExactly("ab", "c").inOrder()
        assertThat(results[0].confidence).isWithin(1e-6f).of(0.9f)
        assertThat(results[1].characterSpans.single().endRatio).isWithin(1e-6f).of(0.5f)
    }
}
