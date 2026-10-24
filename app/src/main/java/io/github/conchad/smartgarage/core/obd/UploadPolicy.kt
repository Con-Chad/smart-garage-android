package io.github.conchad.smartgarage.core.obd

/**
 * When a reading goes to the server (through the outbox): only if it moved past the last one sent, and then either
 * at the end of a trip (if [uploadOnTripEnd]) or once the car has gone [minDistance] further. [minDistance] is in the
 * user's display unit ([displayMiles]), the unit the setting is shown in.
 */
data class UploadPolicy(
    val minDistance: Double = DEFAULT_MIN_DISTANCE,
    val displayMiles: Boolean,
    val uploadOnTripEnd: Boolean = true,
) {
    val minDistanceKm: Double get() = OdometerUnits.displayToKm(minDistance, displayMiles)

    /** [lastSentKm] null = nothing sent yet for this vehicle: the first reading goes up. */
    fun shouldUpload(km: Int, lastSentKm: Int?, tripEnded: Boolean): Boolean {
        if (lastSentKm == null) return true
        if (km <= lastSentKm) return false
        return (tripEnded && uploadOnTripEnd) || km - lastSentKm >= minDistanceKm
    }

    companion object {
        /** 25 in the display unit (25 mi for the HR-V's owner). */
        const val DEFAULT_MIN_DISTANCE = 25.0
    }
}
