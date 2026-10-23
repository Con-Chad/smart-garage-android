package io.github.conchad.smartgarage.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import io.github.conchad.smartgarage.ocr.internal.BundledModels
import io.github.conchad.smartgarage.ocr.internal.UriImageDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [OcrEngine] on PP-OCRv5 + ONNX Runtime with the models bundled as APK assets. The sessions are created on
 * first use and reused; one [Mutex] serialises loading and inference.
 *
 * @param verifyModelHashes also SHA-256 the assets when loading (the byte size is always checked). Off by
 *   default: on a Pixel 10 Pro (CP5) it added ~35-80 ms warm but ~150 ms to the first cold load (assets 195 ->
 *   348 ms of a ~750-950 ms load), at the 150 ms budget. The committed files are hash-checked by
 *   BundledModelAssetsTest and the APK is signed, so the runtime hash would only catch storage corruption.
 */
class OnnxOcrEngine(
    context: Context,
    private val verifyModelHashes: Boolean = false
) : OcrEngine {

    private companion object {
        const val TAG = "Ocr"
    }

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var processor: OcrProcessor? = null

    override suspend fun prepare(): Result<Unit> = run { ensureProcessor() }

    override suspend fun recognize(bitmap: Bitmap): Result<List<OcrTextBlock>> = run {
        val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw OcrError.UnreadableImage("cannot convert bitmap config ${bitmap.config}")
        try {
            infer(ensureProcessor(), input)
        } finally {
            if (input !== bitmap) input.recycle()
        }
    }

    override suspend fun recognize(uri: Uri): Result<OcrImageResult> = run {
        val processor = ensureProcessor()
        val decoded = UriImageDecoder.decode(appContext.contentResolver, uri)
        try {
            val blocks = infer(processor, decoded.bitmap)
            OcrImageResult(
                bitmap = decoded.bitmap,
                width = decoded.bitmap.width,
                height = decoded.bitmap.height,
                originalWidth = decoded.originalWidth,
                originalHeight = decoded.originalHeight,
                blocks = blocks
            )
        } catch (error: Throwable) {
            decoded.bitmap.recycle()
            throw error
        }
    }

    override fun close() {
        // Not under the mutex: close() is non-suspending. A concurrent inference keeps its local reference, so
        // closing mid-run surfaces as OcrError.Inference for that call rather than a crash.
        val current = processor
        processor = null
        current?.close()
    }

    /** Runs [block] on Dispatchers.Default under the mutex and maps every failure to an [OcrError]. */
    private suspend fun <T> run(block: suspend () -> T): Result<T> = try {
        withContext(Dispatchers.Default) { mutex.withLock { Result.success(block()) } }
    } catch (error: OcrError) {
        Result.failure(error)
    } catch (error: CancellationException) {
        currentCoroutineContext().ensureActive() // the caller was cancelled: propagate
        Result.failure(OcrError.Cancelled()) // the pipeline's own signal fired
    } catch (error: OutOfMemoryError) {
        Log.w(TAG, "out of memory")
        Result.failure(OcrError.OutOfMemory(error))
    } catch (error: Exception) {
        Log.w(TAG, "inference failed: ${error.javaClass.simpleName}")
        Result.failure(OcrError.Inference(error))
    }

    private fun ensureProcessor(): OcrProcessor {
        processor?.let { return it }
        val start = SystemClock.elapsedRealtime()
        val models = BundledModels(appContext.assets, verifyModelHashes).load()
        val read = SystemClock.elapsedRealtime()
        val created = OcrProcessor(models)
        processor = created
        val end = SystemClock.elapsedRealtime()
        Log.i(TAG, "models loaded in ${end - start} ms (assets ${read - start} ms, sessions ${end - read} ms, sha256=$verifyModelHashes)")
        return created
    }

    private suspend fun infer(processor: OcrProcessor, bitmap: Bitmap): List<OcrTextBlock> = coroutineScope {
        val signal = OnnxCancellationSignal()
        // Forwards coroutine cancellation into ONNX Runtime (RunOptions.setTerminate) while the blocking run below
        // occupies this thread.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                signal.cancel()
            }
        }
        try {
            val start = SystemClock.elapsedRealtime()
            val result = processor.processImage(bitmap, cancellationSignal = signal)
            val blocks = result.boxes.indices.map { i ->
                val box = result.boxes[i]
                OcrTextBlock(
                    text = result.texts[i],
                    confidence = result.scores[i],
                    points = box.points,
                    boundingRect = box.boundingRect()
                )
            }
            Log.i(TAG, "inference ${SystemClock.elapsedRealtime() - start} ms, ${bitmap.width}x${bitmap.height}, ${blocks.size} blocks")
            blocks
        } finally {
            watcher.cancel()
        }
    }
}
