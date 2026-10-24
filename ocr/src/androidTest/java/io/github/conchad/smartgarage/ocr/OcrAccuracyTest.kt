package io.github.conchad.smartgarage.ocr

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accuracy regression test on 12 real-world fixtures from ente/mobile_ocr (MIT; see
 * androidTest/assets/test_ocr/README.md). Mirrors upstream's plugin_integration_test.dart: every block's text
 * is joined, lower-cased and stripped of everything but letters and digits, and each required text (likewise
 * normalised, plus the `androidRequiredTexts`) must appear as a substring. Upstream requires 100 %; this port
 * gates on aggregate recall >= 90 % plus each image's `minimumBlocks`, and logs per-image recall and timing
 * (`adb logcat -s OcrAccuracy`).
 */
@RunWith(AndroidJUnit4::class)
class OcrAccuracyTest {
    companion object {
        private const val TAG = "OcrAccuracy"
        private const val FIXTURES = "test_ocr"
        private const val MIN_RECALL = 0.90

        private val testContext = InstrumentationRegistry.getInstrumentation().context
        private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        private val engine = OnnxOcrEngine(targetContext)

        @JvmStatic
        @AfterClass
        fun tearDown() = engine.close()

        private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")

        fun normalize(text: String): String = text.lowercase(Locale.ROOT).replace(NON_ALPHANUMERIC, "")
    }

    private fun JSONObject.strings(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return List(array.length()) { array.getString(it) }
    }

    @Test
    fun requiredTextRecallOnUpstreamFixtures() = runBlocking<Unit> {
        val groundTruth = JSONObject(testContext.assets.open("$FIXTURES/ground_truth.json").bufferedReader().use { it.readText() })
        val prepareStart = SystemClock.elapsedRealtime()
        engine.prepare().getOrThrow()
        Log.i(TAG, "prepare ${SystemClock.elapsedRealtime() - prepareStart} ms")

        var found = 0
        var required = 0
        var totalMs = 0L
        val failures = mutableListOf<String>()
        for (fileName in groundTruth.keys().asSequence().sorted()) {
            val expectation = groundTruth.getJSONObject(fileName)
            val file = File(targetContext.cacheDir, "ocr-fixture-$fileName")
            testContext.assets.open("$FIXTURES/$fileName").use { input -> file.outputStream().use { input.copyTo(it) } }
            try {
                val start = SystemClock.elapsedRealtime()
                val result = engine.recognize(Uri.fromFile(file)).getOrThrow()
                val elapsed = SystemClock.elapsedRealtime() - start
                totalMs += elapsed
                result.bitmap.recycle()

                val recognized = normalize(result.blocks.joinToString(" ") { it.text })
                val texts = expectation.strings("requiredTexts") + expectation.strings("androidRequiredTexts")
                val missing = texts.filterNot { recognized.contains(normalize(it)) }
                found += texts.size - missing.size
                required += texts.size

                val minimumBlocks = expectation.getInt("minimumBlocks")
                if (result.blocks.size < minimumBlocks) {
                    failures += "$fileName: expected at least $minimumBlocks blocks, found ${result.blocks.size}"
                }
                Log.i(
                    TAG,
                    "$fileName ${result.width}x${result.height} ${elapsed} ms blocks=${result.blocks.size} " +
                        "recall=${texts.size - missing.size}/${texts.size}" + if (missing.isEmpty()) "" else " missing=$missing"
                )
            } finally {
                file.delete()
            }
        }

        val recall = found.toDouble() / required
        Log.i(TAG, String.format(Locale.US, "aggregate recall %d/%d = %.1f%%, total %d ms", found, required, recall * 100, totalMs))
        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
        assertWithMessage("aggregate required-text recall").that(recall).isAtLeast(MIN_RECALL)
    }
}
