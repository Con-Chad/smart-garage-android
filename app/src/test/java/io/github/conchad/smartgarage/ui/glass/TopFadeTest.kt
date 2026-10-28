package io.github.conchad.smartgarage.ui.glass

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TopFadeTest {
    @Test
    fun `band slides in with the scroll and settles once it has moved its own length`() {
        // No lift (Fuel, Service): opaque end at the scrolled distance, so 1 px of scroll moves it 1 px, not all in.
        assertThat(fadeBandTop(1f, 0f, 72f)).isEqualTo(-71f)
        assertThat(fadeBandTop(36f, 0f, 72f)).isEqualTo(-36f)
        assertThat(fadeBandTop(72f, 0f, 72f)).isEqualTo(0f)
        assertThat(fadeBandTop(500f, 0f, 72f)).isEqualTo(0f)
        assertThat(fadeBandTop(Float.POSITIVE_INFINITY, 0f, 72f)).isEqualTo(0f)
    }

    @Test
    fun `with a lift the band starts over the empty padding and is in after length minus lift`() {
        // At the first pixel the opaque end sits at lift + 1: only the padding (no content) above it is erased.
        assertThat(fadeBandTop(1f, 16f, 72f)).isEqualTo(-55f)
        assertThat(fadeBandTop(56f, 16f, 72f)).isEqualTo(0f)
        assertThat(fadeBandTop(-4f, 16f, 72f)).isEqualTo(-56f)
    }

    @Test
    fun `lazy scroll adds the remembered spacing of the items scrolled off`() {
        val steps = intArrayOf(40, 300, -1, -1, -1, -1, -1, -1)
        assertThat(lazyScrolledPx(0, 0, steps)).isEqualTo(0f)
        assertThat(lazyScrolledPx(0, 12, steps)).isEqualTo(12f)
        // A 40 px header scrolled off: 40 + 10, not "fully in" just because the index moved on.
        assertThat(lazyScrolledPx(1, 10, steps)).isEqualTo(50f)
        assertThat(lazyScrolledPx(2, 5, steps)).isEqualTo(345f)
    }

    @Test
    fun `lazy scroll past items never laid out together is fully in`() {
        val steps = intArrayOf(40, 300, -1, -1, -1, -1, -1, -1)
        assertThat(lazyScrolledPx(3, 0, steps)).isEqualTo(Float.POSITIVE_INFINITY)
        assertThat(lazyScrolledPx(20, 0, steps)).isEqualTo(Float.POSITIVE_INFINITY)
    }
}
