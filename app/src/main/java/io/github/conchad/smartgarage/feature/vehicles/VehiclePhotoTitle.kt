package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.glass.TiltDepths
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.tiltLayer

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
 * A number plate in miniature (D-A52): the light plate colour ([io.github.conchad.smartgarage.ui.glass.GlassTokens.onPhoto])
 * with a dark rim and dark, slightly spaced bold characters ([io.github.conchad.smartgarage.ui.glass.GlassTokens.onPale],
 * ≥ 14:1), and a small shadow, so it reads over any photo, light or dark. Long plates ellipsize at 50 % of a phone's
 * card width. TalkBack reads "Licence plate, AB 123".
 */
@Composable
internal fun PlateBadge(plate: String, modifier: Modifier = Modifier) {
    val tokens = glass
    val shape = RoundedCornerShape(4.dp)
    val description = stringResource(R.string.field_plate) + ", " + plate
    Text(
        plate.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        color = tokens.onPale,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 160.dp)
            .shadow(3.dp, shape)
            .background(tokens.onPhoto, shape)
            .border(1.5.dp, tokens.onPale, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clearAndSetSemantics { contentDescription = description },
    )
}
