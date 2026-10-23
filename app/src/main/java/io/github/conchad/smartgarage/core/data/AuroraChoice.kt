package io.github.conchad.smartgarage.core.data

/**
 * What the aurora (the page background) is tinted in (D-A44): the car's own color, as before, or a color the user
 * picked. A device preference, never sent to the server: the app-wide default lives in [AppSettings], a vehicle's
 * override next to its nickname in [VehicleLocalPrefs]. Stored as text ("match" or "#RRGGBB").
 */
sealed interface AuroraChoice {
    data object MatchVehicle : AuroraChoice

    /** [argb] is opaque packed ARGB. */
    data class Custom(val argb: Int) : AuroraChoice

    fun encode(): String = when (this) {
        MatchVehicle -> MATCH
        is Custom -> "#%06X".format(argb and 0xFFFFFF)
    }

    companion object {
        private const val MATCH = "match"
        private val HEX = Regex("^#([0-9A-Fa-f]{6})$")

        /** Null for absent or unreadable text. */
        fun decode(text: String?): AuroraChoice? = when {
            text == null -> null
            text == MATCH -> MatchVehicle
            else -> HEX.find(text)?.let { Custom((0xFF000000L or it.groupValues[1].toLong(16)).toInt()) }
        }
    }
}
