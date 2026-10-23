package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.relativeTime
import io.github.conchad.smartgarage.ui.sync.SyncInfo

/**
 * Whether the home screen shows the "can't reach the server" popup or only its collapsed refresh icon (D-A24).
 * Dismissal lasts for one outage: a successful sync clears it, so the popup comes back if the server drops again.
 * Shown for [SyncInfo.stale] (the last sync failed after an earlier success, offline or a server error); the
 * never-synced case has the full-screen `CantReachState`, and rejected writes stay with `WriteQueueBanner`.
 */
class DisconnectedState internal constructor(val sync: SyncInfo, private val dismissed: Boolean, private val setDismissed: (Boolean) -> Unit) {
    val unreachable: Boolean get() = sync.stale
    val showPopup: Boolean get() = unreachable && !dismissed
    val showIcon: Boolean get() = unreachable && dismissed

    fun dismiss() = setDismissed(true)
}

@Composable
fun rememberDisconnectedState(sync: SyncInfo): DisconnectedState {
    var dismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sync.stale) { if (!sync.stale) dismissed = false }
    return DisconnectedState(sync, dismissed) { dismissed = it }
}

/**
 * Glass popup near the top of the screen: "Can't reach <server>", with Retry and Dismiss. Dismissing shrinks it
 * towards the top-right, where [RefreshIcon] appears in the top bar.
 */
@Composable
fun DisconnectedPopup(state: DisconnectedState, serverName: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = glass
    val reduced = rememberReducedMotion()
    AnimatedVisibility(
        visible = state.showPopup,
        enter = (fadeIn(tween(Motion.SHORT_MS)) + slideInVertically(tween(Motion.MEDIUM_MS)) { -it / 2 }).orNone(reduced),
        exit = (
            fadeOut(tween(Motion.MEDIUM_MS)) +
                scaleOut(tween(Motion.HERO_MS), targetScale = 0.15f, transformOrigin = TransformOrigin(1f, 0f)) +
                shrinkOut(tween(Motion.HERO_MS), shrinkTowards = Alignment.TopEnd)
            ).orNone(reduced),
        modifier = modifier,
    ) {
        GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentPadding = 0.dp) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = tokens.textSecondary, modifier = Modifier.size(22.dp))
                Column(
                    Modifier.weight(1f).padding(start = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MetallicText(
                        stringResource(R.string.disconnected_title, serverName),
                        style = MaterialTheme.typography.titleSmall,
                        animated = true,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val last = state.sync.lastSyncedAt?.let { relativeTime(it) } ?: stringResource(R.string.time_just_now)
                    Text(
                        stringResource(R.string.disconnected_body, last),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = state::dismiss, colors = ButtonDefaults.textButtonColors(contentColor = tokens.textSecondary)) {
                    Text(stringResource(R.string.action_dismiss))
                }
                TextButton(onClick = onRetry, enabled = !state.sync.status.running, colors = ButtonDefaults.textButtonColors(contentColor = tokens.sheen)) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

/**
 * The collapsed popup: a refresh icon in the top bar that retries on tap, spins while a sync runs, and goes away
 * once a sync succeeds (`stale` turns false).
 */
@Composable
fun RefreshIcon(state: DisconnectedState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    AnimatedVisibility(
        visible = state.showIcon,
        enter = (scaleIn(tween(Motion.MEDIUM_MS)) + fadeIn(tween(Motion.SHORT_MS))).orNone(reduced),
        exit = (scaleOut(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.QUICK_MS))).orNone(reduced),
        modifier = modifier,
    ) {
        val running = state.sync.status.running
        val motion = rememberAmbientMotion()
        val angle = if (running && motion) {
            rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(Motion.SPIN_MS, easing = LinearEasing), RepeatMode.Restart), label = "spinAngle")
        } else {
            rememberStatic(0f)
        }
        val label = stringResource(if (running) R.string.sync_running else R.string.disconnected_retry)
        IconButton(onClick = onRetry, enabled = !running) {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = label,
                tint = glass.sheen,
                modifier = Modifier.graphicsLayer { rotationZ = angle.value },
            )
        }
    }
}
