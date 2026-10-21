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
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
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
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
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
        action = { Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) } },
    )
}

/**
 * Loading, can't-reach, or the screen's own [content], decided the same way on every cache-backed screen:
 * - the cache hasn't answered yet, or the first sync is still running: [LoadingState];
 * - the first sync failed and nothing is cached: [CantReachState];
 * - otherwise [content], which handles its own empty state.
 */
@Composable
fun LoadedContent(loaded: Boolean, sync: SyncInfo, onRetry: () -> Unit, content: @Composable () -> Unit) {
    val phase = when {
        !loaded || sync.firstLoad -> Phase.Loading
        sync.neverSynced -> Phase.CantReach
        else -> Phase.Content
    }
    Crossfade(phase, label = "loaded") { current ->
        when (current) {
            Phase.Loading -> LoadingState(stringResource(if (sync.firstLoad) R.string.loading_first_sync else R.string.loading))
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
 * Slim banner under the top bar when the last sync failed after an earlier success, so the user knows what they see
 * may be out of date. Offline is neutral; a server error uses the error container. Text always carries the meaning;
 * the icon only decorates. Hidden otherwise, so screens show nothing extra in the normal case.
 */
@Composable
fun SyncBanner(sync: SyncInfo, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = sync.stale,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val offline = sync.offline
        // lastSyncedAt is non-null whenever `stale` is; the fallback only keeps the exit animation stable.
        val last = sync.lastSyncedAt?.let { relativeTime(it) } ?: stringResource(R.string.time_just_now)
        Surface(
            color = if (offline) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.errorContainer,
            contentColor = if (offline) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (offline) Icons.Outlined.CloudOff else Icons.Outlined.SyncProblem, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(if (offline) R.string.sync_banner_offline else R.string.sync_banner_failed, last),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                TextButton(onClick = onRetry, enabled = !sync.status.running) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}
