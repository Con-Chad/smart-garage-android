package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.ui.glass.TiltDepths
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.tiltLayer

/**
 * The vehicle's name over a bottom scrim on its photo, one placement for every photo card (Home, the Vehicles tab,
 * the vehicle page): the Home card and the page's card cross-fade over each other at the end of the hero glide, so
 * their titles must sit on the same pixels or the name shows doubled (D-A45). White on the ≥ 55 % black scrim
 * clears 4.5:1. [subtitle] is the server name under a nickname. Align it with [modifier] (bottom-start).
 */
@Composable
internal fun VehiclePhotoTitle(title: String, subtitle: String?, titleMaxLines: Int, modifier: Modifier = Modifier) {
    val tokens = glass
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(tokens.photoScrim))
            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
    ) {
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
}
