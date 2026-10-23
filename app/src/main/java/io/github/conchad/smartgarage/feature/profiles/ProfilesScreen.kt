package io.github.conchad.smartgarage.feature.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.forms.AddFab
import io.github.conchad.smartgarage.ui.glass.GlassAlertDialog
import io.github.conchad.smartgarage.ui.glass.GlassMenu
import io.github.conchad.smartgarage.ui.glass.GlassMenuItem
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.glassListItemColors
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors
import io.github.conchad.smartgarage.core.data.ServerProfile
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.SectionLabel

@Composable
fun ProfilesScreen(
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var removing by rememberSaveable { mutableStateOf<String?>(null) }

    AppScaffold(
        title = stringResource(R.string.profiles_title),
        onBack = onBack,
        floatingActionButton = {
            AddFab(stringResource(R.string.profiles_add), onClick = onAddProfile, icon = Icons.Outlined.Add)
        },
    ) { padding ->
        val current = state ?: return@AppScaffold
        // Below the bar, not under it: the bar's glass is see-through, so rows scrolled under it showed through the
        // title (D-A41). The bottom padding stays content padding, so the last row still clears the tray.
        LazyColumn(
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
        ) {
            item { SectionLabel(stringResource(R.string.profiles_hint)) }
            item {
                GroupCard {
                    current.profiles.forEachIndexed { index, profile ->
                        if (index > 0) RowDivider()
                        ProfileRow(
                            profile = profile,
                            active = profile.id == current.active?.id,
                            signedIn = current.isSignedIn(profile.id),
                            onActivate = { viewModel.activate(profile.id) },
                            onSignOut = { viewModel.signOut(profile.id) },
                            onRemove = { removing = profile.id },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }

        val target = current.profiles.firstOrNull { it.id == removing }
        if (target != null) {
            GlassAlertDialog(
                onDismissRequest = { removing = null },
                title = stringResource(R.string.profiles_remove_title, target.name),
                text = { Text(stringResource(R.string.profiles_remove_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.remove(target.id)
                            removing = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.profiles_remove)) }
                },
                dismissButton = {
                    TextButton(onClick = { removing = null }, colors = glassTextButtonColors()) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: ServerProfile,
    active: Boolean,
    signedIn: Boolean,
    onActivate: () -> Unit,
    onSignOut: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val status = when {
        signedIn -> stringResource(R.string.profiles_signed_in_as, profile.account?.username.orEmpty())
        else -> stringResource(R.string.profiles_signed_out)
    }
    ListItem(
        headlineContent = { Text(profile.name) },
        supportingContent = { Text("${profile.hostLabel} · $status") },
        leadingContent = {
            // The active server in the sheen with a check; the others in the quieter glass grey.
            if (active) IconBadge(Icons.Outlined.CheckCircle) else IconBadge(Icons.Outlined.Dns, tint = glass.textSecondary)
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                }
                // The glass menu (D-A31) as on the vehicle cards, titled with the server's name.
                GlassMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, title = profile.name) {
                    if (!active) {
                        GlassMenuItem(Icons.Outlined.SwapHoriz, stringResource(R.string.profiles_switch)) { menuOpen = false; onActivate() }
                    }
                    if (signedIn) {
                        GlassMenuItem(Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.settings_sign_out)) { menuOpen = false; onSignOut() }
                    }
                    GlassMenuItem(Icons.Outlined.Delete, stringResource(R.string.profiles_remove)) { menuOpen = false; onRemove() }
                }
            }
        },
        colors = glassListItemColors(),
        modifier = Modifier.clickable(enabled = !active, onClick = onActivate),
    )
}
