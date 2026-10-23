package io.github.conchad.smartgarage.feature.documents.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScanGeometryTest {
    private val tl = Pt(0.1f, 0.1f)
    private val tr = Pt(0.9f, 0.15f)
    private val br = Pt(0.85f, 0.9f)
    private val bl = Pt(0.12f, 0.95f)

    @Test fun ordersCornersWhateverTheDragOrder() {
        val expected = listOf(tl, tr, br, bl)
        assertThat(ScanGeometry.orderCorners(listOf(br, tl, bl, tr))).isEqualTo(expected)
        assertThat(ScanGeometry.orderCorners(listOf(bl, br, tr, tl))).isEqualTo(expected)
        assertThat(ScanGeometry.orderCorners(expected)).isEqualTo(expected)
    }

    @Test fun rotationShiftsTheCornersClockwise() {
        val ordered = listOf(tl, tr, br, bl)
        assertThat(ScanGeometry.rotate(ordered, 0)).isEqualTo(ordered)
        assertThat(ScanGeometry.rotate(ordered, 1)).isEqualTo(listOf(bl, tl, tr, br))
        assertThat(ScanGeometry.rotate(ordered, 2)).isEqualTo(listOf(br, bl, tl, tr))
        assertThat(ScanGeometry.rotate(ordered, 4)).isEqualTo(ordered)
        assertThat(ScanGeometry.rotate(ordered, -1)).isEqualTo(ScanGeometry.rotate(ordered, 3))
    }

    @Test fun outputSizeTakesTheLongerOppositeEdgesAndCaps() {
        val rect = listOf(Pt(0f, 0f), Pt(1000f, 0f), Pt(1000f, 1400f), Pt(0f, 1400f))
        assertThat(ScanGeometry.outputSize(rect)).isEqualTo(1000 to 1400)
        val keystone = listOf(Pt(100f, 0f), Pt(900f, 0f), Pt(1000f, 1000f), Pt(0f, 1000f))
        assertThat(ScanGeometry.outputSize(keystone).first).isEqualTo(1000)
        val big = listOf(Pt(0f, 0f), Pt(3000f, 0f), Pt(3000f, 4400f), Pt(0f, 4400f))
        assertThat(ScanGeometry.outputSize(big, 2200)).isEqualTo(1500 to 2200)
        val dot = listOf(Pt(5f, 5f), Pt(5f, 5f), Pt(5f, 5f), Pt(5f, 5f))
        assertThat(ScanGeometry.outputSize(dot)).isEqualTo(1 to 1)
    }

    @Test fun rotatedOutputSwapsWidthAndHeight() {
        val rect = listOf(Pt(0f, 0f), Pt(1000f, 0f), Pt(1000f, 1400f), Pt(0f, 1400f))
        assertThat(ScanGeometry.outputSize(ScanGeometry.rotate(rect, 1))).isEqualTo(1400 to 1000)
    }

    @Test fun polyToPolyPairsCornersWithTheOutputRectangle() {
        val (src, dst) = ScanGeometry.polyToPoly(listOf(Pt(1f, 2f), Pt(3f, 4f), Pt(5f, 6f), Pt(7f, 8f)), 100, 200)
        assertThat(src.toList()).containsExactly(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f).inOrder()
        assertThat(dst.toList()).containsExactly(0f, 0f, 100f, 0f, 100f, 200f, 0f, 200f).inOrder()
    }

    @Test fun convexityAndUsability() {
        assertThat(ScanGeometry.isConvex(listOf(tl, tr, br, bl))).isTrue()
        // A bow-tie: two corners swapped.
        assertThat(ScanGeometry.isConvex(listOf(tl, tr, bl, br))).isFalse()
        assertThat(ScanGeometry.isUsable(ScanGeometry.FULL)).isTrue()
        assertThat(ScanGeometry.isUsable(listOf(Pt(0.5f, 0.5f), Pt(0.51f, 0.5f), Pt(0.51f, 0.51f), Pt(0.5f, 0.51f)))).isFalse()
        assertThat(ScanGeometry.area(ScanGeometry.FULL)).isWithin(1e-6f).of(1f)
        assertThat(ScanGeometry.clamp(Pt(-0.2f, 1.4f))).isEqualTo(Pt(0f, 1f))
    }

    @Test fun pdfPageKeepsAspectWithA4ShortEdge() {
        assertThat(ScanGeometry.pdfPageSize(2100, 2970)).isEqualTo(595 to 842)
        assertThat(ScanGeometry.pdfPageSize(2970, 2100)).isEqualTo(842 to 595)
        assertThat(ScanGeometry.pdfPageSize(1000, 1000)).isEqualTo(595 to 595)
        // A long till receipt stays long.
        assertThat(ScanGeometry.pdfPageSize(600, 2400)).isEqualTo(595 to 2380)
    }

    @Test fun onePageIsAJpegSeveralAreAPdf() {
        assertThat(ScanGeometry.isPdf(1)).isFalse()
        assertThat(ScanGeometry.isPdf(2)).isTrue()
        assertThat(ScanGeometry.fileName("Insurance 2026", 1)).isEqualTo("Insurance 2026.jpg")
        assertThat(ScanGeometry.fileName("Insurance 2026", 3)).isEqualTo("Insurance 2026.pdf")
    }

    @Test fun fileNameDropsUnsafeCharacters() {
        assertThat(ScanGeometry.fileName("  TÜV / Prüfbericht: 03?  ", 1)).isEqualTo("TÜV - Prüfbericht- 03.jpg")
        assertThat(ScanGeometry.fileName("a\nb\tc", 2)).isEqualTo("a-b-c.pdf")
        assertThat(ScanGeometry.fileName("...", 1)).isEqualTo("scan.jpg")
        assertThat(ScanGeometry.fileName("x".repeat(200), 1)).hasLength(84)
    }
}
