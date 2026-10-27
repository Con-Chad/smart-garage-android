package io.github.conchad.smartgarage.ui.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R

/**
 * `GroupHeader` for glass screens: the same paddings and optional text action, with an animated metallic title
 * (the sweep tier, D-A24) in the adaptive ink of the aurora behind it (D-A46). [trailing] puts something else at the end instead (Home's "Swipe to view more" pill, D-A43).
 *
 * [onGlassBar] (D-A54, Home's "Your vehicles"): the whole row sits on a full-width rounded glass strip with the
 * cards' margin, fill and rim, and the title is plain [GlassTokens.textPrimary] on it, the cards' text colour, which
 * clears 4.5:1 on the panel whatever the aurora; no adaptive ink, so no halo (D-A52) behind the glyphs.
 *
 * [onStrip] (D-A73, Home's per-vehicle headings): the row sits on a subtle glass strip ([GlassHeadingStrip]) instead of
 * the bare aurora. The title and action keep the adaptive ink (D-A70), aimed at the aurora under the strip's fill,
 * which the shell makes just dense enough for that ink to clear 5.5:1 on every theme colour ([headingStripOver]).
 */
@Composable
fun GlassGroupHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onGlassBar: Boolean = false,
    onStrip: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val stripInk = if (onStrip) rememberHeadingStripInkSpec() else null
    val end: @Composable () -> Unit = {
        if (trailing != null) {
            trailing()
        } else if (onAction != null) {
            TextButton(onClick = onAction, colors = glassInkTextButtonColors()) {
                Text(action ?: stringResource(R.string.action_see_all), Modifier.auroraInk(stripInk ?: AuroraInkSpec.Text))
            }
        }
    }
    if (onGlassBar) {
        GlassPanel(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.titleMedium,
                    color = glass.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() },
                )
                end()
            }
        }
        return
    }
    // D-A70: the title and its action take one ink between them.
    val inkGroup = remember { AuroraInkGroup() }
    if (stripInk != null) {
        CompositionLocalProvider(LocalAuroraInkGroup provides inkGroup) {
            GlassHeadingStrip(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetallicText(
                        text,
                        style = MaterialTheme.typography.titleMedium,
                        animated = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() },
                        ink = stripInk,
                    )
                    end()
                }
            }
        }
        return
    }
    CompositionLocalProvider(LocalAuroraInkGroup provides inkGroup) {
        Row(
            modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetallicText(
                text,
                style = MaterialTheme.typography.titleMedium,
                animated = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                // On the aurora: the adaptive ink under the sheen (D-A46).
                ink = AuroraInkSpec.Text,
            )
            end()
        }
    }
}

/**
 * The subtle glass strip behind a heading that would otherwise sit on the bare aurora (D-A73): a full pill in
 * [GlassTokens.headingStrip] (the panel's tint, densified by the shell where the aurora needs it), the panel's 1 dp
 * border and its 1 dp inset highlight along the top edge. No lift shadow and no top gloss: it reads as a quiet band of
 * the cards' glass rather than another card, and nothing brightens the backdrop under the glyphs.
 */
@Composable
fun GlassHeadingStrip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tokens = glass
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                drawRect(tokens.headingStrip)
                val line = 1.dp.toPx()
                drawLine(tokens.panelHighlight, Offset(size.height / 2f, line / 2f), Offset(size.width - size.height / 2f, line / 2f), strokeWidth = line)
            }
            .border(1.dp, tokens.panelBorder, shape),
    ) { content() }
}

/**
 * Page indicator under a pager: one dot per page, the current one in the sheen color. Announced as a single
 * "Vehicle 2 of 3" rather than N decorative dots.
 */
@Composable
fun PagerDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count < 2) return
    val tokens = glass
    val label = stringResource(R.string.pager_position, current + 1, count)
    Row(
        modifier.semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == current
            Box(
                Modifier
                    .size(if (active) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .drawBehind { drawRect(if (active) tokens.sheen else tokens.textSecondary.copy(alpha = 0.45f)) },
            )
        }
    }
}

/**
 * For plain text that sits directly on the aurora rather than on a panel (section labels, empty states): a soft
 * halo in the page color behind the glyphs, so a bright ribbon under them can't take the contrast away (D-A30).
 */
fun TextStyle.onAurora(tokens: GlassTokens): TextStyle = copy(shadow = Shadow(tokens.textHalo, Offset.Zero, blurRadius = 12f))
