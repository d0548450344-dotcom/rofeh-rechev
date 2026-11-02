package car.doctor.core.catalog

import car.doctor.core.model.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Online updates for the fault-code texts.
 *
 * The app downloads shared/dtc/generic-dtc-he.json from the repository and, if it
 * passes [DtcSource.parse], installs it as an overlay. The built-in table (DTC_DB) always
 * stays underneath, so a bad or missing download never removes a description.
 * This file does no networking; the app layer fetches the text.
 */
object DtcSource {
    /** Where the app fetches updates from. Public, read-only. */
    const val URL = "https://raw.githubusercontent.com/d0548450344-dotcom/rofeh-rechev/main/shared/dtc/generic-dtc-he.json"

    const val MAX_BYTES = 2_000_000
    private const val MAX_CODES = 20_000
    private const val MAX_TEXT = 400
    private val CODE = Regex("^[PCBU][0-3][0-9A-F]{3}$")
    private val SEVERITY = Severity.entries.associateBy { it.name.lowercase() }

    /**
     * Validates downloaded JSON. Returns null for anything malformed, oversized, or
     * smaller than the built-in table (a truncated file must not shadow good data).
     */
    fun parse(json: String): Map<String, DtcText>? {
        if (json.length > MAX_BYTES) return null
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val codes = root["codes"] as? JsonObject ?: return null
        if (codes.size > MAX_CODES || codes.size < builtInDtcCount()) return null
        val out = HashMap<String, DtcText>(codes.size * 2)
        for ((code, value) in codes) {
            if (!CODE.matches(code)) return null
            val entry = value as? JsonObject ?: return null
            val he = (entry["he"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
            val severity = (entry["severity"] as? JsonPrimitive)?.content?.let(SEVERITY::get) ?: return null
            if (he.length !in 5..MAX_TEXT) return null
            out[code] = DtcText(he, severity)
        }
        return out
    }
}

@Volatile
private var overlay: Map<String, DtcText> = emptyMap()

/** Installs a validated update on top of the built-in table. Replaces any earlier update. */
fun installDtcOverlay(codes: Map<String, DtcText>) {
    overlay = HashMap(codes)
}

internal fun overlayDtc(code: String): DtcText? = overlay[code]

/** How many codes have a description right now (built-in plus update). */
fun knownDtcCount(): Int = (DTC_DB.keys + overlay.keys).size

fun builtInDtcCount(): Int = DTC_DB.size
