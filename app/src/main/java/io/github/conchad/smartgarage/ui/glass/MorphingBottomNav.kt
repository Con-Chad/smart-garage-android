package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** One tab of the [MorphingBottomNav]. */
data class NavTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * The "Morphing Bottom Nav" (D-A24): a floating pill with a soft border and lift shadow, and a glowing gradient
 * blob that springs under the active tab (`spring(0.55, 300)`). The active icon turns [GlassTokens.onAccent] and
 * lifts 2 dp; only it shows its label, the others keep theirs for TalkBack. Respects the navigation-bar inset.
 * Items are equal-width, so the blob's position is `index × item width`; the row's width is read once with
 * `onSizeChanged`, and the blob moves in the layout phase (`offset { }`) rather than recomposing per frame.
 * No idle auto-cycle: the snippet's demo loop is left out on purpose.
 *
 * D-A36: the pill is the cards' liquid glass rather than an opaque block — the same plate as [GlassPanel]
 * (translucent [GlassTokens.navSurface], the aurora-tint plate glow, the inset top highlight and 1 dp top line) and
 * the panel's 1 dp border. The blob carries the same 1 dp highlight edge so it stays distinct over a bright aurora.
 */
@Composable
fun MorphingBottomNav(
    tabs: List<NavTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = glass
    val pillShape = RoundedCornerShape(tokens.radiusLarge)
    val blobShape = RoundedCornerShape(tokens.radiusMedium)
    val reduced = rememberReducedMotion()
    val blobSpec = if (reduced) spring<Float>(stiffness = Spring.StiffnessHigh) else spring(dampingRatio = 0.55f, stiffness = 300f)
    val position by animateFloatAsState(selectedIndex.coerceAtLeast(0).toFloat(), blobSpec, label = "blob")
    var rowWidth by remember { mutableIntStateOf(0) }
    val itemCount = tabs.size.coerceAtLeast(1)
    val auroraGlow = LocalAuroraTint.current.effective ?: MaterialTheme.colorScheme.primary

    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(if (tokens.dark) 14.dp else 8.dp, pillShape, clip = false, ambientColor = tokens.shadow, spotColor = tokens.shadow)
                .clip(pillShape)
                .glassPlate(tokens, glow = auroraGlow, fill = tokens.navSurface)
                .border(1.dp, tokens.navBorder, pillShape)
                .padding(8.dp)
                .height(NAV_HEIGHT)
                .onSizeChanged { rowWidth = it.width },
        ) {
            // The blob: one item wide, full item height, slid along by the animated index.
            if (rowWidth > 0 && selectedIndex >= 0) {
                val itemWidth = rowWidth / itemCount
                Box(
                    Modifier
                        .offset { IntOffset((position * itemWidth).toInt(), 0) }
                        .width(with(LocalDensity.current) { itemWidth.toDp() })
                        .fillMaxHeight()
                        .shadow(10.dp, blobShape, clip = false, ambientColor = tokens.navGlow, spotColor = tokens.navGlow)
                        .clip(blobShape)
                        .drawBehind { drawRect(Brush.linearGradient(listOf(tokens.navBlobStart, tokens.navBlobEnd))) }
                        .border(1.dp, tokens.panelHighlight, blobShape),
                )
            }
            Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    NavItem(tab, selected, onClick = { onSelect(index) }, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun NavItem(tab: NavTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tokens = glass
    val tint by animateColorAsState(if (selected) tokens.onAccent else tokens.navInactive, tween(280), label = "navTint")
    val lift by animateDpAsState(if (selected) (-2).dp else 0.dp, spring(dampingRatio = 0.55f, stiffness = 300f), label = "navLift")
    val label = tab.label
    Box(
        modifier
            .clip(RoundedCornerShape(tokens.radiusMedium))
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                indication = ripple(bounded = true, color = tokens.onAccent),
                interactionSource = null,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset { IntOffset(0, lift.roundToPx()) },
        ) {
            Icon(
                if (selected) tab.selectedIcon else tab.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            AnimatedVisibility(visible = selected, enter = fadeIn(tween(200)), exit = fadeOut(tween(120))) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.onAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The tab already carries the label as its description; don't have TalkBack say it twice.
                    modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {},
                )
            }
        }
    }
}

/** Item height: the snippet's 44 px, raised to the 48 dp touch-target minimum. */
private val NAV_HEIGHT = 48.dp
