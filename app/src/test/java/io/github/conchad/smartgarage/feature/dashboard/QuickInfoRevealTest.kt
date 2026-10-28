package io.github.conchad.smartgarage.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickInfoRevealTest {
    @Test
    fun fullyOpenAtRest() {
        assertEquals(1f, quickInfoReveal(0f, reduced = false), 0f)
    }

    @Test
    fun foldsLinearlyWithTheSwipeInEitherDirection() {
        assertEquals(0.75f, quickInfoReveal(0.25f, reduced = false), 1e-6f)
        assertEquals(0.75f, quickInfoReveal(-0.25f, reduced = false), 1e-6f)
        assertEquals(0.4f, quickInfoReveal(0.6f, reduced = false), 1e-6f)
    }

    @Test
    fun collapsedAFullPageAwayOrMore() {
        assertEquals(0f, quickInfoReveal(1f, reduced = false), 0f)
        assertEquals(0f, quickInfoReveal(-2.3f, reduced = false), 0f)
    }

    @Test
    fun reducedMotionSnapsAtHalfway() {
        assertEquals(1f, quickInfoReveal(0.49f, reduced = true), 0f)
        assertEquals(0f, quickInfoReveal(0.5f, reduced = true), 0f)
        assertEquals(0f, quickInfoReveal(-0.8f, reduced = true), 0f)
    }
}
