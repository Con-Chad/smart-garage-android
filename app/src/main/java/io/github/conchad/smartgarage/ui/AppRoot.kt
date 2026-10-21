package io.github.conchad.smartgarage.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.feature.setup.SetupFlow
import io.github.conchad.smartgarage.feature.setup.SetupMode
import io.github.conchad.smartgarage.ui.navigation.MainShell
import io.github.conchad.smartgarage.ui.theme.SmartGarageTheme

@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SmartGarageTheme(appearance = state.appearance) {
        // A Surface, not a Box: it also sets the content color, so text without an explicit color follows the theme.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            when (val root = state.root) {
                RootState.Loading -> Unit
                RootState.Onboarding -> SetupFlow(mode = SetupMode.FirstRun)
                is RootState.SignedOut -> key(root.profile.id) {
                    SetupFlow(mode = SetupMode.Reauth(root.profile.id))
                }
                // Keyed by profile: switching servers starts a fresh back stack, since every screen shows that
                // server's data.
                is RootState.Ready -> key(root.profile.id) {
                    MainShell(profile = root.profile, account = root.account)
                }
            }
        }
    }
}
