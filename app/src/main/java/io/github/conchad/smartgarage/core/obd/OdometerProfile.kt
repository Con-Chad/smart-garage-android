package io.github.conchad.smartgarage.core.obd

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
enum class OdometerKind {
    @SerialName("honda_cluster") HONDA_CLUSTER,
    @SerialName("standard") STANDARD,
    /** The value is PID 0131's counter, not an odometer: run it through [CodesClearedOffset]. */
    @SerialName("codes_cleared") CODES_CLEARED,
    @SerialName("custom") CUSTOM,
}

/**
 * How to read the odometer, in WiCAN's vocabulary so a WiCAN vehicle profile maps 1:1: [initCommands] is its "init"
 * (";"-separated, e.g. "ATSP7;ATST96;"), [headerCommand] its "pid_init" ("ATSHDA60F1;"), [request] its "pid",
 * [expression] the ODOMETER parameter's expression (WiCAN byte numbering, [ByteExpression]). The value × [multiplier]
 * is in [unit]. Built-in kinds are named by the UI (strings per locale); [name] labels a custom or imported one.
 */
@Serializable
data class OdometerProfile(
    val kind: OdometerKind,
    val initCommands: String,
    val headerCommand: String = "",
    val request: String,
    val expression: String,
    val unit: DistanceUnit = DistanceUnit.KM,
    val multiplier: Double = 1.0,
    val name: String? = null,
) {
    /** A stored built-in picks up fixes to its definition; a custom profile is kept as the user wrote it. */
    fun resolved(): OdometerProfile = OdometerProfiles.builtIn(kind) ?: this

    /** Null when the profile can be used, else what's wrong with it (for the custom-profile form). */
    fun problem(): String? {
        ObdRequest.parseOrNull(request) ?: return "request \"$request\" isn't hex"
        runCatching { ByteExpression.parse(expression) }.onFailure { return it.message }
        if (!multiplier.isFinite() || multiplier <= 0) return "multiplier must be above 0"
        return null
    }
}

class WicanImportException(val reason: Reason, message: String) : IllegalArgumentException(message) {
    enum class Reason { NOT_JSON, NO_ODOMETER, BAD_PROFILE }
}

object OdometerProfiles {
    /**
     * Honda's instrument cluster over UDS 22 70 22, from wican-fw's vehicle_profiles.json "Honda: Clarity-2018". The
     * trailing 9 in the request is ELM's response-count suffix and is sent as written.
     */
    val HONDA_CLUSTER = OdometerProfile(
        kind = OdometerKind.HONDA_CLUSTER,
        initCommands = "ATSP7;ATST96;",
        headerCommand = "ATSHDA60F1;",
        request = "2270229",
        expression = "[B12:B14]",
    )

    /** SAE J1979 PID A6: 4 bytes in 0.1 km. (WiCAN's "AAA: Generic" leaves out the /10.) */
    val STANDARD = OdometerProfile(
        kind = OdometerKind.STANDARD,
        initCommands = "ATSP0;",
        request = "01A6",
        expression = "[B3:B6]/10",
    )

    /** PID 0131, distance since DTCs were cleared (WiCAN's DistanceSinceCodeClear), km. */
    val CODES_CLEARED = OdometerProfile(
        kind = OdometerKind.CODES_CLEARED,
        initCommands = "ATSP0;",
        request = "0131",
        expression = "[B3:B4]",
    )

    val DEFAULT = HONDA_CLUSTER
    val builtIns = listOf(HONDA_CLUSTER, STANDARD, CODES_CLEARED)

    fun builtIn(kind: OdometerKind): OdometerProfile? = builtIns.firstOrNull { it.kind == kind }

    /** A custom profile prefilled from [from], so editing a built-in starts from its values. */
    fun custom(from: OdometerProfile = DEFAULT) = from.copy(kind = OdometerKind.CUSTOM)

    @OptIn(ExperimentalSerializationApi::class)
    private val lenient = Json { isLenient = true; allowTrailingComma = true; allowComments = true }

    /**
     * A Custom profile from a pasted WiCAN vehicle profile: the whole file ({"cars": [...]}), a list of cars, one
     * car, or one pid entry; a stray trailing comma from copying out of the list is fine. Takes the first parameter
     * named ODOMETER (any case; "Odometer", "odo" and "mileage" too) and the car it belongs to ([carModel] picks one
     * when the paste holds several). Its pid_init (on the pid, or on the parameter in newer files) becomes the header
     * command.
     */
    fun fromWicanJson(text: String, carModel: String? = null): OdometerProfile {
        val trimmed = text.trim().trimEnd(',').trim()
        val wrapped = if (trimmed.startsWith("{") || trimmed.startsWith("[")) trimmed else "{$trimmed}"
        val root = runCatching { lenient.parseToJsonElement(wrapped) }.getOrElse {
            throw WicanImportException(WicanImportException.Reason.NOT_JSON, "not JSON: ${it.message}")
        }
        val cars = cars(root)
        val chosen = cars.filter { carModel == null || it.str("car_model").equals(carModel, ignoreCase = true) }
        for (car in chosen) {
            val pids = car["pids"] as? JsonArray ?: if ("pid" in car) JsonArray(listOf(car)) else continue
            for (pid in pids.filterIsInstance<JsonObject>()) {
                val param = parameters(pid).firstOrNull { isOdometer(it.str("name")) } ?: continue
                return build(car, pid, param)
            }
        }
        throw WicanImportException(WicanImportException.Reason.NO_ODOMETER, "no ODOMETER parameter in the paste")
    }

    private fun cars(root: JsonElement): List<JsonObject> = when (root) {
        is JsonArray -> root.filterIsInstance<JsonObject>()
        is JsonObject -> (root["cars"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: listOf(root)
        else -> emptyList()
    }

    /** WiCAN files hold parameters as a list of {name, expression, unit}; older snippets as {NAME: {expression, unit}}. */
    private fun parameters(pid: JsonObject): List<JsonObject> = when (val p = pid["parameters"]) {
        is JsonArray -> p.filterIsInstance<JsonObject>()
        is JsonObject -> p.mapNotNull { (name, v) -> (v as? JsonObject)?.let { JsonObject(it + ("name" to JsonPrimitive(name))) } }
        else -> emptyList()
    }

    private fun isOdometer(name: String?): Boolean =
        name != null && name.trim().lowercase().let { it == "odometer" || it == "odo" || it == "mileage" }

    private fun build(car: JsonObject, pid: JsonObject, param: JsonObject): OdometerProfile {
        val bad = WicanImportException.Reason.BAD_PROFILE
        val request = pid.str("pid") ?: throw WicanImportException(bad, "the pid entry has no \"pid\"")
        val expression = param.str("expression") ?: throw WicanImportException(bad, "ODOMETER has no expression")
        val unitText = param.str("unit")
        val unit = DistanceUnit.parse(unitText) ?: throw WicanImportException(bad, "unit \"$unitText\" isn't km or mi")
        val header = listOfNotNull(pid.str("pid_init"), param.str("pid_init")).filter { it.isNotBlank() }
            .joinToString("") { if (it.trim().endsWith(";")) it.trim() else it.trim() + ";" }
        val profile = OdometerProfile(
            kind = OdometerKind.CUSTOM,
            initCommands = car.str("init").orEmpty(),
            headerCommand = header,
            request = request.trim(),
            expression = expression.trim(),
            unit = unit,
            name = car.str("car_model"),
        )
        profile.problem()?.let { throw WicanImportException(bad, it) }
        return profile
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
