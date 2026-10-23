package io.github.conchad.smartgarage.feature.sections

import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.Composable
import io.github.conchad.smartgarage.ui.navigation.Section

/** Where the sections send the user: the vehicle screen and the editors (CP11 phase B). */
class SectionActions(
    val onOpenVehicle: (vehicleId: String) -> Unit,
    val onAddSchedule: (vehicleId: String?) -> Unit,
    val onEditSchedule: (scheduleId: String) -> Unit,
    val onEditScheduleDefaults: (scheduleId: String) -> Unit,
    val onLogService: (scheduleId: String) -> Unit,
    val onAddExpense: (vehicleId: String?) -> Unit,
    val onAddDocument: (vehicleId: String?) -> Unit,
    /** The camera, pre-linked to a service record (D-A23). */
    val onAttachReceipt: (vehicleId: String, recordId: String) -> Unit,
    val onNewNote: (vehicleId: String?) -> Unit,
    val onEditNote: (noteId: String) -> Unit,
    val onAddWishlistItem: (vehicleId: String?) -> Unit,
    val onEditWishlistItem: (itemId: String) -> Unit,
)

@Composable
fun SectionScreen(section: Section, onBack: () -> Unit, actions: SectionActions) {
    when (section) {
        Section.Reminders -> RemindersScreen(onBack, actions)
        Section.History -> HistoryScreen(onBack, actions)
        Section.Odometer -> OdometerScreen(onBack)
        Section.Expenses -> ExpensesScreen(onBack, actions)
        Section.Documents -> DocumentsScreen(onBack, actions)
        Section.Notes -> NotesScreen(onBack, actions)
        Section.Wishlist -> WishlistScreen(onBack, actions)
    }
}
