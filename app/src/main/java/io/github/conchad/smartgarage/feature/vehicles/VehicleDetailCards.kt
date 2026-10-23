package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.VehicleDetail
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.VehiclePhoto
import io.github.conchad.smartgarage.ui.components.VehicleStatusChip
import io.github.conchad.smartgarage.ui.glass.GlassCard
import io.github.conchad.smartgarage.ui.glass.HeroContent
import io.github.conchad.smartgarage.ui.glass.VehicleTint
import io.github.conchad.smartgarage.ui.glass.bevelFill
import io.github.conchad.smartgarage.ui.glass.bevelRim
import io.github.conchad.smartgarage.ui.glass.glassShadow
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.TiltDepths
import io.github.conchad.smartgarage.ui.glass.TiltPhotoFrame
import io.github.conchad.smartgarage.ui.glass.staticMetallicBrush
import io.github.conchad.smartgarage.ui.glass.tiltLayer
import io.github.conchad.smartgarage.ui.links.ICON_SIZE
import io.github.conchad.smartgarage.ui.links.rememberInsurerIcon

/**
 * The photo card (note 13, D-A30, D-A31): a flat glass card holding the chrome-framed photo, 16:9, with the
 * vehicle's name bottom-left over the shared photo scrim. Only the frame tilts toward the gyroscope and shines; the
 * photo sits behind it and the name floats above it. The name is the device-local nickname when there is one, with
 * the server's name under it, as on the home card. Tap or hold for the photo / rename menu (note 10); TalkBack gets
 * the same as custom actions.
 */
@Composable
internal fun PhotoCard(vehicle: Vehicle, cardActions: VehicleCardActions, modifier: Modifier = Modifier) {
    val tokens = glass
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val nickname = cardActions.nicknameOf(vehicle)
    val menuLabel = stringResource(R.string.vehicle_photo_options)
    GlassCard(modifier = modifier, glow = VehicleTint.color(vehicle.color, MaterialTheme.colorScheme.primary)) {
        HeroContent {
            Box(Modifier.padding(12.dp)) {
                TiltPhotoFrame(
                    cornerRadius = tokens.radiusMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .vehicleCardSemantics(vehicle, cardActions, onOpen = null),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .combinedClickable(onClick = { menuOpen = true }, onClickLabel = menuLabel, onLongClick = { menuOpen = true }),
                    ) {
                        VehiclePhoto(vehicle, Modifier.fillMaxSize().tiltLayer(TiltDepths.Photo), iconSize = 72.dp)
                        VehiclePhotoTitle(cardActions.titleOf(vehicle), if (nickname != null) vehicle.displayName else null, titleMaxLines = 2, Modifier.align(Alignment.BottomStart), plate = vehicle.licensePlate)
                    }
                    VehicleCardMenu(vehicle, expanded = menuOpen, onDismiss = { menuOpen = false }, actions = cardActions)
                }
            }
        }
    }
}

/**
 * "Owner: Connor" under the photo (note 14) with the service status chips on the right; the owner line is one TalkBack
 * item. It sits on a frosted panel pill (the same fill as the cards) rather than straight on the aurora, because the
 * aurora's bright spot often lands right under the photo and bare text vanished there in dark theme.
 */
@Composable
internal fun OwnerLine(ownerName: String?, shared: Boolean, detail: VehicleDetail, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null) {
    val tokens = glass
    val haptics = LocalHapticFeedback.current
    val longPressLabel = stringResource(R.string.action_edit_owner_name)
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f, fill = false)
                // D-A52: raised glass, so it stands off the aurora: a soft lift shadow, the panel fill under a top
                // gloss, and a 1.5 dp bevelled rim (lit top edge, shaded bottom edge).
                .glassShadow(CircleShape, elevation = 6.dp, color = tokens.shadow)
                .clip(CircleShape)
                .bevelFill(tokens, tokens.panel)
                .bevelRim(tokens, CircleShape)
                // D-A43: a long-press edits the name shown here (local only); a tap does nothing, as before.
                .then(
                    if (onLongPress == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(onLongPress) {
                            detectTapGestures(onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPress()
                            })
                        }
                    },
                )
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                .semantics(mergeDescendants = true) {
                    if (onLongPress != null) {
                        onLongClick(longPressLabel) {
                            onLongPress()
                            true
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (shared) Icons.Outlined.People else Icons.Outlined.Person,
                contentDescription = null,
                tint = tokens.sheen,
                modifier = Modifier.padding(end = 8.dp).size(22.dp),
            )
            Text(
                stringResource(R.string.vehicle_owner, ownerName ?: "—"),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        VehicleStatusChip(detail.summary, Modifier.padding(start = 8.dp))
    }
}

/** One "At a glance" tile: icon, label, value, and for the insurer the installed app's launcher icon (D-A31). */
private class GlanceTile(val icon: ImageVector, val label: String, val value: String, val badge: ImageBitmap? = null)

/**
 * The figures wanted at a glance (note 14): mileage, the insurer in force and this year's money, two by two (the
 * last one full width). D-A52: the plate moved onto the photo card as a badge. Each tile is one TalkBack item ("Mileage, 123,456 km").
 */
@Composable
internal fun ImmediateInfo(detail: VehicleDetail, fmt: Formatter) {
    val vehicle = detail.vehicle
    val tiles = listOf(
        GlanceTile(Icons.Outlined.Speed, stringResource(R.string.vehicle_mileage), fmt.distance(vehicle.currentOdometerKm)),
        GlanceTile(Icons.Outlined.Shield, stringResource(R.string.vehicle_insurance_plan), detail.currentPolicy?.provider ?: "—", rememberInsurerIcon(detail.currentPolicy?.provider)),
        // The sum is fuel + service + expenses, each stored in the server's preferred currency (D-A20), so it is
        // labelled with that currency, not the vehicle's purchaseCurrency, which the server may default differently.
        GlanceTile(Icons.Outlined.Payments, stringResource(R.string.vehicle_spent_this_year), fmt.money(detail.spentThisYearCents)),
    )
    Column(Modifier.padding(vertical = 4.dp)) {
        tiles.chunked(2).forEachIndexed { index, pair ->
            if (index > 0) RowDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { tile -> InfoTile(tile, Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One figure straight on the card's frosted surface (D-A32: the small glass tile around it is gone), icon and label
 * above the value. The value shrinks before it wraps so a long plate or sum stays readable.
 */
@Composable
private fun InfoTile(tile: GlanceTile, modifier: Modifier = Modifier) {
    val tokens = glass
    Column(
        modifier.padding(horizontal = 12.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(tile.icon, contentDescription = null, tint = tokens.sheen, modifier = Modifier.size(18.dp))
            Text(
                tile.label,
                style = MaterialTheme.typography.labelMedium.copy(hyphens = Hyphens.Auto),
                color = tokens.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        val style = MaterialTheme.typography.titleMedium.copy(brush = staticMetallicBrush(tokens))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tile.badge != null) {
                Image(tile.badge, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(ICON_SIZE))
            }
            BasicText(
                tile.value,
                style = style,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = style.fontSize),
            )
        }
    }
}
