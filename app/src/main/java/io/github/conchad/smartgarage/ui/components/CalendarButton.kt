package io.github.conchad.smartgarage.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R

/**
 * "Add to calendar" (D-A25): the same outlined, full-width, 48 dp button in every form and sheet that knows a due
 * date, so the action is recognisable wherever it appears. The label carries the date, so TalkBack says which day.
 */
@Composable
fun AddToCalendarButton(dateText: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.Event, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.calendar_add_dated, dateText), modifier = Modifier.padding(start = 8.dp))
    }
}
