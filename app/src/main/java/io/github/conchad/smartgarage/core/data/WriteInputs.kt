package io.github.conchad.smartgarage.core.data

import io.github.conchad.smartgarage.core.model.MaintenanceItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// Request bodies for `/api/v1` writes, mirroring the server's zod schemas (lib/api/v1/schemas.ts). Stored units
// throughout: km, liters, integer cents, ISO 8601 dates. A null field is left out of the request.
//
// Patches: the server merges a PATCH onto the stored row, so only the fields given change. To clear a nullable
// field, name it in `clear`; it is then sent as JSON null.

@Serializable
data class FuelEntryInput(
    val filledAt: String,
    val totalCostCents: Long,
    val odometerKm: Int? = null,
    val liters: Double? = null,
    val currency: String? = null,
    val stationName: String? = null,
    val note: String? = null,
    /** The `documentId` a receipt scan returned (D-A27); the server links that document to the new entry. */
    val receiptDocumentId: String? = null,
)

@Serializable
data class ExpenseInput(
    /** An `ExpenseCategory` name, e.g. `FUEL`, `MAINTENANCE`, `OTHER`. */
    val category: String,
    val occurredAt: String,
    val amountCents: Long,
    val currency: String? = null,
    val odometerKm: Int? = null,
    val maintenanceRecordId: String? = null,
    val description: String? = null,
    /** A scanned receipt's `documentId` (D-A53); the server links that document to the new expense. */
    val receiptDocumentId: String? = null,
)

@Serializable
data class OdometerInput(
    val odometerKm: Int,
    /** Defaults to now on the server. */
    val recordedAt: String? = null,
    val note: String? = null,
)

@Serializable
data class ScheduleInput(
    val templateId: String? = null,
    val customName: String? = null,
    /** A `MaintenanceCategory` name. */
    val category: String? = null,
    val intervalKm: Int? = null,
    val intervalMonths: Int? = null,
    val estimatedCostCents: Long? = null,
    val currency: String? = null,
    val notes: String? = null,
)

@Serializable
data class SchedulePatch(
    val customName: String? = null,
    val category: String? = null,
    val intervalKm: Int? = null,
    val intervalMonths: Int? = null,
    val estimatedCostCents: Long? = null,
    val currency: String? = null,
    val notes: String? = null,
    /** Baseline used until the schedule has real service records. */
    val lastPerformedAt: String? = null,
    val lastOdometerKm: Int? = null,
    @Transient val clear: Set<String> = emptySet(),
)

@Serializable
data class RecordInput(
    val performedAt: String,
    val odometerKm: Int? = null,
    val costCents: Long? = null,
    val currency: String? = null,
    val vendorName: String? = null,
    val note: String? = null,
    /** Parts and fluids; the item ids are ignored by the server. */
    val items: List<MaintenanceItem>? = null,
    /** Also store these items as the schedule's default parts. */
    val saveAsDefault: Boolean? = null,
    /**
     * A scanned receipt's `documentId` (D-A53); the server links it to the new record. The server also books a
     * `MAINTENANCE` expense for any record with a cost, so the app never posts a second expense for the receipt.
     */
    val receiptDocumentId: String? = null,
)

@Serializable
data class RecordPatch(
    val performedAt: String? = null,
    val odometerKm: Int? = null,
    val costCents: Long? = null,
    val currency: String? = null,
    val vendorName: String? = null,
    val note: String? = null,
    /** Replaces every item when given; omitted keeps the stored items. */
    val items: List<MaintenanceItem>? = null,
    val saveAsDefault: Boolean? = null,
    @Transient val clear: Set<String> = emptySet(),
)

@Serializable
data class NoteInput(
    val title: String,
    val content: String? = null,
    val vehicleId: String? = null,
    val maintenanceRecordId: String? = null,
    val isPinned: Boolean? = null,
    /** Up to 8 tags of up to 30 characters. */
    val tags: List<String>? = null,
)

@Serializable
data class NotePatch(
    val title: String? = null,
    val content: String? = null,
    val vehicleId: String? = null,
    val maintenanceRecordId: String? = null,
    val isPinned: Boolean? = null,
    val tags: List<String>? = null,
    @Transient val clear: Set<String> = emptySet(),
)

@Serializable
data class WishlistInput(
    val title: String,
    val description: String? = null,
    /** A `WishlistCategory` name. */
    val category: String? = null,
    /** IDEA, PLANNED, ORDERED or DONE. */
    val status: String? = null,
    val priority: Int? = null,
    val targetDate: String? = null,
    val url: String? = null,
    val estimatedCostCents: Long? = null,
    val currency: String? = null,
    val vehicleId: String? = null,
)

@Serializable
data class WishlistPatch(
    val title: String? = null,
    val description: String? = null,
    val category: String? = null,
    val status: String? = null,
    val priority: Int? = null,
    val targetDate: String? = null,
    val url: String? = null,
    val estimatedCostCents: Long? = null,
    val currency: String? = null,
    val vehicleId: String? = null,
    @Transient val clear: Set<String> = emptySet(),
)

/** Manual vehicle entry (D-035); catalog-based setup stays on the web. */
@Serializable
data class VehicleInput(
    val make: String,
    val model: String,
    val productionYear: Int,
    val currentOdometerKm: Int,
    val licensePlate: String? = null,
    val vin: String? = null,
    val hsn: String? = null,
    val tsn: String? = null,
    val color: String? = null,
    val notes: String? = null,
    /** `FuelType`, `BodyType` and `DriveType` names. */
    val fuelType: String? = null,
    val bodyType: String? = null,
    val driveType: String? = null,
    val powerKw: Int? = null,
    val powerPs: Int? = null,
    val torqueNm: Int? = null,
    val displacementCc: Int? = null,
    val engineDescription: String? = null,
)

@Serializable
data class VehiclePatch(
    val licensePlate: String? = null,
    val vin: String? = null,
    val hsn: String? = null,
    val tsn: String? = null,
    val color: String? = null,
    val notes: String? = null,
    val purchaseDate: String? = null,
    val purchasePriceCents: Long? = null,
    val purchaseCurrency: String? = null,
    @Transient val clear: Set<String> = emptySet(),
)

/** One inspection per vehicle and type: saving again replaces the existing one. */
@Serializable
data class InspectionInput(
    /** An `InspectionType` name, e.g. `HU`. */
    val type: String,
    val nextDueAt: String,
    val lastPerformedAt: String? = null,
    val reminderWeeksBefore: Int? = null,
    val stickerNumber: String? = null,
    val notes: String? = null,
)

@Serializable
data class InsuranceInput(
    val provider: String,
    val startDate: String,
    val endDate: String,
    val premiumCents: Long,
    val policyNumber: String? = null,
    val currency: String? = null,
    val sfClass: String? = null,
    /** An `InsuranceCoverageType` name; LIABILITY when omitted. */
    val coverageType: String? = null,
    val autoRenew: Boolean? = null,
    val notes: String? = null,
)

@Serializable
data class InsurancePatch(
    val provider: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val premiumCents: Long? = null,
    val policyNumber: String? = null,
    val currency: String? = null,
    val sfClass: String? = null,
    val coverageType: String? = null,
    val autoRenew: Boolean? = null,
    val notes: String? = null,
    @Transient val clear: Set<String> = emptySet(),
)

@Serializable
data class DocumentInput(
    /** A `DocumentCategory` name, e.g. `INVOICE`. */
    val category: String? = null,
    val title: String? = null,
    val maintenanceRecordId: String? = null,
)
