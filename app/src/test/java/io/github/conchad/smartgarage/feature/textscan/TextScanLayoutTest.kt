package io.github.conchad.smartgarage.feature.textscan

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.ocr.BoundingRect
import org.junit.Test

class TextScanLayoutTest {

    // ── Image → view mapping ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tall image is letterboxed left and right`() {
        val fit = ImageFit.fit(imageWidth = 1000, imageHeight = 2000, viewWidth = 1000f, viewHeight = 1000f)!!
        assertThat(fit.scale).isEqualTo(0.5f)
        assertThat(fit.offsetX).isEqualTo(250f)
        assertThat(fit.offsetY).isEqualTo(0f)
        assertThat(fit.toView(BoundingRect(100f, 200f, 300f, 400f))).isEqualTo(ViewRect(300f, 100f, 400f, 200f))
    }

    @Test
    fun `a wide image is letterboxed top and bottom`() {
        val fit = ImageFit.fit(imageWidth = 4000, imageHeight = 1000, viewWidth = 800f, viewHeight = 600f)!!
        assertThat(fit.scale).isEqualTo(0.2f)
        assertThat(fit.offsetX).isEqualTo(0f)
        assertThat(fit.offsetY).isWithin(1e-4f).of(200f)
        val corner = fit.toView(BoundingRect(4000f, 1000f, 4000f, 1000f))
        assertThat(corner.right).isWithin(1e-3f).of(800f)
        assertThat(corner.bottom).isWithin(1e-3f).of(400f)
    }

    @Test
    fun `a small image is scaled up to fill the view`() {
        val fit = ImageFit.fit(imageWidth = 100, imageHeight = 100, viewWidth = 300f, viewHeight = 300f)!!
        assertThat(fit.scale).isEqualTo(3f)
        assertThat(fit.x(10f)).isEqualTo(30f)
        assertThat(fit.y(20f)).isEqualTo(60f)
    }

    @Test
    fun `empty sizes have no fit`() {
        assertThat(ImageFit.fit(0, 100, 100f, 100f)).isNull()
        assertThat(ImageFit.fit(100, 100, 0f, 100f)).isNull()
    }

    // ── Hit-testing ─────────────────────────────────────────────────────────────────────────────────────────

    private val big = ViewRect(0f, 0f, 400f, 200f)
    private val word = ViewRect(100f, 50f, 140f, 70f)
    private val far = ViewRect(600f, 600f, 700f, 640f)

    @Test
    fun `a tap inside a box picks it`() {
        assertThat(hitTest(listOf(far, big), 10f, 10f, minTarget = 48f)).isEqualTo(1)
    }

    @Test
    fun `the smaller of two boxes under the tap wins`() {
        assertThat(hitTest(listOf(big, word), 120f, 60f, minTarget = 48f)).isEqualTo(1)
    }

    @Test
    fun `a tap just outside a small box still reaches it`() {
        val thin = ViewRect(100f, 100f, 200f, 110f) // 10 px tall: answers 19 px above and below
        assertThat(hitTest(listOf(thin), 150f, 125f, minTarget = 48f)).isEqualTo(0)
        assertThat(hitTest(listOf(thin), 150f, 135f, minTarget = 48f)).isNull()
    }

    @Test
    fun `between two thin lines the nearer one wins`() {
        val upper = ViewRect(0f, 0f, 100f, 10f)
        val lower = ViewRect(0f, 20f, 100f, 30f)
        assertThat(hitTest(listOf(upper, lower), 50f, 13f, minTarget = 48f)).isEqualTo(0)
        assertThat(hitTest(listOf(upper, lower), 50f, 18f, minTarget = 48f)).isEqualTo(1)
    }

    @Test
    fun `a tap on empty photo picks nothing`() {
        assertThat(hitTest(listOf(word, far), 500f, 400f, minTarget = 48f)).isNull()
        assertThat(hitTest(emptyList(), 1f, 1f, minTarget = 48f)).isNull()
    }

    // ── Reading order ───────────────────────────────────────────────────────────────────────────────────────

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = BoundingRect(left, top, right, bottom)

    @Test
    fun `rows run top to bottom and left to right`() {
        val rects = listOf(
            box(300f, 102f, 380f, 124f), // 0: "12.50", slightly lower than its label
            box(10f, 100f, 120f, 120f), //  1: "TOTAL"
            box(10f, 10f, 200f, 30f), //    2: "SHELL"
            box(10f, 140f, 150f, 160f), //  3: "Thank you"
        )
        assertThat(readingOrder(rects)).containsExactly(listOf(2), listOf(1, 0), listOf(3)).inOrder()
    }

    @Test
    fun `a skewed row keeps its blocks together`() {
        val rects = listOf(
            box(0f, 100f, 100f, 120f),
            box(110f, 106f, 210f, 126f),
            box(220f, 112f, 320f, 132f),
            box(0f, 150f, 100f, 170f),
        )
        assertThat(readingOrder(rects)).containsExactly(listOf(0, 1, 2), listOf(3)).inOrder()
    }

    @Test
    fun `adjacent lines stay separate`() {
        val rects = listOf(box(0f, 0f, 100f, 20f), box(0f, 22f, 100f, 42f), box(0f, 44f, 100f, 64f))
        assertThat(readingOrder(rects)).containsExactly(listOf(0), listOf(1), listOf(2)).inOrder()
    }

    @Test
    fun `a tall box does not swallow the small lines beside it`() {
        val rects = listOf(
            box(0f, 0f, 100f, 100f), //   0: a big logo word
            box(200f, 5f, 300f, 20f), //  1: a small line near its top
            box(200f, 80f, 300f, 95f), // 2: a small line near its bottom
        )
        assertThat(readingOrder(rects)).containsExactly(listOf(1), listOf(0), listOf(2)).inOrder()
    }

    @Test
    fun `no boxes, no rows`() {
        assertThat(readingOrder(emptyList())).isEmpty()
    }

    // ── Text assembly and selection ─────────────────────────────────────────────────────────────────────────

    private val texts = listOf("12.50", "TOTAL", "SHELL", " Thank you ")
    private val rows = listOf(listOf(2), listOf(1, 0), listOf(3))

    @Test
    fun `nothing selected gives all the text in reading order`() {
        assertThat(assembleText(texts, rows)).isEqualTo("SHELL\nTOTAL 12.50\nThank you")
    }

    @Test
    fun `a selection keeps reading order whatever order it was tapped in`() {
        assertThat(assembleText(texts, rows, setOf(3, 0))).isEqualTo("12.50\nThank you")
        assertThat(assembleText(texts, rows, setOf(0, 1))).isEqualTo("TOTAL 12.50")
    }

    @Test
    fun `blank texts leave no empty lines`() {
        assertThat(assembleText(listOf("a", " ", "b"), listOf(listOf(0), listOf(1), listOf(2)))).isEqualTo("a\nb")
    }

    @Test
    fun `selection toggles, selects all and clears`() {
        var s = TextSelection(count = 3)
        assertThat(s.none).isTrue()
        s = s.toggle(1).toggle(2)
        assertThat(s.selected).containsExactly(1, 2)
        s = s.toggle(1)
        assertThat(s.selected).containsExactly(2)
        assertThat(s.toggle(5)).isEqualTo(s)
        assertThat(s.toggle(-1)).isEqualTo(s)
        s = s.selectAll()
        assertThat(s.all).isTrue()
        assertThat(s.selected).containsExactly(0, 1, 2)
        assertThat(s.clear().none).isTrue()
        assertThat(TextSelection(0).selectAll().all).isFalse()
    }

    @Test
    fun `a restored selection drops indices that no longer exist`() {
        assertThat(TextSelection.restore(3, intArrayOf(0, 2, 7, -1)).selected).containsExactly(0, 2)
        assertThat(TextSelection.restore(3, null).none).isTrue()
    }
}
