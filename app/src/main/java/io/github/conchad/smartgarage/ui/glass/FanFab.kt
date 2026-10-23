package io.github.conchad.smartgarage.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import kotlin.math.tan
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One petal of the [FanFab]: an icon, its TalkBack [label] (the full verb, "Log fill-up"), the short [caption]
 * written next to it on screen ("Fuel", D-A32) and what it starts.
 */
class FanAction(val icon: ImageVector, val label: String, val caption: String, val onClick: () -> Unit)

/** The core's diameter. */
private val CORE = 56.dp

/** A petal's drawn diameter; its touch target is [PETAL_TARGET]. */
private val PETAL = 46.dp
private val PETAL_TARGET = 48.dp

/** Distance from the core's centre to a petal's centre when open. */
private val RADIUS = 124.dp

/**
 * The petals' angles, degrees counter-clockwise from "right": a fan toward the upper left (D-A32). Chosen so that
 * at [RADIUS] neighbouring 48 dp targets clear each other (chords 49 / 52 / 58 dp) and the captions to the left of
 * the petals never share a line: their centres are 48.5 / 42 / 28.5 dp apart vertically, more than a `labelMedium`
 * line at font scale 1.3 (≈ 21 dp). Equal 30° steps would leave the top two only 13 dp apart.
 */
private val ANGLES = floatArrayOf(180f, 157f, 133f, 106f)

/** Room the fan's touch targets need around the core: half the core, the radius and half a petal target, rounded up. The captions may draw beyond it. */
private val EXTENT = 184.dp

/**
 * The connector line from a petal to its caption: it starts [PETAL_GAP] off the petal's drawn edge and ends
 * [CAPTION_GAP] short of the text. A straight connector is [CONNECTOR] long. An elbowed one ([ELBOW]) leaves the
 * petal [ELBOW_ANGLE] above horizontal for [ELBOW_RUN] (horizontally), then bends into a [CONNECTOR] horizontal run,
 * lifting its caption clear of the petal below.
 */
private val CONNECTOR = 12.dp
private val CAPTION_GAP = 6.dp
private val PETAL_GAP = 5.dp
private const val ELBOW_ANGLE = 20f
private val ELBOW_RUN = 16.dp

/**
 * Which petals' connectors elbow upward, parallel to [ANGLES]. Only the top petal (Scan Receipt, 106°): its caption
 * runs left over the 133° petal, whose top it would otherwise graze at font scale 1.3; the elbow lifts it ≈ 15 dp.
 */
private val ELBOW = booleanArrayOf(false, false, false, true)

/** A held core or petal shrinks to this (D-A40): the beads are small, so a touch more than a pill. */
private const val BEAD_PRESS_SCALE = 0.92f

private const val OPEN_STAGGER_MS = 45L
private const val CLOSE_STAGGER_MS = 30L

/** The WCAG AA floor for the glyphs on the fills. */
private const val MIN_CONTRAST = 4.5f

/** How far the depth gradient lightens the top-left and darkens the bottom-right of a disc, as a blend toward white / black. */
private const val DEPTH_LUMA = 0.10f

/** The inner highlight's peak alpha, white, at the top-left of a disc. */
private const val HIGHLIGHT_ALPHA = 0.16f

/** The connector lines' alpha in the caption colour. */
private const val CONNECTOR_ALPHA = 0.6f

/**
 * The radial fan FAB (D-A31, D-A32): an opaque 56 dp "+" that turns into an × and fans **four** 46 dp petals out in
 * a spring arc toward the upper left ([ANGLES] at [RADIUS] from the centre, 45 ms stagger opening, 30 ms closing,
 * petals scale and fade in along the way). Each petal has a thin connector line running left from its edge to a
 * short caption, which animate with it. Core and petals are solid, coloured from the aurora tint in effect
 * ([AuroraTintHolder.effective], i.e. the car in view) and cross-fade over 800 ms when it changes (see
 * [fanPalette]); each disc carries a soft top-left → bottom-right depth gradient and an inner highlight so it reads
 * as a bead rather than a flat dot. Open, the core takes the nav blob's gradient and glow. Under reduced motion
 * everything snaps. The touch targets sit in a [EXTENT] square so they stay inside a hit-testable parent; the
 * square itself takes no touches. [visible] false hides it (the home pager is scrolling) and closes it. Back closes
 * it; the caller draws a [FanScrim] under it for outside taps.
 *
 * TalkBack: the core is a button named [label] with its expanded / collapsed state; each petal is a button named
 * after its action, and the caption beside it is not a separate node. The petals are only in the tree while they
 * are (or are becoming) visible.
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

    val tint = LocalAuroraTint.current.effective ?: MaterialTheme.colorScheme.primary
    val palette = remember(tint, tokens) { fanPalette(tint, tokens) }
    // The fan's colour follows the aurora on the aurora's own cross-fade.
    val fadeSpec = tween<Color>(Motion.TINT_MS).orSnap(reduced)
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
                        petal.animateTo(target, Motion.petalSpring())
                    }
                }
            }
        }
        if (!expanded) shown = false
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = (fadeIn(tween(Motion.SHORT_MS)) + scaleIn(Motion.popSpring(), initialScale = 0.6f)).orNone(reduced),
        exit = (fadeOut(tween(Motion.QUICK_MS)) + scaleOut(tween(Motion.QUICK_MS), targetScale = 0.6f)).orNone(reduced),
    ) {
        Box(Modifier.size(EXTENT)) {
            if (shown) {
                actions.forEachIndexed { index, action ->
                    val angle = ANGLES.getOrElse(index) { 180f }
                    PetalCaption(
                        text = action.caption,
                        progress = progress[index],
                        angle = angle,
                        elbow = ELBOW.getOrElse(index) { false },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                    Petal(
                        action = action,
                        progress = progress[index],
                        angle = angle,
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
 * ([GlassTokens.onPale]) clears 4.5:1 on it, because a mid-tone car colour alone clears it with neither. Since
 * D-A32 the check is against the disc's lightest point (the depth gradient's top-left plus the inner highlight) and
 * its darkest (the gradient's bottom-right), not the flat fill, so the glyph clears the floor everywhere on the bead.
 */
internal fun fanPalette(tint: Color, tokens: GlassTokens): FanPalette {
    val base = tint.copy(alpha = 1f).compositeOver(tokens.pageBackground)
    val toward = if (tokens.dark) tokens.pageBackground else Color.White
    val core = opaqueFill(base, toward, if (tokens.dark) 0.35f else 0.25f, tokens)
    val petal = opaqueFill(base, toward, if (tokens.dark) 0.15f else 0.45f, tokens)
    return FanPalette(core.first, core.second, petal.first, petal.second)
}

/** [base] blended [start] toward [toward], and further by 0.1 until an ink clears [MIN_CONTRAST] on both extremes of the disc; the fill and that ink. */
private fun opaqueFill(base: Color, toward: Color, start: Float, tokens: GlassTokens): Pair<Color, Color> {
    var t = start
    while (true) {
        val fill = lerp(base, toward, t.coerceAtMost(1f))
        val lightest = lightestOf(fill)
        val darkest = darkestOf(fill)
        val light = min(contrast(lightest, tokens.onPhoto), contrast(darkest, tokens.onPhoto))
        val dark = min(contrast(lightest, tokens.onPale), contrast(darkest, tokens.onPale))
        val ink = if (light >= dark) tokens.onPhoto else tokens.onPale
        if (max(light, dark) >= MIN_CONTRAST || t >= 1f) return fill to ink
        t += 0.1f
    }
}

/** The depth gradient's light end, with the inner highlight's peak on top: the lightest colour a glyph can sit on. */
private fun lightestOf(fill: Color): Color = Color.White.copy(alpha = HIGHLIGHT_ALPHA).compositeOver(lerp(fill, Color.White, DEPTH_LUMA))

/** The depth gradient's dark end. */
private fun darkestOf(fill: Color): Color = lerp(fill, Color.Black, DEPTH_LUMA)

/** WCAG contrast ratio of two opaque colours. */
internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return max(la, lb) / min(la, lb)
}

/**
 * The bead look (D-A32): the fill as a linear gradient from [DEPTH_LUMA] lighter at the top-left to [DEPTH_LUMA]
 * darker at the bottom-right, then a soft white radial highlight peaking at [HIGHLIGHT_ALPHA] just up-left of the
 * centre. Brushes are built in `drawWithCache` and rebuilt only while the 800 ms tint fade changes [fill]; an
 * [overlay] (the open core's accent gradient) is drawn between the two at [overlayAlpha] so the highlight stays on top.
 */
private fun Modifier.bead(fill: State<Color>, overlay: Brush? = null, overlayAlpha: () -> Float = { 0f }): Modifier = drawWithCache {
    val base = fill.value
    val depth = Brush.linearGradient(
        listOf(lerp(base, Color.White, DEPTH_LUMA), base, lerp(base, Color.Black, DEPTH_LUMA)),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val highlight = Brush.radialGradient(
        0f to Color.White.copy(alpha = HIGHLIGHT_ALPHA),
        1f to Color.Transparent,
        center = Offset(size.width * 0.35f, size.height * 0.30f),
        radius = size.width * 0.6f,
    )
    onDrawBehind {
        drawRect(depth)
        if (overlay != null) {
            val a = overlayAlpha()
            if (a > 0.01f) drawRect(overlay, alpha = a)
        }
        drawRect(highlight)
    }
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
    val spec = Motion.popSpring<Float>().orSnap(reduced)
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressFeedback(interaction)
    val rotation by animateFloatAsState(if (expanded) 135f else 0f, spec, label = "fanCore")
    val accent = animateFloatAsState(if (expanded) 1f else 0f, spec, label = "fanAccent")
    val accentBrush = remember(tokens) { Brush.linearGradient(listOf(tokens.navBlobStart, tokens.navBlobEnd)) }
    val stateText = stringResource(if (expanded) R.string.form_expanded else R.string.form_collapsed)
    Box(
        modifier
            .size(CORE)
            .pressScale(press, BEAD_PRESS_SCALE)
            .shadow(
                elevation = if (expanded) 14.dp else 10.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = if (expanded) tokens.navGlow else tokens.shadow,
                spotColor = if (expanded) tokens.navGlow else tokens.shadow,
            )
            .clip(CircleShape)
            .bead(fill, overlay = accentBrush, overlayAlpha = { accent.value })
            .border(1.dp, lerp(lerp(fill.value, ink.value, 0.3f), tokens.onAccent, accent.value * 0.4f), CircleShape)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = stateText
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = lerp(ink.value, tokens.onAccent, accent.value),
            modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}

/** Where a petal's centre is, relative to the core's centre, at [progress] along [angle]: `(reach · cos, −reach · sin)` in px. */
private class PetalVector(angle: Float) {
    val dx = cos(Math.toRadians(angle.toDouble())).toFloat()
    val dy = sin(Math.toRadians(angle.toDouble())).toFloat()
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
    val vector = remember(angle) { PetalVector(angle) }
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressFeedback(interaction)
    Box(
        modifier
            .size(PETAL_TARGET)
            .offset {
                val p = progress.value
                val inset = ((CORE - PETAL_TARGET) / 2).roundToPx()
                val reach = RADIUS.toPx() * p
                IntOffset(-inset + (reach * vector.dx).roundToInt(), -(inset + (reach * vector.dy).roundToInt()))
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
                .pressScale(press, BEAD_PRESS_SCALE)
                .shadow(8.dp, CircleShape, clip = false, ambientColor = tokens.shadow, spotColor = tokens.shadow)
                .clip(CircleShape)
                .bead(fill)
                .border(1.dp, lerp(fill.value, ink.value, 0.3f), CircleShape)
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(action.icon, contentDescription = null, tint = ink.value, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Where a caption's connector starts and how high its caption sits, relative to the petal's centre, in dp: the start
 * is [PETAL_GAP] off the petal's edge, straight left or, for an [elbow], along [ELBOW_ANGLE]; the horizontal run (and
 * the caption's centre) is [rise] above the centre.
 */
private class ConnectorGeometry(elbow: Boolean) {
    private val rad = Math.toRadians(if (elbow) ELBOW_ANGLE.toDouble() else 0.0)
    private val startR = PETAL.value / 2 + PETAL_GAP.value
    /** Start point's distance left of / above the petal's centre. */
    val startDx = (startR * cos(rad)).toFloat().dp
    val startDy = (startR * sin(rad)).toFloat().dp
    /** The angled segment's horizontal and vertical extent (zero when straight). */
    val runDx = if (elbow) ELBOW_RUN else 0.dp
    val runDy = (runDx.value * tan(rad)).toFloat().dp
    /** Height of the horizontal run, and the caption's centre, above the petal's centre. */
    val rise = startDy + runDy
    /** The connector's drawing box: [width] wide, [height] tall, centred on the caption; the petal's centre line is [rise] below its middle. */
    val width = runDx + CONNECTOR
    val height = rise * 2 + 2.dp
}

/**
 * A petal's caption (D-A32): a 1 dp connector line from just off the petal's edge ([PETAL_GAP]) to just short of the
 * short `labelMedium` text ([CAPTION_GAP]); straight and level with the petal's centre, or for an [elbow] first rising
 * at [ELBOW_ANGLE] and then running level, with the text centred on that run. Both sit on the [FanScrim], so the text
 * is the glass text colour with the on-aurora halo and the line that colour at [CONNECTOR_ALPHA]. It follows the
 * petal's position, scale and alpha from the line's start (the petal side), read in `offset {}` / `graphicsLayer {}`
 * only. Not a TalkBack node: the petal itself carries the full name.
 */
@Composable
private fun PetalCaption(text: String, progress: Animatable<Float, *>, angle: Float, elbow: Boolean, modifier: Modifier) {
    val tokens = glass
    val vector = remember(angle) { PetalVector(angle) }
    val geo = remember(elbow) { ConnectorGeometry(elbow) }
    Row(
        modifier
            .height(PETAL_TARGET)
            .offset {
                val p = progress.value
                val reach = RADIUS.toPx() * p
                // Right edge at the connector's start: the petal's centre is half the core in from the corner.
                val rightEdge = -(CORE / 2 + geo.startDx).roundToPx() + (reach * vector.dx).roundToInt()
                val inset = ((CORE - PETAL_TARGET) / 2).roundToPx()
                IntOffset(rightEdge, -(inset + geo.rise.roundToPx() + (reach * vector.dy).roundToInt()))
            }
            .graphicsLayer {
                val p = progress.value
                alpha = p
                scaleX = 0.4f + 0.6f * p
                scaleY = 0.4f + 0.6f * p
                transformOrigin = TransformOrigin(1f, 0.5f + (geo.rise - geo.startDy) / PETAL_TARGET)
            }
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.onAurora(tokens),
            color = tokens.textPrimary,
            maxLines = 1,
        )
        Spacer(Modifier.width(CAPTION_GAP))
        Box(
            Modifier
                .width(geo.width)
                .height(geo.height)
                .drawBehind {
                    val color = tokens.textPrimary.copy(alpha = CONNECTOR_ALPHA)
                    val stroke = 1.dp.toPx()
                    val mid = size.height / 2
                    val bend = Offset(CONNECTOR.toPx(), mid)
                    drawLine(color, Offset(0f, mid), bend, stroke)
                    if (elbow) {
                        // Start sits startDy above the petal's centre line, which is rise below the middle.
                        val start = Offset(size.width, mid + (geo.rise - geo.startDy).toPx())
                        drawLine(color, bend, start, stroke, cap = StrokeCap.Round)
                    }
                },
        )
    }
}

/**
 * The veil under an open [FanFab]: the page colour at 60 %, tap anywhere to close. Fades under motion, appears at
 * once under reduced motion. Drawn by the screen between the content and the fan.
 */
@Composable
fun FanScrim(visible: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = glass
    val reduced = rememberReducedMotion()
    val dismissLabel = stringResource(R.string.action_cancel)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(Motion.SHORT_MS)).orNone(reduced),
        exit = fadeOut(tween(Motion.SHORT_MS)).orNone(reduced),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(tokens.veil) }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = dismissLabel, onClick = onDismiss),
        )
    }
}
