package io.github.conchad.smartgarage.feature.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Garage
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.glass.GlassPanel
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.onAurora

/**
 * Welcome → server → sign-in. Nothing is saved until sign-in succeeds; then the profile becomes active and
 * AppRoot swaps this flow for the main shell.
 */
@Composable
fun SetupFlow(
    mode: SetupMode,
    onCancel: (() -> Unit)? = null,
    viewModel: SetupViewModel = hiltViewModel(key = mode.key),
) {
    LaunchedEffect(mode) { viewModel.start(mode) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    BackHandler(enabled = state.canGoBack || onCancel != null) {
        if (!viewModel.back()) onCancel?.invoke() ?: activity?.finish()
    }
    val onBack: (() -> Unit)? = when {
        state.canGoBack -> { { viewModel.back() } }
        else -> onCancel
    }

    AnimatedContent(
        targetState = state.step,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
        },
        label = "setup-step",
    ) { step ->
        when (step) {
            SetupStep.Welcome -> WelcomeStep(onGetStarted = viewModel::getStarted)
            SetupStep.Server -> ServerStep(state = state, viewModel = viewModel, onBack = onBack)
            SetupStep.SignIn -> SignInStep(state = state, viewModel = viewModel, onBack = onBack)
        }
    }
}

@Composable
private fun WelcomeStep(onGetStarted: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(48.dp))
            IconBadge(
                Icons.Outlined.Garage,
                size = 104,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(24.dp))
            // On the aurora (D-A30): haloed title and body, the three features on a glass panel.
            Text(
                stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineMedium.onAurora(glass),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.welcome_body),
                style = MaterialTheme.typography.bodyLarge.onAurora(glass),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            GlassPanel(Modifier.fillMaxWidth(), contentPadding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Feature(Icons.Outlined.Storage, R.string.welcome_feature_server_title, R.string.welcome_feature_server_body)
                    Feature(Icons.Outlined.CloudOff, R.string.welcome_feature_offline_title, R.string.welcome_feature_offline_body)
                    Feature(Icons.Outlined.Lock, R.string.welcome_feature_private_title, R.string.welcome_feature_private_body)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Button(onClick = onGetStarted, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).height(52.dp)) {
            Text(stringResource(R.string.welcome_get_started))
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, body: Int) {
    Row(verticalAlignment = Alignment.Top) {
        IconBadge(icon)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
