package io.github.conchad.smartgarage.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Due-status colors, the same meaning as the web's red / amber / green badges. Not part of the M3 scheme. */
@Immutable
data class StatusColors(
    val overdue: Color,
    val onOverdue: Color,
    val overdueContainer: Color,
    val onOverdueContainer: Color,
    val dueSoon: Color,
    val dueSoonContainer: Color,
    val onDueSoonContainer: Color,
    val ok: Color,
    val okContainer: Color,
    val onOkContainer: Color,
)

val DarkStatusColors = StatusColors(
    overdue = Color(0xFFFFB4AB),
    onOverdue = Color(0xFF690005),
    overdueContainer = Color(0xFF5C1A17),
    onOverdueContainer = Color(0xFFFFDAD6),
    dueSoon = Color(0xFFF5C451),
    dueSoonContainer = Color(0xFF4A3800),
    onDueSoonContainer = Color(0xFFFFDF9E),
    ok = Color(0xFF8BD8A0),
    okContainer = Color(0xFF12391F),
    onOkContainer = Color(0xFFA6F4BA),
)

val LightStatusColors = StatusColors(
    overdue = Color(0xFFBA1A1A),
    onOverdue = Color(0xFFFFFFFF),
    overdueContainer = Color(0xFFFFDAD6),
    onOverdueContainer = Color(0xFF410002),
    dueSoon = Color(0xFF7A5900),
    dueSoonContainer = Color(0xFFFFDF9E),
    onDueSoonContainer = Color(0xFF261A00),
    ok = Color(0xFF1B6D36),
    okContainer = Color(0xFFA6F4BA),
    onOkContainer = Color(0xFF00210B),
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatusColors }
