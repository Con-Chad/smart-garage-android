package io.github.conchad.smartgarage.ui.theme

import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val Base = Typography()

/** The system font (Roboto Flex on Pixels), with slightly heavier headings for scannable cards. */
val AppTypography = Base.copy(
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

/**
 * The licence-plate characters (D-A55): the platform's condensed sans (`sans-serif-condensed`, Roboto Condensed on
 * every Android device, nothing bundled) in bold, set tight with the font padding trimmed, so a short plate reads
 * like the stamped, narrow face of a real plate. Sizes in sp, so it follows the user's font scale.
 */
val PlateTextStyle = TextStyle(
    fontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD)),
    fontWeight = FontWeight.Bold,
    fontSize = 16.sp,
    lineHeight = 17.sp,
    letterSpacing = 0.6.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)
