package io.github.conchad.smartgarage.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
 */
@Composable
fun GlassGroupHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onGlassBar: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val end: @Composable () -> Unit = {
        if (trailing != null) {
            // D-A75: the shadow is the header's own text only; a trailing pill keeps its halo.
            CompositionLocalProvider(LocalAuroraInkShadow provides false) { trailing() }
        } else if (onAction != null) {
            TextButton(onClick = onAction, colors = glassInkTextButtonColors()) {
                Text(action ?: stringResource(R.string.action_see_all), Modifier.auroraInk())
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
    // D-A75: the title and its text action get a soft drop shadow instead of the halo.
    CompositionLocalProvider(LocalAuroraInkGroup provides inkGroup, LocalAuroraInkShadow provides true) {
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
