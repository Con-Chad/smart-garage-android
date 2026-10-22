package io.github.conchad.smartgarage.ui.navigation

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.conchad.smartgarage.R
import kotlinx.serialization.Serializable

@Serializable object DashboardRoute
@Serializable object VehiclesRoute
@Serializable object MaintenanceRoute
@Serializable object FuelRoute
@Serializable object MoreRoute
@Serializable object SettingsRoute
@Serializable object ProfilesRoute
@Serializable object AddProfileRoute
@Serializable data class VehicleDetailRoute(val vehicleId: String)

/**
 * The quick fill-up form; [vehicleId] preselects the vehicle it was opened from. [scan] (the fan FAB's "Scan
 * Receipt" petal, D-A32) starts the receipt scan on arrival — the camera when the server offers the scanner, the
 * "not available" snackbar when it doesn't.
 */
@Serializable data class FuelEntryRoute(val vehicleId: String? = null, val scan: Boolean = false)

/** Log a service ([scheduleId] fixes the schedule, [vehicleId] only narrows the pick) or edit [recordId]. */
@Serializable data class RecordFormRoute(val recordId: String? = null, val scheduleId: String? = null, val vehicleId: String? = null)

/** Add an expense; [vehicleId] preselects the vehicle. */
@Serializable data class ExpenseFormRoute(val vehicleId: String? = null)

/** New note, or edit [noteId]; [vehicleId] preselects the vehicle for a new one. */
@Serializable data class NoteFormRoute(val noteId: String? = null, val vehicleId: String? = null)

/** New wishlist item, or edit [itemId]. */
@Serializable data class WishlistFormRoute(val itemId: String? = null, val vehicleId: String? = null)

/** New service schedule for a vehicle, or edit [scheduleId]. */
@Serializable data class ScheduleFormRoute(val scheduleId: String? = null, val vehicleId: String? = null)

/** The schedule's default parts and fluids. */
@Serializable data class ScheduleDefaultsRoute(val scheduleId: String)

/** New vehicle (manual entry), or edit [vehicleId]. */
@Serializable data class VehicleFormRoute(val vehicleId: String? = null)

/** An inspection (HU/AU) for [vehicleId]; [inspectionId] edits the existing one. */
@Serializable data class InspectionFormRoute(val vehicleId: String, val inspectionId: String? = null)

/** An insurance policy for [vehicleId]; [policyId] edits the existing one. */
@Serializable data class InsuranceFormRoute(val vehicleId: String, val policyId: String? = null)

/**
 * Upload a document; [vehicleId] preselects the vehicle. With [maintenanceRecordId] the file is a receipt filed
 * under that record (vehicle fixed); [capture] opens the camera straight away (D-A23).
 */
@Serializable data class DocumentFormRoute(val vehicleId: String? = null, val maintenanceRecordId: String? = null, val capture: Boolean = false)

/** A section reached from More that has no screen yet; replaced one by one in CP10. */
@Serializable data class SectionRoute(val section: Section)

/** [label] is the short bottom-bar name; screens keep the web app's full title. */
enum class TopLevel(
    val route: Any,
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Dashboard(DashboardRoute, R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home),
    Vehicles(VehiclesRoute, R.string.tab_vehicles, Icons.Outlined.DirectionsCar, Icons.Filled.DirectionsCar),
    Maintenance(MaintenanceRoute, R.string.tab_service, Icons.Outlined.Build, Icons.Filled.Build),
    Fuel(FuelRoute, R.string.tab_fuel, Icons.Outlined.LocalGasStation, Icons.Filled.LocalGasStation),
    More(MoreRoute, R.string.tab_more, Icons.Outlined.Menu, Icons.Filled.Menu),
}

/** Everything the web app's sidebar has beyond the four bottom-bar tabs, in the web's order. */
@Keep // a nav argument: R8 must not rename it, or restoring a back stack breaks in release builds
enum class Section(@StringRes val label: Int, @StringRes val description: Int, val icon: ImageVector) {
    Reminders(R.string.nav_reminders, R.string.section_reminders_desc, Icons.Outlined.NotificationsActive),
    History(R.string.nav_history, R.string.section_history_desc, Icons.Outlined.History),
    Odometer(R.string.nav_odometer, R.string.section_odometer_desc, Icons.Outlined.Speed),
    Expenses(R.string.nav_expenses, R.string.section_expenses_desc, Icons.AutoMirrored.Outlined.ReceiptLong),
    Documents(R.string.nav_documents, R.string.section_documents_desc, Icons.Outlined.Description),
    Notes(R.string.nav_notes, R.string.section_notes_desc, Icons.AutoMirrored.Outlined.StickyNote2),
    Wishlist(R.string.nav_wishlist, R.string.section_wishlist_desc, Icons.Outlined.ShoppingBag),
}
