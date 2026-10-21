package io.github.conchad.smartgarage.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.conchad.smartgarage.core.data.AppearanceSettings
import io.github.conchad.smartgarage.core.data.ThemeMode

@Composable
fun SmartGarageTheme(
    appearance: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val dark = when (appearance.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    // minSdk 31 guarantees dynamic color is available.
    val colors = when {
        appearance.dynamicColor && dark -> dynamicDarkColorScheme(context)
        appearance.dynamicColor -> dynamicLightColorScheme(context)
        dark -> BrandDarkColors
        else -> BrandLightColors
    }
    // enableEdgeToEdge picks bar icon colors from the system theme; follow the in-app choice instead.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatusColors else LightStatusColors) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
