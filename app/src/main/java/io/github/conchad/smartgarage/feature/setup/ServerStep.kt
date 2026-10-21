package io.github.conchad.smartgarage.feature.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.network.HealthResult
import io.github.conchad.smartgarage.core.network.ServerUrl
import io.github.conchad.smartgarage.ui.components.IconBadge

@Composable
internal fun ServerStep(state: SetupUiState, viewModel: SetupViewModel, onBack: (() -> Unit)?) {
    val form = state.form
    val urlError = (state.formError as? FormError.Url)?.reason

    StepScaffold(
        title = stringResource(R.string.server_title),
        onBack = onBack,
        bottomBar = {
            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = !state.testing,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                if (state.testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.server_test), maxLines = 1)
                }
            }
            Button(
                onClick = viewModel::continueToSignIn,
                enabled = state.verified && !state.testing,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text(stringResource(R.string.action_continue), maxLines = 1) }
        },
    ) {
        Text(
            stringResource(R.string.server_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = form.url,
            onValueChange = { value -> viewModel.updateForm { it.copy(url = value) } },
            label = { Text(stringResource(R.string.server_url_label)) },
            placeholder = { Text("https://garage.example.com") },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Link, contentDescription = null) },
            singleLine = true,
            isError = urlError != null,
            supportingText = {
                Text(urlError?.let { urlErrorText(it) } ?: stringResource(R.string.server_url_hint))
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = form.name,
            onValueChange = { value -> viewModel.updateForm { it.copy(name = value.take(40)) } },
            label = { Text(stringResource(R.string.server_name_label)) },
            placeholder = { Text(state.serverHost) },
            singleLine = true,
            supportingText = { Text(stringResource(R.string.server_name_hint)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        CloudflareAccessCard(state = state, viewModel = viewModel)

        state.check?.takeIf { it.form == form }?.let { check ->
            when (val result = check.result) {
                is HealthResult.Ok -> StatusPanel(
                    tone = Tone.Success,
                    title = stringResource(R.string.server_ok_title),
                    body = listOfNotNull(
                        result.version?.let { stringResource(R.string.server_ok_version, it) },
                        if (result.behindCloudflare) stringResource(R.string.server_ok_cloudflare) else null,
                    ).joinToString(" ").ifBlank { null },
                )
                is HealthResult.Problem -> {
                    val text = result.problem.describe(usesCfAccess = form.useCfAccess)
                    StatusPanel(tone = Tone.Error, title = text.title, body = text.body)
                }
            }
        }
    }
}

@Composable
private fun CloudflareAccessCard(state: SetupUiState, viewModel: SetupViewModel) {
    val form = state.form
    val missing = state.formError == FormError.CfCredentialsMissing
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateForm { it.copy(useCfAccess = !it.useCfAccess) } }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(Icons.Outlined.Shield)
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.server_cf_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.server_cf_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = form.useCfAccess,
                    onCheckedChange = { checked -> viewModel.updateForm { it.copy(useCfAccess = checked) } },
                )
            }
            AnimatedVisibility(visible = form.useCfAccess) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = form.cfClientId,
                        onValueChange = { value -> viewModel.updateForm { it.copy(cfClientId = value.trim()) } },
                        label = { Text(stringResource(R.string.server_cf_client_id)) },
                        placeholder = { Text("xxxxxxxx.access") },
                        singleLine = true,
                        isError = missing && form.cfClientId.isBlank(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecretField(
                        value = form.cfClientSecret,
                        onValueChange = { value -> viewModel.updateForm { it.copy(cfClientSecret = value.trim()) } },
                        label = stringResource(R.string.server_cf_client_secret),
                        isError = missing && form.cfClientSecret.isBlank(),
                        imeAction = ImeAction.Done,
                        onImeAction = viewModel::testConnection,
                    )
                    Text(
                        stringResource(if (missing) R.string.server_cf_missing else R.string.server_cf_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun urlErrorText(reason: ServerUrl.Reason): String = stringResource(
    when (reason) {
        ServerUrl.Reason.Empty -> R.string.server_url_error_empty
        ServerUrl.Reason.Malformed -> R.string.server_url_error_malformed
        ServerUrl.Reason.UnsupportedScheme -> R.string.server_url_error_scheme
        ServerUrl.Reason.CleartextPublicHost -> R.string.server_url_error_cleartext
    },
)
