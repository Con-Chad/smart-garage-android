package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Press feedback for glass surfaces (D-A40): while a finger is down the surface scales a little ([pressScale]) and
 * a faint white wash brightens its fill ([pressGlow]), both on [Motion.pressSpring]. One [PressFeedback] per
 * surface, from [rememberPressFeedback] on the surface's [InteractionSource]. Its value is read in `graphicsLayer {}`
 * / draw lambdas only, so a press recomposes nothing. Under reduced motion the scale is dropped and the wash snaps:
 * the state is still shown, nothing moves.
 */
@Stable
class PressFeedback internal constructor(internal val reduced: Boolean) {
    internal val progress = Animatable(0f)
}

@Composable
fun rememberPressFeedback(interaction: InteractionSource): PressFeedback {
    val reduced = rememberReducedMotion()
    val press = remember(reduced) { PressFeedback(reduced) }
    LaunchedEffect(interaction, press) {
        var held = 0
        // Only presses: a hover or focus event must not cancel a running press animation.
        interaction.interactions.filterIsInstance<PressInteraction>().collectLatest { event ->
            when (event) {
                is PressInteraction.Press -> held++
                is PressInteraction.Release, is PressInteraction.Cancel -> held = (held - 1).coerceAtLeast(0)
            }
            val target = if (held > 0) 1f else 0f
            if (press.reduced) press.progress.snapTo(target) else press.progress.animateTo(target, Motion.pressSpring())
        }
    }
    return press
}

/** Scales this surface toward [pressedScale] while [press] is held ([Motion.PRESS_SCALE] shrinks, [Motion.PRESS_LIFT] lifts). */
fun Modifier.pressScale(press: PressFeedback, pressedScale: Float = Motion.PRESS_SCALE): Modifier =
    if (press.reduced) {
        this
    } else {
        graphicsLayer {
            val s = 1f + (pressedScale - 1f) * press.progress.value
            scaleX = s
            scaleY = s
        }
    }

/** Washes this surface's fill with white while [press] is held; drawn behind the content, so put it after the clip and fill. */
fun Modifier.pressGlow(press: PressFeedback): Modifier = drawBehind {
    val p = press.progress.value
    if (p > 0.01f) drawRect(Color.White.copy(alpha = Motion.PRESS_GLOW_ALPHA * p))
}
