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
    /**
     * The veil under an open fan menu (D-A60): the page colour at 72 %, a step denser than [veil]'s 60 % so the
     * captions stand off the content behind them, still letting the page show through.
     */
    val fanScrim: Color,

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
    /** The gold accent on glass: pill labels, icons, focus rings (`--tte03-sheen`). */
    val sheen: Color,
    /**
     * The band that sweeps across animated metallic titles and its centre highlight (D-A38): a muted, faintly cool
     * silver, no longer the gold [sheen] (`--tte03-sheen-hi` was the old gold highlight).
     */
    val sheenBand: Color,
    val sheenHighlight: Color,

    /** Chrome frame around the vehicle photo (`conic-gradient` of the foil chip) and its glow. */
    val frameChrome: List<Color>,
    val frameGlow: Color,

    /**
     * The tilt-parallax plate (D-A30): the plate glow's opacity (the glow itself is the aurora / vehicle tint) and the
     * halo behind text that sits directly on the aurora. The pointer-following shine over the photo went with D-A46.
     */
    val plateGlowAlpha: Float,
    val textHalo: Color,

    /**
     * Bottom bar: pill surface, soft border, blob gradient and its glow (`--c09`). Since D-A36 the surface is the
     * cards' glass — the panel tint, a little denser (~72 %) so the inactive icons keep ≥ 3:1 over a bright aurora
     * or a pale car's glow — and the border is the panel's 1 dp edge.
     */
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
    /**
     * The dark ink for an opaque fill that came out light (the fan FAB on a pale car, D-A31), the counterpart of
     * [onPhoto]; also shared by both sets, since the fill it sits on is the car's colour, not the theme's.
     */
    val onPale: Color,
    /**
     * The dark ink on the aurora, for text and pills alike (D-A49, D-A70): #1B2130 (L 0.015), the light theme's text
     * colour, so text that turns dark on a pale aurora is the same dark as the cards' text. D-A49's softer #474B54
     * (L 0.070) sat at 1.4–2:1 on mid-tone auroras (steel, yellow on dark); this one meets the light ink at
     * [AdaptiveInk.CENTER] (≈ 3.9:1 there, the halo making up the rest) and clears 4.5:1 from L 0.25 up. Shared by both sets.
     */
    val inkDark: Color,
    /**
     * The halo under adaptive ink that falls short of 4.5:1 on its backdrop (D-A52): [inkHaloDark] behind light ink,
     * [inkHaloLight] behind dark ink, blended with the ink's own mix and scaled by the shortfall. Shared by both sets.
     */
    val inkHaloDark: Color,
    val inkHaloLight: Color,

    /**
     * Frosted glass on a glass card (D-A52): the quick-action buttons. Denser than [panel], so the buttons read as a
     * second sheet sitting on the card rather than holes in it; [textPrimary] on it clears 4.5:1 whatever the aurora.
     */
    val frost: Color,
    /**
     * The bevelled rim of raised glass (D-A52, the owner pill and the frosted buttons): light along the top edge,
     * shade along the bottom, so the edge reads as a lit, three-dimensional lip.
     */
    val rimLight: Color,
    val rimShade: Color,

    /**
     * The licence-plate badge on a vehicle photo (D-A55), shared by both sets since it sits on a photo: a light
     * [plateFace] with a soft blue outer outline ([plateRim]), a thin blue band along the top ([plateBand]) and the
     * characters and thin inner border in [plateInk] (≥ 14:1 on the face).
     */
    val plateFace: Color,
    val plateRim: Color,
    val plateBand: Color,
    val plateInk: Color,
    /**
     * The stronger unfocused outline for the emphasized form fields (D-A62, the Add vehicle form): ≥ 3:1 against the
     * panel over the page background, where [panelBorder] is a faint hairline. Focused fields keep the [sheen].
     */
    val fieldBorderStrong: Color,
    /**
     * The frosted fill of a glass form field at rest (D-A69, the Add vehicle form): a faint frost on the card's glass so
     * the field reads as a soft well rather than a hollow box. Focused, and while its suggestion list is attached
     * below it, the field takes [frost] instead, the same surface as the list.
     */
    val fieldFill: Color,
) {
    val radiusMedium: Dp get() = 14.dp
    val radiusLarge: Dp get() = 22.dp

    /** The corner of a glass form field and of the suggestion list joined to it (D-A69). */
    val radiusField: Dp get() = 12.dp

    /**
     * The band at the top of a page's scrolling body over which content fades out under the top bar (D-A76,
     * [topFadeEdge]): transparent at the mask's top edge, opaque this far below it, on a smoothstep ramp.
     */
    val contentFade: Dp get() = 72.dp

    /**
     * How far a scroll container directly under the top bar reaches up under it (D-A76), so the fade's transparent
     * edge sits just under the bar's title instead of at the bar's bottom; matched by as much top content padding.
     */
    val contentFadeLift: Dp get() = 16.dp
}

// Shared by both sets: a photo is neither light nor dark themed.
private val PhotoScrim = listOf(Color.Transparent, Color(0xB3000000), Color(0xD9000000))
private val OnPhoto = Color(0xFFF4F6FB)
private val OnPhotoSecondary = Color(0xFFC7CCDB)
private val OnPale = Color(0xFF1B2130)
private val InkDark = Color(0xFF1B2130)
private val InkHaloDark = Color(0xFF000000)
private val InkHaloLight = Color(0xFFFFFFFF)
private val PlateFace = Color(0xFFF7F8FA)
private val PlateRim = Color(0xFF8DB4E8)
private val PlateBand = Color(0xFF3F6FB8)
private val PlateInk = Color(0xFF16203A)

val DarkGlassTokens = GlassTokens(
    dark = true,
    pageBackground = Color(0xFF08090D),
    auroraAlpha = 0.85f,
    auroraBlend = BlendMode.Screen,
    veil = Color(0x9908090D),
    fanScrim = Color(0xB808090D),
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
    sheenBand = Color(0xFFDDE1E8),
    sheenHighlight = Color(0xFFFFFFFF),
    frameChrome = listOf(Color(0xFFD8D097), Color(0xFFEBD5A2), Color(0xFFBCB6A9), Color(0xFFD8D097)),
    frameGlow = Color(0xB3EBD5A2),
    plateGlowAlpha = 0.22f,
    textHalo = Color(0xB308090D),
    navSurface = Color(0xB8141622),
    navBorder = Color(0x29FFFFFF),
    navBlobStart = Color(0xFFDA3E8C),
    navBlobEnd = Color(0xFFC0B459),
    navGlow = Color(0x80C0B459),
    navInactive = Color(0xFFA3AABF),
    onAccent = Color(0xFFFFFFFF),
    photoScrim = PhotoScrim,
    onPhoto = OnPhoto,
    onPhotoSecondary = OnPhotoSecondary,
    onPale = OnPale,
    inkDark = InkDark,
    inkHaloDark = InkHaloDark,
    inkHaloLight = InkHaloLight,
    frost = Color(0xC7262A3A),
    rimLight = Color(0x99FFFFFF),
    rimShade = Color(0x73000000),
    plateFace = PlateFace,
    plateRim = PlateRim,
    plateBand = PlateBand,
    plateInk = PlateInk,
    fieldBorderStrong = Color(0x99FFFFFF),
    fieldFill = Color(0x14FFFFFF),
)

val LightGlassTokens = GlassTokens(
    dark = false,
    pageBackground = Color(0xFFEEF1F6),
    auroraAlpha = 0.45f,
    auroraBlend = BlendMode.SrcOver,
    veil = Color(0x99F2F4F8),
    fanScrim = Color(0xB8F2F4F8),
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
    sheenBand = Color(0xFF7E8594),
    sheenHighlight = Color(0xFFA4ABB8),
    frameChrome = listOf(Color(0xFFB08726), Color(0xFFD8C27A), Color(0xFF948A75), Color(0xFFB08726)),
    frameGlow = Color(0x738B6A1E),
    plateGlowAlpha = 0.14f,
    textHalo = Color(0xB3FFFFFF),
    navSurface = Color(0xB8FFFFFF),
    navBorder = Color(0x24141826),
    navBlobStart = Color(0xFFCD277A),
    navBlobEnd = Color(0xFF776E2D),
    navGlow = Color(0x4D776E2D),
    navInactive = Color(0xFF5B6178),
    onAccent = Color(0xFFFFFFFF),
    photoScrim = PhotoScrim,
    onPhoto = OnPhoto,
    onPhotoSecondary = OnPhotoSecondary,
    onPale = OnPale,
    inkDark = InkDark,
    inkHaloDark = InkHaloDark,
    inkHaloLight = InkHaloLight,
    frost = Color(0xD9FFFFFF),
    rimLight = Color(0xFFFFFFFF),
    rimShade = Color(0x47141826),
    plateFace = PlateFace,
    plateRim = PlateRim,
    plateBand = PlateBand,
    plateInk = PlateInk,
    fieldBorderStrong = Color(0x8C141826),
    fieldFill = Color(0x73FFFFFF),
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

/**
 * The preset swatches of the aurora color picker (D-A44), in hue order with the steel last. Packed ARGB; the
 * names are string resources in the picker. Close to [VehicleTint]'s color words, so a preset looks like a car.
 */
val AuroraPresets: List<Int> = listOf(
    0xFFD0302C, 0xFFE87A2A, 0xFFD4AF37, 0xFFE8C62A, 0xFF2E9A55, 0xFF1F8A8A,
    0xFF7CB8E8, 0xFF2F6FD6, 0xFF1F3A7A, 0xFF7A3AB8, 0xFFD03A8C, 0xFF9FB3C8,
).map { it.toInt() }

/**
 * Car paint swatches for the vehicle form's colour picker (D-A52), packed ARGB: what the paint looks like, not the
 * aurora tint [VehicleTint] derives from its name (which maps neutrals to steel).
 */
object CarPaints {
    const val BLACK: Int = 0xFF141518.toInt()
    const val WHITE: Int = 0xFFF2F2EE.toInt()
    const val SILVER: Int = 0xFFC3C7CC.toInt()
    const val GREY: Int = 0xFF6D7278.toInt()
    const val BLUE: Int = 0xFF2F5FB8.toInt()
    const val RED: Int = 0xFFB8202A.toInt()
    const val GREEN: Int = 0xFF2E7D4A.toInt()
    const val YELLOW: Int = 0xFFE8C62A.toInt()
    const val ORANGE: Int = 0xFFE87A2A.toInt()
    const val BROWN: Int = 0xFF6E4A2E.toInt()
    const val BEIGE: Int = 0xFFD2BD9A.toInt()
    const val GOLD: Int = 0xFFC9A43A.toInt()
    const val PURPLE: Int = 0xFF6A3A9A.toInt()
}
