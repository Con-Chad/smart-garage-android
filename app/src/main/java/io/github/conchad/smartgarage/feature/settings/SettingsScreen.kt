package io.github.conchad.smartgarage.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.ImeAction
import io.github.conchad.smartgarage.core.data.AuroraChoice
import io.github.conchad.smartgarage.core.data.withLocalName
import io.github.conchad.smartgarage.ui.components.forms.FormTextField
import io.github.conchad.smartgarage.ui.glass.AuroraColorSetting
import io.github.conchad.smartgarage.ui.glass.AuroraPresets
import kotlinx.coroutines.delay
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.BuildConfig
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.glass.GlassAlertDialog
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.glassListItemColors
import io.github.conchad.smartgarage.ui.glass.glassSegmentedColors
import io.github.conchad.smartgarage.ui.glass.glassSwitchColors
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.core.data.DisplayPreferences
import io.github.conchad.smartgarage.core.data.PushProblem
import io.github.conchad.smartgarage.core.data.ServerProfile
import io.github.conchad.smartgarage.core.data.ThemeMode
import io.github.conchad.smartgarage.core.push.PushTestOutcome
import io.github.conchad.smartgarage.feature.dashboard.Avatar
import io.github.conchad.smartgarage.feature.more.NavRow
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.SectionLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageProfiles: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val profile = state.profile ?: return
    // Shown with the name set below (D-A44), as Home greets with it.
    val account = profile.account?.withLocalName(profile.localName)
    val snackbar = remember { SnackbarHostState() }
    PushTestSnackbar(state.push.testOutcome, snackbar, viewModel::pushTestShown)

    AppScaffold(
        title = stringResource(R.string.nav_settings),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Below the bar, not under it: the bar's glass is see-through, so rows scrolled under it showed through the
        // title (D-A41). The bottom padding stays content padding, so the last row still clears the tray.
        LazyColumn(
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
        ) {
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
                        LocalNameField(
                            saved = profile.localName,
                            fallback = profile.account?.greetingName ?: account.greetingName,
                            onSave = viewModel::setLocalName,
                        )
                        RowDivider()
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
                            trailingContent = { Switch(checked = enabled, onCheckedChange = null, colors = glassSwitchColors()) },
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
                            IconBadge(Icons.AutoMirrored.Outlined.Logout, tint = MaterialTheme.colorScheme.error)
                        },
                        trailingContent = if (state.signingOut) {
                            { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = glass.sheen) }
                        } else {
                            null
                        },
                        colors = transparentRow(),
                        modifier = Modifier.clickable(enabled = !state.signingOut) { confirmSignOut = true },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.settings_notifications)) }
            item { PushCard(profile, state.push, viewModel) }

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
                        trailingContent = { Switch(checked = track, onCheckedChange = null, colors = glassSwitchColors()) },
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
                            Switch(checked = state.appearance.dynamicColor, onCheckedChange = viewModel::setDynamicColor, colors = glassSwitchColors())
                        },
                        colors = transparentRow(),
                        modifier = Modifier.clickable { viewModel.setDynamicColor(!state.appearance.dynamicColor) },
                    )
                    RowDivider()
                    AuroraSettingRows(state.aurora, viewModel::setAurora)
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
        GlassAlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = stringResource(R.string.settings_sign_out_confirm_title),
            text = { Text(stringResource(R.string.settings_sign_out_confirm_body, profile.name)) },
            confirmButton = {
                TextButton(colors = glassTextButtonColors(), onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }, colors = glassTextButtonColors()) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * The push rows (D-A29): the switch, what the registration is doing, a test button and a plain explanation. The
 * notification permission (API 33+) is asked for here, when the user turns the switch on, never at launch.
 */
@Composable
private fun PushCard(profile: ServerProfile, push: PushUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val settings = profile.push
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted or not, go ahead: a denied permission is shown as "blocked" under the switch.
        viewModel.notificationPermissionChanged()
        viewModel.setPushEnabled(true)
    }
    val turnOn = {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.setPushEnabled(true)
    }

    GroupCard {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_push)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pushStatus(profile, push))
                    if (settings.enabled && push.notificationsBlocked) {
                        Text(stringResource(R.string.settings_push_blocked), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            leadingContent = { IconBadge(Icons.Outlined.NotificationsActive) },
            trailingContent = {
                if (push.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = glass.sheen)
                } else {
                    Switch(checked = settings.enabled, onCheckedChange = null, enabled = profile.pushAllowed, colors = glassSwitchColors())
                }
            },
            colors = transparentRow(),
            modifier = Modifier.toggleable(
                value = settings.enabled,
                enabled = profile.pushAllowed && !push.busy,
                role = Role.Switch,
                onValueChange = { on -> if (on) turnOn() else viewModel.setPushEnabled(false) },
            ),
        )
        if (settings.enabled) {
            RowDivider()
            val canTest = settings.registered && !push.sendingTest
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_push_test)) },
                supportingContent = { Text(stringResource(R.string.settings_push_test_desc)) },
                leadingContent = { IconBadge(Icons.Outlined.Send) },
                trailingContent = if (push.sendingTest) {
                    { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = glass.sheen) }
                } else {
                    null
                },
                colors = transparentRow(),
                modifier = Modifier.clickable(enabled = canTest, onClick = viewModel::sendTestPush),
            )
        }
        Text(
            stringResource(R.string.settings_push_about),
            style = MaterialTheme.typography.bodySmall,
            color = glass.textSecondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        )
    }

    val choices = push.choices
    if (choices != null) {
        if (choices.isEmpty()) {
            GlassAlertDialog(
                onDismissRequest = viewModel::dismissPushDistributors,
                title = stringResource(R.string.settings_push_none_title),
                text = { Text(stringResource(R.string.settings_push_none_body)) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissPushDistributors, colors = glassTextButtonColors()) { Text(stringResource(R.string.action_ok)) }
                },
            )
        } else {
            GlassAlertDialog(
                onDismissRequest = viewModel::dismissPushDistributors,
                title = stringResource(R.string.settings_push_pick_title),
                text = {
                    Column {
                        Text(stringResource(R.string.settings_push_pick_body))
                        Spacer(Modifier.height(8.dp))
                        choices.forEach { distributor ->
                            ListItem(
                                headlineContent = { Text(distributor.label) },
                                supportingContent = { Text(distributor.packageName, style = MaterialTheme.typography.bodySmall) },
                                colors = transparentRow(),
                                modifier = Modifier.clickable { viewModel.choosePushDistributor(distributor.packageName) },
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = viewModel::dismissPushDistributors, colors = glassTextButtonColors()) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
    }
}

@Composable
private fun pushStatus(profile: ServerProfile, push: PushUiState): String {
    val settings = profile.push
    return when {
        !profile.pushAllowed -> stringResource(R.string.settings_push_cleartext_blocked)
        !settings.enabled -> stringResource(R.string.settings_push_off_desc)
        settings.problem != null -> when (settings.problem) {
            PushProblem.NoDistributor -> stringResource(R.string.settings_push_problem_no_distributor)
            PushProblem.DistributorFailed -> stringResource(R.string.settings_push_problem_distributor_failed)
            PushProblem.NetworkRequired -> stringResource(R.string.settings_push_problem_network)
            PushProblem.ActionRequired -> stringResource(R.string.settings_push_problem_action_required)
            PushProblem.NoKeys -> stringResource(R.string.settings_push_problem_no_keys)
            PushProblem.EndpointRejected -> stringResource(
                R.string.settings_push_problem_endpoint_rejected,
                settings.problemDetail ?: stringResource(R.string.settings_unknown),
            )
            PushProblem.ServerRejected -> stringResource(R.string.settings_push_problem_server_rejected)
            PushProblem.Unreachable -> stringResource(R.string.settings_push_problem_unreachable)
            PushProblem.Unregistered -> stringResource(R.string.settings_push_problem_unregistered)
        }
        settings.registeredAt == null -> stringResource(R.string.settings_push_registering)
        else -> {
            val at = runCatching { Instant.parse(settings.registeredAt) }.getOrNull()
            val formatter = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()) }
            stringResource(
                R.string.settings_push_registered,
                push.distributorLabel ?: "UnifiedPush",
                at?.let(formatter::format) ?: stringResource(R.string.settings_unknown),
            )
        }
    }
}

@Composable
private fun PushTestSnackbar(outcome: PushTestOutcome?, snackbar: SnackbarHostState, onShown: () -> Unit) {
    val message = when (outcome) {
        null -> null
        PushTestOutcome.Sent -> stringResource(R.string.settings_push_test_sent)
        PushTestOutcome.NotRegistered -> stringResource(R.string.settings_push_test_not_registered)
        is PushTestOutcome.RateLimited -> outcome.retryAfterSeconds?.toInt()
            ?.let { pluralStringResource(R.plurals.settings_push_test_rate_limited, it, it) }
            ?: stringResource(R.string.settings_push_test_rate_limited_short)
        PushTestOutcome.SubscriptionGone -> stringResource(R.string.settings_push_test_gone)
        is PushTestOutcome.Failed -> outcome.message?.let { stringResource(R.string.settings_push_test_failed, it) }
            ?: stringResource(R.string.settings_push_test_failed_generic)
        PushTestOutcome.Unreachable -> stringResource(R.string.settings_push_test_unreachable)
    }
    LaunchedEffect(outcome) {
        if (message != null) {
            onShown()
            snackbar.showSnackbar(message)
        }
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
            color = glass.textSecondary,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
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
                colors = glassSegmentedColors(),
            ) { Text(stringResource(labels.getValue(mode))) }
        }
    }
}

/**
 * The app-wide aurora color (D-A44). The switch and presets save at once; a wheel or slider drag is held locally
 * and saved once it rests for [AURORA_SAVE_DELAY_MS], so a drag doesn't write the preferences every frame.
 */
@Composable
private fun AuroraSettingRows(saved: AuroraChoice, onSave: (AuroraChoice) -> Unit) {
    var draft by remember { mutableStateOf<AuroraChoice?>(null) }
    val shown = draft ?: saved
    // The color the picker opens on when the switch goes off: the last custom one, else the first preset.
    var lastColor by rememberSaveable { mutableStateOf((saved as? AuroraChoice.Custom)?.argb ?: AuroraPresets.first()) }
    LaunchedEffect(draft) {
        val pending = draft ?: return@LaunchedEffect
        delay(AURORA_SAVE_DELAY_MS)
        onSave(pending)
    }
    LaunchedEffect(saved) { if (saved == draft) draft = null }
    ListItem(
        headlineContent = { Text(stringResource(R.string.aurora_title)) },
        leadingContent = { IconBadge(Icons.Outlined.Gradient) },
        colors = transparentRow(),
    )
    AuroraColorSetting(
        match = shown == AuroraChoice.MatchVehicle,
        onMatchChange = { on ->
            draft = if (on) AuroraChoice.MatchVehicle else AuroraChoice.Custom(lastColor)
        },
        color = Color((shown as? AuroraChoice.Custom)?.argb ?: lastColor),
        onColorChange = { c ->
            lastColor = c.toArgb()
            draft = AuroraChoice.Custom(c.toArgb())
        },
        matchDescription = stringResource(
            if (shown == AuroraChoice.MatchVehicle) R.string.settings_aurora_match_desc else R.string.settings_aurora_custom_desc,
        ),
    )
}

private const val AURORA_SAVE_DELAY_MS = 250L

/**
 * The greeting name on this phone (D-A44): Home says "Good morning, <name>" with it instead of the account's
 * name. Saved shortly after typing stops, and on Done; empty falls back to [fallback].
 */
@Composable
private fun LocalNameField(saved: String?, fallback: String, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(saved.orEmpty()) }
    LaunchedEffect(text) {
        if (text.trim() == saved.orEmpty()) return@LaunchedEffect
        delay(600)
        onSave(text)
    }
    FormTextField(
        value = text,
        onValueChange = { if (it.length <= 60) text = it },
        label = stringResource(R.string.settings_your_name),
        supporting = stringResource(R.string.settings_your_name_desc, fallback),
        capitalizeWords = true,
        imeAction = ImeAction.Done,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .onFocusChanged { if (!it.isFocused && text.trim() != saved.orEmpty()) onSave(text) },
    )
}

@Composable
private fun transparentRow() = glassListItemColors()
