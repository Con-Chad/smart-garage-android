package io.github.conchad.smartgarage.ocr

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // SHA-256 of "abc".
    private val abc = ModelAsset("abc.txt", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3)

    @Test
    fun verifiesSizeAndDigestOfBytesAndFiles() {
        assertThat(ModelManager.verify(abc, "abc".toByteArray())).isTrue()
        assertThat(ModelManager.verify(abc, "abd".toByteArray())).isFalse()
        assertThat(ModelManager.verify(abc, "abcd".toByteArray())).isFalse()

        val file = tmp.newFile("abc.txt").apply { writeText("abc") }
        assertThat(ModelManager.verify(abc, file)).isTrue()
        assertThat(ModelManager.verify(abc, tmp.root.resolve("missing.txt"))).isFalse()
    }

    @Test
    fun missingModelsAreReportedUnavailable() {
        val manager = ModelManager(tmp.root)

        assertThat(manager.locateModels()).isNull()
        assertThat(manager.locateDetectionModel()).isNull()
        assertThat(manager.getAvailability())
            .isEqualTo(ModelAvailability(ModelManager.MODEL_VERSION, detectorReady = false, recognizerReady = false))
    }

    @Test
    fun requiresTheFourPpOcrV5Files() {
        assertThat(ModelManager.REQUIRED_ASSETS.map { it.fileName })
            .containsExactly("det.onnx", "rec.onnx", "cls.onnx", "ppocrv5_dict.txt").inOrder()
    }
}
