package io.github.conchad.smartgarage.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
import kotlin.math.max
import kotlin.math.min
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

/** The petals' angles, degrees counter-clockwise from "right": a fan toward the upper left, 180° → 100°. */
private val ANGLES = floatArrayOf(180f, 140f, 100f)

/** Room the fan needs around the core: half the core, the radius and half a petal target, rounded up. */
private val EXTENT = 160.dp

private const val OPEN_STAGGER_MS = 45L
private const val CLOSE_STAGGER_MS = 30L

/** How long the fan's colour takes to follow the aurora; the same as the aurora's own cross-fade. */
private const val TINT_FADE_MS = 800

/** The WCAG AA floor for the glyphs on the fills. */
private const val MIN_CONTRAST = 4.5f

/**
 * The radial fan FAB (D-A31): an opaque 56 dp "+" that turns into an × and fans three 46 dp petals out in a spring
 * arc toward the upper left (180°, 140°, 100° at 76 dp from the centre, 45 ms stagger opening, 30 ms closing,
 * petals scale and fade in along the way). Core and petals are solid, coloured from the aurora tint in effect
 * ([LocalAuroraTint], i.e. the car in view; the theme primary when nothing has set one) and cross-fade over 800 ms
 * when it changes (see [fanPalette]). Open, the core takes the nav blob's gradient and glow. Under reduced motion
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
    val tokens = glass
    val reduced = rememberReducedMotion()
    val progress = remember(actions.size) { List(actions.size) { Animatable(0f) } }
    var shown by remember { mutableStateOf(expanded) }

    val tint = LocalAuroraTint.current.color ?: MaterialTheme.colorScheme.primary
    val palette = remember(tint, tokens) { fanPalette(tint, tokens) }
    val fadeSpec = if (reduced) snap() else tween<Color>(TINT_FADE_MS)
    val coreFill = animateColorAsState(palette.coreFill, fadeSpec, label = "fanCoreFill")
    val coreInk = animateColorAsState(palette.coreInk, fadeSpec, label = "fanCoreInk")
    val petalFill = animateColorAsState(palette.petalFill, fadeSpec, label = "fanPetalFill")
    val petalInk = animateColorAsState(palette.petalInk, fadeSpec, label = "fanPetalInk")

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
                        angle = ANGLES.getOrElse(index) { 180f },
                        enabled = expanded,
                        fill = petalFill,
                        ink = petalInk,
                        onClick = {
                            onExpandedChange(false)
                            action.onClick()
                        },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
            Core(
                label = label,
                expanded = expanded,
                reduced = reduced,
                fill = coreFill,
                ink = coreInk,
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/** The fan's opaque colours for one tint: each fill with the ink that clears [MIN_CONTRAST] on it. */
@Immutable
internal data class FanPalette(val coreFill: Color, val coreInk: Color, val petalFill: Color, val petalInk: Color)

/**
 * Derives the fan's colours from the aurora [tint]. The core is the tint made opaque over the page and blended
 * **35 %** toward the page colour on dark (a slightly deeper version of the car) or **25 %** toward white on light;
 * the petals are a step the other way (15 % toward the page on dark, 45 % toward white on light). Each fill is then
 * pushed further the same way, in 10 % steps, until the light ink ([GlassTokens.onPhoto]) or the dark ink
 * ([GlassTokens.onPale]) clears 4.5:1 on it, because a mid-tone car colour alone clears it with neither.
 */
internal fun fanPalette(tint: Color, tokens: GlassTokens): FanPalette {
    val base = tint.copy(alpha = 1f).compositeOver(tokens.pageBackground)
    val toward = if (tokens.dark) tokens.pageBackground else Color.White
    val core = opaqueFill(base, toward, if (tokens.dark) 0.35f else 0.25f, tokens)
    val petal = opaqueFill(base, toward, if (tokens.dark) 0.15f else 0.45f, tokens)
    return FanPalette(core.first, core.second, petal.first, petal.second)
}

/** [base] blended [start] toward [toward], and further by 0.1 until an ink clears [MIN_CONTRAST]; the fill and that ink. */
private fun opaqueFill(base: Color, toward: Color, start: Float, tokens: GlassTokens): Pair<Color, Color> {
    var t = start
    while (true) {
        val fill = lerp(base, toward, t.coerceAtMost(1f))
        val light = contrast(fill, tokens.onPhoto)
        val dark = contrast(fill, tokens.onPale)
        val ink = if (light >= dark) tokens.onPhoto else tokens.onPale
        if (max(light, dark) >= MIN_CONTRAST || t >= 1f) return fill to ink
        t += 0.1f
    }
}

/** WCAG contrast ratio of two opaque colours. */
internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return max(la, lb) / min(la, lb)
}

@Composable
private fun Core(
    label: String,
    expanded: Boolean,
    reduced: Boolean,
    fill: State<Color>,
    ink: State<Color>,
    onClick: () -> Unit,
    modifier: Modifier,
) {
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
                drawRect(fill.value)
                if (accent > 0.01f) drawRect(accentBrush, alpha = accent)
            }
            .border(1.dp, lerp(lerp(fill.value, ink.value, 0.3f), tokens.onAccent, accent * 0.4f), CircleShape)
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
            tint = lerp(ink.value, tokens.onAccent, accent),
            modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * One petal, placed in the layout phase from its [progress]: its centre starts on the core's and ends [RADIUS]
 * away along [angle] (measured from the core's centre at the bottom right, so the x step is zero or leftward);
 * it scales from 40 % and fades in on the way. Reads the animation in `offset {}` and `graphicsLayer {}` only,
 * so a frame of the fan recomposes nothing.
 */
@Composable
private fun Petal(
    action: FanAction,
    progress: Animatable<Float, *>,
    angle: Float,
    enabled: Boolean,
    fill: State<Color>,
    ink: State<Color>,
    onClick: () -> Unit,
    modifier: Modifier,
) {
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
                IntOffset(-inset + (reach * dx).roundToInt(), -(inset + (reach * dy).roundToInt()))
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
                .drawBehind { drawRect(fill.value) }
                .border(1.dp, lerp(fill.value, ink.value, 0.3f), CircleShape)
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(action.icon, contentDescription = null, tint = ink.value, modifier = Modifier.size(22.dp))
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
