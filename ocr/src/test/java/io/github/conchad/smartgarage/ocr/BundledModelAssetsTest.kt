package io.github.conchad.smartgarage.ocr

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** The model files committed under src/main/assets/ocr are exactly the upstream-verified ones. */
class BundledModelAssetsTest {
    private val assetDir = File("src/main/assets/ocr")

    @Test
    fun everyRequiredAssetMatchesSizeAndSha256() {
        for (asset in ModelManager.REQUIRED_ASSETS) {
            val file = File(assetDir, asset.fileName)
            assertThat(file.isFile).isTrue()
            assertThat(file.length()).isEqualTo(asset.sizeBytes)
            assertThat(ModelManager.verify(asset, file)).isTrue()
        }
    }

    @Test
    fun noUnexpectedFilesAreBundled() {
        val names = assetDir.list()!!.toSet()
        assertThat(names).containsExactlyElementsIn(ModelManager.REQUIRED_ASSETS.map { it.fileName })
    }
}
