package io.github.conchad.smartgarage.feature.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Dashboard
import io.github.conchad.smartgarage.core.model.VehicleSummary
import io.github.conchad.smartgarage.feature.vehicles.QuickInfoDetails
import io.github.conchad.smartgarage.feature.vehicles.VehicleCardFooter
import io.github.conchad.smartgarage.feature.vehicles.VehicleCardActions
import io.github.conchad.smartgarage.feature.vehicles.VehicleCardMenu
import io.github.conchad.smartgarage.feature.vehicles.vehicleCardSemantics
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.VehiclePhoto
import io.github.conchad.smartgarage.ui.components.VehicleStatusChip
import io.github.conchad.smartgarage.ui.glass.GlassCard
import io.github.conchad.smartgarage.ui.glass.LocalVehicleHeroOrigins
import io.github.conchad.smartgarage.ui.glass.heroAnchor
import io.github.conchad.smartgarage.ui.glass.launch
import io.github.conchad.smartgarage.ui.glass.rememberHeroAnchor
import io.github.conchad.smartgarage.ui.glass.vehicleHero
import io.github.conchad.smartgarage.ui.glass.MetallicText
import io.github.conchad.smartgarage.ui.glass.TiltDepths
import io.github.conchad.smartgarage.ui.glass.TiltPhotoFrame
import io.github.conchad.smartgarage.ui.glass.VehicleTint
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.rememberCardTilt
import io.github.conchad.smartgarage.ui.glass.tiltLayer
import kotlinx.coroutines.launch

/**
 * One vehicle on a glass card (D-A30, D-A31): only the chrome-framed photo tilts toward the gyroscope, with the
 * photo behind its frame and the name floating above it; the card around it is flat. Under the photo: the plate,
 * the mileage and the status chip, then a centred "View all" pill (haptic confirm, opens the vehicle page), then a
 * "Quick info" toggle that drops down insurance, mileage and inspection. Tap on the photo opens the vehicle page,
 * long-press the photo / rename menu; TalkBack gets the same as custom actions. [tiltEnabled] is false while the
 * pager moves or for a page that isn't the settled one: the photo then eases flat.
 */
@Composable
internal fun VehicleCard(
    summary: VehicleSummary,
    extras: VehicleExtras,
    dashboard: Dashboard,
    fmt: Formatter,
    tiltEnabled: Boolean,
    cardActions: VehicleCardActions,
    actions: DashboardActions,
    onOpen: () -> Unit,
    onOpenOdometer: () -> Unit,
) {
    val vehicle = summary.vehicle
    val tokens = glass
    val glow = VehicleTint.color(vehicle.color, MaterialTheme.colorScheme.primary)
    val haptics = LocalHapticFeedback.current
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val nickname = cardActions.nicknameOf(vehicle)
    val title = cardActions.titleOf(vehicle)
    // The card is the hero of the vehicle page (D-A34): its last bottom edge goes with the tap, so the page's
    // drawer can slide up from exactly there while the card glides into place.
    val anchor = rememberHeroAnchor()
    val origins = LocalVehicleHeroOrigins.current
    val onOpen = {
        origins.launch(vehicle.id, anchor)
        onOpen()
    }

    GlassCard(modifier = Modifier.vehicleHero(vehicle.id).heroAnchor(anchor), glow = glow) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
            TiltPhotoFrame(
                tilt = rememberCardTilt(followGyro = tiltEnabled),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .vehicleCardSemantics(vehicle, cardActions, onOpen),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
                ) {
                    VehiclePhoto(vehicle, Modifier.fillMaxSize().tiltLayer(TiltDepths.Photo), iconSize = 64.dp)
                    // Name over a bottom scrim so it reads on any photo; white on ≥ 55 % black clears 4.5:1.
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(tokens.photoScrim))
                            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = tokens.onPhoto,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.tiltLayer(TiltDepths.Title),
                        )
                        if (nickname != null) {
                            Text(
                                vehicle.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.onPhotoSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.tiltLayer(TiltDepths.Subtitle),
                            )
                        }
                    }
                }
                VehicleCardMenu(vehicle, expanded = menuOpen, onDismiss = { menuOpen = false }, actions = cardActions, onOpen = onOpen)
            }
        }
        vehicle.licensePlate?.takeIf { it.isNotBlank() }?.let {
            MetallicText(
                it,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp),
            )
        }
        // The mileage over the status chip on the left, "View all" and "Quick info" beside them (D-A41, user-set).
        VehicleCardFooter(
            expanded = expanded,
            onToggle = { expanded = !expanded },
            onViewAll = {
                // The subtlest distinct confirm the platform has; a long-press buzz would read as "held".
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onOpen()
            },
            modifier = Modifier.padding(top = 12.dp),
        ) {
            MetallicText(fmt.distance(vehicle.currentOdometerKm), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            VehicleStatusChip(summary)
        }
        // The drawer slides open and closed under the footer (D-A34); the card's height follows it frame by frame.
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                RowDivider()
                QuickInfoDetails(
                    vehicle = vehicle,
                    policy = dashboard.policyByVehicle[vehicle.id],
                    inspection = dashboard.inspectionByVehicle[vehicle.id],
                    lastOdometerAt = extras.lastOdometerAt,
                    fmt = fmt,
                    actions = actions.quickInfo,
                    onOpenOdometer = onOpenOdometer,
                )
            }
        }
    }
}

/** The arrow on the right edge of the pager: there is another vehicle; tap to go to it. Always over a photo, so a fixed dark pill. */
@Composable
internal fun NextChevron(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = stringResource(R.string.pager_next), tint = glass.onPhoto)
        }
    }
}
