package io.github.conchad.smartgarage.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.BuildConfig
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.data.DisplayPreferences
import io.github.conchad.smartgarage.core.data.ThemeMode
import io.github.conchad.smartgarage.feature.dashboard.Avatar
import io.github.conchad.smartgarage.feature.more.NavRow
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.SectionLabel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageProfiles: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val profile = state.profile ?: return
    val account = profile.account

    AppScaffold(title = stringResource(R.string.nav_settings), onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item { SectionLabel(stringResource(R.string.settings_account)) }
            item {
                GroupCard {
                    if (account != null) {
                        ListItem(
                            headlineContent = { Text(account.greetingName, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = {
                                Text(stringResource(R.string.settings_signed_in_as, account.username, profile.hostLabel))
                            },
                            leadingContent = { Avatar(account.greetingName, size = 48) },
                            colors = transparentRow(),
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    NavRow(
                        icon = Icons.Outlined.Dns,
                        title = stringResource(R.string.settings_servers),
                        subtitle = pluralStringResource(R.plurals.settings_servers_count, state.profileCount, state.profileCount),
                        onClick = onManageProfiles,
                    )
                    if (profile.isCleartext) {
                        val enabled = profile.backgroundSyncOnCleartext
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_cleartext_background_sync)) },
                            supportingContent = { Text(stringResource(R.string.settings_cleartext_background_sync_desc)) },
                            leadingContent = { IconBadge(Icons.Outlined.Sync) },
                            trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
                            colors = transparentRow(),
                            modifier = Modifier.toggleable(
                                value = enabled,
                                role = Role.Switch,
                                onValueChange = viewModel::setBackgroundSyncOnCleartext,
                            ),
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_sign_out)) },
                        supportingContent = account?.let { { Text(stringResource(R.string.settings_device_name, it.deviceName)) } },
                        leadingContent = {
                            IconBadge(
                                Icons.AutoMirrored.Outlined.Logout,
                                container = MaterialTheme.colorScheme.errorContainer,
                                content = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        },
                        trailingContent = if (state.signingOut) {
                            { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                        } else {
                            null
                        },
                        colors = transparentRow(),
                        modifier = Modifier.clickable(enabled = !state.signingOut) { confirmSignOut = true },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.settings_units)) }
            item { UnitsCard(profile.preferences) }

            item { SectionLabel(stringResource(R.string.settings_fuel)) }
            item {
                GroupCard {
                    val track = state.fuel.trackFuelEconomy
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_track_fuel_economy)) },
                        supportingContent = { Text(stringResource(R.string.settings_track_fuel_economy_desc)) },
                        leadingContent = { IconBadge(Icons.Outlined.LocalGasStation) },
                        trailingContent = { Switch(checked = track, onCheckedChange = null) },
                        colors = transparentRow(),
                        modifier = Modifier.toggleable(value = track, role = Role.Switch, onValueChange = viewModel::setTrackFuelEconomy),
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.settings_appearance)) }
            item {
                GroupCard {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_theme)) },
                        leadingContent = { IconBadge(Icons.Outlined.Palette) },
                        colors = transparentRow(),
                    )
                    ThemeModeSelector(
                        selected = state.appearance.themeMode,
                        onSelect = viewModel::setThemeMode,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) },
                        supportingContent = { Text(stringResource(R.string.settings_dynamic_color_desc)) },
                        trailingContent = {
                            Switch(checked = state.appearance.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
                        },
                        colors = transparentRow(),
                        modifier = Modifier.clickable { viewModel.setDynamicColor(!state.appearance.dynamicColor) },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.settings_about)) }
            item {
                GroupCard {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_app_version)) },
                        supportingContent = { Text(BuildConfig.VERSION_NAME) },
                        leadingContent = { IconBadge(Icons.Outlined.Info) },
                        colors = transparentRow(),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_server_version)) },
                        supportingContent = {
                            Text(profile.serverVersion ?: stringResource(R.string.settings_unknown))
                        },
                        leadingContent = { IconBadge(Icons.Outlined.Dns) },
                        colors = transparentRow(),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out_confirm_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_confirm_body, profile.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun UnitsCard(preferences: DisplayPreferences?) {
    GroupCard {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_units_title)) },
            supportingContent = { Text(stringResource(R.string.settings_units_desc)) },
            leadingContent = { IconBadge(Icons.Outlined.Straighten) },
            colors = transparentRow(),
        )
        val unknown = stringResource(R.string.settings_unknown)
        Column(
            Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            UnitLine(R.string.settings_unit_distance, distanceLabel(preferences?.distanceUnit) ?: unknown)
            UnitLine(R.string.settings_unit_volume, volumeLabel(preferences?.volumeUnit) ?: unknown)
            UnitLine(R.string.settings_unit_currency, preferences?.currency ?: unknown)
            UnitLine(R.string.settings_unit_timezone, preferences?.timezone ?: unknown)
        }
    }
}

@Composable
private fun UnitLine(label: Int, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun distanceLabel(unit: String?): String? = when (unit) {
    "mi" -> stringResource(R.string.unit_miles)
    "km" -> stringResource(R.string.unit_kilometers)
    else -> null
}

@Composable
private fun volumeLabel(unit: String?): String? = when (unit) {
    "gal" -> stringResource(R.string.unit_gallons)
    "l" -> stringResource(R.string.unit_liters)
    else -> null
}

@Composable
private fun ThemeModeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    val labels = mapOf(
        ThemeMode.System to R.string.settings_theme_system,
        ThemeMode.Light to R.string.settings_theme_light,
        ThemeMode.Dark to R.string.settings_theme_dark,
    )
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
            ) { Text(stringResource(labels.getValue(mode))) }
        }
    }
}

@Composable
private fun transparentRow() = ListItemDefaults.colors(containerColor = Color.Transparent)
