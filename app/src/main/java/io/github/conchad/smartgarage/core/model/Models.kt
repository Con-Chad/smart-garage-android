package io.github.conchad.smartgarage.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

// Wire shapes from the server's resource registry (lib/api/v1/resources.ts). Stored units throughout: km, liters,
// integer cents. Dates are ISO strings; enums are Prisma names kept as strings so a new server value never breaks
// decoding. Only fields the app uses are declared; the rest are ignored.

@Serializable
data class Vehicle(
    val id: String,
    val ownerUserId: String,
    val owner: Owner? = null,
    val access: Access = Access(),
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val productionYear: Int? = null,
    val vin: String? = null,
    val licensePlate: String? = null,
    val hsn: String? = null,
    val tsn: String? = null,
    val color: String? = null,
    val currentOdometerKm: Int = 0,
    val purchaseDate: String? = null,
    val purchasePriceCents: Long? = null,
    val purchaseCurrency: String? = null,
    val notes: String? = null,
    val imageDocumentId: String? = null,
    val factorySpecs: Specs? = null,
    val currentSpecs: Specs? = null,
    val updatedAt: String = "",
) {
    /** "2019 Mazda MX-5", falling back to the plate when make and model are empty. */
    val displayName: String
        get() = listOfNotNull(year?.toString(), make, model).joinToString(" ").ifBlank { licensePlate ?: "—" }

    val specs: Specs? get() = currentSpecs ?: factorySpecs
}

@Serializable
data class Owner(val id: String, val username: String, val displayName: String? = null) {
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: username
}

/** `role` is OWNER, EDITOR or VIEWER; viewers get a read-only UI. */
@Serializable
data class Access(val role: String = "OWNER", val canEdit: Boolean = true) {
    val isShared: Boolean get() = role != "OWNER"
}

@Serializable
data class Specs(
    val engineDescription: String? = null,
    val powerKw: Int? = null,
    val powerPs: Int? = null,
    val torqueNm: Int? = null,
    val fuelType: String? = null,
    val displacementCc: Int? = null,
    val cylinders: Int? = null,
    val doors: Int? = null,
    val seats: Int? = null,
    val bodyType: String? = null,
    val driveType: String? = null,
    val transmissionTypes: List<String>? = null,
)

@Serializable
data class MaintenanceTemplate(
    val id: String,
    val slug: String,
    val category: String = "OTHER",
    val nameEn: String,
    val nameDe: String,
)

enum class DueStatus { OK, DUE_SOON, OVERDUE }

@Serializable
data class Schedule(
    val id: String,
    val vehicleId: String,
    val templateId: String? = null,
    val customName: String? = null,
    val category: String = "OTHER",
    val intervalKm: Int? = null,
    val intervalMonths: Int? = null,
    val lastPerformedAt: String? = null,
    val lastOdometerKm: Int? = null,
    val nextDueAt: String? = null,
    val nextDueOdometerKm: Int? = null,
    val estimatedCostCents: Long? = null,
    val currency: String = "EUR",
    val notes: String? = null,
    val dueStatus: String = "OK",
    val itemDefaults: List<MaintenanceItem> = emptyList(),
) {
    val status: DueStatus get() = DueStatus.entries.firstOrNull { it.name == dueStatus } ?: DueStatus.OK

    /** Same rule as the web's `scheduleDisplayName`: custom name, else the template's name in the app language. */
    fun displayName(template: MaintenanceTemplate?, german: Boolean): String =
        customName?.takeIf { it.isNotBlank() }
            ?: template?.let { if (german) it.nameDe else it.nameEn }
            ?: category.lowercase().replaceFirstChar { it.uppercase() }
}

@Serializable
data class MaintenanceItem(
    val id: String? = null,
    val category: String = "OTHER",
    val name: String? = null,
    val brand: String? = null,
    val productName: String? = null,
    val partNumber: String? = null,
    val specification: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val customUnit: String? = null,
    val costCents: Long? = null,
)

@Serializable
data class MaintenanceRecord(
    val id: String,
    val vehicleId: String,
    val scheduleId: String? = null,
    val performedAt: String,
    val odometerKm: Int? = null,
    val costCents: Long = 0,
    val currency: String = "EUR",
    val vendorName: String? = null,
    val title: String? = null,
    val note: String? = null,
    val items: List<MaintenanceItem> = emptyList(),
)

@Serializable
data class FuelEntry(
    val id: String,
    val vehicleId: String,
    val filledAt: String,
    val odometerKm: Int? = null,
    val liters: Double? = null,
    val totalCostCents: Long = 0,
    val currency: String = "EUR",
    val stationName: String? = null,
    val note: String? = null,
)

@Serializable
data class OdometerLog(
    val id: String,
    val vehicleId: String,
    val odometerKm: Int,
    val recordedAt: String,
    val source: String = "manual",
    val note: String? = null,
)

@Serializable
data class Expense(
    val id: String,
    val vehicleId: String,
    val category: String = "OTHER",
    val occurredAt: String,
    val amountCents: Long = 0,
    val currency: String = "EUR",
    val odometerKm: Int? = null,
    val maintenanceRecordId: String? = null,
    val description: String? = null,
)

@Serializable
data class Inspection(
    val id: String,
    val vehicleId: String,
    val type: String = "HU",
    val nextDueAt: String,
    val lastPerformedAt: String? = null,
    val reminderWeeksBefore: Int = 4,
    val stickerNumber: String? = null,
    val notes: String? = null,
)

@Serializable
data class InsurancePolicy(
    val id: String,
    val vehicleId: String,
    val provider: String,
    val policyNumber: String? = null,
    val premiumCents: Long = 0,
    val currency: String = "EUR",
    val coverageType: String = "LIABILITY",
    val startDate: String,
    val endDate: String,
    val autoRenew: Boolean = true,
    val sfClass: String? = null,
    val notes: String? = null,
)

/** An uploaded file; its bytes come from `/api/v1/documents/{id}/content` when opened. */
@Serializable
data class Document(
    val id: String,
    val vehicleId: String? = null,
    val maintenanceRecordId: String? = null,
    val expenseId: String? = null,
    val purpose: String = "OTHER",
    val category: String? = null,
    val title: String? = null,
    val originalFilename: String,
    val mimeType: String,
    val sizeBytes: Long = 0,
    val createdAt: String,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val displayName: String get() = title?.takeIf { it.isNotBlank() } ?: originalFilename
}

/** Notes are private to their author, even on a shared vehicle. */
@Serializable
data class Note(
    val id: String,
    val title: String,
    val content: String = "",
    val vehicleId: String? = null,
    val isPinned: Boolean = false,
    val tags: List<String> = emptyList(),
    val updatedAt: String,
)

@Serializable
data class WishlistItem(
    val id: String,
    val vehicleId: String? = null,
    val title: String,
    val description: String? = null,
    val category: String = "OTHER",
    /** IDEA, PLANNED, ORDERED or DONE. */
    val status: String = "IDEA",
    val priority: Int = 0,
    val targetDate: String? = null,
    val url: String? = null,
    val estimatedCostCents: Long? = null,
    val currency: String = "EUR",
    val createdAt: String,
)

/** A tuning part or upgrade fitted to a vehicle (`modifications` in `/sync`); read-only in the app (D-A16). */
@Serializable
data class Modification(
    val id: String,
    val vehicleId: String,
    /** A `ModificationCategory` name, e.g. `STAGE_1`, `EXHAUST`, `CUSTOM`. */
    val category: String = "CUSTOM",
    val name: String = "",
    val description: String? = null,
    val installedAt: String? = null,
    val costCents: Long? = null,
    val currency: String? = null,
    val addedPowerKw: Int? = null,
    val addedPowerPs: Int? = null,
    val addedTorqueNm: Int? = null,
    val notes: String? = null,
    val isCustom: Boolean = false,
    val createdAt: String = "",
)

/** A field the user defined for every vehicle (`customFields` in `/sync`); its values are [CustomFieldValue]s. */
@Serializable
data class CustomField(
    val id: String,
    val userId: String = "",
    val label: String = "",
    /** TEXT, NUMBER, DATE or BOOLEAN. */
    val fieldType: String = "TEXT",
    val unit: String? = null,
    val position: Int = 0,
    val createdAt: String = "",
)

/** One vehicle's value for a [CustomField]; stored as text whatever the field's type. */
@Serializable
data class CustomFieldValue(
    val id: String,
    val fieldId: String,
    val vehicleId: String,
    val value: String = "",
)

/** Parses the server's ISO timestamps; null for missing or malformed values. */
fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }
