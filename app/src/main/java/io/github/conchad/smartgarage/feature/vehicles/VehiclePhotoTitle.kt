package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.glass.TiltDepths
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.tiltLayer
import io.github.conchad.smartgarage.ui.theme.PlateTextStyle

/**
 * The vehicle's name over a bottom scrim on its photo, one placement for every photo card (Home, the Vehicles tab,
 * the vehicle page): the Home card and the page's card cross-fade over each other at the end of the hero glide, so
 * their titles must sit on the same pixels or the name shows doubled (D-A45). White on the ≥ 55 % black scrim
 * clears 4.5:1. [subtitle] is the server name under a nickname. Align it with [modifier] (bottom-start).
 *
 * D-A52: [plate], when set, sits in the photo's bottom-right corner as a small number-plate badge beside the name
 * (the name wraps or ellipsizes before it), in the same place on every card so the hero cross-fade lines up.
 */
@Composable
internal fun VehiclePhotoTitle(title: String, subtitle: String?, titleMaxLines: Int, modifier: Modifier = Modifier, plate: String? = null) {
    val tokens = glass
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(tokens.photoScrim))
            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = tokens.onPhoto,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.tiltLayer(TiltDepths.Title),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.onPhotoSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.tiltLayer(TiltDepths.Subtitle),
                )
            }
        }
        plate?.takeIf { it.isNotBlank() }?.let {
            PlateBadge(it, Modifier.padding(start = 8.dp, bottom = 2.dp).tiltLayer(TiltDepths.Subtitle))
        }
    }
}

/**
 * A number plate in miniature (D-A52, redrawn as a real plate in D-A55): a light plate face
 * ([io.github.conchad.smartgarage.ui.glass.GlassTokens.plateFace]) inside a soft blue 1.5 dp outline
 * ([io.github.conchad.smartgarage.ui.glass.GlassTokens.plateRim]), a thin dark inner border inset from it, a slim blue
 * band along the top, and the characters in the bold condensed [PlateTextStyle] in the plate ink (≥ 14:1 on the
 * face). At least [PLATE_MIN_WIDTH] wide, so a short plate keeps roughly the 2:1 shape of a plate; long plates
 * ellipsize at [PLATE_MAX_WIDTH]. A small shadow lifts it off any photo, light or dark. TalkBack reads
 * "Licence plate, AB 123".
 */
@Composable
internal fun PlateBadge(plate: String, modifier: Modifier = Modifier) {
    val tokens = glass
    val outer = RoundedCornerShape(5.dp)
    val inner = RoundedCornerShape(3.dp)
    val description = stringResource(R.string.field_plate) + ", " + plate
    Box(
        modifier
            .widthIn(min = PLATE_MIN_WIDTH, max = PLATE_MAX_WIDTH)
            .shadow(4.dp, outer)
            .background(tokens.plateFace, outer)
            .border(1.5.dp, tokens.plateRim, outer)
            .padding(3.dp)
            .clip(inner)
            .drawBehind { drawRect(tokens.plateBand, size = Size(size.width, PLATE_BAND.toPx())) }
            .border(0.75.dp, tokens.plateInk.copy(alpha = PLATE_INNER_BORDER_ALPHA), inner)
            .padding(start = 6.dp, end = 6.dp, top = PLATE_BAND + 3.dp, bottom = 3.dp)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            plate.uppercase(),
            style = PlateTextStyle,
            color = tokens.plateInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The plate's narrowest: with the badge's ~31 dp height at font scale 1, about the 2:1 of a plate. */
private val PLATE_MIN_WIDTH = 64.dp

/** Long plates ellipsize here, about half a phone card's width. */
private val PLATE_MAX_WIDTH = 160.dp

/** The blue band along the top of the plate face. */
private val PLATE_BAND = 4.dp

/** The thin inner border in the plate ink, softened. */
private const val PLATE_INNER_BORDER_ALPHA = 0.6f
