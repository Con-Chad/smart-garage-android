package io.github.conchad.smartgarage.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import android.os.SystemClock
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.conchad.smartgarage.ui.glass.Motion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.conchad.smartgarage.ui.glass.GlassPanel
import io.github.conchad.smartgarage.ui.glass.GlassPillButton
import io.github.conchad.smartgarage.ui.glass.SkeletonList
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors
import io.github.conchad.smartgarage.ui.glass.onAurora
import io.github.conchad.smartgarage.ui.sync.SyncInfo

/** Centered spinner with a line of text; the one way a screen waits for the cache or the first sync. */
@Composable
fun LoadingState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge.onAurora(glass), textAlign = TextAlign.Center)
    }
}

/** Nothing cached and the server can't be reached: the only state with nothing at all to show. */
@Composable
fun CantReachState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Outlined.CloudOff,
        title = stringResource(R.string.cant_reach_title),
        body = stringResource(R.string.cant_reach_body),
        modifier = modifier,
        action = { GlassPillButton(stringResource(R.string.action_retry), onClick = onRetry) },
    )
}

/**
 * Loading, can't-reach, or the screen's own [content], decided the same way on every cache-backed screen:
 * - the cache hasn't answered yet, or the first sync is still running: [LoadingState], or with [skeleton] a
 *   [SkeletonList] shaped like the screen's grouped list (D-A41: the list tabs and the More sections);
 * - the first sync failed and nothing is cached: [CantReachState];
 * - otherwise [content], which handles its own empty state.
 *
 * D-A51: a cache that answers within [Motion.LOADING_GRACE_MS] of the screen appearing (a tab opened for the first
 * time, its ViewModel just created) swaps straight to the content instead of cross-fading the spinner over it, which
 * on a tab switch read as a second page glitching in under the first. A real wait still cross-fades.
 */
@Composable
fun LoadedContent(loaded: Boolean, sync: SyncInfo, onRetry: () -> Unit, skeleton: Boolean = false, content: @Composable () -> Unit) {
    val phase = when {
        !loaded || sync.firstLoad -> Phase.Loading
        sync.neverSynced -> Phase.CantReach
        else -> Phase.Content
    }
    val shownAt = remember { SystemClock.uptimeMillis() }
    val spec: FiniteAnimationSpec<Float> = remember(phase) {
        if (SystemClock.uptimeMillis() - shownAt < Motion.LOADING_GRACE_MS) snap() else tween()
    }
    Crossfade(phase, animationSpec = spec, label = "loaded") { current ->
        when (current) {
            Phase.Loading -> if (skeleton) {
                SkeletonList(
                    label = stringResource(R.string.loading),
                    caption = if (sync.firstLoad) stringResource(R.string.loading_first_sync) else null,
                )
            } else {
                LoadingState(stringResource(if (sync.firstLoad) R.string.loading_first_sync else R.string.loading))
            }
            Phase.CantReach -> CantReachState(onRetry)
            Phase.Content -> content()
        }
    }
}

private enum class Phase { Loading, CantReach, Content }

/**
 * Pull-to-refresh around a screen's body (D-A13). The spinner also shows for automatic syncs, so every tab gives the
 * same "refreshing" feedback; it stays hidden during the first sync, which has its own [LoadingState].
 */
@Composable
fun RefreshBox(sync: SyncInfo, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    PullToRefreshBox(
        isRefreshing = sync.status.running && !sync.firstLoad,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        content = content,
    )
}

/**
 * Slim banner under the top bar, hidden in the normal case. It has three things to say, most urgent first:
 * - "N changes couldn't be saved" while writes the server rejected wait to be retried or dismissed (error tone,
 *   [onShowFailed] opens the list); screens that can't show the list leave it null and the banner
 *   stays with the sync states;
 * - "Offline · N changes waiting to sync" when the last sync failed after an earlier success and there are queued
 *   writes (neutral), else "Offline · last updated X";
 * - "Sync failed · last updated X" when the server answered with an error (error tone).
 * Text always carries the meaning; the icon only decorates.
 *
 * [syncStates] false keeps only the failed-writes message: the home screen shows "can't reach" as its own popup
 * (D-A24) and must not say it twice.
 */
@Composable
fun SyncBanner(sync: SyncInfo, onRetry: () -> Unit, modifier: Modifier = Modifier, onShowFailed: (() -> Unit)? = null, syncStates: Boolean = true) {
    val showFailed = sync.hasFailed && onShowFailed != null
    AnimatedVisibility(
        visible = (syncStates && sync.stale) || showFailed,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val offline = sync.offline
        val error = showFailed || (sync.stale && !offline)
        // lastSyncedAt is non-null whenever `stale` is; the fallback only keeps the exit animation stable.
        val last = sync.lastSyncedAt?.let { relativeTime(it) } ?: stringResource(R.string.time_just_now)
        val text = when {
            showFailed -> pluralStringResource(R.plurals.sync_banner_failed_writes, sync.failed, sync.failed)
            offline && sync.hasPending -> pluralStringResource(R.plurals.sync_banner_offline_pending, sync.pending, sync.pending)
            offline -> stringResource(R.string.sync_banner_offline, last)
            else -> stringResource(R.string.sync_banner_failed, last)
        }
        // A glass strip (D-A41), not an opaque bar: the state is in the words and the tinted icon and glow.
        val tokens = glass
        val tone = if (error) MaterialTheme.colorScheme.error else tokens.sheen
        GlassPanel(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(tokens.radiusMedium),
            glow = tone,
            elevated = false,
        ) {
            Row(
                Modifier
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when {
                        showFailed -> Icons.Outlined.ErrorOutline
                        offline && sync.hasPending -> Icons.Outlined.CloudUpload
                        offline -> Icons.Outlined.CloudOff
                        else -> Icons.Outlined.SyncProblem
                    },
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(20.dp),
                )
                Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                if (showFailed) {
                    TextButton(onClick = onShowFailed!!, colors = glassTextButtonColors()) { Text(stringResource(R.string.action_show)) }
                } else {
                    TextButton(onClick = onRetry, enabled = !sync.status.running, colors = glassTextButtonColors()) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}
