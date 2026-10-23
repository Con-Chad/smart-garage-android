package io.github.conchad.smartgarage.ocr.internal

import android.content.res.AssetManager
import io.github.conchad.smartgarage.ocr.ModelAsset
import io.github.conchad.smartgarage.ocr.ModelManager
import io.github.conchad.smartgarage.ocr.ModelSource
import io.github.conchad.smartgarage.ocr.OcrError
import io.github.conchad.smartgarage.ocr.OcrModels
import java.io.IOException

/** Reads the PP-OCRv5 models bundled under `assets/ocr/` and checks them against [ModelManager]'s constants. */
internal class BundledModels(
    private val assets: AssetManager,
    private val verifySha256: Boolean
) {
    companion object {
        const val ASSET_DIR = "ocr"
    }

    fun load(): OcrModels = OcrModels(
        detection = ModelSource.FromBytes(read(ModelManager.DETECTION_MODEL)),
        recognition = ModelSource.FromBytes(read(ModelManager.RECOGNITION_MODEL)),
        classification = ModelSource.FromBytes(read(ModelManager.CLASSIFICATION_MODEL)),
        dictionary = ModelSource.FromBytes(read(ModelManager.DICTIONARY))
    )

    private fun read(asset: ModelAsset): ByteArray {
        val bytes = try {
            assets.open("$ASSET_DIR/${asset.fileName}").use { it.readBytes() }
        } catch (error: IOException) {
            throw OcrError.CorruptModel(asset.fileName, "missing (${error.message})")
        }
        if (bytes.size.toLong() != asset.sizeBytes) {
            throw OcrError.CorruptModel(asset.fileName, "size ${bytes.size}, expected ${asset.sizeBytes}")
        }
        if (verifySha256 && !ModelManager.verify(asset, bytes)) {
            throw OcrError.CorruptModel(asset.fileName, "SHA-256 mismatch")
        }
        return bytes
    }
}
