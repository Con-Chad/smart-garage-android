package io.github.conchad.smartgarage.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.ui.components.forms.ConfirmDialog
import kotlinx.coroutines.launch

/**
 * A row that slides left to delete: past the threshold it slides off and [ConfirmDialog] asks with [title] / [body]
 * / [confirmLabel]; Cancel slides it back, confirming runs [onDelete] (the row then goes with the item). With
 * [enabled] false (a view-only vehicle) it is just the row.
 */
@Composable
fun SwipeToDelete(
    enabled: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onDelete: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    // A third of the row or a flick: a short sideways nudge while scrolling shouldn't ask anything.
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.35f })
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var confirming by remember { mutableStateOf(false) }
    // One tick as the swipe crosses the point where letting go would delete.
    LaunchedEffect(state.targetValue) {
        if (state.targetValue == SwipeToDismissBoxValue.EndToStart) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        gesturesEnabled = enabled,
        onDismiss = { confirming = true },
        backgroundContent = {
            val error = MaterialTheme.colorScheme.error
            Row(
                Modifier
                    .fillMaxSize()
                    // The rows are see-through glass, so the red fills only the strip the row has slid off, not
                    // the space under the row itself; the icon is clipped to that strip with it.
                    .drawWithContent {
                        val revealed = (-state.requireOffset()).coerceIn(0f, size.width)
                        clipRect(left = size.width - revealed) {
                            drawRect(error)
                            this@drawWithContent.drawContent()
                        }
                    }
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
            }
        },
        content = content,
    )
    if (confirming) {
        ConfirmDialog(
            title = title,
            body = body,
            confirmLabel = confirmLabel,
            onConfirm = {
                confirming = false
                onDelete()
            },
            onDismiss = {
                confirming = false
                scope.launch { state.reset() }
            },
            destructive = true,
        )
    }
}
