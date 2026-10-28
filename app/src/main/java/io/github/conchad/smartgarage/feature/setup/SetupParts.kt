package io.github.conchad.smartgarage.feature.setup

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.theme.LocalStatusColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import io.github.conchad.smartgarage.core.network.NetworkFailure
import io.github.conchad.smartgarage.core.network.ServerProblem
import io.github.conchad.smartgarage.ui.glass.GlassPanel
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.topFadeEdge

/**
 * Setup screen frame: top bar, scrolling form, and the step's actions in a row at the end of the form. D-A52: the
 * actions no longer sit in a frosted bar pinned above the keyboard (it covered the fields); the column pads for the
 * keyboard instead, so the focused field and the actions scroll into view above it.
 */
@Composable
internal fun StepScaffold(
    title: String,
    onBack: (() -> Unit)?,
    bottomBar: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scroll = rememberScrollState()
    // On the aurora (D-A30: AppRoot draws one behind the setup flows, the shell behind "add a server"); the form
    // sits on a glass panel with the actions at its end (D-A52).
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
            )
        },
    ) { padding: PaddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .topFadeEdge(scroll, glass.contentFadeLift)
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp + glass.contentFadeLift, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlassPanel(Modifier.widthIn(max = 560.dp).fillMaxWidth(), contentPadding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { bottomBar() }
            }
        }
    }
}

enum class Tone { Success, Error, Info }

/**
 * Result panel for the connection test and sign-in errors. It scrolls itself into view (results land below the
 * form on small screens or large fonts) and is announced to screen readers.
 */
@Composable
internal fun StatusPanel(tone: Tone, title: String, body: String?, modifier: Modifier = Modifier) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(title, body) { bringIntoView.bringIntoView() }
    // Inside the form's glass panel (D-A41): a tone-tinted wash and border rather than an opaque tonal box; the tone
    // is also in the icon and the words, never colour alone.
    val tokens = glass
    val toneColor = when (tone) {
        Tone.Success -> LocalStatusColors.current.ok
        Tone.Error -> MaterialTheme.colorScheme.error
        Tone.Info -> tokens.sheen
    }
    val shape = RoundedCornerShape(tokens.radiusMedium)
    Box(
        modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clip(shape)
            .background(toneColor.copy(alpha = 0.12f))
            .border(1.dp, toneColor.copy(alpha = 0.5f), shape),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                when (tone) {
                    Tone.Success -> Icons.Outlined.CheckCircle
                    Tone.Error -> Icons.Outlined.ErrorOutline
                    Tone.Info -> Icons.Outlined.Info
                },
                contentDescription = null,
                tint = toneColor,
            )
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary)
            }
        }
    }
}

data class ProblemText(val title: String, val body: String)

/** Plain-language explanation of each failure, with what to check next. */
@Composable
internal fun ServerProblem.describe(usesCfAccess: Boolean): ProblemText = when (this) {
    is ServerProblem.Unreachable -> when (kind) {
        NetworkFailure.Dns -> ProblemText(
            stringResource(R.string.problem_dns_title),
            stringResource(R.string.problem_dns_body),
        )
        NetworkFailure.Refused -> ProblemText(
            stringResource(R.string.problem_refused_title),
            stringResource(R.string.problem_refused_body),
        )
        NetworkFailure.Timeout -> ProblemText(
            stringResource(R.string.problem_timeout_title),
            stringResource(R.string.problem_timeout_body),
        )
        NetworkFailure.Tls -> ProblemText(
            stringResource(R.string.problem_tls_title),
            stringResource(R.string.problem_tls_body),
        )
        NetworkFailure.Other -> ProblemText(
            stringResource(R.string.problem_refused_title),
            stringResource(R.string.problem_network_other_body),
        )
    }
    ServerProblem.AccessDenied -> ProblemText(
        stringResource(R.string.problem_access_title),
        stringResource(if (usesCfAccess) R.string.problem_access_body_token else R.string.problem_access_body_missing),
    )
    ServerProblem.Challenge -> ProblemText(
        stringResource(R.string.problem_challenge_title),
        stringResource(R.string.problem_challenge_body),
    )
    is ServerProblem.GatewayError -> ProblemText(
        stringResource(R.string.problem_gateway_title),
        stringResource(R.string.problem_gateway_body, status),
    )
    is ServerProblem.Redirected -> ProblemText(
        stringResource(R.string.problem_redirect_title),
        location?.let { stringResource(R.string.problem_redirect_body, it) }
            ?: stringResource(R.string.problem_redirect_body_unknown),
    )
    is ServerProblem.NotSmartGarage -> ProblemText(
        stringResource(R.string.problem_not_sg_title),
        stringResource(R.string.problem_not_sg_body, status),
    )
    is ServerProblem.UnsupportedApi -> ProblemText(
        stringResource(R.string.problem_api_title),
        stringResource(R.string.problem_api_body),
    )
    ServerProblem.DatabaseDown -> ProblemText(
        stringResource(R.string.problem_db_title),
        stringResource(R.string.problem_db_body),
    )
    is ServerProblem.Unexpected -> ProblemText(
        stringResource(R.string.problem_unexpected_title),
        listOfNotNull(stringResource(R.string.problem_unexpected_body, status), message).joinToString(" "),
    )
}
