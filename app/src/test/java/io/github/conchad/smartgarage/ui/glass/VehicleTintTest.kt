package io.github.conchad.smartgarage.ui.glass

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VehicleTintTest {
    private fun rgb(text: String?) = VehicleTint.argb(text)?.and(0xFFFFFF)

    @Test
    fun `hex literals win, with or without the hash for six digits`() {
        assertThat(rgb("#1a5e2a")).isEqualTo(0x1A5E2A)
        assertThat(rgb("1A5E2A metallic")).isEqualTo(0x1A5E2A)
        assertThat(rgb("#abc")).isEqualTo(0xAABBCC)
        // A bare three-letter word that happens to be hex digits is a word, not a color.
        assertThat(VehicleTint.argb("fab")).isNull()
    }

    @Test
    fun `english and german color words match whole words and compounds`() {
        assertThat(rgb("Forest Green")).isEqualTo(0x1F6B3A)
        assertThat(rgb("green")).isEqualTo(0x2E9A55)
        assertThat(rgb("Grün")).isEqualTo(rgb("green"))
        assertThat(rgb("Rot")).isEqualTo(rgb("red"))
        assertThat(rgb("Blau")).isEqualTo(rgb("blue"))
        assertThat(rgb("Dunkelrot")).isNotNull()
        assertThat(rgb("Dunkelrot")).isNotEqualTo(rgb("rot"))
        assertThat(rgb("Weiß")).isEqualTo(VehicleTint.STEEL and 0xFFFFFF)
    }

    @Test
    fun `neutrals map to a visible steel rather than black or white`() {
        for (word in listOf("Silver", "silber", "Black", "schwarz", "white", "Grey", "gray", "grau", "Anthrazit")) {
            assertThat(rgb(word)).isEqualTo(VehicleTint.STEEL and 0xFFFFFF)
        }
    }

    @Test
    fun `dark and light modifiers shift the tone and metallic desaturates`() {
        val red = VehicleTint.argb("red")!!
        val dark = VehicleTint.argb("dark red")!!
        val light = VehicleTint.argb("light red")!!
        val metallic = VehicleTint.argb("red metallic")!!
        fun r(c: Int) = (c shr 16) and 0xFF
        assertThat(r(dark)).isLessThan(r(red))
        assertThat(r(light)).isGreaterThan(r(red))
        assertThat(metallic).isNotEqualTo(red)
        // Steel stays steel: "metallic silver" must not drift away from the neutral tone.
        assertThat(rgb("metallic silver")).isEqualTo(VehicleTint.STEEL and 0xFFFFFF)
    }

    @Test
    fun `unknown text and blanks give null so the caller falls back to the theme`() {
        assertThat(VehicleTint.argb(null)).isNull()
        assertThat(VehicleTint.argb("  ")).isNull()
        assertThat(VehicleTint.argb("Soul Red Crystal")).isNotNull() // "red" inside a trade name still counts
        assertThat(VehicleTint.argb("Machine Finish")).isNull()
    }
}
