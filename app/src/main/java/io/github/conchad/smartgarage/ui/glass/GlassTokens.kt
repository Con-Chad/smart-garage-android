package io.github.conchad.smartgarage.ui.glass

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design tokens for the glass look (D-A24): the aurora page background, frosted panels, metallic text, the chrome
 * photo frame and the morphing bottom bar. Dark values come from the user's reference snippets; the light set is
 * their `[data-theme="light"]` block. Chosen from the theme in [io.github.conchad.smartgarage.ui.theme.SmartGarageTheme],
 * never from `isSystemInDarkTheme` alone, so the Light / Dark / System setting and dynamic color both apply.
 */
@Immutable
data class GlassTokens(
    val dark: Boolean,
    /** Page background under the aurora (`--bg-900` / `--c02-page-a`). */
    val pageBackground: Color,
    /** Per-ribbon opacity multiplier; light mode keeps the ribbons quiet so text on them stays readable. */
    val auroraAlpha: Float,
    /** Screen on dark, as the snippet; normal on light where screen would wash out to white. */
    val auroraBlend: BlendMode,
    /** The bottom veil's end color (`rgba(bg,.6)` at 100 %). */
    val veil: Color,

    /** Frosted panel fill, border and inset top highlight (`--c02-panel-*`). */
    val panel: Color,
    val panelBorder: Color,
    val panelHighlight: Color,
    /** The small pill inside a panel (`--c02-pill-*`). */
    val pill: Color,
    val pillBorder: Color,
    val shadow: Color,

    /** Text on glass (`--text-0` / `--text-1`); both ≥ 4.5:1 on the panel over the page background. */
    val textPrimary: Color,
    val textSecondary: Color,

    /** Base metal for text: light steel on dark, dark steel on light, so body copy stays legible (see [MetallicText]). */
    val metalDim: Color,
    val metalMid: Color,
    /** The gold sheen band and its highlight (`--tte03-sheen`, `--tte03-sheen-hi`). */
    val sheen: Color,
    val sheenHighlight: Color,

    /** Chrome frame around the vehicle photo (`conic-gradient` of the foil chip) and its glow. */
    val frameChrome: List<Color>,
    val frameGlow: Color,

    /**
     * The tilt-parallax plate (D-A30): the plate glow's opacity (the glow itself is the aurora / vehicle tint), the
     * pointer-following shine and how it blends (screen on dark, plain over on light where screen does nothing on
     * a near-white panel), and the halo behind text that sits directly on the aurora.
     */
    val plateGlowAlpha: Float,
    val shine: Color,
    val shineBlend: BlendMode,
    val textHalo: Color,

    /** Bottom bar: pill surface, soft border, blob gradient and its glow (`--c09`). */
    val navSurface: Color,
    val navBorder: Color,
    val navBlobStart: Color,
    val navBlobEnd: Color,
    val navGlow: Color,
    val navInactive: Color,
    val onAccent: Color,

    /**
     * The bottom scrim under a vehicle's name inside its photo (home card, vehicles list, vehicle page), transparent
     * → dark, and the fixed light pair on it. These sit on a photo, not on the theme, so both token sets share them:
     * white on ≥ 55 % black clears 4.5:1 whatever the picture.
     */
    val photoScrim: List<Color>,
    val onPhoto: Color,
    val onPhotoSecondary: Color,
) {
    val radiusMedium: Dp get() = 14.dp
    val radiusLarge: Dp get() = 22.dp
}

// Shared by both sets: a photo is neither light nor dark themed.
private val PhotoScrim = listOf(Color.Transparent, Color(0xB3000000), Color(0xD9000000))
private val OnPhoto = Color(0xFFF4F6FB)
private val OnPhotoSecondary = Color(0xFFC7CCDB)

val DarkGlassTokens = GlassTokens(
    dark = true,
    pageBackground = Color(0xFF08090D),
    auroraAlpha = 0.85f,
    auroraBlend = BlendMode.Screen,
    veil = Color(0x9908090D),
    panel = Color(0x6B141622),
    panelBorder = Color(0x29FFFFFF),
    panelHighlight = Color(0x38FFFFFF),
    pill = Color(0x1FFFFFFF),
    pillBorder = Color(0x2EFFFFFF),
    shadow = Color(0xE6000000),
    textPrimary = Color(0xFFF4F6FB),
    textSecondary = Color(0xFFC7CCDB),
    metalDim = Color(0xFFC7CCDB),
    metalMid = Color(0xFFF4F6FB),
    sheen = Color(0xFFF5D76E),
    sheenHighlight = Color(0xFFFFF6D6),
    frameChrome = listOf(Color(0xFFD8D097), Color(0xFFEBD5A2), Color(0xFFBCB6A9), Color(0xFFD8D097)),
    frameGlow = Color(0xB3EBD5A2),
    plateGlowAlpha = 0.22f,
    shine = Color(0x47FFFFFF),
    shineBlend = BlendMode.Screen,
    textHalo = Color(0xB308090D),
    navSurface = Color(0xFF1C2030),
    navBorder = Color(0x12FFFFFF),
    navBlobStart = Color(0xFFDA3E8C),
    navBlobEnd = Color(0xFFC0B459),
    navGlow = Color(0x80C0B459),
    navInactive = Color(0xFF8A91A8),
    onAccent = Color(0xFFFFFFFF),
    photoScrim = PhotoScrim,
    onPhoto = OnPhoto,
    onPhotoSecondary = OnPhotoSecondary,
)

val LightGlassTokens = GlassTokens(
    dark = false,
    pageBackground = Color(0xFFEEF1F6),
    auroraAlpha = 0.45f,
    auroraBlend = BlendMode.SrcOver,
    veil = Color(0x99F2F4F8),
    panel = Color(0x8CFFFFFF),
    panelBorder = Color(0x24141826),
    panelHighlight = Color(0xA6FFFFFF),
    pill = Color(0x14141826),
    pillBorder = Color(0x29141826),
    shadow = Color(0x47141826),
    textPrimary = Color(0xFF1B2130),
    textSecondary = Color(0xFF4F5B80),
    metalDim = Color(0xFF2C2F3D),
    metalMid = Color(0xFF4A4E60),
    sheen = Color(0xFFAC880C),
    sheenHighlight = Color(0xFFC9A247),
    frameChrome = listOf(Color(0xFFB08726), Color(0xFFD8C27A), Color(0xFF948A75), Color(0xFFB08726)),
    frameGlow = Color(0x738B6A1E),
    plateGlowAlpha = 0.14f,
    shine = Color(0x8CFFFFFF),
    shineBlend = BlendMode.SrcOver,
    textHalo = Color(0xB3FFFFFF),
    navSurface = Color(0xFFFFFFFF),
    navBorder = Color(0x14000000),
    navBlobStart = Color(0xFFCD277A),
    navBlobEnd = Color(0xFF776E2D),
    navGlow = Color(0x4D776E2D),
    navInactive = Color(0xFF5B6178),
    onAccent = Color(0xFFFFFFFF),
    photoScrim = PhotoScrim,
    onPhoto = OnPhoto,
    onPhotoSecondary = OnPhotoSecondary,
)

val LocalGlassTokens = staticCompositionLocalOf { DarkGlassTokens }

/**
 * The tokens for the resolved theme. Light / Dark / System pick the set; with dynamic color on, the bottom bar's
 * blob takes the wallpaper's primary → tertiary instead of the snippet's magenta → gold, so the one saturated
 * accent on screen agrees with the rest of the Material You palette. Everything else (glass, aurora, metal) is
 * neutral and stays as designed.
 */
fun glassTokensFor(dark: Boolean, scheme: ColorScheme, dynamicColor: Boolean): GlassTokens {
    val base = if (dark) DarkGlassTokens else LightGlassTokens
    if (!dynamicColor) return base
    return base.copy(
        navBlobStart = scheme.primary,
        navBlobEnd = scheme.tertiary,
        navGlow = scheme.primary.copy(alpha = if (dark) 0.5f else 0.3f),
        onAccent = scheme.onPrimary,
    )
}

/** The current theme's glass tokens; shorthand for `LocalGlassTokens.current`. */
val glass: GlassTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalGlassTokens.current
