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
    title = { Text(title) },
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
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = if (scrollBehavior != null) modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else modifier,
        containerColor = if (transparent) Color.Transparent else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = title,
                modifier = topBarModifier,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = { actions() },
                scrollBehavior = scrollBehavior,
                colors = if (transparent) {
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
 * glass sheen (D-A41). Data screens use [GroupHeader] instead, which is larger and can carry a "See all" action.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.onAurora(glass),
        color = glass.sheen,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}
