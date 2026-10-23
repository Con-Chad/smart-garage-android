package io.github.conchad.smartgarage.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R

/**
 * `GroupHeader` for glass screens: the same paddings and optional text action, with an animated metallic title
 * (the sweep tier, D-A24). [trailing] puts something else at the end instead (Home's "Swipe to view more" pill, D-A43).
 */
@Composable
fun GlassGroupHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
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
        )
        if (trailing != null) {
            trailing()
        } else if (onAction != null) {
            TextButton(onClick = onAction, colors = glassInkTextButtonColors()) {
                Text(action ?: stringResource(R.string.action_see_all))
            }
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
