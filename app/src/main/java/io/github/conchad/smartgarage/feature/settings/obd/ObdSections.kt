package io.github.conchad.smartgarage.feature.settings.obd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.obd.DistanceUnit
import io.github.conchad.smartgarage.core.obd.OdometerKind
import io.github.conchad.smartgarage.core.obd.OdometerProfile
import io.github.conchad.smartgarage.core.obd.OdometerProfiles
import io.github.conchad.smartgarage.core.obd.OdometerRead
import io.github.conchad.smartgarage.core.obd.data.ObdReading
import io.github.conchad.smartgarage.core.obd.data.ReadingState
import io.github.conchad.smartgarage.core.obd.trip.RawResult
import io.github.conchad.smartgarage.core.obd.trip.ReadNowResult
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.Pill
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.forms.FormDisclosure
import io.github.conchad.smartgarage.ui.components.forms.FormDistanceField
import io.github.conchad.smartgarage.ui.components.forms.FormNotesField
import io.github.conchad.smartgarage.ui.components.forms.FormNumberField
import io.github.conchad.smartgarage.ui.components.forms.FormPicker
import io.github.conchad.smartgarage.ui.components.forms.FormSwitchRow
import io.github.conchad.smartgarage.ui.components.forms.FormTextField
import io.github.conchad.smartgarage.ui.glass.GlassButton
import io.github.conchad.smartgarage.ui.glass.GlassOutlinedButton
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.glassListItemColors
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors
import io.github.conchad.smartgarage.ui.theme.LocalStatusColors
import io.github.conchad.smartgarage.ui.theme.MonoTextStyle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Readings shown before "Show all". */
private const val RECENT_SHOWN = 5

/** Typing pause before a number field saves (as Settings' name field). */
private const val SAVE_DELAY_MS = 600L

// ── Vehicle and odometer profile ─────────────────────────────────────────────────────────────────────────────

private data class ProfileChoice(val profile: OdometerProfile, val label: String)

@Composable
internal fun VehicleProfileCard(
    state: ObdUiState,
    onVehicle: (String) -> Unit,
    onBuiltIn: (OdometerKind) -> Unit,
    onImport: (String) -> Unit,
) {
    val settings = state.settings
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.vehicles.isEmpty()) {
                Text(stringResource(R.string.obd_vehicle_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                FormPicker(
                    label = stringResource(R.string.obd_vehicle),
                    options = state.vehicles,
                    selected = state.vehicles.firstOrNull { it.id == settings.vehicleId },
                    optionLabel = { it.label },
                    onSelect = { onVehicle(it.id) },
                    supporting = stringResource(R.string.obd_vehicle_desc),
                    placeholder = stringResource(R.string.obd_vehicle_pick),
                )
            }
            val choices = ObdAdapterViewModel.profileChoices(settings.profile).map { ProfileChoice(it, profileLabel(it)) }
            FormPicker(
                label = stringResource(R.string.obd_profile),
                options = choices,
                selected = choices.firstOrNull { it.profile.kind == settings.profile.kind },
                optionLabel = { it.label },
                onSelect = { if (it.profile.kind != OdometerKind.CUSTOM) onBuiltIn(it.profile.kind) },
                supporting = stringResource(profileDescription(settings.profile.kind)),
            )
            var details by rememberSaveable { mutableStateOf(false) }
            FormDisclosure(
                title = stringResource(R.string.obd_profile_details),
                expanded = details,
                onToggle = { details = !details },
                summary = stringResource(R.string.obd_profile_details_summary),
            ) { ProfileDetails(settings.profile.resolved()) }
            var paste by rememberSaveable { mutableStateOf(false) }
            var json by rememberSaveable { mutableStateOf("") }
            FormDisclosure(
                title = stringResource(R.string.obd_wican_title),
                expanded = paste,
                onToggle = { paste = !paste },
                summary = stringResource(R.string.obd_wican_summary),
            ) {
                FormNotesField(
                    value = json,
                    onValueChange = { json = it },
                    label = stringResource(R.string.obd_wican_field),
                    supporting = stringResource(R.string.obd_wican_hint),
                )
                GlassOutlinedButton(onClick = { onImport(json) }, enabled = json.isNotBlank()) {
                    Text(stringResource(R.string.obd_wican_import))
                }
            }
        }
    }
}

@Composable
private fun ProfileDetails(profile: OdometerProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailLine(R.string.obd_detail_init, profile.initCommands.ifBlank { "—" })
        if (profile.headerCommand.isNotBlank()) DetailLine(R.string.obd_detail_header, profile.headerCommand)
        DetailLine(R.string.obd_detail_request, profile.request)
        DetailLine(R.string.obd_detail_expression, profile.expression)
        DetailLine(R.string.obd_detail_unit, if (profile.unit == DistanceUnit.MI) "mi" else "km")
        DetailLine(R.string.obd_detail_multiplier, number(profile.multiplier))
    }
}

@Composable
private fun DetailLine(label: Int, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = glass.textSecondary,
            modifier = Modifier.weight(0.4f).padding(end = 8.dp),
        )
        Text(value, style = MonoTextStyle, modifier = Modifier.weight(0.6f))
    }
}

@Composable
internal fun profileLabel(profile: OdometerProfile): String = when (profile.kind) {
    OdometerKind.HONDA_CLUSTER -> stringResource(R.string.obd_profile_honda)
    OdometerKind.STANDARD -> stringResource(R.string.obd_profile_standard)
    OdometerKind.CODES_CLEARED -> stringResource(R.string.obd_profile_codes)
    OdometerKind.CUSTOM -> profile.name?.let { stringResource(R.string.obd_profile_custom, it) } ?: stringResource(R.string.obd_profile_custom_plain)
}

private fun profileDescription(kind: OdometerKind): Int = when (kind) {
    OdometerKind.HONDA_CLUSTER -> R.string.obd_profile_honda_desc
    OdometerKind.STANDARD -> R.string.obd_profile_standard_desc
    OdometerKind.CODES_CLEARED -> R.string.obd_profile_codes_desc
    OdometerKind.CUSTOM -> R.string.obd_profile_custom_desc
}

/** A multiplier or expression value without a trailing ".0", at most 6 decimals. */
internal fun number(value: Double): String =
    if (value == Math.floor(value) && abs(value) < 1e15) value.toLong().toString()
    else String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')

// ── Read now and the dash check ──────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ReadNowCard(
    state: ObdUiState,
    read: ReadUiState,
    onRead: () -> Unit,
    onCheckDash: (String, String) -> Boolean,
    onDashEdited: () -> Unit,
    onApplyFix: () -> Unit,
) {
    val hasAdapter = state.settings.adapter != null
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.obd_read_desc), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
            GlassButton(onClick = onRead, enabled = hasAdapter && !read.reading) {
                if (read.reading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = glass.textSecondary)
                } else {
                    Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(stringResource(R.string.obd_read), modifier = Modifier.padding(start = 8.dp))
            }
            if (!hasAdapter) {
                Text(stringResource(R.string.obd_read_needs_adapter), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
            }
            read.result?.let { result ->
                ReadResult(result, state.fmt, state.settings.profile.resolved())
                if (result.error == null && result.km != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    DashCheck(state, read.calibration, onCheckDash, onDashEdited, onApplyFix)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadResult(result: ReadNowResult, fmt: Formatter, profile: OdometerProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (result.km != null) {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.obd_read_value), style = MaterialTheme.typography.labelLarge, color = glass.textSecondary)
                Text(fmt.distance(result.km), style = MaterialTheme.typography.headlineMedium)
            }
        }
        result.counterKm?.let { Text(stringResource(R.string.obd_read_counter, fmt.distance(it)), style = MaterialTheme.typography.bodyMedium) }
        result.error?.let { error ->
            Text(
                stringResource(errorText(error)) + (result.detail?.let { "\n$it" }.orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        result.failure?.let { failure ->
            Text(failureText(failure), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        result.value?.let { value ->
            Text(stringResource(R.string.obd_read_expression, profile.expression, number(value)), style = MonoTextStyle)
        }
        result.raw?.let { raw ->
            Text(stringResource(R.string.obd_read_raw), style = MaterialTheme.typography.labelLarge, color = glass.textSecondary)
            CodeBlock(raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() && it != ">" }.joinToString("\n"))
        }
        if (result.bytes.isNotEmpty()) {
            Text(stringResource(R.string.obd_read_bytes), style = MaterialTheme.typography.labelLarge, color = glass.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                result.bytes.forEach { byte ->
                    Surface(
                        color = glass.pill,
                        contentColor = glass.textPrimary,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${byte.label} ${byte.hex}" },
                    ) {
                        Column(Modifier.widthIn(min = 40.dp).padding(horizontal = 6.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(byte.label, style = MaterialTheme.typography.labelSmall, color = glass.textSecondary)
                            Text(byte.hex, style = MonoTextStyle)
                        }
                    }
                }
            }
            Text(stringResource(R.string.obd_read_bytes_hint), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
        }
    }
}

@Composable
private fun failureText(failure: OdometerRead.Failed): String = when (failure.reason) {
    OdometerRead.Reason.ADAPTER -> stringResource(R.string.obd_read_failed_adapter, failure.detail)
    OdometerRead.Reason.NEGATIVE -> stringResource(R.string.obd_read_failed_negative, failure.detail)
    OdometerRead.Reason.PENDING -> stringResource(R.string.obd_read_failed_pending, failure.detail)
    OdometerRead.Reason.UNEXPECTED -> stringResource(R.string.obd_read_failed_unexpected, failure.detail)
    OdometerRead.Reason.EXPRESSION -> stringResource(R.string.obd_read_failed_expression, failure.detail)
}

/** Monospace text on the pill fill, for replies and logs. */
@Composable
private fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Surface(color = glass.pill, contentColor = glass.textPrimary, shape = RoundedCornerShape(8.dp), modifier = modifier.fillMaxWidth()) {
        Text(text, style = MonoTextStyle, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

@Composable
private fun DashCheck(
    state: ObdUiState,
    calibration: Calibration?,
    onCheck: (String, String) -> Boolean,
    onEdited: () -> Unit,
    onApply: () -> Unit,
) {
    val profile = state.settings.profile
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.obd_dash_title), style = MaterialTheme.typography.titleSmall, color = glass.sheen)
        if (profile.kind == OdometerKind.CODES_CLEARED) {
            Text(stringResource(R.string.obd_dash_na), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
            return@Column
        }
        Text(stringResource(R.string.obd_dash_desc), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
        var dash by rememberSaveable { mutableStateOf("") }
        var invalid by rememberSaveable { mutableStateOf(false) }
        val customName = stringResource(R.string.obd_dash_calibrated, profileLabel(profile.resolved()))
        FormDistanceField(
            value = dash,
            onValueChange = {
                dash = it
                invalid = false
                onEdited()
            },
            label = stringResource(R.string.obd_dash_field),
            fmt = state.fmt,
            error = if (invalid) stringResource(R.string.obd_dash_invalid) else null,
            imeAction = ImeAction.Done,
        )
        GlassOutlinedButton(onClick = { invalid = !onCheck(dash, customName) }, enabled = dash.isNotBlank()) {
            Text(stringResource(R.string.obd_dash_check))
        }
        when (calibration) {
            null -> Unit
            Calibration.Matches -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = LocalStatusColors.current.ok, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.obd_dash_matches), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            is Calibration.Fix -> {
                val how = when {
                    calibration.profile.unit != profile.resolved().unit && calibration.profile.unit == DistanceUnit.MI ->
                        stringResource(R.string.obd_dash_fix_unit_mi)
                    calibration.profile.unit != profile.resolved().unit -> stringResource(R.string.obd_dash_fix_unit_km)
                    else -> stringResource(R.string.obd_dash_fix_multiplier, number(calibration.profile.multiplier))
                }
                Text(
                    stringResource(R.string.obd_dash_fix, calibration.factor.label) + " " + how,
                    style = MaterialTheme.typography.bodyMedium,
                )
                GlassButton(onClick = onApply) { Text(stringResource(R.string.obd_dash_apply)) }
            }
            is Calibration.NoCleanFactor -> Text(
                stringResource(R.string.obd_dash_no_factor, state.fmt.distance(abs(calibration.differenceKm))),
                style = MaterialTheme.typography.bodyMedium,
            )
            Calibration.NotApplicable -> Text(stringResource(R.string.obd_dash_na), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── Uploads ──────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun UploadCard(
    state: ObdUiState,
    onPoll: (String) -> Unit,
    onMinDistance: (String) -> Unit,
    onTripEnd: (Boolean) -> Unit,
    onCellular: (Boolean) -> Unit,
) {
    val settings = state.settings
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SavedNumberField(
                saved = settings.pollMinutes.toString(),
                onSave = onPoll,
                label = stringResource(R.string.obd_poll),
                unit = stringResource(R.string.obd_poll_unit),
                supporting = stringResource(R.string.obd_poll_desc),
                integer = true,
            )
            SavedNumberField(
                saved = ObdInputs.distanceText(settings.minUploadDistance),
                onSave = onMinDistance,
                label = stringResource(R.string.obd_min_distance),
                unit = state.fmt.distanceUnit,
                supporting = stringResource(R.string.obd_min_distance_desc),
                integer = false,
            )
            FormSwitchRow(
                label = stringResource(R.string.obd_trip_end),
                supporting = stringResource(R.string.obd_trip_end_desc),
                checked = settings.uploadOnTripEnd,
                onCheckedChange = onTripEnd,
            )
            FormSwitchRow(
                label = stringResource(R.string.obd_cellular),
                supporting = stringResource(R.string.obd_cellular_desc),
                checked = settings.cellularUploads,
                onCheckedChange = onCellular,
            )
            val lines = listOfNotNull(
                settings.lastSyncAt?.let { stringResource(R.string.obd_last_sync, state.fmt.dateTime(Instant.ofEpochMilli(it))) },
                settings.lastSentKm?.let { stringResource(R.string.obd_last_sent, state.fmt.distance(it)) },
            )
            if (lines.isNotEmpty()) {
                Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
            }
        }
    }
}

/** A number saved shortly after typing stops; the stored (clamped) value comes back into the field. */
@Composable
internal fun SavedNumberField(saved: String, onSave: (String) -> Unit, label: String, unit: String, supporting: String, integer: Boolean) {
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    LaunchedEffect(text) {
        if (text == saved) return@LaunchedEffect
        delay(SAVE_DELAY_MS)
        onSave(text)
    }
    FormNumberField(
        value = text,
        onValueChange = { text = it },
        label = label,
        unit = unit,
        supporting = supporting,
        integer = integer,
        imeAction = ImeAction.Done,
    )
}

// ── Readings ─────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ReadingsCard(state: ObdUiState) {
    var all by rememberSaveable { mutableStateOf(false) }
    GroupCard {
        ListItem(
            headlineContent = {
                Text(
                    if (state.queued > 0) pluralStringResource(R.plurals.obd_queued, state.queued, state.queued)
                    else stringResource(R.string.obd_queued_none),
                )
            },
            leadingContent = { IconBadge(Icons.Outlined.CloudUpload) },
            colors = glassListItemColors(),
        )
        RowDivider()
        if (state.recent.isEmpty()) {
            Text(
                stringResource(R.string.obd_recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = glass.textSecondary,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            val names = state.vehicles.associate { it.id to it.label }
            val several = state.recent.map { it.vehicleId }.distinct().size > 1
            state.recent.take(if (all) state.recent.size else RECENT_SHOWN).forEach { reading ->
                ReadingRow(reading, state.fmt, if (several) names[reading.vehicleId] else null)
            }
            if (state.recent.size > RECENT_SHOWN) {
                TextButton(
                    onClick = { all = !all },
                    colors = glassTextButtonColors(),
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                ) {
                    Text(if (all) stringResource(R.string.obd_show_fewer) else stringResource(R.string.obd_show_all, state.recent.size))
                }
            }
        }
    }
}

@Composable
private fun ReadingRow(reading: ObdReading, fmt: Formatter, vehicle: String?) {
    val colors = LocalStatusColors.current
    val (label, container, content) = when (reading.state) {
        ReadingState.QUEUED -> Triple(R.string.obd_state_queued, colors.dueSoonContainer, colors.onDueSoonContainer)
        ReadingState.SENT -> Triple(R.string.obd_state_sent, colors.okContainer, colors.onOkContainer)
        ReadingState.REJECTED -> Triple(R.string.obd_state_rejected, colors.overdueContainer, colors.onOverdueContainer)
        ReadingState.SUPERSEDED -> Triple(R.string.obd_state_superseded, glass.pill, glass.textPrimary)
        ReadingState.LOCAL -> Triple(R.string.obd_state_local, glass.pill, glass.textPrimary)
    }
    ListItem(
        headlineContent = {
            Text(reading.km?.let(fmt::distance) ?: stringResource(R.string.obd_reading_no_km, reading.counterKm?.let(fmt::distance) ?: "—"))
        },
        supportingContent = { Text(listOfNotNull(fmt.dateTime(Instant.ofEpochMilli(reading.readAt)), vehicle).joinToString(" · ")) },
        trailingContent = { Pill(stringResource(label), container = container, content = content) },
        colors = glassListItemColors(),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    )
}

// ── Diagnostics ──────────────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun DiagnosticsCard(
    state: ObdUiState,
    console: List<RawResult>,
    sending: Boolean,
    onSend: (String) -> Unit,
    onClearConsole: () -> Unit,
    onExport: () -> Unit,
) {
    var showLog by rememberSaveable { mutableStateOf(false) }
    var showTranscript by rememberSaveable { mutableStateOf(false) }
    var showConsole by rememberSaveable { mutableStateOf(false) }
    val fmt = state.fmt
    GroupCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FormDisclosure(
                title = stringResource(R.string.obd_log),
                expanded = showLog,
                onToggle = { showLog = !showLog },
                summary = pluralStringResource(R.plurals.obd_entries, state.connectionLog.size, state.connectionLog.size),
            ) {
                if (state.connectionLog.isEmpty()) {
                    Text(stringResource(R.string.obd_log_empty), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
                } else {
                    CodeBlock(
                        state.connectionLog.asReversed().take(LOG_SHOWN)
                            .joinToString("\n") { "${fmt.time(it.atMillis)}  ${it.message}" },
                    )
                }
            }
            FormDisclosure(
                title = stringResource(R.string.obd_transcript),
                expanded = showTranscript,
                onToggle = { showTranscript = !showTranscript },
                summary = pluralStringResource(R.plurals.obd_entries, state.transcript.size, state.transcript.size),
            ) {
                if (state.transcript.isEmpty()) {
                    Text(stringResource(R.string.obd_log_empty), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
                } else {
                    CodeBlock(
                        state.transcript.asReversed().joinToString("\n") { e ->
                            val reply = e.raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() && it != ">" }
                            (listOf("${fmt.time(e.atMillis)} > ${e.command}  [${e.status}]") + reply.map { "    $it" }).joinToString("\n")
                        },
                    )
                }
            }
            FormDisclosure(
                title = stringResource(R.string.obd_console),
                expanded = showConsole,
                onToggle = { showConsole = !showConsole },
                summary = stringResource(R.string.obd_console_summary),
            ) {
                var command by rememberSaveable { mutableStateOf("") }
                FormTextField(
                    value = command,
                    onValueChange = { command = it.uppercase() },
                    label = stringResource(R.string.obd_console_field),
                    imeAction = ImeAction.Send,
                    enabled = state.settings.adapter != null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(onClick = { onSend(command) }, enabled = command.isNotBlank() && !sending && state.settings.adapter != null) {
                        if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = glass.textSecondary)
                        else Text(stringResource(R.string.obd_console_send))
                    }
                    if (console.isNotEmpty()) {
                        TextButton(onClick = onClearConsole, colors = glassTextButtonColors()) { Text(stringResource(R.string.obd_console_clear)) }
                    }
                }
                if (state.settings.adapter == null) {
                    Text(stringResource(R.string.obd_read_needs_adapter), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
                }
                console.asReversed().forEach { result -> ConsoleResult(result) }
            }
            RowDivider()
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GlassOutlinedButton(onClick = onExport) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.obd_export), modifier = Modifier.padding(start = 8.dp))
                }
                Text(stringResource(R.string.obd_export_desc), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
            }
        }
    }
}

@Composable
private fun ConsoleResult(result: RawResult) {
    val reply = result.reply
    val text = when {
        reply != null -> listOf("> ${result.command}  [${reply.status}]") + (reply.raw ?: "").split('\r', '\n')
            .map { it.trim() }.filter { it.isNotEmpty() && it != ">" }
        else -> listOf("> ${result.command}")
    }.joinToString("\n")
    CodeBlock(text)
    if (reply == null) {
        val why = result.error?.let { stringResource(errorText(it)) } ?: result.detail ?: "?"
        Text(stringResource(R.string.obd_console_no_reply, why), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

private const val LOG_SHOWN = 100

private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Wall-clock time with seconds, in the server's zone, for log lines. */
private fun Formatter.time(millis: Long): String = LOG_TIME.withZone(zone).format(Instant.ofEpochMilli(millis))

// ── Debug simulator ──────────────────────────────────────────────────────────────────────────────────────────

/** Debug builds with the simulated adapter only: where the pretend car starts and how fast it drives. */
@Composable
internal fun SimulatorSettingsBlock(state: ObdUiState, onStart: (String) -> Unit, onSpeed: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val fmt = state.fmt
    val sim = state.settings.simulator
    FormDisclosure(
        title = stringResource(R.string.obd_sim_title),
        expanded = open,
        onToggle = { open = !open },
        summary = stringResource(R.string.obd_sim_summary),
    ) {
        SavedNumberField(
            saved = fmt.distanceValue(sim.startOdometerKm).roundToLong().toString(),
            onSave = onStart,
            label = stringResource(R.string.obd_sim_start),
            unit = fmt.distanceUnit,
            supporting = stringResource(R.string.obd_sim_start_desc),
            integer = true,
        )
        SavedNumberField(
            saved = fmt.distanceValue(sim.speedKmh).roundToLong().toString(),
            onSave = onSpeed,
            label = stringResource(R.string.obd_sim_speed),
            unit = fmt.distanceUnit + "/h",
            supporting = stringResource(R.string.obd_sim_speed_desc, number(sim.timeScale)),
            integer = true,
        )
    }
}
