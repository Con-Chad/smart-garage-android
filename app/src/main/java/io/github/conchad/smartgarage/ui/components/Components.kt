package io.github.conchad.smartgarage.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.glass.GlassButton
import io.github.conchad.smartgarage.ui.glass.GlassIconBadge
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.onAurora
import io.github.conchad.smartgarage.ui.glass.AdaptiveInk
import io.github.conchad.smartgarage.ui.glass.AuroraInkSpec
import io.github.conchad.smartgarage.ui.glass.LocalAuroraInk
import io.github.conchad.smartgarage.ui.glass.auroraInk
import io.github.conchad.smartgarage.ui.glass.onAuroraInk
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import io.github.conchad.smartgarage.ui.glass.Motion
import io.github.conchad.smartgarage.ui.glass.orSnap
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion

/**
 * The height of the shell's floating bottom bar (navigation bar included) that a screen draws under, or 0 where the
 * bar is hidden. Provided by MainShell (D-A40); [AppScaffold] adds it to its content padding and FAB placement.
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

/**
 * Standard screen frame. Tab screens draw full height, under the shell's glass bottom bar (D-A40): the bar's height
 * ([LocalBottomBarInset]) is part of the bottom content padding, which a scrolling list takes as contentPadding so
 * its last item still clears the bar, and the FAB sits above it. Pushed screens (no bar) get the navigation-bar
 * inset from the default Scaffold insets. Transparent by
 * default since D-A30: the shell's aurora runs behind every screen, so the scaffold and the bar draw no surface of
 * their own (the bar tints with the glass panel color once content scrolls under it).
 */
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = TopAppBarDefaults.pinnedScrollBehavior(),
    transparent: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) = AppScaffold(
    title = { Text(title, Modifier.onAuroraInk()) },
    modifier = modifier,
    onBack = onBack,
    actions = actions,
    floatingActionButton = floatingActionButton,
    snackbarHost = snackbarHost,
    scrollBehavior = scrollBehavior,
    transparent = transparent,
    content = content,
)

/**
 * [AppScaffold] with a custom title slot, for the Dashboard's two-line greeting. Same bar, insets and scroll tint.
 * [transparent] drops the scaffold's and the bar's own surface so the shell's aurora shows through (glass screens,
 * D-A24); the bar then tints with the glass panel color once the content scrolls under it.
 */
@Composable
fun AppScaffold(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = TopAppBarDefaults.pinnedScrollBehavior(),
    transparent: Boolean = true,
    /** On the top bar alone, e.g. the vehicle page fading its bar while its cards pop out (D-A43). */
    topBarModifier: Modifier = Modifier,
    /**
     * A transparent bar's scrolled glass without its hard bottom edge (D-A49, the vehicle page): the panel fills the
     * bar and feathers out over [GlassTokens.barFeather] below it instead of ending in a line.
     */
    softBarEdge: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    // D-A46: a transparent bar's title sits on the aurora, so it takes the adaptive ink; once content scrolls under
    // the bar its glass panel lies between, which the ink accounts for.
    val panel = glass.panel
    // D-A48: overlappedFraction moves on every scroll frame; the ink and the soft edge only need to hear when it
    // crosses the threshold, so the derived flag spares them a snapshot notification and a resample per frame.
    val overlapped = remember(scrollBehavior) { derivedStateOf { (scrollBehavior?.state?.overlappedFraction ?: 0f) > 0.01f } }
    val barInk = remember(overlapped, panel) { AuroraInkSpec { if (overlapped.value) panel else Color.Transparent } }
    val soft = transparent && softBarEdge
    // The soft edge draws the panel itself, fading it in and out as the bar's own scrolled colour would.
    val softAlpha = animateFloatAsState(if (soft && overlapped.value) 1f else 0f, tween<Float>(Motion.SHORT_MS).orSnap(rememberReducedMotion()), label = "barGlass")
    val feather = glass.barFeather
    Scaffold(
        modifier = if (scrollBehavior != null) modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else modifier,
        containerColor = if (transparent) Color.Transparent else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { CompositionLocalProvider(LocalAuroraInk provides if (transparent) barInk else null, content = title) },
                modifier = if (soft) topBarModifier.softBarGlass(panel, feather) { softAlpha.value } else topBarModifier,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = { actions() },
                scrollBehavior = scrollBehavior,
                colors = if (soft) {
                    TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
                } else if (transparent) {
                    TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = glass.panel)
                } else {
                    TopAppBarDefaults.topAppBarColors()
                },
            )
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
        // The bar's height already includes the navigation bar, so the larger of the two is the bottom inset.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets(bottom = LocalBottomBarInset.current)),
        content = content,
    )
}

/**
 * The bar's glass [panel] over its whole height, then feathering to nothing over [feather] below it (D-A49): no hard
 * bottom line. Eased stops, so the fade has no visible start either. [alpha] is read in the draw phase only, so its
 * animation repaints without recomposing; the brush is rebuilt only when the size changes.
 */
private fun Modifier.softBarGlass(panel: Color, feather: Dp, alpha: () -> Float): Modifier = drawWithCache {
    val f = feather.toPx()
    val total = size.height + f
    val edge = size.height / total
    val brush = Brush.verticalGradient(
        0f to panel,
        edge to panel,
        edge + (1f - edge) * 0.25f to panel.copy(alpha = panel.alpha * 0.80f),
        edge + (1f - edge) * 0.50f to panel.copy(alpha = panel.alpha * 0.45f),
        edge + (1f - edge) * 0.75f to panel.copy(alpha = panel.alpha * 0.15f),
        1f to panel.copy(alpha = 0f),
        startY = 0f,
        endY = total,
    )
    onDrawBehind {
        val a = alpha()
        if (a > 0f) drawRect(brush, size = Size(size.width, total), alpha = a)
    }
}

/**
 * Fades a scrolling list's top [length] out instead of cutting it at its bounds (D-A49, the vehicle page under its
 * soft-edged bar), so no straight edge of clipped cards shows where the bar's glass feathers out. [strength] (0..1,
 * read in the draw and layer phases only) ramps it in once content has scrolled; at 0 the list draws as before, with
 * no offscreen layer.
 */
fun Modifier.fadingTopEdge(length: Dp, strength: () -> Float): Modifier = this
    .graphicsLayer { compositingStrategy = if (strength() > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
    .drawWithCache {
        val l = length.toPx()
        // Erases (DstOut) fully at the top and not at all at [length], on eased stops.
        val mask = Brush.verticalGradient(
            0f to Color.Black,
            0.25f to Color.Black.copy(alpha = 0.80f),
            0.50f to Color.Black.copy(alpha = 0.45f),
            0.75f to Color.Black.copy(alpha = 0.15f),
            1f to Color.Transparent,
            startY = 0f,
            endY = l,
        )
        onDrawWithContent {
            drawContent()
            val a = strength()
            if (a > 0f) drawRect(mask, size = Size(size.width, l), alpha = a, blendMode = BlendMode.DstOut)
        }
    }

/**
 * Icon in a glass circle, one line saying what is missing, an optional explanation and the action that fills it
 * (D-A41: the list screens pass just the line and a [GlassPillButton]). Centered in the space it is given but
 * scrollable, so it still reaches the top at large font sizes and still answers pull-to-refresh. On the aurora,
 * not on a panel: the text carries the glass halo (D-A30).
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val tokens = glass
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .then(if (viewport.isFinite) Modifier.heightIn(min = viewport) else Modifier)
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.widthIn(max = 360.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                ) {
                    GlassIconBadge(icon = icon, size = 72.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.onAurora(tokens),
                        color = tokens.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    if (body != null) {
                        Text(
                            body,
                            style = MaterialTheme.typography.bodyMedium.onAurora(tokens),
                            color = tokens.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (action != null) {
                    Spacer(Modifier.height(4.dp))
                    action()
                }
            }
        }
    }
}

/**
 * An empty state's action (D-A41): the solid [GlassButton] (sheen fill, dark ink), which stays readable over any
 * aurora where a translucent pill would not. Only where no FAB already offers the verb (one "Add" per screen, D-A20).
 */
@Composable
fun EmptyAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    GlassButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp), maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** An icon in a small glass circle, the sheen by default ([GlassIconBadge], D-A41; a tonal disc before). */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Int = 40, tint: Color = glass.sheen) =
    GlassIconBadge(icon, modifier, size.dp, tint)

/**
 * Small label above a group of settings-style rows (More, Settings, Profiles), as in system Settings, in the
 * adaptive ink over the aurora (D-A46; the glass sheen with a halo before). Data screens use [GroupHeader] instead, which is larger and can carry a "See all" action.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = glass.sheen,
        // D-A46: a section title on the aurora, in the adaptive ink (which replaces the halo it carried, D-A30).
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).auroraInk(),
    )
}
