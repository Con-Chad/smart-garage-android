package io.github.conchad.smartgarage.ui.components

import android.text.format.Formatter as FileSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import androidx.compose.foundation.layout.FlowRow
import io.github.conchad.smartgarage.ui.glass.GlassOutlinedButton
import io.github.conchad.smartgarage.ui.glass.GlassPillBackground
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Document
import io.github.conchad.smartgarage.core.model.Expense
import io.github.conchad.smartgarage.core.model.Inspection
import io.github.conchad.smartgarage.core.model.InsurancePolicy
import io.github.conchad.smartgarage.core.model.Note
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.WishlistItem
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.forms.PendingMark

// Detail sheets for the rows phase B made editable (D-A20): the row stays one tap target; Edit and Delete live
// here, after the details, and delete confirms first. Each takes `vehicle` for the view-only note.

@Composable
fun ExpenseSheet(
    expense: Expense,
    vehicle: Vehicle?,
    fmt: Formatter,
    onDismiss: () -> Unit,
    pending: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    DetailSheet(
        Icons.AutoMirrored.Outlined.ReceiptLong,
        fmt.money(expense.amountCents, expense.currency),
        listOfNotNull(expense.occurredAt.toInstantOrNull()?.let(fmt::date), vehicle?.displayName).joinToString(" · "),
        onDismiss,
    ) {
        DetailRow(stringResource(R.string.wishlist_category), expenseCategoryLabel(expense.category))
        DetailRow(stringResource(R.string.expense_description), expense.description)
        DetailRow(stringResource(R.string.field_odometer), expense.odometerKm?.let(fmt::distance))
        if (expense.maintenanceRecordId != null) {
            Text(
                stringResource(R.string.expense_from_service),
                style = MaterialTheme.typography.bodySmall,
                color = glass.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (pending) PendingMark(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (onDelete != null) SheetActions(vehicle, onDelete = { confirmDelete = true })
    }
    if (confirmDelete && onDelete != null) {
        DeleteConfirm(
            title = stringResource(R.string.expense_delete_title),
            body = stringResource(R.string.expense_delete_body),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
fun InspectionSheet(
    inspection: Inspection,
    vehicle: Vehicle?,
    fmt: Formatter,
    onDismiss: () -> Unit,
    pending: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** Opens the calendar with the due date prefilled (D-A25). */
    onAddToCalendar: (() -> Unit)? = null,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val due = inspection.nextDueAt.toInstantOrNull()
    DetailSheet(Icons.Outlined.EventAvailable, inspectionLabel(inspection.type), vehicle?.displayName, onDismiss) {
        DetailRow(
            stringResource(R.string.inspection_next_due),
            listOfNotNull(due?.let(fmt::date), due?.let { dueDays(fmt.daysFromToday(it).toInt()) }).joinToString(" · ").ifEmpty { null },
        )
        DetailRow(stringResource(R.string.inspection_last_done), inspection.lastPerformedAt.toInstantOrNull()?.let(fmt::date))
        DetailRow(
            stringResource(R.string.inspection_reminder),
            pluralStringResource(R.plurals.inspection_weeks_before, inspection.reminderWeeksBefore, inspection.reminderWeeksBefore),
        )
        DetailRow(stringResource(R.string.inspection_sticker), inspection.stickerNumber)
        DetailRow(stringResource(R.string.field_notes), inspection.notes)
        if (pending) PendingMark(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        val calendar = due?.let { d -> onAddToCalendar?.let { fmt.date(d) to it } }
        if (onEdit != null || onDelete != null || calendar != null) {
            SheetActions(vehicle, onEdit = onEdit, onDelete = onDelete?.let { { confirmDelete = true } }, calendar = calendar)
        }
    }
    if (confirmDelete && onDelete != null) {
        DeleteConfirm(
            title = stringResource(R.string.inspection_delete_title),
            body = stringResource(R.string.inspection_delete_body),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
fun InsuranceSheet(
    policy: InsurancePolicy,
    vehicle: Vehicle?,
    fmt: Formatter,
    onDismiss: () -> Unit,
    pending: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** Opens the calendar with the renewal date (the policy's end) prefilled (D-A25). */
    onAddToCalendar: (() -> Unit)? = null,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    DetailSheet(Icons.Outlined.Shield, policy.provider, vehicle?.displayName, onDismiss) {
        DetailRow(stringResource(R.string.insurance_coverage), coverageLabel(policy.coverageType))
        DetailRow(stringResource(R.string.insurance_premium_label), stringResource(R.string.insurance_premium, fmt.money(policy.premiumCents, policy.currency)))
        DetailRow(stringResource(R.string.insurance_sf_class), policy.sfClass)
        DetailRow(stringResource(R.string.insurance_policy_number), policy.policyNumber)
        DetailRow(stringResource(R.string.insurance_start), policy.startDate.toInstantOrNull()?.let(fmt::date))
        DetailRow(stringResource(R.string.insurance_end), policy.endDate.toInstantOrNull()?.let(fmt::date))
        DetailRow(stringResource(R.string.insurance_auto_renew), stringResource(if (policy.autoRenew) R.string.yes else R.string.no))
        DetailRow(stringResource(R.string.field_notes), policy.notes)
        if (pending) PendingMark(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        val calendar = policy.endDate.toInstantOrNull()?.let { end -> onAddToCalendar?.let { fmt.date(end) to it } }
        if (onEdit != null || onDelete != null || calendar != null) {
            SheetActions(vehicle, onEdit = onEdit, onDelete = onDelete?.let { { confirmDelete = true } }, calendar = calendar)
        }
    }
    if (confirmDelete && onDelete != null) {
        DeleteConfirm(
            title = stringResource(R.string.insurance_delete_title),
            body = stringResource(R.string.insurance_delete_body),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A document: what and when, "Open" as the primary action, Delete after it. */
@Composable
fun DocumentSheet(
    document: Document,
    vehicle: Vehicle?,
    fmt: Formatter,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    pending: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    DetailSheet(
        when {
            document.isImage -> Icons.Outlined.Image
            document.mimeType == "application/pdf" -> Icons.Outlined.PictureAsPdf
            else -> Icons.Outlined.Description
        },
        document.displayName,
        vehicle?.displayName,
        onDismiss,
    ) {
        DetailRow(stringResource(R.string.document_file_name), document.originalFilename.takeIf { it != document.displayName })
        DetailRow(
            stringResource(R.string.wishlist_category),
            document.category?.let { documentCategoryLabel(it) }
                ?: stringResource(R.string.documents_vehicle_photo).takeIf { document.purpose == "VEHICLE_IMAGE" },
        )
        DetailRow(stringResource(R.string.document_type), document.mimeType)
        DetailRow(stringResource(R.string.document_size), FileSize.formatShortFileSize(context, document.sizeBytes))
        DetailRow(stringResource(R.string.document_uploaded), document.createdAt.toInstantOrNull()?.let(fmt::date))
        if (pending) PendingMark(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        SheetActions(
            vehicle,
            primary = stringResource(R.string.action_open) to onOpen,
            onDelete = onDelete?.let { { confirmDelete = true } },
        )
    }
    if (confirmDelete && onDelete != null) {
        DeleteConfirm(
            title = stringResource(R.string.document_delete_title),
            body = stringResource(R.string.document_delete_body),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A note is the author's own, whatever the vehicle's sharing, so the actions never show the view-only note. */
@Composable
fun NoteSheet(
    note: Note,
    vehicle: Vehicle?,
    fmt: Formatter,
    onDismiss: () -> Unit,
    pending: Boolean = false,
    /** Pins or unpins (D-A23): pinned notes lead the list, so the toggle is the sheet's primary verb. */
    onTogglePin: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    DetailSheet(
        Icons.AutoMirrored.Outlined.StickyNote2,
        note.title,
        listOfNotNull(vehicle?.displayName, note.updatedAt.toInstantOrNull()?.let(fmt::date)).joinToString(" · "),
        onDismiss,
    ) {
        if (note.tags.isNotEmpty()) {
            // Glass pills that wrap onto more lines at large font sizes (D-A41).
            FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                note.tags.forEach { tag ->
                    GlassPillBackground {
                        Text("#$tag", style = MaterialTheme.typography.labelLarge, color = glass.sheen)
                    }
                }
            }
        }
        if (note.content.isNotBlank()) {
            Text(note.content, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
        }
        if (pending) PendingMark(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (onTogglePin != null || onEdit != null || onDelete != null) {
            SheetActions(
                null,
                secondary = onTogglePin?.let { stringResource(if (note.isPinned) R.string.note_unpin else R.string.note_pin_action) to it },
                onEdit = onEdit,
                onDelete = onDelete?.let { { confirmDelete = true } },
            )
        }
    }
    if (confirmDelete && onDelete != null) {
        DeleteConfirm(
            title = stringResource(R.string.note_delete_title),
            body = stringResource(R.string.note_delete_body, note.title),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
fun WishlistSheet(
    item: WishlistItem,
    vehicle: Vehicle?,
    fmt: Formatter,
    onDismiss: () -> Unit,
    pending: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    DetailSheet(
        Icons.Outlined.ShoppingBag,
        item.title,
        listOfNotNull(wishlistStatusLabel(item.status), vehicle?.displayName).joinToString(" · "),
        onDismiss,
    ) {
        DetailRow(stringResource(R.string.wishlist_category), wishlistCategoryLabel(item.category))
        DetailRow(stringResource(R.string.wishlist_priority), item.priority.takeIf { it > 0 }?.toString())
        DetailRow(stringResource(R.string.wishlist_estimate), item.estimatedCostCents?.let { wishlistEstimate(fmt, it, item.currency) })
        DetailRow(stringResource(R.string.wishlist_target), item.targetDate.toInstantOrNull()?.let(fmt::date))
        DetailRow(stringResource(R.string.wishlist_description), item.description)
        item.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { url ->
            GlassOutlinedButton(
                onClick = { runCatching { uriHandler.openUri(url) } },
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.wishlist_open_link), modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (pending) PendingMark(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (onEdit != null || onDelete != null) {
            SheetActions(null, onEdit = onEdit, onDelete = onDelete?.let { { confirmDelete = true } })
        }
    }
    if (confirmDelete && onDelete != null) {
        DeleteConfirm(
            title = stringResource(R.string.wishlist_delete_title),
            body = stringResource(R.string.wishlist_delete_body, item.title),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Estimates are usually round numbers; "$2,500" reads better than "$2,500.00". */
fun wishlistEstimate(fmt: Formatter, cents: Long, currency: String): String =
    fmt.money(cents / 100.0, currency, fractionDigits = if (cents % 100 == 0L) 0 else null)

/** Same words as the web's `vehicles.insurance.coverage`. */
@Composable
fun coverageLabel(value: String): String = when (value) {
    "LIABILITY" -> stringResource(R.string.insurance_coverage_liability)
    "PARTIAL" -> stringResource(R.string.insurance_coverage_partial)
    "FULL" -> stringResource(R.string.insurance_coverage_full)
    else -> enumLabel(value)
}

/** `MaintenanceCategory` names, as the web's `maintenance.categories`. */
@Composable
fun maintenanceCategoryLabel(value: String): String = when (value) {
    "ENGINE" -> stringResource(R.string.maint_category_engine)
    "FILTERS" -> stringResource(R.string.maint_category_filters)
    "FLUIDS" -> stringResource(R.string.maint_category_fluids)
    "BRAKES" -> stringResource(R.string.maint_category_brakes)
    "TIRES" -> stringResource(R.string.maint_category_tires)
    "INSPECTION" -> stringResource(R.string.maint_category_inspection)
    "ADDITIVE" -> stringResource(R.string.maint_category_additive)
    "OTHER" -> stringResource(R.string.maint_category_other)
    else -> enumLabel(value)
}
