package io.github.conchad.smartgarage.ui.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The chrome frame around a vehicle photo (D-A24): a 3 dp sweep gradient of the foil chip colors with a soft gold
 * glow, content clipped inside. The holographic sheen band, the grain and the frame's own 3D tilt went with D-A30:
 * the card that holds the frame now tilts and shines as a whole ([TiltParallaxCard]), and the photo inside floats
 * as a [TiltLayer].
 */
@Composable
fun MetallicFrame(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = glass.radiusLarge,
    borderWidth: Dp = 3.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = glass
    val outer = RoundedCornerShape(cornerRadius)
    val inner = RoundedCornerShape(cornerRadius - borderWidth)
    Box(
        modifier
            .shadow(8.dp, outer, clip = false, ambientColor = tokens.frameGlow, spotColor = tokens.frameGlow)
            .clip(outer)
            .border(borderWidth, Brush.sweepGradient(tokens.frameChrome), outer),
    ) {
        Box(Modifier.padding(borderWidth).clip(inner), content = content)
    }
}
