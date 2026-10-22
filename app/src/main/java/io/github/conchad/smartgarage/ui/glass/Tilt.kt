package io.github.conchad.smartgarage.ui.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The screen's "pointer" for the tilt-parallax cards (D-A30), as a point 0..1 × 0..1 over a card: `(0.5, 0.5)` is
 * level. On a phone it is the gyroscope: `rememberTilt` maps the phone's ±[GYRO_RANGE]° roll/pitch to the full
 * 0..1 range. One per screen, shared by every card on it; each card mixes in its own touch via [rememberCardTilt].
 *
 * [motion] is false under reduced motion or when the screen isn't resumed; every card then stays level, with no
 * parallax and no shine. [hasSensor] is false on a phone without a rotation vector: cards then only react to touch.
 */
@Stable
class TiltState internal constructor(
    val x: State<Float>,
    val y: State<Float>,
    val motion: Boolean,
    val hasSensor: Boolean,
)

/** Degrees of phone tilt that reach the edge of the pointer range. */
const val GYRO_RANGE = 12f

/** Smallest tilt change (degrees) the sensor path passes on; below it a card would move under a pixel. */
private const val TILT_DEADBAND = 0.015f

private const val GAIN = 0.6f

/**
 * The gyroscope pointer (D-A24 sensor rules, D-A30 mapping). With [enabled] the game rotation vector
 * (`TYPE_GAME_ROTATION_VECTOR`, falling back to `TYPE_ROTATION_VECTOR`) drives it: roll → x, pitch → y, measured
 * against a slowly tracking resting pose so a card is level however the phone is held. Registered at
 * `SENSOR_DELAY_UI` only while this composable is in the tree with [enabled] true and the lifecycle RESUMED (and
 * motion isn't reduced), unregistered on dispose. No permission is involved. A change under [TILT_DEADBAND] is
 * dropped, so a phone at rest re-targets nothing and the cards stop repainting.
 *
 * There is no idle drift: a phone lying flat is simply level, and touch gives the card life on its own.
 */
@Composable
fun rememberTilt(enabled: Boolean = true): TiltState {
    val context = LocalContext.current
    val motion = rememberAmbientMotion()
    val active = enabled && motion
    val manager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val sensor = remember(manager) {
        manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    val x = remember { mutableFloatStateOf(0.5f) }
    val y = remember { mutableFloatStateOf(0.5f) }

    if (active && sensor != null && manager != null) {
        DisposableEffect(manager, sensor) {
            val rotation = FloatArray(9)
            val orientation = FloatArray(3)
            var basePitch = Float.NaN
            var baseRoll = Float.NaN
            var lastX = 0f
            var lastY = 0f
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    SensorManager.getOrientation(rotation, orientation)
                    val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                    val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                    if (basePitch.isNaN()) {
                        basePitch = pitch
                        baseRoll = roll
                    } else {
                        // The resting pose follows the hand slowly, so a steady tilt relaxes back to level.
                        basePitch += (pitch - basePitch) * 0.02f
                        baseRoll += (roll - baseRoll) * 0.02f
                    }
                    val degX = ((pitch - basePitch) * GAIN).coerceIn(-GYRO_RANGE, GYRO_RANGE)
                    val degY = ((roll - baseRoll) * GAIN).coerceIn(-GYRO_RANGE, GYRO_RANGE)
                    // Deadband in degrees, as before; then ±GYRO_RANGE → 0..1. Roll moves the pointer sideways, pitch up
                    // and down, with the same signs the holographic frame used (pitch forward → pointer above center).
                    if (abs(degY - lastY) >= TILT_DEADBAND) {
                        lastY = degY
                        x.floatValue = 0.5f + degY / (2 * GYRO_RANGE)
                    }
                    if (abs(degX - lastX) >= TILT_DEADBAND) {
                        lastX = degX
                        y.floatValue = 0.5f - degX / (2 * GYRO_RANGE)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose {
                manager.unregisterListener(listener)
                x.floatValue = 0.5f
                y.floatValue = 0.5f
            }
        }
    }
    val hasSensor = sensor != null
    return remember(active, hasSensor) { TiltState(x, y, motion = active, hasSensor = hasSensor) }
}

/** A level pointer that never moves; the default for screens that provide no gyroscope (cards then react to touch only when motion is allowed). */
@Composable
fun rememberStaticTilt(): TiltState {
    val motion = rememberAmbientMotion()
    val center = rememberStatic(0.5f)
    return remember(motion) { TiltState(center, center, motion = motion, hasSensor = false) }
}

/** The screen's gyroscope pointer, set once per glass screen; cards read it through [rememberCardTilt]. */
val LocalTilt = staticCompositionLocalOf<TiltState?> { null }

/** Largest rotation of a tilt-parallax card, degrees, reached when the pointer is at the card's edge. */
const val MAX_TILT_DEGREES = 14f

/** Shine strength while the gyroscope alone drives the pointer; a touch brings it to 1. */
private const val GYRO_SHINE = 0.45f

/**
 * One card's pointer and what it draws from (D-A30): the gyroscope pointer from [LocalTilt], overridden by the
 * finger while it is pressed on the card. The pointer eases with a soft ease-out over 280 ms, or tracks the finger
 * with a 90 ms linear tween while pressed; on release it eases back to the gyroscope value. Rotation follows the
 * reference: `rotationX = (0.5 − py) · max · 2`, `rotationY = (px − 0.5) · max · 2`. The shine is 1 while pressed,
 * [GYRO_SHINE] while only the gyroscope moves the card, and 0 when the card is static.
 *
 * Everything animates on an [Animatable] driven from a `snapshotFlow`, so a sensor tick or a touch never
 * recomposes the card: the values are read in `graphicsLayer` and draw lambdas only.
 */
@Stable
class CardTilt internal constructor(
    /** Pointer 0..1 across the card, animated. */
    val px: State<Float>,
    val py: State<Float>,
    /** Degrees, derived from the pointer. */
    val rotationX: State<Float>,
    val rotationY: State<Float>,
    /** 0..1 strength of the radial shine. */
    val shine: State<Float>,
    /** False when motion is reduced or the screen isn't resumed; the card is then static and ignores touch. */
    val motion: Boolean,
    internal val press: (Offset) -> Unit,
    internal val release: () -> Unit,
)

private val easeOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val settleSpec: AnimationSpec<Float> = tween(280, easing = easeOut)
private val trackSpec: AnimationSpec<Float> = tween(90, easing = LinearEasing)

private class PointerTarget(val x: Float, val y: Float, val tracking: Boolean, val shine: Float)

/**
 * A card's [CardTilt] from the screen's [source] (default: [LocalTilt], else static). [followGyro] false gives a
 * touch-only card: level and shine-free until pressed, which costs nothing while idle (the content cards and the
 * drop-downs). [maxDegrees] is the rotation at the pointer's edge.
 */
@Composable
fun rememberCardTilt(
    source: TiltState? = LocalTilt.current,
    followGyro: Boolean = true,
    maxDegrees: Float = MAX_TILT_DEGREES,
): CardTilt {
    val fallback = if (source == null) rememberStaticTilt() else null
    val tilt = source ?: fallback!!
    val motion = tilt.motion
    val gyro = motion && followGyro && tilt.hasSensor
    var touch by remember { mutableStateOf<Offset?>(null) }
    val ax = remember { Animatable(0.5f) }
    val ay = remember { Animatable(0.5f) }
    val shine = remember { Animatable(0f) }

    LaunchedEffect(tilt, motion, gyro) {
        if (!motion) {
            touch = null
            coroutineScope {
                launch { ax.animateTo(0.5f, settleSpec) }
                launch { ay.animateTo(0.5f, settleSpec) }
                launch { shine.animateTo(0f, settleSpec) }
            }
            return@LaunchedEffect
        }
        snapshotFlow {
            val t = touch
            when {
                t != null -> PointerTarget(t.x, t.y, tracking = true, shine = 1f)
                gyro -> PointerTarget(tilt.x.value, tilt.y.value, tracking = false, shine = GYRO_SHINE)
                else -> PointerTarget(0.5f, 0.5f, tracking = false, shine = 0f)
            }
        }.collectLatest { target ->
            val spec = if (target.tracking) trackSpec else settleSpec
            coroutineScope {
                launch { ax.animateTo(target.x, spec) }
                launch { ay.animateTo(target.y, spec) }
                launch { shine.animateTo(target.shine, settleSpec) }
            }
        }
    }

    return remember(ax, ay, shine, motion, maxDegrees) {
        CardTilt(
            px = ax.asState(),
            py = ay.asState(),
            rotationX = object : State<Float> {
                override val value: Float get() = (0.5f - ay.value) * maxDegrees * 2
            },
            rotationY = object : State<Float> {
                override val value: Float get() = (ax.value - 0.5f) * maxDegrees * 2
            },
            shine = shine.asState(),
            motion = motion,
            press = { touch = it },
            release = { touch = null },
        )
    }
}

/**
 * Feeds the finger to [tilt] without taking the gesture from anyone: observed in the final pass, never consumed.
 * The press (consumed by a clickable or not) starts tracking; a move that a scroll or pager has consumed, or that
 * has left the card, ends it, so a list under a dragging finger doesn't tilt every card it passes. Up or cancel
 * releases.
 */
fun Modifier.tiltPointer(tilt: CardTilt): Modifier = if (!tilt.motion) this else pointerInput(tilt) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull()
            val inside = change != null &&
                change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()
            val tracking = change != null && change.pressed && inside &&
                (event.type == PointerEventType.Press || !change.isConsumed)
            if (tracking) {
                tilt.press(Offset(change!!.position.x / size.width, change.position.y / size.height))
            } else {
                tilt.release()
            }
        }
    }
}
