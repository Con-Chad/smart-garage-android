package io.github.conchad.smartgarage.core.obd.link

import io.github.conchad.smartgarage.core.obd.data.SimulatorSettings

/**
 * The debug simulator as a transport (D-A70). Only debug builds bind one (src/debug); in release the optional binding
 * is absent, so "Simulated adapter" isn't offered and no simulator code ships.
 */
interface SimulatorTransport : AdapterTransport {
    /** Applied at the next [open]; the odometer carries over. */
    var settings: SimulatorSettings

    /** The pretend car's odometer now, km. */
    val odometerKm: Double
}
