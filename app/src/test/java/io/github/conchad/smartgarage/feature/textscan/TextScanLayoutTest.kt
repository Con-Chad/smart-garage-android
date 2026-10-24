package io.github.conchad.smartgarage.feature.textscan

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.ocr.BoundingRect
import io.github.conchad.smartgarage.ocr.OcrPoint
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
    fun `a small box set low by skew stays on its row (D-A67)`() {
        // Measured on the Pixel from a tilted bill: a tiny quantity "1" sits lower than the row's centre line.
        val rects = listOf(
            box(80f, 860f, 200f, 888f), //  0: "Burritos (Ga)"
            box(80f, 900f, 230f, 928f), //  1: "Veggie burger"
            box(560f, 912f, 640f, 938f), // 2: "75.000" (TT)
            box(400f, 918f, 480f, 946f), // 3: "75.000"
            box(300f, 927f, 312f, 940f), // 4: "1"
            box(80f, 960f, 230f, 988f), //  5: "Chicken curry"
        )
        assertThat(readingOrder(rects)).containsExactly(listOf(0), listOf(1, 4, 3, 2), listOf(5)).inOrder()
    }

    // ── Tilted photos (D-A67) ───────────────────────────────────────────────────────────────────────────────

    /** A receipt's rows (label left, amount far right, 60 px apart) photographed turned by [degrees]. */
    private fun tiltedReceipt(degrees: Double): Pair<List<List<OcrPoint>>, List<BoundingRect>> {
        val upright = (0 until 4).flatMap { row ->
            val top = 100f + row * 60f
            listOf(box(80f, top, 300f, top + 40f), box(1100f, top, 1300f, top + 40f))
        }
        val a = Math.toRadians(degrees)
        val quads = upright.map { r ->
            listOf(OcrPoint(r.left, r.top), OcrPoint(r.right, r.top), OcrPoint(r.right, r.bottom), OcrPoint(r.left, r.bottom))
                .map { p -> OcrPoint((p.x * Math.cos(a) - p.y * Math.sin(a)).toFloat(), (p.x * Math.sin(a) + p.y * Math.cos(a)).toFloat()) }
        }
        val bounds = quads.map { q -> BoundingRect(q.minOf { it.x }, q.minOf { it.y }, q.maxOf { it.x }, q.maxOf { it.y }) }
        return quads to bounds
    }

    @Test
    fun `a tilted receipt keeps each amount on its own label's row`() {
        val (quads, bounds) = tiltedReceipt(-3.0) // amounts drift up a row's worth: 63 px over 1200 px
        val rows = listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5), listOf(6, 7))
        assertThat(readingOrder(bounds)).isNotEqualTo(rows) // the problem
        assertThat(readingOrder(levelledBounds(quads, bounds))).isEqualTo(rows)
        assertThat(readingOrder(levelledBounds(tiltedReceipt(4.0).first, tiltedReceipt(4.0).second))).isEqualTo(rows)
    }

    @Test
    fun `a level photo or one without line boxes is left as it is`() {
        val (quads, bounds) = tiltedReceipt(0.0)
        assertThat(levelledBounds(quads, bounds)).isEqualTo(bounds)
        val squares = listOf(listOf(OcrPoint(0f, 0f), OcrPoint(20f, 5f), OcrPoint(15f, 25f), OcrPoint(-5f, 20f)))
        val squareBounds = listOf(box(-5f, 0f, 20f, 25f))
        assertThat(levelledBounds(squares, squareBounds)).isEqualTo(squareBounds)
        assertThat(levelledBounds(emptyList(), squareBounds)).isEqualTo(squareBounds)
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
