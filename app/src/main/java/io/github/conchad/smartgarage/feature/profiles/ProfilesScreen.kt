package io.github.conchad.smartgarage.feature.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.data.ServerProfile
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.SectionCard
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
            ExtendedFloatingActionButton(
                onClick = onAddProfile,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.profiles_add)) },
            )
        },
    ) { padding ->
        val current = state ?: return@AppScaffold
        LazyColumn(contentPadding = padding) {
            item { SectionLabel(stringResource(R.string.profiles_hint)) }
            item {
                SectionCard {
                    current.profiles.forEach { profile ->
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
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text(stringResource(R.string.profiles_remove_title, target.name)) },
                text = { Text(stringResource(R.string.profiles_remove_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.remove(target.id)
                        removing = null
                    }) { Text(stringResource(R.string.profiles_remove)) }
                },
                dismissButton = {
                    TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) }
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
            if (active) {
                IconBadge(
                    Icons.Outlined.CheckCircle,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                IconBadge(Icons.Outlined.Dns)
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!active) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profiles_switch)) },
                            onClick = { menuOpen = false; onActivate() },
                        )
                    }
                    if (signedIn) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_sign_out)) },
                            onClick = { menuOpen = false; onSignOut() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profiles_remove)) },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = !active, onClick = onActivate),
    )
}
