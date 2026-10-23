package io.github.conchad.smartgarage.ui.glass

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.data.AuroraChoice
import org.junit.Test

class AuroraTintResolverTest {
    private val purple = AuroraChoice.Custom(0xFF7A3AB8.toInt())
    private val teal = AuroraChoice.Custom(0xFF1F8A8A.toInt())
    private val match = AuroraChoice.MatchVehicle

    private fun rgb(argb: Int?) = argb?.and(0xFFFFFF)

    @Test
    fun `with nothing chosen the car's own color wins, as before`() {
        assertThat(AuroraTintResolver.resolve(null, match, "Forest Green")).isEqualTo(VehicleTint.argb("Forest Green"))
        assertThat(AuroraTintResolver.resolve(null, match, null)).isNull()
    }

    @Test
    fun `the settings choice replaces the car's color when the vehicle has no override`() {
        assertThat(rgb(AuroraTintResolver.resolve(null, purple, "Forest Green"))).isEqualTo(0x7A3AB8)
        assertThat(rgb(AuroraTintResolver.resolve(null, purple, null))).isEqualTo(0x7A3AB8)
    }

    @Test
    fun `a vehicle override beats the settings choice`() {
        assertThat(rgb(AuroraTintResolver.resolve(teal, purple, "Forest Green"))).isEqualTo(0x1F8A8A)
        assertThat(rgb(AuroraTintResolver.resolve(teal, match, "Forest Green"))).isEqualTo(0x1F8A8A)
    }

    @Test
    fun `a vehicle set to match keeps its own color even when settings picked one`() {
        assertThat(AuroraTintResolver.resolve(match, purple, "red")).isEqualTo(VehicleTint.argb("red"))
        assertThat(AuroraTintResolver.resolve(match, purple, "no color word")).isNull()
    }

    @Test
    fun `very pale and very dark picks are clamped to a legible lightness`() {
        val white = AuroraTintResolver.resolve(null, AuroraChoice.Custom(0xFFFFFFFF.toInt()), null)!!
        val black = AuroraTintResolver.resolve(null, AuroraChoice.Custom(0xFF000000.toInt()), null)!!
        assertThat(AuroraTintResolver.hsl(white).third).isWithin(0.01f).of(AuroraTintResolver.MAX_LIGHTNESS)
        assertThat(AuroraTintResolver.hsl(black).third).isWithin(0.01f).of(AuroraTintResolver.MIN_LIGHTNESS)
        // A mid-tone pick is left exactly as chosen.
        assertThat(AuroraTintResolver.legible(0xFF2F6FD6.toInt())).isEqualTo(0xFF2F6FD6.toInt())
    }

    @Test
    fun `pale swatches take the dark ink`() {
        assertThat(AuroraTintResolver.isPale(0xFFE8C62A.toInt())).isTrue()
        assertThat(AuroraTintResolver.isPale(0xFF1F3A7A.toInt())).isFalse()
    }

    @Test
    fun `choices round-trip through their stored text`() {
        assertThat(AuroraChoice.decode(match.encode())).isEqualTo(match)
        assertThat(AuroraChoice.decode(purple.encode())).isEqualTo(purple)
        assertThat(AuroraChoice.decode("#zzz")).isNull()
        assertThat(AuroraChoice.decode(null)).isNull()
    }
}
