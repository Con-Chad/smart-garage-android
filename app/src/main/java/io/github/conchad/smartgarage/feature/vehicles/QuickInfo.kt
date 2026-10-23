package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import io.github.conchad.smartgarage.ui.glass.rememberPillInk
import io.github.conchad.smartgarage.ui.glass.Motion
import io.github.conchad.smartgarage.ui.glass.glassPillSurface
import io.github.conchad.smartgarage.ui.glass.orSnap
import io.github.conchad.smartgarage.ui.glass.pressScale
import io.github.conchad.smartgarage.ui.glass.rememberPressFeedback
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
 * The row under a vehicle card's photo (D-A33, shared by the home card and the vehicles list).
 *
 * D-A36: "View all" is a two-line pill ("View" over "all ›", [GlassStackedPillButton]).
 * D-A38: both pills on the glass pill surface, pinned to one height (the taller of the two).
 * D-A41 (user-set): the pills moved up beside the card's [details] (the mileage over the status chip): one row, the
 * details column on the left taking what the pills leave, "View all" then "Quick info" at the end, top-aligned so
 * the pills' tops sit level with the mileage line. No row of its own beneath, so the card is shorter. The pills
 * keep their natural widths; at 1.3× (German too) the details column narrows and the chips wrap instead.
 * [onViewAll] null leaves only "Quick info".
 */
@Composable
fun VehicleCardFooter(
    expanded: Boolean,
    onToggle: () -> Unit,
    onViewAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
    details: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(end = FooterGap), verticalArrangement = Arrangement.spacedBy(8.dp), content = details)
        Layout(
            content = {
                if (onViewAll != null) {
                    GlassStackedPillButton(
                        topLine = stringResource(R.string.card_view_all_line1),
                        bottomLine = stringResource(R.string.card_view_all_line2),
                        contentDescription = stringResource(R.string.card_view_all),
                        icon = Icons.Outlined.ChevronRight,
                        onClick = onViewAll,
                    )
                }
                QuickInfoToggle(expanded = expanded, onToggle = onToggle)
            },
        ) { measurables, constraints ->
            val gap = FooterGap.roundToPx()
            val toggle = measurables.last()
            val viewAll = measurables.firstOrNull()?.takeIf { measurables.size == 2 }
            // One height for both pills: the taller one's natural height.
            val height = maxOf(toggle.maxIntrinsicHeight(constraints.maxWidth), viewAll?.maxIntrinsicHeight(constraints.maxWidth) ?: 0)
            val toggleP = toggle.measure(Constraints(maxWidth = constraints.maxWidth, minHeight = height, maxHeight = height))
            val roomLeft = (constraints.maxWidth - toggleP.width - gap).coerceAtLeast(0)
            val viewAllP = viewAll?.measure(Constraints(maxWidth = roomLeft, minHeight = height, maxHeight = height))
            val width = toggleP.width + (viewAllP?.let { it.width + gap } ?: 0)
            layout(width, height) {
                viewAllP?.placeRelative(0, 0)
                toggleP.placeRelative(width - toggleP.width, 0)
            }
        }
    }
}

/** The space between the "View all" and "Quick info" pills, and between them and the card details (D-A38, D-A41). */
private val FooterGap = 8.dp

/**
 * The "Quick info" pill (D-A31, D-A33, D-A38): the label stacked over a 24 dp ring chevron on the glass pill surface
 * (the same fill, border and 14 dp corners as "View all"); the whole pill, at least 48 dp, is the target, with the
 * ripple clipped to it. It fills the height its caller pins. The chevron turns 180° on a spring (snaps under reduced
 * motion). TalkBack reads it as a button with its state and the show / hide action.
 */
@Composable
fun QuickInfoToggle(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = glass
    // D-A43: white over the aurora, dark on a light backdrop.
    val ink = rememberPillInk()
    val reduced = rememberReducedMotion()
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        animationSpec = Motion.standardSpring<Float>().orSnap(reduced),
        label = "chevron",
    )
    val stateText = stringResource(if (expanded) R.string.form_expanded else R.string.form_collapsed)
    val actionText = stringResource(if (expanded) R.string.card_details_hide else R.string.card_details_show)
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressFeedback(interaction)
    Column(
        modifier
            .pressScale(press)
            .glassPillSurface(tokens, RoundedCornerShape(tokens.radiusMedium), press)
            .clickable(interactionSource = interaction, indication = ripple(), onClick = onToggle, onClickLabel = actionText, role = Role.Button)
            .semantics(mergeDescendants = true) { stateDescription = stateText }
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.card_quick_info), style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, softWrap = false)
        Icon(
            Icons.Outlined.ExpandCircleDown,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.padding(top = 2.dp).size(24.dp).graphicsLayer { rotationZ = rotation },
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
