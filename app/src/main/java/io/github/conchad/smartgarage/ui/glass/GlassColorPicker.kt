package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The aurora color setting (D-A44), shared by Settings (the app-wide default) and the vehicle form (a per-vehicle
 * override): a "Match vehicle color" switch row and, when it is off, [GlassColorPicker]. The picker folds in and out
 * with the standard short expand, or at once under reduced motion.
 */
@Composable
fun AuroraColorSetting(
    match: Boolean,
    onMatchChange: (Boolean) -> Unit,
    color: Color,
    onColorChange: (Color) -> Unit,
    matchDescription: String,
    modifier: Modifier = Modifier,
    /** 16 dp inside a card (Settings); 0 in a form, whose fields are already inset. */
    horizontalPadding: Dp = 16.dp,
) {
    val tokens = glass
    val reduced = rememberReducedMotion()
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = match, role = Role.Switch, onValueChange = onMatchChange)
                .padding(horizontal = horizontalPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.aurora_match_vehicle), style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary)
                Text(matchDescription, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
            Switch(checked = match, onCheckedChange = null, colors = glassSwitchColors())
        }
        AnimatedVisibility(
            visible = !match,
            enter = (expandVertically(tween(Motion.SHORT_MS)) + fadeIn(tween(Motion.SHORT_MS))).orNone(reduced),
            exit = (shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.QUICK_MS))).orNone(reduced),
        ) {
            GlassColorPicker(color, onColorChange, Modifier.padding(start = horizontalPadding, end = horizontalPadding, bottom = 16.dp))
        }
    }
}

/**
 * A color picker on glass (D-A44): the preset swatches ([AuroraPresets]) and, for any other shade, a hue /
 * saturation wheel drawn on a Canvas with a brightness slider under it. [onColorChange] fires continuously while
 * the wheel or slider is dragged; callers that persist should debounce. Swatches are 48 dp radio targets with a
 * check on the selected one (in [GlassTokens.onPale] on a pale swatch); the wheel offers TalkBack actions to
 * turn the hue and change the saturation, and the slider is the stock accessible one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlassColorPicker(color: Color, onColorChange: (Color) -> Unit, modifier: Modifier = Modifier) {
    val tokens = glass
    var hsv by remember { mutableStateOf(Hsv.of(color)) }
    // Follow a color set from outside (a preset, a reset) without losing the hue of a grey.
    LaunchedEffect(color) { if (hsv.color().toArgb() != color.toArgb()) hsv = Hsv.of(color, keepHue = hsv.h) }
    val update = { next: Hsv ->
        hsv = next
        onColorChange(next.color())
    }
    val names = presetNames()
    val selectedArgb = color.toArgb()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.aurora_presets), style = MaterialTheme.typography.labelLarge, color = tokens.textSecondary)
        FlowRow(Modifier.fillMaxWidth()) {
            AuroraPresets.forEachIndexed { index, argb ->
                Swatch(argb, selected = argb == selectedArgb, label = names[index]) {
                    hsv = Hsv.of(Color(argb))
                    onColorChange(Color(argb))
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.aurora_custom),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.textSecondary,
                modifier = Modifier.weight(1f),
            )
            val hex = "#%06X".format(selectedArgb and 0xFFFFFF)
            Box(
                Modifier
                    .size(20.dp)
                    .background(color, CircleShape)
                    .border(1.dp, tokens.pillBorder, CircleShape),
            )
            Text(
                hex,
                style = MaterialTheme.typography.labelLarge,
                color = tokens.textPrimary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        HueSaturationWheel(hsv, onChange = update)

        Text(stringResource(R.string.aurora_brightness), style = MaterialTheme.typography.labelLarge, color = tokens.textSecondary)
        val brightnessLabel = stringResource(R.string.aurora_brightness)
        Slider(
            value = hsv.v,
            onValueChange = { update(hsv.copy(v = it)) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = tokens.sheen,
                activeTrackColor = Hsv(hsv.h, hsv.s, 1f).color(),
                inactiveTrackColor = tokens.pill,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = brightnessLabel },
        )
        Text(stringResource(R.string.aurora_softened), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
    }
}

@Composable
private fun Swatch(argb: Int, selected: Boolean, label: String, onClick: () -> Unit) {
    val tokens = glass
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressFeedback(interaction)
    val swatch = Color(argb)
    Box(
        Modifier
            .size(48.dp)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .pressScale(press),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(swatch, CircleShape)
                .border(if (selected) 2.dp else 1.dp, if (selected) tokens.sheen else tokens.pillBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (AuroraTintResolver.isPale(argb)) tokens.onPale else tokens.onPhoto,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun HueSaturationWheel(hsv: Hsv, onChange: (Hsv) -> Unit) {
    val tokens = glass
    val wheelLabel = stringResource(R.string.aurora_wheel)
    val state = stringResource(R.string.aurora_wheel_state, hsv.h.roundToInt(), (hsv.s * 100).roundToInt())
    val hueUp = stringResource(R.string.aurora_hue_forward)
    val hueDown = stringResource(R.string.aurora_hue_back)
    val satUp = stringResource(R.string.aurora_more_saturated)
    val satDown = stringResource(R.string.aurora_less_saturated)
    // Read inside the gesture loop, which is keyed once: always the latest value and callback.
    val latest by rememberUpdatedState(hsv)
    val latestChange by rememberUpdatedState(onChange)

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, 240.dp)
        Canvas(
            Modifier
                .size(side)
                .clearAndSetSemantics {
                    contentDescription = wheelLabel
                    stateDescription = state
                    customActions = listOf(
                        CustomAccessibilityAction(hueUp) { onChange(hsv.copy(h = (hsv.h + 15f) % 360f)); true },
                        CustomAccessibilityAction(hueDown) { onChange(hsv.copy(h = (hsv.h + 345f) % 360f)); true },
                        CustomAccessibilityAction(satUp) { onChange(hsv.copy(s = (hsv.s + 0.1f).coerceAtMost(1f))); true },
                        CustomAccessibilityAction(satDown) { onChange(hsv.copy(s = (hsv.s - 0.1f).coerceAtLeast(0f))); true },
                    )
                }
                .pointerInput(Unit) {
                    fun pick(at: Offset) {
                        val r = size.width / 2f
                        val dx = at.x - r
                        val dy = at.y - r
                        val hue = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()) + 360f) % 360f
                        val sat = (hypot(dx, dy) / r).coerceIn(0f, 1f)
                        latestChange(latest.copy(h = hue, s = sat))
                    }
                    awaitEachGesture {
                        // Consumed from the first touch, so a list around the wheel doesn't scroll while picking.
                        val down = awaitFirstDown()
                        down.consume()
                        pick(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            pick(change.position)
                        }
                    }
                },
        ) {
            val r = size.minDimension / 2f
            val hues = (0..6).map { Color.hsv((it * 60f) % 360f, 1f, 1f) }
            drawCircle(Brush.sweepGradient(hues, center), r)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r)
            // Brightness shows on the wheel as a dark veil, so the wheel previews what the slider does.
            drawCircle(Color.Black.copy(alpha = 1f - hsv.v), r)
            drawCircle(tokens.pillBorder, r, style = Stroke(1.dp.toPx()))

            val angle = Math.toRadians(hsv.h.toDouble())
            val thumb = Offset(center.x + (cos(angle) * hsv.s * r).toFloat(), center.y + (sin(angle) * hsv.s * r).toFloat())
            val thumbR = 12.dp.toPx()
            drawCircle(hsv.color(), thumbR, thumb)
            drawCircle(Color.White, thumbR, thumb, style = Stroke(3.dp.toPx()))
            drawCircle(tokens.onPale, thumbR + 1.5.dp.toPx(), thumb, style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun presetNames(): List<String> = listOf(
    stringResource(R.string.aurora_preset_red),
    stringResource(R.string.aurora_preset_orange),
    stringResource(R.string.aurora_preset_gold),
    stringResource(R.string.aurora_preset_yellow),
    stringResource(R.string.aurora_preset_green),
    stringResource(R.string.aurora_preset_teal),
    stringResource(R.string.aurora_preset_sky),
    stringResource(R.string.aurora_preset_blue),
    stringResource(R.string.aurora_preset_navy),
    stringResource(R.string.aurora_preset_purple),
    stringResource(R.string.aurora_preset_magenta),
    stringResource(R.string.aurora_preset_steel),
)

/** Hue in degrees, saturation and value in 0..1. */
private data class Hsv(val h: Float, val s: Float, val v: Float) {
    fun color(): Color = Color.hsv(h.coerceIn(0f, 359.99f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))

    companion object {
        fun of(c: Color, keepHue: Float = 0f): Hsv {
            val max = maxOf(c.red, c.green, c.blue)
            val min = minOf(c.red, c.green, c.blue)
            val d = max - min
            if (d < 1e-4f) return Hsv(keepHue, 0f, max)
            val h = when (max) {
                c.red -> 60f * (((c.green - c.blue) / d) % 6f)
                c.green -> 60f * (((c.blue - c.red) / d) + 2f)
                else -> 60f * (((c.red - c.green) / d) + 4f)
            }
            return Hsv((h + 360f) % 360f, if (max == 0f) 0f else d / max, max)
        }
    }
}
