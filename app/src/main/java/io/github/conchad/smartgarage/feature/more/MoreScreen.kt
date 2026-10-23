package io.github.conchad.smartgarage.feature.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.glass.glassListItemColors
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.SectionLabel
import io.github.conchad.smartgarage.ui.navigation.Section

@Composable
fun MoreScreen(onOpenSection: (Section) -> Unit, onOpenSettings: () -> Unit) {
    AppScaffold(title = stringResource(R.string.nav_more)) { padding ->
        // Below the bar, not under it: the bar's glass is see-through, so rows scrolled under it showed through the
        // title (D-A41). The bottom padding stays content padding, so the last row still clears the tray.
        LazyColumn(
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
        ) {
            item { SectionLabel(stringResource(R.string.more_group_garage)) }
            item {
                GroupCard {
                    Section.entries.forEach { section ->
                        NavRow(
                            icon = section.icon,
                            title = stringResource(section.label),
                            subtitle = stringResource(section.description),
                            onClick = { onOpenSection(section) },
                        )
                    }
                }
            }
            item { SectionLabel(stringResource(R.string.more_group_app)) }
            item {
                GroupCard {
                    NavRow(
                        icon = Icons.Outlined.Settings,
                        title = stringResource(R.string.nav_settings),
                        subtitle = stringResource(R.string.more_settings_desc),
                        onClick = onOpenSettings,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Tappable settings-style row with an icon badge and a chevron. */
@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { IconBadge(icon) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = glassListItemColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
