package io.github.conchad.smartgarage.feature.settings.obd

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.obd.ObdPermissionState
import io.github.conchad.smartgarage.core.obd.WicanImportException
import io.github.conchad.smartgarage.core.obd.ble.ScannedAdapter
import io.github.conchad.smartgarage.core.obd.link.ObdAdapter
import io.github.conchad.smartgarage.core.obd.link.ObdTransportKind
import io.github.conchad.smartgarage.core.obd.trip.ObdError
import io.github.conchad.smartgarage.core.obd.trip.ObdStatus
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.Pill
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.SectionLabel
import io.github.conchad.smartgarage.ui.components.forms.ConfirmDialog
import io.github.conchad.smartgarage.ui.glass.GlassAlertDialog
import io.github.conchad.smartgarage.ui.glass.GlassButton
import io.github.conchad.smartgarage.ui.glass.GlassOutlinedButton
import io.github.conchad.smartgarage.ui.glass.auroraInk
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.glassListItemColors
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors
import io.github.conchad.smartgarage.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import java.time.Instant

/** What to do once the permission dialog closes. */
private enum class AfterPermissions { SCAN, CONNECT }

/**
 * Settings → OBD adapter (D-A81): the adapter, the vehicle and odometer profile, "Read now" with the dash check,
 * uploads, readings and diagnostics, one glass card each; the advanced parts (profile details, WiCAN paste, logs,
 * raw console) folded away. Permissions are explained before Android asks for them.
 */
@Composable
fun ObdAdapterScreen(onBack: () -> Unit, viewModel: ObdAdapterViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }

    // ── Permissions: explained first, then asked for (D-A81) ──
    var asked by rememberSaveable { mutableStateOf(false) }
    var explainFor by rememberSaveable { mutableStateOf<AfterPermissions?>(null) }
    var after by rememberSaveable { mutableStateOf<AfterPermissions?>(null) }
    val requester = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        viewModel.refreshPermissions()
        val now = viewModel.permissions.value
        when (after) {
            AfterPermissions.SCAN -> if (now.canScan) viewModel.startScan()
            AfterPermissions.CONNECT -> if (now.canConnect || state.settings.adapter?.transport == ObdTransportKind.SIMULATED) viewModel.connect()
            null -> Unit
        }
        after = null
    }
    fun withPermissions(action: AfterPermissions, run: () -> Unit) {
        if (permissions.missing.isNotEmpty() && !asked) explainFor = action else run()
    }

    // ── One-off events ──
    var direct by remember { mutableStateOf<ObdEvent.OfferDirect?>(null) }
    var forgotBond by remember { mutableStateOf<ObdEvent.Forgot?>(null) }
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    val association = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onAssociationResult(result.resultCode, result.data)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ObdEvent.LaunchAssociation -> association.launch(IntentSenderRequest.Builder(event.sender).build())
                is ObdEvent.OfferDirect -> direct = event
                is ObdEvent.Forgot ->
                    if (event.bluetooth && !event.bondRemoved) forgotBond = event else say(resources.getString(R.string.obd_forgot))
                is ObdEvent.Share -> context.startSafely(event.intent)
                is ObdEvent.ScanFailed -> say(resources.getString(R.string.obd_scan_failed, resources.getString(errorText(event.error))))
                is ObdEvent.ImportFailed -> say(
                    when (event.reason) {
                        WicanImportException.Reason.NOT_JSON -> resources.getString(R.string.obd_wican_not_json, event.detail.orEmpty())
                        WicanImportException.Reason.NO_ODOMETER -> resources.getString(R.string.obd_wican_no_odometer)
                        WicanImportException.Reason.BAD_PROFILE -> resources.getString(R.string.obd_wican_bad, event.detail.orEmpty())
                    },
                )
                ObdEvent.Imported -> say(resources.getString(R.string.obd_wican_imported))
                ObdEvent.CalibrationApplied -> say(resources.getString(R.string.obd_dash_applied))
            }
        }
    }

    AppScaffold(
        title = stringResource(R.string.obd_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding() + 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = glass.sheen)
            }
            return@AppScaffold
        }
        val read by viewModel.read.collectAsStateWithLifecycle()
        val console by viewModel.console.collectAsStateWithLifecycle()
        val sending by viewModel.sending.collectAsStateWithLifecycle()
        val adapter = state.settings.adapter
        LazyColumn(
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.obd_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = glass.sheen,
                    // On the aurora, not a card: the adaptive ink, as the section labels (D-A46).
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).auroraInk(),
                )
            }
            if (needsPermissionCard(permissions, adapter)) {
                item { SectionLabel(stringResource(R.string.obd_perm_title)) }
                item {
                    PermissionCard(
                        permissions = permissions,
                        denied = asked && permissions.missing.isNotEmpty(),
                        // The card is the explanation, so Allow asks straight away.
                        onAllow = {
                            after = null
                            requester.launch(permissions.missing.toTypedArray())
                        },
                        onAppSettings = { context.startSafely(appSettings(context)) },
                        onBluetoothSettings = { context.startSafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    )
                }
            }
            item { SectionLabel(stringResource(R.string.obd_section_adapter)) }
            item {
                AdapterCard(
                    state = state,
                    simulatorAvailable = viewModel.simulatorAvailable,
                    canScan = permissions.bluetoothLe,
                    onScan = { withPermissions(AfterPermissions.SCAN, viewModel::startScan) },
                    onStopScan = viewModel::stopScan,
                    onChoose = viewModel::choose,
                    onSimulator = viewModel::useSimulator,
                    onConnect = { withPermissions(AfterPermissions.CONNECT, viewModel::connect) },
                    onDisconnect = viewModel::disconnect,
                    onForget = { confirmForget = true },
                    simulatorSettings = { SimulatorSettingsBlock(state, viewModel::setSimulatorStart, viewModel::setSimulatorSpeed) },
                )
            }
            item { SectionLabel(stringResource(R.string.obd_section_vehicle)) }
            item {
                VehicleProfileCard(
                    state = state,
                    onVehicle = viewModel::setVehicle,
                    onBuiltIn = viewModel::setBuiltIn,
                    onImport = viewModel::importWican,
                )
            }
            item { SectionLabel(stringResource(R.string.obd_section_read)) }
            item {
                ReadNowCard(
                    state = state,
                    read = read,
                    onRead = viewModel::readNow,
                    onCheckDash = viewModel::checkDash,
                    onDashEdited = viewModel::clearCalibration,
                    onApplyFix = viewModel::applyCalibration,
                )
            }
            item { SectionLabel(stringResource(R.string.obd_section_uploads)) }
            item {
                UploadCard(
                    state = state,
                    onPoll = viewModel::setPollMinutes,
                    onMinDistance = viewModel::setMinUploadDistance,
                    onTripEnd = viewModel::setUploadOnTripEnd,
                    onCellular = viewModel::setCellularUploads,
                )
            }
            item { SectionLabel(stringResource(R.string.obd_section_readings)) }
            item { ReadingsCard(state) }
            item { SectionLabel(stringResource(R.string.obd_section_diagnostics)) }
            item {
                DiagnosticsCard(
                    state = state,
                    console = console,
                    sending = sending,
                    onSend = viewModel::sendRaw,
                    onClearConsole = viewModel::clearConsole,
                    onExport = viewModel::exportDiagnostics,
                )
            }
        }
    }

    explainFor?.let { action ->
        PermissionExplainDialog(
            permissions = permissions,
            onContinue = {
                explainFor = null
                after = action
                requester.launch(permissions.missing.toTypedArray())
            },
            onDismiss = { explainFor = null },
        )
    }
    if (confirmForget) {
        ConfirmDialog(
            title = stringResource(R.string.obd_forget_title),
            body = stringResource(R.string.obd_forget_body, adapterLabel(state.settings.adapter)),
            confirmLabel = stringResource(R.string.obd_forget),
            onConfirm = {
                confirmForget = false
                viewModel.forget()
            },
            onDismiss = { confirmForget = false },
            destructive = true,
        )
    }
    direct?.let { offer ->
        val name = offer.adapter.name ?: offer.adapter.address
        GlassAlertDialog(
            onDismissRequest = { direct = null },
            title = stringResource(R.string.obd_direct_title),
            text = {
                Text(
                    when (offer.why) {
                        DirectReason.UNAVAILABLE -> stringResource(R.string.obd_direct_unavailable, name)
                        DirectReason.DECLINED -> stringResource(R.string.obd_direct_declined, name)
                        DirectReason.FAILED -> stringResource(R.string.obd_direct_failed, name, offer.detail ?: "?")
                    },
                )
            },
            confirmButton = {
                TextButton(colors = glassTextButtonColors(), onClick = {
                    direct = null
                    viewModel.useDirect(offer.adapter)
                }) { Text(stringResource(R.string.obd_direct_use)) }
            },
            dismissButton = {
                TextButton(colors = glassTextButtonColors(), onClick = { direct = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    forgotBond?.let { event ->
        GlassAlertDialog(
            onDismissRequest = { forgotBond = null },
            title = stringResource(R.string.obd_forgot_bond_title),
            text = { Text(stringResource(R.string.obd_forgot_bond_body, event.name ?: stringResource(R.string.obd_unnamed))) },
            confirmButton = {
                TextButton(colors = glassTextButtonColors(), onClick = {
                    forgotBond = null
                    context.startSafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }) { Text(stringResource(R.string.obd_bt_settings)) }
            },
            dismissButton = {
                TextButton(colors = glassTextButtonColors(), onClick = { forgotBond = null }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
}

/** Permissions to ask for, Bluetooth off for a real adapter, or no Bluetooth LE at all. */
private fun needsPermissionCard(permissions: ObdPermissionState, adapter: ObdAdapter?): Boolean {
    val simulated = adapter?.transport == ObdTransportKind.SIMULATED
    return permissions.missing.isNotEmpty() || (!simulated && (!permissions.bluetoothOn || !permissions.bluetoothLe))
}

private fun appSettings(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // A stripped ROM without the settings page: nothing to open.
    }
}

// ── Permissions ──────────────────────────────────────────────────────────────────────────────────────────────

private val NEARBY = setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

@Composable
private fun PermissionCard(
    permissions: ObdPermissionState,
    denied: Boolean,
    onAllow: () -> Unit,
    onAppSettings: () -> Unit,
    onBluetoothSettings: () -> Unit,
) {
    GroupCard {
        if (permissions.missing.isNotEmpty()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.obd_perm_dialog_title)) },
                supportingContent = { Text(stringResource(if (denied) R.string.obd_perm_denied else R.string.obd_perm_missing)) },
                leadingContent = { IconBadge(Icons.Outlined.Security) },
                colors = glassListItemColors(),
            )
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionLines(permissions)
                if (denied) {
                    GlassOutlinedButton(onClick = onAppSettings) { Text(stringResource(R.string.obd_perm_open_settings)) }
                } else {
                    GlassButton(onClick = onAllow) { Text(stringResource(R.string.obd_perm_allow)) }
                }
            }
        }
        if (!permissions.bluetoothLe) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.obd_bt_unavailable)) },
                leadingContent = { IconBadge(Icons.Outlined.BluetoothDisabled, tint = MaterialTheme.colorScheme.error) },
                colors = glassListItemColors(),
            )
        } else if (!permissions.bluetoothOn) {
            if (permissions.missing.isNotEmpty()) RowDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.obd_bt_off)) },
                supportingContent = { Text(stringResource(R.string.obd_bt_off_desc)) },
                leadingContent = { IconBadge(Icons.Outlined.BluetoothDisabled) },
                colors = glassListItemColors(),
            )
            GlassOutlinedButton(onClick = onBluetoothSettings, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Text(stringResource(R.string.obd_bt_settings))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** One line per thing Android will ask for, and what the companion pairing brings (asked for later, by the system). */
@Composable
private fun PermissionLines(permissions: ObdPermissionState) {
    if (permissions.missing.any { it in NEARBY }) {
        PermissionLine(Icons.Outlined.BluetoothSearching, R.string.obd_perm_nearby_title, R.string.obd_perm_nearby_desc)
    }
    if (permissions.missing.any { it !in NEARBY }) {
        PermissionLine(Icons.Outlined.Notifications, R.string.obd_perm_notify_title, R.string.obd_perm_notify_desc)
    }
    if (permissions.companionDevices) {
        PermissionLine(Icons.Outlined.Devices, R.string.obd_perm_companion_title, R.string.obd_perm_companion_desc)
    } else {
        PermissionLine(Icons.Outlined.Devices, R.string.obd_perm_companion_title, R.string.obd_perm_no_companion)
    }
}

@Composable
private fun PermissionLine(icon: ImageVector, title: Int, description: Int) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = glass.sheen, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
        }
    }
}

@Composable
private fun PermissionExplainDialog(permissions: ObdPermissionState, onContinue: () -> Unit, onDismiss: () -> Unit) {
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.obd_perm_dialog_title),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionLines(permissions)
            }
        },
        confirmButton = {
            TextButton(colors = glassTextButtonColors(), onClick = onContinue) { Text(stringResource(R.string.obd_perm_continue)) }
        },
        dismissButton = {
            TextButton(colors = glassTextButtonColors(), onClick = onDismiss) { Text(stringResource(R.string.obd_perm_not_now)) }
        },
    )
}

// ── Adapter ──────────────────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdapterCard(
    state: ObdUiState,
    simulatorAvailable: Boolean,
    canScan: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onChoose: (ScannedAdapter) -> Unit,
    onSimulator: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    simulatorSettings: @Composable () -> Unit,
) {
    val adapter = state.settings.adapter
    GroupCard {
        StatusRow(state.status, adapter, state.fmt)
        RowDivider()
        if (adapter == null) {
            val scanning = state.status is ObdStatus.Scanning
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.obd_none_chosen), style = MaterialTheme.typography.bodyMedium)
                if (scanning) {
                    GlassOutlinedButton(onClick = onStopScan) { Text(stringResource(R.string.obd_stop_scan)) }
                } else {
                    GlassButton(onClick = onScan, enabled = canScan) {
                        Icon(Icons.Outlined.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.obd_scan), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (scanning && state.scanResults.isEmpty()) {
                    Text(stringResource(R.string.obd_scan_empty), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
                }
            }
            state.scanResults.forEach { found -> ScanRow(found) { onChoose(found) } }
            if (simulatorAvailable) {
                RowDivider()
                ListItem(
                    headlineContent = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.obd_simulator))
                            DebugTag()
                        }
                    },
                    supportingContent = { Text(stringResource(R.string.obd_simulator_desc)) },
                    leadingContent = { IconBadge(Icons.Outlined.Memory) },
                    colors = glassListItemColors(),
                    modifier = Modifier.clickable(onClick = onSimulator),
                )
            }
        } else {
            val simulated = adapter.transport == ObdTransportKind.SIMULATED
            ListItem(
                headlineContent = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(adapterLabel(adapter))
                        if (simulated) DebugTag()
                    }
                },
                supportingContent = {
                    Text(
                        when {
                            simulated -> stringResource(R.string.obd_simulator_desc)
                            adapter.associated -> stringResource(R.string.obd_kind_companion) + "\n" + adapter.address
                            else -> stringResource(R.string.obd_kind_direct) + "\n" + adapter.address
                        },
                    )
                },
                leadingContent = { IconBadge(if (simulated) Icons.Outlined.Memory else Icons.Outlined.Bluetooth) },
                colors = glassListItemColors(),
            )
            val noVehicle = state.settings.vehicleId == null
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (noVehicle && !state.running) {
                    Text(stringResource(R.string.obd_need_vehicle), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.running) {
                        GlassButton(onClick = onDisconnect) { Text(stringResource(R.string.obd_disconnect)) }
                    } else {
                        GlassButton(onClick = onConnect, enabled = !noVehicle) { Text(stringResource(R.string.obd_connect)) }
                    }
                    GlassOutlinedButton(onClick = onForget) { Text(stringResource(R.string.obd_forget)) }
                }
                if (simulated) simulatorSettings()
            }
        }
    }
}

@Composable
private fun StatusRow(status: ObdStatus, adapter: ObdAdapter?, fmt: Formatter) {
    val colors = LocalStatusColors.current
    val dot: Color = when (status) {
        is ObdStatus.Connected, is ObdStatus.Reading -> colors.ok
        is ObdStatus.Connecting, is ObdStatus.Pairing, ObdStatus.Scanning -> colors.dueSoon
        is ObdStatus.Error -> colors.overdue
        ObdStatus.Disconnected -> glass.textSecondary
    }
    val busy = status is ObdStatus.Connecting || status is ObdStatus.Reading || status is ObdStatus.Scanning || status is ObdStatus.Pairing
    val details = buildList {
        when (status) {
            is ObdStatus.Connected -> {
                status.identity?.let { add(stringResource(R.string.obd_status_identity, it)) }
                status.lastKm?.let { km ->
                    val at = status.lastReadAt?.let { " · " + fmt.dateTime(Instant.ofEpochMilli(it)) }.orEmpty()
                    add(stringResource(R.string.obd_status_last, fmt.distance(km)) + at)
                }
            }
            is ObdStatus.Reading -> status.lastKm?.let { add(stringResource(R.string.obd_status_last, fmt.distance(it))) }
            is ObdStatus.Error -> status.detail?.let(::add)
            else -> Unit
        }
    }
    ListItem(
        headlineContent = {
            // The dot sits on the first line, so a status that wraps (large font, German) keeps it beside its start.
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 9.dp).size(10.dp).clip(CircleShape).background(dot))
                Text(statusText(status, adapter), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
            }
        },
        supportingContent = details.takeIf { it.isNotEmpty() }?.let { { Text(it.joinToString("\n")) } },
        leadingContent = { IconBadge(statusIcon(status, adapter)) },
        trailingContent = if (busy) {
            { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = glass.sheen) }
        } else {
            null
        },
        colors = glassListItemColors(),
    )
}

private fun statusIcon(status: ObdStatus, adapter: ObdAdapter?): ImageVector = when {
    adapter?.transport == ObdTransportKind.SIMULATED -> Icons.Outlined.Memory
    status is ObdStatus.Connected || status is ObdStatus.Reading -> Icons.Outlined.BluetoothConnected
    status is ObdStatus.Scanning || status is ObdStatus.Connecting || status is ObdStatus.Pairing -> Icons.Outlined.BluetoothSearching
    status is ObdStatus.Error -> Icons.Outlined.BluetoothDisabled
    else -> Icons.Outlined.Bluetooth
}

@Composable
private fun statusText(status: ObdStatus, adapter: ObdAdapter?): String = when (status) {
    ObdStatus.Disconnected -> stringResource(R.string.obd_status_disconnected)
    ObdStatus.Scanning -> stringResource(R.string.obd_status_scanning)
    is ObdStatus.Connecting -> stringResource(if (status.waiting) R.string.obd_status_waiting else R.string.obd_status_connecting)
    is ObdStatus.Pairing -> stringResource(R.string.obd_status_pairing)
    is ObdStatus.Connected -> (if (adapter?.transport == ObdTransportKind.SIMULATED) adapterLabel(adapter) else status.adapterName)
        ?.let { stringResource(R.string.obd_notification_connected, it) } ?: stringResource(R.string.obd_status_connected)
    is ObdStatus.Reading -> stringResource(R.string.obd_status_reading)
    is ObdStatus.Error -> stringResource(errorText(status.reason))
}

internal fun errorText(error: ObdError): Int = when (error) {
    ObdError.NO_ADAPTER -> R.string.obd_error_no_adapter
    ObdError.NO_VEHICLE -> R.string.obd_error_no_vehicle
    ObdError.BLUETOOTH_OFF -> R.string.obd_error_bluetooth_off
    ObdError.BLUETOOTH_UNAVAILABLE -> R.string.obd_error_bluetooth_unavailable
    ObdError.PERMISSION_MISSING -> R.string.obd_error_permission
    ObdError.PAIRING_FAILED -> R.string.obd_error_pairing_failed
    ObdError.CONNECT_FAILED -> R.string.obd_error_connect_failed
    ObdError.NO_UART -> R.string.obd_error_no_uart
    ObdError.NOT_ELM -> R.string.obd_error_not_elm
    ObdError.NO_REPLY -> R.string.obd_error_no_reply
    ObdError.TRANSPORT_UNAVAILABLE -> R.string.obd_error_transport
    ObdError.NO_MANUAL_READING -> R.string.obd_error_no_manual
}

@Composable
private fun adapterLabel(adapter: ObdAdapter?): String = when {
    adapter == null -> stringResource(R.string.obd_unnamed)
    // The core stores an English name for the simulator; the screen shows its own.
    adapter.transport == ObdTransportKind.SIMULATED -> stringResource(R.string.obd_sim_name)
    else -> adapter.name ?: adapter.address
}

@Composable
private fun ScanRow(found: ScannedAdapter, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(found.name ?: stringResource(R.string.obd_unnamed), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.obd_scan_row, found.address, found.rssi))
                if (found.likelyObd) Text(stringResource(R.string.obd_likely), color = glass.sheen)
            }
        },
        leadingContent = { IconBadge(Icons.Outlined.Bluetooth) },
        colors = glassListItemColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** The simulator's label: never mistaken for a real adapter. */
@Composable
private fun DebugTag() {
    val colors = LocalStatusColors.current
    Pill(stringResource(R.string.obd_debug_tag), container = colors.dueSoonContainer, content = colors.onDueSoonContainer)
}
