package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.RowDivider

/**
 * A [GlassCard] that opens and closes under a bolder metallic header (D-A26): the whole header row is the tap
 * target (≥ 56 dp, so it stays comfortable at font scale 1.3 with two lines), the chevron turns, and the card's
 * body grows and shrinks inside the glass ([drawerExpand] / [drawerCollapse], D-A55) so the card's height and
 * everything laid out below it glide both ways. TalkBack reads the header as a button with its state
 * ("Registration, collapsed, button"). Under reduced motion the chevron and the body snap.
 *
 * [content] is composed while [expanded] and while it closes; the caller keeps the state so it can be saved
 * across rotation.
 */
@Composable
fun GlassDropdown(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = glass
    val reduced = rememberReducedMotion()
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        animationSpec = Motion.standardSpring<Float>().orSnap(reduced),
        label = "chevron",
    )
    val stateText = stringResource(if (expanded) R.string.form_expanded else R.string.form_collapsed)
    GlassCard(modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle, role = Role.Button)
                .semantics(mergeDescendants = true) { stateDescription = stateText }
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetallicText(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                animated = true,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = tokens.sheen,
                modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotation },
            )
        }
        // D-A55: the body animates inside the card (it used to be the card's animateContentSize, outside the glass:
        // closing, the content went at once and the glass snapped shut while only the space below it shrank).
        AnimatedVisibility(
            expanded,
            enter = drawerExpand().orNone(reduced),
            exit = drawerCollapse().orNone(reduced),
        ) {
            Column {
                RowDivider()
                Column(content = content)
            }
        }
    }
}
