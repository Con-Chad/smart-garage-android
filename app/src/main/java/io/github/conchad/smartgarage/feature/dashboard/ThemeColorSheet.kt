package io.github.conchad.smartgarage.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.data.AuroraChoice
import io.github.conchad.smartgarage.ui.components.DetailSheet
import io.github.conchad.smartgarage.ui.glass.AuroraColorSetting
import io.github.conchad.smartgarage.ui.glass.AuroraPresets
import io.github.conchad.smartgarage.ui.glass.GlassButton
import io.github.conchad.smartgarage.ui.glass.LocalAuroraChoices
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors
import kotlinx.coroutines.delay

/**
 * The fan's "Theme color" sheet (D-A57): the vehicle form's aurora override (D-A44) for the car in view, reachable
 * in two taps from Home. Same rules as the form: the switch on follows the car's color (no override when Settings
 * matches too, an explicit "match" when Settings picked a color); off shows [AuroraColorSetting]'s picker; "Use app
 * setting" drops the override. Changes apply live: switch and presets save at once, a wheel or slider drag is held
 * and saved once it rests [SAVE_DELAY_MS] (as in Settings), and whatever is pending is saved when the sheet closes.
 * The override is a phone-only preference, so view-only shared vehicles can use it too.
 */
@Composable
internal fun ThemeColorSheet(
    vehicleId: String,
    vehicleName: String,
    onSave: (AuroraChoice?) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = LocalAuroraChoices.current
    val saved = choices.overrides[vehicleId]
    val appAurora = choices.appDefault
    // Null draft: nothing pending. A pending "use app setting" is a draft whose choice is null.
    var draft by remember { mutableStateOf<Pending?>(null) }
    val override = draft.let { if (it != null) it.choice else saved }
    val effective = override ?: appAurora
    var lastColor by rememberSaveable {
        mutableStateOf((saved as? AuroraChoice.Custom)?.argb ?: (appAurora as? AuroraChoice.Custom)?.argb ?: AuroraPresets.first())
    }
    val save by rememberUpdatedState(onSave)

    LaunchedEffect(draft) {
        val pending = draft ?: return@LaunchedEffect
        if (pending.debounce) delay(SAVE_DELAY_MS)
        save(pending.choice)
    }
    // The draft stays on screen until the saved choice catches up, so the switch and picker never flick back.
    LaunchedEffect(saved) { draft?.let { if (it.choice == saved) draft = null } }
    // Closing mid-drag still keeps the last shade.
    DisposableEffect(Unit) { onDispose { draft?.let { save(it.choice) } } }

    DetailSheet(icon = Icons.Outlined.Palette, title = stringResource(R.string.theme_color_title), subtitle = vehicleName, onDismiss = onDismiss) {
        AuroraColorSetting(
            match = effective == AuroraChoice.MatchVehicle,
            onMatchChange = { on ->
                val next = when {
                    !on -> AuroraChoice.Custom(lastColor)
                    appAurora == AuroraChoice.MatchVehicle -> null
                    else -> AuroraChoice.MatchVehicle
                }
                draft = Pending(next, debounce = false)
            },
            color = Color((effective as? AuroraChoice.Custom)?.argb ?: lastColor),
            onColorChange = { c ->
                lastColor = c.toArgb()
                draft = Pending(AuroraChoice.Custom(c.toArgb()), debounce = true)
            },
            matchDescription = stringResource(
                when {
                    override == null && appAurora != AuroraChoice.MatchVehicle -> R.string.vehicle_aurora_follows_app
                    effective == AuroraChoice.MatchVehicle -> R.string.vehicle_aurora_match_desc
                    else -> R.string.vehicle_aurora_custom_desc
                },
            ),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (override != null) {
                TextButton(
                    onClick = {
                        (appAurora as? AuroraChoice.Custom)?.let { lastColor = it.argb }
                        draft = Pending(null, debounce = false)
                    },
                    colors = glassTextButtonColors(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.aurora_use_app_setting))
                }
            }
            GlassButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    }
}

/** A change waiting to be saved; [choice] null means "drop the override". */
private class Pending(val choice: AuroraChoice?, val debounce: Boolean)

private const val SAVE_DELAY_MS = 250L
