package io.github.conchad.smartgarage.feature.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.glass.GlassAlertDialog
import io.github.conchad.smartgarage.ui.glass.GlassButton
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.glassListItemColors
import io.github.conchad.smartgarage.ui.glass.glassPillSurface
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors
import io.github.conchad.smartgarage.ui.glass.glassTextFieldColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import io.github.conchad.smartgarage.ui.components.IconBadge
import kotlin.math.ceil

@Composable
internal fun SignInStep(state: SetupUiState, viewModel: SetupViewModel, onBack: (() -> Unit)?) {
    val focus = LocalFocusManager.current
    val reauth = state.mode is SetupMode.Reauth
    var pickServer by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val submit = {
        focus.clearFocus()
        viewModel.signIn()
    }

    StepScaffold(
        title = stringResource(R.string.signin_title),
        onBack = onBack,
        bottomBar = {
            GlassButton(
                onClick = submit,
                enabled = !state.signingIn && !state.loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.signingIn) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = glass.textSecondary,
                    )
                } else {
                    Text(stringResource(R.string.signin_submit))
                }
            }
        },
    ) {
        if (reauth) {
            StatusPanel(
                tone = Tone.Info,
                title = stringResource(R.string.signin_session_ended_title),
                body = stringResource(R.string.signin_session_ended_body),
            )
        }

        ServerSummary(state = state, onChange = viewModel::editServer)

        OutlinedTextField(

            colors = glassTextFieldColors(),
            value = state.username,
            onValueChange = viewModel::updateUsername,
            label = { Text(stringResource(R.string.signin_username)) },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            singleLine = true,
            isError = state.signInError == SignInError.InvalidCredentials ||
                (state.signInError == SignInError.MissingFields && state.username.isBlank()),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
        )

        SecretField(
            value = state.password,
            onValueChange = viewModel::updatePassword,
            label = stringResource(R.string.signin_password),
            isError = state.signInError == SignInError.InvalidCredentials ||
                (state.signInError == SignInError.MissingFields && state.password.isEmpty()),
            imeAction = ImeAction.Done,
            onImeAction = submit,
            modifier = Modifier.semantics { contentType = ContentType.Password },
        )

        OutlinedTextField(

            colors = glassTextFieldColors(),
            value = state.deviceName,
            onValueChange = viewModel::updateDeviceName,
            label = { Text(stringResource(R.string.signin_device_name)) },
            singleLine = true,
            supportingText = { Text(stringResource(R.string.signin_device_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )

        state.signInError?.let { error -> SignInErrorPanel(error, usesCfAccess = state.form.useCfAccess) }

        if (reauth) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (state.otherProfiles.isNotEmpty()) {
                    TextButton(onClick = { pickServer = true }, colors = glassTextButtonColors()) { Text(stringResource(R.string.signin_switch_server)) }
                }
                TextButton(onClick = { confirmRemove = true }) {
                    Text(stringResource(R.string.signin_remove_server), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (pickServer) {
        GlassAlertDialog(
            onDismissRequest = { pickServer = false },
            title = stringResource(R.string.signin_switch_server),
            text = {
                Column {
                    state.otherProfiles.forEach { profile ->
                        ListItem(
                            headlineContent = { Text(profile.name) },
                            supportingContent = { Text(profile.hostLabel) },
                            leadingContent = { IconBadge(Icons.Outlined.Dns) },
                            colors = glassListItemColors(),
                            modifier = Modifier.clickable {
                                pickServer = false
                                viewModel.switchTo(profile.id)
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickServer = false }, colors = glassTextButtonColors()) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (confirmRemove) {
        GlassAlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = stringResource(R.string.profiles_remove_title, state.serverName),
            text = { Text(stringResource(R.string.profiles_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        viewModel.removeThisServer()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.profiles_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }, colors = glassTextButtonColors()) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ServerSummary(state: SetupUiState, onChange: () -> Unit) {
    val tokens = glass
    Box(
        Modifier
            .fillMaxWidth()
            .glassPillSurface(tokens, RoundedCornerShape(tokens.radiusMedium)),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.Dns)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(state.serverName, style = MaterialTheme.typography.titleSmall)
                Text(
                    state.serverAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textSecondary,
                )
            }
            TextButton(onClick = onChange, colors = glassTextButtonColors()) { Text(stringResource(R.string.action_change)) }
        }
    }
}

@Composable
private fun SignInErrorPanel(error: SignInError, usesCfAccess: Boolean) {
    when (error) {
        SignInError.MissingFields -> StatusPanel(Tone.Error, stringResource(R.string.signin_error_missing), null)
        SignInError.InvalidCredentials -> StatusPanel(
            Tone.Error,
            stringResource(R.string.signin_error_credentials_title),
            stringResource(R.string.signin_error_credentials_body),
        )
        is SignInError.RateLimited -> StatusPanel(
            Tone.Error,
            stringResource(R.string.signin_error_rate_limited_title),
            error.retryAfterSeconds?.let {
                val minutes = ceil(it / 60.0).toInt().coerceAtLeast(1)
                pluralStringResource(R.plurals.signin_error_rate_limited_minutes, minutes, minutes)
            } ?: stringResource(R.string.signin_error_rate_limited_later),
        )
        is SignInError.Problem -> {
            val text = error.problem.describe(usesCfAccess)
            StatusPanel(Tone.Error, text.title, text.body)
        }
    }
}

/** Password-style field with a show/hide toggle; nothing typed here is ever logged or saved unencrypted. */
@Composable
internal fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        colors = glassTextFieldColors(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (visible) R.string.action_hide else R.string.action_show),
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        modifier = modifier.fillMaxWidth(),
    )
}
