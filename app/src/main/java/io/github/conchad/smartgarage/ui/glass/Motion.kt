package io.github.conchad.smartgarage.ui.glass

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
        value = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    return reduced
}

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
