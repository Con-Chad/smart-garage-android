// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: TextRecognizer.decodeOutput/ctcDecode and OcrProcessor.loadCharacterDict moved
// here as pure functions over FloatArray/IntArray so CTC decoding is testable on the JVM. Logic unchanged.
package io.github.conchad.smartgarage.ocr.internal

internal data class CharacterSpan(
    val text: String,
    val confidence: Float,
    val startRatio: Float,
    val endRatio: Float
)

internal data class RecognitionResult(
    val text: String,
    val confidence: Float,
    val characterSpans: List<CharacterSpan>
)

internal object CtcDecoder {
    private const val MIN_SPAN_RATIO = 1e-3f

    /** Index 0 is the CTC blank; the dictionary file's lines follow, then a trailing space character. */
    fun characterDictionary(dictionaryLines: List<String>): List<String> {
        val characters = dictionaryLines.toMutableList()
        characters.add(" ")
        return listOf("blank") + characters
    }

    /**
     * Greedy-decodes a recognizer output of shape `[batchSize, seqLen, vocabSize]` (row-major in [output]).
     * [contentWidths] are the unpadded widths of each item inside the padded [targetWidth] input; they scale
     * character spans back to ratios of the real content.
     */
    fun decodeBatch(
        output: FloatArray,
        batchSize: Int,
        seqLen: Int,
        vocabSize: Int,
        contentWidths: IntArray,
        targetWidth: Int,
        characterDict: List<String>
    ): List<RecognitionResult> {
        val results = mutableListOf<RecognitionResult>()

        for (b in 0 until batchSize) {
            val batchOffset = b * seqLen * vocabSize

            // Get argmax and probabilities for each time step
            val charIndices = IntArray(seqLen)
            val probs = FloatArray(seqLen)

            for (t in 0 until seqLen) {
                val timeOffset = batchOffset + t * vocabSize

                var maxProb = output[timeOffset]
                var maxIndex = 0

                for (c in 1 until vocabSize) {
                    val prob = output[timeOffset + c]
                    if (prob > maxProb) {
                        maxProb = prob
                        maxIndex = c
                    }
                }

                charIndices[t] = maxIndex
                probs[t] = maxProb
            }

            val contentWidth = if (b < contentWidths.size && contentWidths[b] > 0) {
                contentWidths[b]
            } else {
                targetWidth
            }
            val scaleFactor = if (contentWidth >= targetWidth) 1f else targetWidth.toFloat() / contentWidth
            val recognition = decode(charIndices, probs, scaleFactor, characterDict)

            results.add(recognition)
        }

        return results
    }

    /**
     * Greedy CTC collapse: skips blanks (index 0), merges consecutive repeats into one character whose
     * confidence is the mean of its frames, and keeps repeats separated by a blank. Overall confidence is
     * the mean of the per-character confidences. Indices outside [characterDict] are dropped.
     */
    fun decode(
        charIndices: IntArray,
        probs: FloatArray,
        scale: Float,
        characterDict: List<String>
    ): RecognitionResult {
        val seqLen = charIndices.size
        if (seqLen == 0) {
            return RecognitionResult("", 0f, emptyList())
        }

        val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
        val decodedChars = mutableListOf<String>()
        val decodedProbs = mutableListOf<Float>()
        val spans = mutableListOf<CharacterSpan>()

        var t = 0
        while (t < seqLen) {
            val currentIndex = charIndices[t]

            if (currentIndex == 0) {
                t++
                continue
            }

            val start = t
            var end = t + 1
            var probSum = probs[t]
            var count = 1

            while (end < seqLen && charIndices[end] == currentIndex) {
                probSum += probs[end]
                end++
                count++
            }

            if (currentIndex < characterDict.size) {
                val character = characterDict[currentIndex]
                decodedChars.add(character)

                val meanProb = probSum / count
                decodedProbs.add(meanProb)

                val minSpan = ((1f / seqLen) * safeScale).coerceAtLeast(MIN_SPAN_RATIO)

                var startRatio = (start.toFloat() / seqLen) * safeScale
                var endRatio = (end.toFloat() / seqLen) * safeScale

                startRatio = startRatio.coerceIn(0f, 1f)
                endRatio = endRatio.coerceIn(startRatio, 1f)

                if (endRatio - startRatio < minSpan) {
                    endRatio = (startRatio + minSpan).coerceAtMost(1f)
                    if (endRatio - startRatio < minSpan) {
                        startRatio = (endRatio - minSpan).coerceAtLeast(0f)
                    }
                }

                spans.add(
                    CharacterSpan(
                        text = character,
                        confidence = meanProb,
                        startRatio = startRatio,
                        endRatio = endRatio
                    )
                )
            }

            t = end
        }

        val text = decodedChars.joinToString("")
        val confidence = if (decodedProbs.isNotEmpty()) {
            decodedProbs.average().toFloat()
        } else {
            0f
        }

        return RecognitionResult(text, confidence, spans)
    }
}
