package io.github.conchad.smartgarage.ui.components.forms

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Below a revealed field, a little room so its last line doesn't sit flush on the keyboard. */
private val REVEAL_MARGIN: Dp = 8.dp

/** How long the keyboard's inset and the field's size must hold still before the field is revealed. */
private const val REVEAL_SETTLE_MS = 80L

/**
 * D-A71: while anything inside has focus, keeps the whole field (its error / supporting line, an attached list) in
 * view above the keyboard, not only the cursor, which is all the text field brings into view itself. Re-revealed
 * once the keyboard has finished sliding in and whenever the field's size changes (an error line appearing, a list
 * opening), never while either is still moving; a field taller than the visible area keeps its top in view. Needs a
 * scrolling container that pads for the IME ([FormScreen] does).
 */
internal fun Modifier.revealWhenFocused(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) to size }.collectLatest { (_, s) ->
            delay(REVEAL_SETTLE_MS)
            if (s.height > 0) {
                val margin = with(density) { REVEAL_MARGIN.toPx() }
                requester.bringIntoView(Rect(0f, 0f, s.width.toFloat(), s.height + margin))
            }
        }
    }
    this
        .onFocusChanged { focused = it.hasFocus }
        .bringIntoViewRequester(requester)
        .onSizeChanged { size = it }
}
