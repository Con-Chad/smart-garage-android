package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.ExpandCircleDown
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Inspection
import io.github.conchad.smartgarage.core.model.InsurancePolicy
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.dueDays
import io.github.conchad.smartgarage.ui.components.inspectionLabel
import io.github.conchad.smartgarage.ui.components.relativeTime
import io.github.conchad.smartgarage.ui.glass.GlassPillButton
import io.github.conchad.smartgarage.ui.glass.GlassStackedPillButton
import io.github.conchad.smartgarage.ui.glass.MetallicText
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion
import io.github.conchad.smartgarage.ui.links.ICON_SIZE
import io.github.conchad.smartgarage.ui.links.InsuranceApps
import io.github.conchad.smartgarage.ui.links.rememberInsurerIcon
import java.time.Instant

/** What the "Quick info" rows' Add / Update pills start (D-A31); the shell wires each to the form route. */
@Stable
class QuickInfoActions(
    val onAddInsurance: (vehicleId: String) -> Unit,
    val onEditInsurance: (vehicleId: String, policyId: String) -> Unit,
    val onAddInspection: (vehicleId: String) -> Unit,
    val onEditInspection: (vehicleId: String, inspectionId: String) -> Unit,
)

/**
 * The row under a vehicle card's photo (D-A33, shared by the home card and the vehicles list): the mileage on the
 * left, the "View all" pill exactly centred (both sides take an equal weight), the "Quick info" bubble on the right,
 * all centred on one line. [onViewAll] null leaves the centre empty.
 *
 * D-A36: "View all" is a two-line pill ("View" over "all ›", [GlassStackedPillButton]) so it takes about half the
 * width; with the trimmed bubble padding, the mileage stays on one line and "Quick info" / "Kurzinfo" on one row
 * up to 1.3× font scale on a 360 dp phone.
 */
@Composable
fun VehicleCardFooter(
    mileage: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onViewAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            MetallicText(mileage, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onViewAll != null) {
            GlassStackedPillButton(
                topLine = stringResource(R.string.card_view_all_line1),
                bottomLine = stringResource(R.string.card_view_all_line2),
                contentDescription = stringResource(R.string.card_view_all),
                icon = Icons.Outlined.ChevronRight,
                onClick = onViewAll,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            QuickInfoToggle(expanded = expanded, onToggle = onToggle)
        }
    }
}

/**
 * The "Quick info" bubble (D-A31, D-A33): the label stacked over a 28 dp ring chevron, and the whole bubble — label,
 * chevron and padding, at least 48 dp — is the target, with a ripple clipped to its rounded shape. The chevron turns
 * 180° on a spring (snaps under reduced motion). TalkBack reads it as a button with its state and the show / hide action.
 */
@Composable
fun QuickInfoToggle(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = glass
    val reduced = rememberReducedMotion()
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        animationSpec = if (reduced) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "chevron",
    )
    val stateText = stringResource(if (expanded) R.string.form_expanded else R.string.form_collapsed)
    val actionText = stringResource(if (expanded) R.string.card_details_hide else R.string.card_details_show)
    Column(
        modifier
            .clip(RoundedCornerShape(tokens.radiusMedium))
            .clickable(onClick = onToggle, onClickLabel = actionText, role = Role.Button)
            .semantics(mergeDescendants = true) { stateDescription = stateText }
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.card_quick_info), style = MaterialTheme.typography.labelLarge, color = tokens.sheen, maxLines = 1, softWrap = false)
        Icon(
            Icons.Outlined.ExpandCircleDown,
            contentDescription = null,
            tint = tokens.sheen,
            modifier = Modifier.padding(top = 2.dp).size(28.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * The drop-down under [QuickInfoToggle]: insurance plan (the insurer's launcher icon beside the name and a tap
 * opens the app when installed), mileage (opens the odometer sheet), inspection due. Each row ends in "Add" or
 * "Update" for people who can edit (D-A31): insurance and inspection go to their forms, mileage to the odometer
 * sheet. Plain text on the card's frosted surface, no inner boxes (D-A32); the caller keeps the open state and
 * `animateContentSize` on the card, so it drops down rather than popping.
 */
@Composable
fun QuickInfoDetails(
    vehicle: Vehicle,
    policy: InsurancePolicy?,
    inspection: Inspection?,
    lastOdometerAt: Instant?,
    fmt: Formatter,
    actions: QuickInfoActions,
    onOpenOdometer: () -> Unit,
) {
    val canEdit = vehicle.access.canEdit
    val context = LocalContext.current
    val insurerIntent = remember(policy?.provider) { InsuranceApps.launchIntent(context, policy?.provider) }
    val insurerIcon = if (insurerIntent != null) rememberInsurerIcon(policy?.provider) else null
    val add = stringResource(R.string.action_add)
    val update = stringResource(R.string.action_update)

    Column(Modifier.padding(bottom = 8.dp)) {
        DetailLine(
            icon = Icons.Outlined.Shield,
            label = stringResource(R.string.dashboard_insurance),
            value = policy?.provider ?: stringResource(R.string.card_no_insurance),
            detail = policy?.endDate?.toInstantOrNull()?.let { stringResource(R.string.dashboard_insurance_until, fmt.date(it)) },
            badge = insurerIcon,
            trailing = if (insurerIntent != null) Icons.Outlined.OpenInNew else null,
            actionLabel = policy?.let { stringResource(R.string.card_insurance_open_app, it.provider) }.takeIf { insurerIntent != null },
            onClick = if (insurerIntent != null) ({ InsuranceApps.open(context, policy?.provider) }) else null,
            button = when {
                !canEdit -> null
                policy != null -> update to { actions.onEditInsurance(vehicle.id, policy.id) }
                else -> add to { actions.onAddInsurance(vehicle.id) }
            },
        )
        RowDivider()
        val hasReading = lastOdometerAt != null || vehicle.currentOdometerKm > 0
        DetailLine(
            icon = Icons.Outlined.Speed,
            label = stringResource(R.string.card_mileage),
            value = fmt.distance(vehicle.currentOdometerKm),
            detail = lastOdometerAt?.let { stringResource(R.string.odometer_updated, relativeTime(it)) },
            trailing = null,
            actionLabel = stringResource(R.string.action_update_odometer).takeIf { canEdit },
            onClick = onOpenOdometer.takeIf { canEdit },
            button = if (canEdit) (if (hasReading) update else add) to onOpenOdometer else null,
        )
        RowDivider()
        val due = inspection?.nextDueAt?.toInstantOrNull()
        DetailLine(
            icon = Icons.Outlined.EventAvailable,
            label = inspection?.let { inspectionLabel(it.type) } ?: stringResource(R.string.inspection_hu),
            value = due?.let { fmt.date(it) } ?: stringResource(R.string.card_no_inspection),
            detail = due?.let { dueDays(fmt.daysFromToday(it).toInt()) },
            trailing = null,
            actionLabel = null,
            onClick = null,
            button = when {
                !canEdit -> null
                inspection != null -> update to { actions.onEditInspection(vehicle.id, inspection.id) }
                else -> add to { actions.onAddInspection(vehicle.id) }
            },
        )
    }
}

/**
 * Icon, label, value (with an optional app-icon [badge] beside it) and a detail line as one TalkBack item, with
 * the tap action named when there is one; the optional compact [button] at the end is its own target.
 */
@Composable
private fun DetailLine(
    icon: ImageVector,
    label: String,
    value: String,
    detail: String?,
    trailing: ImageVector?,
    actionLabel: String?,
    onClick: (() -> Unit)?,
    badge: ImageBitmap? = null,
    button: Pair<String, () -> Unit>? = null,
) {
    val tokens = glass
    Row(Modifier.fillMaxWidth().padding(end = if (button != null) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { if (actionLabel != null) contentDescription = "$label, $value. $actionLabel" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tokens.sheen, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (badge != null) {
                        Image(badge, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(ICON_SIZE))
                    }
                    MetallicText(value, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) Icon(trailing, contentDescription = null, tint = tokens.textSecondary, modifier = Modifier.size(18.dp))
        }
        if (button != null) {
            GlassPillButton(label = button.first, onClick = button.second, compact = true)
        }
    }
}
