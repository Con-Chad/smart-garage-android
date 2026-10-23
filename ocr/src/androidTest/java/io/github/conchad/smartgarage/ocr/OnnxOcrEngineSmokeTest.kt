package io.github.conchad.smartgarage.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test only (accuracy lives in CP10): the bundled models load and read clean rendered text. */
@RunWith(AndroidJUnit4::class)
class OnnxOcrEngineSmokeTest {
    companion object {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val engine = OnnxOcrEngine(context)

        @JvmStatic
        @AfterClass
        fun tearDown() = engine.close()
    }

    private fun textBitmap(text: String?): Bitmap {
        val bitmap = Bitmap.createBitmap(900, 260, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        if (text != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 72f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(text, 60f, 160f, paint)
        }
        return bitmap
    }

    @Test
    fun recognizesRenderedText() = runBlocking<Unit> {
        assertThat(engine.prepare().isSuccess).isTrue()
        val bitmap = textBitmap("TOTAL 42.50")
        val cold = engine.recognize(bitmap).getOrThrow()
        val start = SystemClock.elapsedRealtime()
        val warm = engine.recognize(bitmap).getOrThrow()
        Log.i("OcrSmoke", "warm recognize ${SystemClock.elapsedRealtime() - start} ms")
        val text = warm.joinToString(" ") { it.text }
        assertThat(cold.map { it.text }).isEqualTo(warm.map { it.text })
        assertThat(text).contains("42.50")
        assertThat(text.uppercase()).contains("TOTAL")
        warm.forEach { assertThat(it.points).hasSize(4) }
    }

    @Test
    fun blankImageYieldsNoBlocks() = runBlocking<Unit> {
        assertThat(engine.recognize(textBitmap(null)).getOrThrow()).isEmpty()
    }

    @Test
    fun recognizesFromFileUri() = runBlocking<Unit> {
        val file = File(context.cacheDir, "ocr-smoke.jpg")
        file.outputStream().use { textBitmap("TOTAL 42.50").compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val result = engine.recognize(Uri.fromFile(file)).getOrThrow()
        assertThat(result.width).isEqualTo(900)
        assertThat(result.height).isEqualTo(260)
        assertThat(result.blocks.joinToString(" ") { it.text }).contains("42.50")
        result.bitmap.recycle()
        file.delete()
    }

    @Test
    fun unreadableUriFailsWithOcrError() = runBlocking<Unit> {
        val missing = Uri.fromFile(File(context.cacheDir, "does-not-exist.jpg"))
        assertThat(engine.recognize(missing).exceptionOrNull()).isInstanceOf(OcrError.UnreadableImage::class.java)
    }

    @Test
    fun closeThenRecognizeReprepares() = runBlocking<Unit> {
        engine.close()
        assertThat(engine.recognize(textBitmap("TOTAL 42.50")).isSuccess).isTrue()
    }

    @Test
    fun measureOnDeviceSha256Cost() {
        val start = SystemClock.elapsedRealtime()
        for (asset in ModelManager.REQUIRED_ASSETS) {
            val bytes = context.assets.open("ocr/${asset.fileName}").use { it.readBytes() }
            assertThat(ModelManager.verify(asset, bytes)).isTrue()
        }
        val readAndHash = SystemClock.elapsedRealtime() - start
        val readStart = SystemClock.elapsedRealtime()
        for (asset in ModelManager.REQUIRED_ASSETS) {
            context.assets.open("ocr/${asset.fileName}").use { it.readBytes() }
        }
        val readOnly = SystemClock.elapsedRealtime() - readStart
        Log.i("OcrSmoke", "assets read+sha256 $readAndHash ms, read only $readOnly ms, sha256 ~${readAndHash - readOnly} ms")
    }
}
