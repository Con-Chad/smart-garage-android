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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
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
import io.github.conchad.smartgarage.feature.vehicles.VehiclePhotoTitle
import io.github.conchad.smartgarage.feature.vehicles.VehicleCardActions
import io.github.conchad.smartgarage.feature.vehicles.VehicleCardMenu
import io.github.conchad.smartgarage.feature.vehicles.vehicleCardSemantics
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.VEHICLE_PHOTO_ASPECT
import io.github.conchad.smartgarage.ui.components.VehiclePhoto
import io.github.conchad.smartgarage.ui.components.VehicleStatusChip
import io.github.conchad.smartgarage.ui.glass.GlassCard
import io.github.conchad.smartgarage.ui.glass.HeroContent
import io.github.conchad.smartgarage.ui.glass.heroPlateOff
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
import io.github.conchad.smartgarage.ui.glass.orNone
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One vehicle on a glass card (D-A30, D-A31): only the chrome-framed photo tilts toward the gyroscope, with the
 * photo behind its frame and the name floating above it; the card around it is flat. Under the photo: the plate,
 * the mileage and the status chip, then a centred "View all" pill (haptic confirm, opens the vehicle page), then a
 * "Quick info" toggle that drops down insurance, mileage and inspection. Tap on the photo opens the vehicle page,
 * long-press the photo / rename menu; TalkBack gets the same as custom actions. [tiltEnabled] is false while the
 * pager moves or for a page that isn't the settled one: the photo then eases flat. [expanded] (the Quick info
 * drawer) is held by the pager, which closes it once another page settles (D-A77). [quickInfoReveal] (1 at rest,
 * 0 a full page away) folds an open drawer in step with the swipe (D-A78); it is read in layout and draw only.
 */
@Composable
internal fun VehicleCard(
    summary: VehicleSummary,
    extras: VehicleExtras,
    dashboard: Dashboard,
    fmt: Formatter,
    tiltEnabled: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    quickInfoReveal: () -> Float = { 1f },
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

    // Opening the page, the card's own plate gives way to the arriving card's under it (D-A45).
    val plateOff = heroPlateOff()
    val reduced = rememberReducedMotion()
    GlassCard(modifier = Modifier.vehicleHero(vehicle.id).heroAnchor(anchor), glow = glow, elevated = !plateOff, plate = !plateOff) {
        HeroContent {
            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
                TiltPhotoFrame(
                    tilt = rememberCardTilt(followGyro = tiltEnabled),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(VEHICLE_PHOTO_ASPECT)
                        .vehicleCardSemantics(vehicle, cardActions, onOpen),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
                    ) {
                        VehiclePhoto(vehicle, Modifier.fillMaxSize().tiltLayer(TiltDepths.Photo), iconSize = 64.dp)
                        // Name over a bottom scrim, placed exactly as on the vehicle page (D-A45).
                        VehiclePhotoTitle(title, if (nickname != null) vehicle.displayName else null, titleMaxLines = 1, Modifier.align(Alignment.BottomStart), plate = vehicle.licensePlate)
                    }
                    VehicleCardMenu(vehicle, expanded = menuOpen, onDismiss = { menuOpen = false }, actions = cardActions, onOpen = onOpen)
                }
            }
            // D-A52: the plate is a badge on the photo now ([VehiclePhotoTitle]), not a line under it.
            // The mileage over the status chip on the left, "View all" and "Quick info" beside them (D-A41, user-set).
            VehicleCardFooter(
                expanded = expanded,
                onToggle = onToggleExpanded,
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
            // D-A78: while the pager moves, the open drawer folds by how far its page is from centre, so the card
            // (and the pager, and the sections below) shrink in step with the swipe. Read in layout/draw, no recomposition.
            AnimatedVisibility(
                expanded,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = quickInfoReveal()
                        clip = true
                    }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val reveal = quickInfoReveal()
                        val height = if (reveal >= 1f) placeable.height else (placeable.height * reveal).roundToInt()
                        // Bottom-anchored like shrinkVertically(): the drawer slides up under the footer.
                        layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
                    },
                enter = (expandVertically() + fadeIn()).orNone(reduced),
                exit = (shrinkVertically() + fadeOut()).orNone(reduced),
            ) {
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
}

/**
 * Which card keeps Quick info open once the pager settles on [settledId] (D-A77): the open one stays open only if it
 * is the one in view. The pager measures to its tallest composed page, so a drawer left open beside the view would
 * hold the sections below it down.
 */
internal fun quickInfoAfterSettle(openId: String?, settledId: String?): String? = openId?.takeIf { it == settledId }

/**
 * How much of an open Quick info drawer shows while the pager moves (D-A78), from its page's signed distance to the
 * centre in pages: 1 at rest, 0 a full page (or more) away, linear in between so it tracks the finger. With reduced
 * motion it snaps: open until the page is half-way out, then closed.
 */
internal fun quickInfoReveal(pageDistance: Float, reduced: Boolean): Float {
    val distance = abs(pageDistance)
    return if (reduced) {
        if (distance < 0.5f) 1f else 0f
    } else {
        (1f - distance).coerceIn(0f, 1f)
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
