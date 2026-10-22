package io.github.conchad.smartgarage.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/** Which row's detail sheet is open. Holds ids rather than objects so it survives rotation and fresh data. */
sealed interface SheetTarget {
    val id: String

    data class Schedule(override val id: String) : SheetTarget
    data class Record(override val id: String) : SheetTarget
    data class Fuel(override val id: String) : SheetTarget
    data class Inspection(override val id: String) : SheetTarget
    data class Insurance(override val id: String) : SheetTarget
}

@Composable
fun rememberSheetTarget(): MutableState<SheetTarget?> = rememberSaveable(saver = SheetTargetSaver) { mutableStateOf(null) }

private val SheetTargetSaver = Saver<MutableState<SheetTarget?>, String>(
    save = { state ->
        when (val target = state.value) {
            is SheetTarget.Schedule -> "s:${target.id}"
            is SheetTarget.Record -> "r:${target.id}"
            is SheetTarget.Fuel -> "f:${target.id}"
            is SheetTarget.Inspection -> "i:${target.id}"
            is SheetTarget.Insurance -> "p:${target.id}"
            null -> ""
        }
    },
    restore = { saved ->
        val id = saved.substringAfter(':')
        mutableStateOf(
            when (saved.substringBefore(':')) {
                "s" -> SheetTarget.Schedule(id)
                "r" -> SheetTarget.Record(id)
                "f" -> SheetTarget.Fuel(id)
                "i" -> SheetTarget.Inspection(id)
                "p" -> SheetTarget.Insurance(id)
                else -> null
            },
        )
    },
)
