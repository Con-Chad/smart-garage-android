package io.github.conchad.smartgarage.feature.settings.licenses

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.SectionLabel
import io.github.conchad.smartgarage.ui.glass.GlassOutlinedButton
import io.github.conchad.smartgarage.ui.glass.drawerCollapse
import io.github.conchad.smartgarage.ui.glass.drawerExpand
import io.github.conchad.smartgarage.ui.glass.glassListItemColors
import io.github.conchad.smartgarage.ui.glass.orNone
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.conchad.smartgarage.ui.glass.topFadeEdge
import io.github.conchad.smartgarage.ui.glass.glass

/** Loading state of the bundled notices: still reading, read, or the asset could not be read. */
private sealed interface NoticesState {
    data object Loading : NoticesState
    data class Loaded(val notices: OpenSourceNotices) : NoticesState
    data object Failed : NoticesState
}

/**
 * Settings → About → Open-source licenses (D-A65): every component from the bundled OPEN_SOURCE_NOTICES.md, by
 * group, as rows that drop down to their copyright, artifacts, a source link and the full licence text.
 */
@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by produceState<NoticesState>(NoticesState.Loading, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(OpenSourceNoticesParser.ASSET).bufferedReader().use { it.readText() }
            }.map { NoticesState.Loaded(OpenSourceNoticesParser.parse(it)) }.getOrDefault(NoticesState.Failed)
        }
    }

    AppScaffold(title = stringResource(R.string.licenses_title), onBack = onBack) { padding ->
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(top = padding.calculateTopPadding()).topFadeEdge(listState, glass.contentFadeLift),
            contentPadding = PaddingValues(top = glass.contentFadeLift, bottom = padding.calculateBottomPadding()),
        ) {
            when (val s = state) {
                NoticesState.Loading -> Unit
                NoticesState.Failed -> item {
                    Text(
                        stringResource(R.string.licenses_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                is NoticesState.Loaded -> {
                    item {
                        GroupCard(Modifier.padding(top = 8.dp)) {
                            Text(
                                stringResource(R.string.licenses_intro),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    s.notices.groups.forEach { (group, components) ->
                        item(key = "label-$group") { SectionLabel(group) }
                        item(key = "group-$group") {
                            GroupCard {
                                components.forEachIndexed { index, component ->
                                    if (index > 0) RowDivider()
                                    ComponentRow(component, s.notices.licenseText(component))
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ComponentRow(component: NoticeComponent, licenseText: String?) {
    var expanded by rememberSaveable(component.group, component.name) { mutableStateOf(false) }
    val reduced = rememberReducedMotion()
    val uriHandler = LocalUriHandler.current
    val expandedLabel = stringResource(if (expanded) R.string.licenses_expanded else R.string.licenses_collapsed)
    Column {
        ListItem(
            headlineContent = { Text(component.name) },
            supportingContent = {
                Text(stringResource(R.string.licenses_row_summary, component.license, component.copyright))
            },
            trailingContent = {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            },
            colors = glassListItemColors(),
            modifier = Modifier
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = expandedLabel },
        )
        AnimatedVisibility(
            expanded,
            enter = drawerExpand().orNone(reduced),
            exit = drawerCollapse().orNone(reduced),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                component.version?.let { Detail(stringResource(R.string.licenses_version), it) }
                component.artifacts?.let { Detail(stringResource(R.string.licenses_artifacts), it) }
                component.note?.let { Detail(stringResource(R.string.licenses_note), it) }
                component.source?.takeIf { it.startsWith("https://") }?.let { url ->
                    GlassOutlinedButton(
                        onClick = { runCatching { uriHandler.openUri(url) } },
                        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.licenses_source), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                // MIT asks for the copyright notice next to the permission text; the shared MIT text has none of its own.
                Text(
                    component.copyright,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    licenseText ?: component.license,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
