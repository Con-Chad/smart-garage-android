package io.github.conchad.smartgarage.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Timing probe, not a gate: cold model load, then warm inference on a small rendered label and on a 9.4 MP
 * camera photo (`adb logcat -s OcrBench`). Reports the median of several warm runs.
 */
@RunWith(AndroidJUnit4::class)
class OcrBenchmarkTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun label(): Bitmap {
        val bitmap = Bitmap.createBitmap(900, 260, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 72f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("TOTAL 42.50", 60f, 160f, paint)
        return bitmap
    }

    private suspend fun medianWarm(engine: OcrEngine, bitmap: Bitmap, runs: Int): Long {
        engine.recognize(bitmap).getOrThrow() // warm-up
        val times = List(runs) {
            val start = SystemClock.elapsedRealtime()
            engine.recognize(bitmap).getOrThrow()
            SystemClock.elapsedRealtime() - start
        }.sorted()
        return times[runs / 2]
    }

    @Test
    fun measure() = runBlocking<Unit> {
        val engine = OnnxOcrEngine(targetContext)
        try {
            val coldStart = SystemClock.elapsedRealtime()
            engine.prepare().getOrThrow()
            val cold = SystemClock.elapsedRealtime() - coldStart

            val small = label()
            val photo = testContext.assets.open("test_ocr/ocr_working.jpg").use { BitmapFactory.decodeStream(it) }
            val smallMs = medianWarm(engine, small, 7)
            val photoMs = medianWarm(engine, photo, 5)
            Log.i(
                "OcrBench", "debuggable=${(targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0} " +
                "cold load $cold ms; warm median small 900x260 $smallMs ms; photo ${photo.width}x${photo.height} $photoMs ms"
            )
            assertThat(engine.recognize(small).getOrThrow().joinToString(" ") { it.text }).contains("42.50")
            small.recycle()
            photo.recycle()
        } finally {
            engine.close()
        }
    }
}
