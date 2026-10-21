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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.network.NetworkFailure
import io.github.conchad.smartgarage.core.network.ServerProblem

/** Setup screen frame: top bar, scrolling form, and an action bar that rides above the keyboard. */
@Composable
internal fun StepScaffold(
    title: String,
    onBack: (() -> Unit)?,
    bottomBar: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
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
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { bottomBar() }
            }
        },
    ) { padding: PaddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
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
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        Tone.Success -> colors.tertiaryContainer to colors.onTertiaryContainer
        Tone.Error -> colors.errorContainer to colors.onErrorContainer
        Tone.Info -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                when (tone) {
                    Tone.Success -> Icons.Outlined.CheckCircle
                    Tone.Error -> Icons.Outlined.ErrorOutline
                    Tone.Info -> Icons.Outlined.Info
                },
                contentDescription = null,
            )
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
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
