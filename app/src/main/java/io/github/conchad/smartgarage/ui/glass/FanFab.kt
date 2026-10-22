package io.github.conchad.smartgarage.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One petal of the [FanFab]: an icon, its TalkBack label and what it starts. */
class FanAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** The core's diameter. */
private val CORE = 56.dp

/** A petal's drawn diameter; its touch target is [PETAL_TARGET]. */
private val PETAL = 46.dp
private val PETAL_TARGET = 48.dp

/** Distance from the core's centre to a petal's centre when open. */
private val RADIUS = 76.dp

/** The petals' angles, degrees counter-clockwise from "right": a fan toward the upper right, 80° → 0°. */
private val ANGLES = floatArrayOf(80f, 40f, 0f)

/** Room the fan needs around the core: half the core, the radius and half a petal target, rounded up. */
private val EXTENT = 160.dp

private const val OPEN_STAGGER_MS = 45L
private const val CLOSE_STAGGER_MS = 30L

/**
 * The radial fan FAB (D-A31): a 56 dp glass "+" that turns into an × and fans three 46 dp petals out in a spring arc
 * toward the upper right (80°, 40°, 0° at 76 dp from the centre, 45 ms stagger opening, 30 ms closing, petals
 * scale and fade in along the way). Open, the core takes the nav blob's gradient and glow. Under reduced motion
 * everything snaps. The whole thing sits in a 160 dp square so the petals stay inside a hit-testable parent; the
 * square itself takes no touches. [visible] false hides it (the home pager is scrolling) and closes it. Back
 * closes it; the caller draws a [FanScrim] under it for outside taps.
 *
 * TalkBack: the core is a button named [label] with its expanded / collapsed state; each petal is a button named
 * after its action. The petals are only in the tree while they are (or are becoming) visible.
 */
@Composable
fun FanFab(
    actions: List<FanAction>,
    label: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val reduced = rememberReducedMotion()
    val progress = remember(actions.size) { List(actions.size) { Animatable(0f) } }
    var shown by remember { mutableStateOf(expanded) }

    BackHandler(enabled = expanded) { onExpandedChange(false) }
    LaunchedEffect(visible) { if (!visible && expanded) onExpandedChange(false) }
    LaunchedEffect(expanded, reduced) {
        if (expanded) shown = true
        val target = if (expanded) 1f else 0f
        if (reduced) {
            progress.forEach { it.snapTo(target) }
        } else {
            val last = progress.lastIndex
            coroutineScope {
                progress.forEachIndexed { index, petal ->
                    launch {
                        delay(if (expanded) index * OPEN_STAGGER_MS else (last - index) * CLOSE_STAGGER_MS)
                        petal.animateTo(target, spring(dampingRatio = 0.62f, stiffness = 380f))
                    }
                }
            }
        }
        if (!expanded) shown = false
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduced) EnterTransition.None else fadeIn() + scaleIn(initialScale = 0.6f),
        exit = if (reduced) ExitTransition.None else fadeOut() + scaleOut(targetScale = 0.6f),
    ) {
        Box(Modifier.size(EXTENT)) {
            if (shown) {
                actions.forEachIndexed { index, action ->
                    Petal(
                        action = action,
                        progress = progress[index],
                        angle = ANGLES.getOrElse(index) { 0f },
                        enabled = expanded,
                        onClick = {
                            onExpandedChange(false)
                            action.onClick()
                        },
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
            }
            Core(
                label = label,
                expanded = expanded,
                reduced = reduced,
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun Core(label: String, expanded: Boolean, reduced: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tokens = glass
    val spec = if (reduced) snap() else spring<Float>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
    val rotation by animateFloatAsState(if (expanded) 135f else 0f, spec, label = "fanCore")
    val accent by animateFloatAsState(if (expanded) 1f else 0f, spec, label = "fanAccent")
    val accentBrush = remember(tokens) { Brush.linearGradient(listOf(tokens.navBlobStart, tokens.navBlobEnd)) }
    val stateText = stringResource(if (expanded) R.string.form_expanded else R.string.form_collapsed)
    Box(
        modifier
            .size(CORE)
            .shadow(
                elevation = if (expanded) 14.dp else 10.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = if (expanded) tokens.navGlow else tokens.shadow,
                spotColor = if (expanded) tokens.navGlow else tokens.shadow,
            )
            .clip(CircleShape)
            .drawBehind {
                drawRect(tokens.panel)
                drawRect(tokens.pageBackground.copy(alpha = 0.35f))
                if (accent > 0.01f) drawRect(accentBrush, alpha = accent)
            }
            .border(1.dp, if (expanded) tokens.navBorder else tokens.panelBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = stateText
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = if (expanded) tokens.onAccent else tokens.sheen,
            modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * One petal, placed in the layout phase from its [progress]: its centre starts on the core's and ends [RADIUS]
 * away along [angle]; it scales from 40 % and fades in on the way. Reads the animation in `offset {}` and
 * `graphicsLayer {}` only, so a frame of the fan recomposes nothing.
 */
@Composable
private fun Petal(action: FanAction, progress: Animatable<Float, *>, angle: Float, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tokens = glass
    val radians = Math.toRadians(angle.toDouble())
    val dx = cos(radians).toFloat()
    val dy = sin(radians).toFloat()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(PETAL_TARGET)
            .offset {
                val p = progress.value
                val inset = ((CORE - PETAL_TARGET) / 2).roundToPx()
                val reach = RADIUS.toPx() * p
                IntOffset(inset + (reach * dx).roundToInt(), -(inset + (reach * dy).roundToInt()))
            }
            .graphicsLayer {
                val p = progress.value
                alpha = p
                scaleX = 0.4f + 0.6f * p
                scaleY = 0.4f + 0.6f * p
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = action.label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(PETAL)
                .shadow(8.dp, CircleShape, clip = false, ambientColor = tokens.shadow, spotColor = tokens.shadow)
                .clip(CircleShape)
                .drawBehind {
                    drawRect(tokens.panel)
                    drawRect(tokens.pageBackground.copy(alpha = 0.35f))
                }
                .border(1.dp, tokens.panelBorder, CircleShape)
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(action.icon, contentDescription = null, tint = tokens.sheen, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * The veil under an open [FanFab]: the page colour at 60 %, tap anywhere to close. Fades under motion, appears at
 * once under reduced motion. Drawn by the screen between its content and the fan.
 */
@Composable
fun FanScrim(visible: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = glass
    val reduced = rememberReducedMotion()
    val dismissLabel = stringResource(R.string.action_cancel)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduced) EnterTransition.None else fadeIn(),
        exit = if (reduced) ExitTransition.None else fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(tokens.veil) }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = dismissLabel, onClick = onDismiss),
        )
    }
}
