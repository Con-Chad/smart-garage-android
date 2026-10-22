package io.github.conchad.smartgarage.ui.glass

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * True when the user turned animations off (animator duration scale 0 in Developer options / "Remove animations"
 * in Accessibility). Ambient effects then render one static frame, the snippets' `prefers-reduced-motion` guard.
 * Re-read whenever the screen resumes, so a change in system settings applies on return.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val resumed = rememberIsResumed()
    val reduced by produceState(initialValue = false, resumed) {
        value = isReducedMotion(context)
    }
    return reduced
}

/**
 * The same setting read synchronously, for a decision that is taken once at first composition and never revisited
 * (the bubble pop-in, D-A32): [rememberReducedMotion]'s first frame is always `false` while its producer runs.
 */
fun isReducedMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/** Whether the hosting lifecycle is RESUMED; ambient animations stop composing when it isn't (the `visibilitychange` pause). */
@Composable
fun rememberIsResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    return state.isAtLeast(Lifecycle.State.RESUMED)
}

/** Ambient motion runs only while on screen and allowed; one flag for every effect on a screen. */
@Composable
fun rememberAmbientMotion(): Boolean {
    val resumed = rememberIsResumed()
    val reduced = rememberReducedMotion()
    return resumed && !reduced
}

/** A [State] that never changes, for the static branch of an animation. */
@Composable
fun <T> rememberStatic(value: T): State<T> = remember(value) { mutableStateOf(value) }

/**
 * Milliseconds since this composable entered the tree, advanced from the frame clock but at most once per
 * [minIntervalMillis]. Ambient effects derive their phase from it inside a draw lambda, so they repaint at that
 * cadence instead of once per vsync: a 120 Hz panel would otherwise redraw the aurora 120 times a second for
 * ribbons that move about 25 px/s. Between ticks the coroutine sleeps rather than waking on every frame; the
 * tick itself lands on a vsync so the write lines up with a frame. The value is real elapsed time, so uneven tick
 * spacing never changes the speed of what is drawn from it.
 */
@Composable
fun rememberAmbientClock(minIntervalMillis: Long): State<Long> {
    val elapsed = remember { mutableLongStateOf(0L) }
    LaunchedEffect(minIntervalMillis) {
        val start = withFrameNanos { it }
        while (true) {
            val sleep = minIntervalMillis - FRAME_SLACK_MS
            if (sleep > 0) delay(sleep)
            val now = withFrameNanos { it }
            elapsed.longValue = (now - start) / 1_000_000L
        }
    }
    return elapsed
}

/** Woken this much before the interval ends so the next vsync, not the one after, carries the tick. */
private const val FRAME_SLACK_MS = 4L
