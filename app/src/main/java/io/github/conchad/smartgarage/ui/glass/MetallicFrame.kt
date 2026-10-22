package io.github.conchad.smartgarage.ui.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope

/** Tilt of the holographic frame, in degrees (≤ ±[MAX_TILT]) plus the sheen position 0..1 that follows it. */
@Stable
class TiltState internal constructor(
    val rotationX: State<Float>,
    val rotationY: State<Float>,
    val sheen: State<Float>,
    /** Pointer fallback: the frame reports touches here when no gyroscope drives the tilt. */
    internal val onPointer: ((xFraction: Float, yFraction: Float) -> Unit)?,
)

const val MAX_TILT = 12f

/**
 * The tilt source for a [MetallicFrame] (D-A24). With [enabled] the phone's game rotation vector
 * (`TYPE_GAME_ROTATION_VECTOR`, falling back to `TYPE_ROTATION_VECTOR`) drives it: roll → rotationY, pitch →
 * rotationX, measured against a slowly tracking resting pose so the frame is level however the phone is held.
 * Registered only while this composable is in the tree with [enabled] true, at `SENSOR_DELAY_UI`, and
 * unregistered on dispose, so a frame off screen or a paused app costs no battery. No permission is needed.
 *
 * Without a rotation sensor the frame follows the finger and drifts idly, like the snippet's pointer mode.
 * Reduced motion gives a level frame with the sheen parked at center.
 */
@Composable
fun rememberTilt(enabled: Boolean): TiltState {
    val context = LocalContext.current
    val motion = rememberAmbientMotion()
    val active = enabled && motion
    val manager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val sensor = remember(manager) {
        manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    var targetX by remember { mutableFloatStateOf(0f) }
    var targetY by remember { mutableFloatStateOf(0f) }
    var pointerDriven by remember { mutableFloatStateOf(0f) }

    if (active && sensor != null && manager != null) {
        DisposableEffect(manager, sensor) {
            val rotation = FloatArray(9)
            val orientation = FloatArray(3)
            var basePitch = Float.NaN
            var baseRoll = Float.NaN
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
                    val nextX = ((pitch - basePitch) * GAIN).coerceIn(-MAX_TILT, MAX_TILT)
                    val nextY = ((roll - baseRoll) * GAIN).coerceIn(-MAX_TILT, MAX_TILT)
                    // A phone at rest still reports a few hundredths of a degree of jitter; each would re-target the
                    // springs and keep the frame repainting every frame for a change far under a pixel.
                    if (abs(nextX - targetX) >= TILT_DEADBAND) targetX = nextX
                    if (abs(nextY - targetY) >= TILT_DEADBAND) targetY = nextY
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose {
                manager.unregisterListener(listener)
                targetX = 0f
                targetY = 0f
            }
        }
    } else if (active && sensor == null) {
        // No rotation sensor: a gentle idle drift (the snippet's `t += 0.011` loop) until the user touches the frame.
        LaunchedEffect(Unit) {
            var t = 0f
            while (true) {
                withFrameMillis { }
                if (pointerDriven == 0f) {
                    t += 0.011f
                    targetY = sin(t) * 0.42f * MAX_TILT
                    targetX = cos(t * 0.7f) * 0.3f * MAX_TILT
                }
            }
        }
    }
    LaunchedEffect(active) {
        if (!active) {
            targetX = 0f
            targetY = 0f
            pointerDriven = 0f
        }
    }

    val rx = animateFloatAsState(targetX, spring(dampingRatio = 0.8f, stiffness = 120f), label = "tiltX")
    val ry = animateFloatAsState(targetY, spring(dampingRatio = 0.8f, stiffness = 120f), label = "tiltY")
    val sheen = remember(rx, ry) {
        object : State<Float> {
            override val value: Float get() = 0.5f + (ry.value / MAX_TILT) * 0.5f
        }
    }
    val onPointer: ((Float, Float) -> Unit)? = if (active && sensor == null) {
        { px, py ->
            pointerDriven = 1f
            targetY = ((px - 0.5f) * MAX_TILT * 2).coerceIn(-MAX_TILT, MAX_TILT)
            targetX = ((0.5f - py) * MAX_TILT * 2).coerceIn(-MAX_TILT, MAX_TILT)
        }
    } else {
        null
    }
    return remember(rx, ry, sheen, onPointer) { TiltState(rx, ry, sheen, onPointer) }
}

/** A level, static tilt for frames that don't move (lists). */
@Composable
fun rememberStaticTilt(): TiltState {
    val zero = rememberStatic(0f)
    val center = rememberStatic(0.5f)
    return remember { TiltState(zero, zero, center, null) }
}

private const val GAIN = 0.6f

/** Smallest tilt change (degrees) the sensor path passes on; below it the sheen would move under a pixel. */
private const val TILT_DEADBAND = 0.015f

/**
 * The "Holographic Iridescent" frame (D-A24) around a vehicle photo: a chrome border (3 dp gradient of the foil
 * chip colors), a sheen band at 115° whose position follows the tilt, faint diagonal grain, and a 3D tilt via
 * `rotationX`/`rotationY` with a camera distance that keeps the perspective gentle. Content is clipped inside the
 * border.
 */
@Composable
fun MetallicFrame(
    modifier: Modifier = Modifier,
    tilt: TiltState = rememberStaticTilt(),
    cornerRadius: Dp = glass.radiusLarge,
    borderWidth: Dp = 3.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = glass
    val outer = RoundedCornerShape(cornerRadius)
    val inner = RoundedCornerShape(cornerRadius - borderWidth)
    Box(
        modifier
            .graphicsLayer {
                rotationX = tilt.rotationX.value
                rotationY = tilt.rotationY.value
                cameraDistance = 12f * density
            }
            .shadow(8.dp, outer, clip = false, ambientColor = tokens.frameGlow, spotColor = tokens.frameGlow)
            .clip(outer)
            .border(borderWidth, Brush.sweepGradient(tokens.frameChrome), outer)
            .then(
                if (tilt.onPointer != null) {
                    Modifier.pointerInput(tilt) {
                        coroutineScope {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: continue
                                    if (change.pressed) {
                                        tilt.onPointer.invoke(
                                            (change.position.x / size.width).coerceIn(0f, 1f),
                                            (change.position.y / size.height).coerceIn(0f, 1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(Modifier.padding(borderWidth).clip(inner)) {
            content()
            // Sheen + grain overlay, drawn above the photo; reads the tilt in the draw phase only.
            Box(
                Modifier.fillMaxSize().drawWithCache {
                    // Grain: the snippet's `repeating-linear-gradient(115deg, grain 0 2px, transparent 2px 5px)` as one
                    // tiled brush (2 px on, 3 px off along the 115° normal), built once per size.
                    val step = 5.dp.toPx()
                    val angle = Math.toRadians(115.0)
                    val normal = Offset(sin(angle).toFloat() * step, -cos(angle).toFloat() * step)
                    val on = (2f / 5f)
                    val grainBrush = Brush.linearGradient(
                        colorStops = arrayOf(0f to tokens.frameGrain, on to tokens.frameGrain, on to Color.Transparent, 1f to Color.Transparent),
                        start = Offset.Zero,
                        end = normal,
                        tileMode = TileMode.Repeated,
                    )
                    // Sheen band: 240 % wide gradient whose position follows the tilt (0..1 → left..right). Built once
                    // at offset 0; a frame slides the canvas under it instead of creating a new shader.
                    val gradientW = size.width * 2.4f
                    val sheenBrush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.18f to Color.Transparent,
                            0.38f to tokens.frameSheen[0],
                            0.50f to tokens.frameSheen[1],
                            0.62f to tokens.frameSheen[2],
                            0.82f to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                        start = Offset.Zero,
                        end = Offset(gradientW, size.height * 0.45f),
                    )
                    onDrawWithContent {
                        drawContent()
                        val offset = -(gradientW - size.width) * tilt.sheen.value
                        translate(left = offset) {
                            drawRect(sheenBrush, topLeft = Offset(-offset, 0f), size = size, alpha = 0.85f, blendMode = tokens.frameSheenBlend)
                        }
                        drawRect(grainBrush, alpha = 0.5f)
                    }
                },
            )
        }
    }
}

/** Keeps a value's magnitude under [limit]; for callers clamping their own tilt inputs. */
fun clampTilt(value: Float, limit: Float = MAX_TILT): Float = if (abs(value) > limit) limit * (if (value < 0) -1f else 1f) else value
