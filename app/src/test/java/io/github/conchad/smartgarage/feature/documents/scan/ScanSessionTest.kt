package io.github.conchad.smartgarage.feature.documents.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScanSessionTest {
    private fun page(n: Int) = ScanPage("/cache/scans/$n.jpg", ScanGeometry.insetCorners(), 0)

    @Test fun newPhotoIsAddedAndOpensItsCorners() {
        val s = ScanSession(name = "Scan").withPhoto(page(1)).withPhoto(page(2))
        assertThat(s.pages.map { it.path }).containsExactly("/cache/scans/1.jpg", "/cache/scans/2.jpg").inOrder()
        assertThat(s.cropIndex).isEqualTo(1)
    }

    @Test fun retakeReplacesInPlace() {
        val s = ScanSession(pages = listOf(page(1), page(2)), retakeIndex = 0).withPhoto(page(3))
        assertThat(s.pages.map { it.path }).containsExactly("/cache/scans/3.jpg", "/cache/scans/2.jpg").inOrder()
        assertThat(s.cropIndex).isEqualTo(0)
        assertThat(s.retakeIndex).isNull()
    }

    @Test fun moveRotateRemove() {
        val s = ScanSession(pages = listOf(page(1), page(2), page(3)))
        assertThat(s.moved(2, -1).pages.map { it.path.last { c -> c.isDigit() } }).containsExactly('1', '3', '2').inOrder()
        assertThat(s.moved(0, -1)).isEqualTo(s)
        assertThat(s.rotated(1).rotated(1).rotated(1).rotated(1).pages[1].turns).isEqualTo(0)
        assertThat(s.rotated(1).pages[1].turns).isEqualTo(1)
        assertThat(s.removed(0).pages).hasSize(2)
        assertThat(s.withCorners(0, listOf(Pt(-1f, 0f), Pt(2f, 0f), Pt(1f, 1f), Pt(0f, 1f))).pages[0].corners[0]).isEqualTo(Pt(0f, 0f))
    }

    @Test fun finishNeedsPagesAndAName() {
        assertThat(ScanSession(name = "x").canFinish).isFalse()
        assertThat(ScanSession(pages = listOf(page(1)), name = " ").canFinish).isFalse()
        assertThat(ScanSession(pages = listOf(page(1)), name = "x").canFinish).isTrue()
        assertThat(ScanSession(pages = listOf(page(1)), name = "x", exporting = true).canFinish).isFalse()
        assertThat(ScanSession(pages = List(ScanGeometry.MAX_PAGES) { page(it) }).canAddPage).isFalse()
    }

    @Test fun encodeRoundTrips() {
        val s = ScanSession(
            pages = listOf(page(1), ScanPage("/cache/scans/2.jpg", listOf(Pt(0.1f, 0.2f), Pt(0.9f, 0.1f), Pt(0.8f, 0.9f), Pt(0.05f, 0.95f)), 3)),
            filter = ScanFilter.BlackWhite,
            name = "Kaufvertrag\nGolf",
            cropIndex = 1,
        )
        val back = ScanSession.decode(s.encode())
        assertThat(back).isEqualTo(s.copy(name = "Kaufvertrag Golf"))
        assertThat(ScanSession.decode(null)).isNull()
        assertThat(ScanSession.decode("v0\nOriginal\n\n\nx")).isNull()
        assertThat(ScanSession.decode(ScanSession(name = "a").encode())).isEqualTo(ScanSession(name = "a"))
    }
}
