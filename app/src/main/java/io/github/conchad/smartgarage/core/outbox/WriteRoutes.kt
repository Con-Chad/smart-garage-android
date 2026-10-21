package io.github.conchad.smartgarage.core.outbox

import io.github.conchad.smartgarage.core.cache.CachedRow
import io.github.conchad.smartgarage.core.cache.OutboxEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Where each cached resource is written. The `/sync` keys and the URL paths differ for four of them (D-035). */
object WriteRoutes {
    val apiPath: Map<String, String> = mapOf(
        "vehicles" to "vehicles",
        "fuelEntries" to "fuel",
        "maintenanceRecords" to "records",
        "schedules" to "schedules",
        "odometerLogs" to "odometer",
        "expenses" to "expenses",
        "documents" to "documents",
        "notes" to "notes",
        "wishlistItems" to "wishlist",
        "inspections" to "inspections",
        "insurancePolicies" to "insurance",
    )

    /** The request path for an entry: the collection for a plain POST, `/{id}[/{subpath}]` for everything else. */
    fun path(entry: OutboxEntry): String {
        val base = collectionPath(entry.resource)
        if (entry.method == "POST" && entry.subpath == null) return base
        return buildString {
            append(base).append('/').append(entry.targetId)
            entry.subpath?.let { append('/').append(it) }
        }
    }

    fun itemPath(resource: String, id: String) = "${collectionPath(resource)}/$id"

    private fun collectionPath(resource: String) =
        "/api/v1/" + (apiPath[resource] ?: throw IllegalArgumentException("no write route for $resource"))
}

/** Client-side ids for rows created offline; replaced by the server's id when the create replays (D-A16). */
object TempIds {
    private const val PREFIX = "tmp-"
    private val pattern = Regex("tmp-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun next(): String = PREFIX + UUID.randomUUID()

    fun isTemp(id: String): Boolean = id.startsWith(PREFIX)

    /** Every temp id mentioned in [text]. */
    fun findAll(text: String?): Set<String> = text?.let { pattern.findAll(it).map { m -> m.value }.toSet() }.orEmpty()
}

/** One server row as the cache stores it; shared by the pull and the replay. */
object ServerRows {
    fun toCachedRow(resource: String, json: JsonObject): CachedRow? {
        val id = json["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val vehicleId = if (resource == "vehicles") id else json["vehicleId"]?.jsonPrimitive?.contentOrNull
        return CachedRow(
            resource = resource,
            id = id,
            vehicleId = vehicleId,
            updatedAt = json["updatedAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            json = json.toString(),
        )
    }
}

/** Whether an entry mentions [id] anywhere: as its target, its vehicle, or inside its JSON. */
fun OutboxEntry.references(id: String): Boolean =
    targetId == id || vehicleId == id || body.contains(id) || localJson?.contains(id) == true

/** The entry with every mention of [from] replaced by [to]. */
fun OutboxEntry.remapped(from: String, to: String): OutboxEntry = copy(
    targetId = targetId.replace(from, to),
    vehicleId = vehicleId?.replace(from, to),
    body = body.replace(from, to),
    localJson = localJson?.replace(from, to),
)
