package car.doctor.protocol

/**
 * Turns the text an ELM327 prints for one OBD request into byte messages.
 *
 * Assumes headers off (ATH0) and CAN auto-formatting on (the ELM default), so every
 * message starts at the service byte. Handles single frames, ISO-TP multi-frame
 * output ("014" / "0: .." / "1: .."), several ECUs answering at once, and the
 * adapter's own status words ("NO DATA", "UNABLE TO CONNECT", ...).
 */
object ElmResponseParser {

    sealed class Result {
        /** One entry per ECU message, each starting at the service byte. */
        data class Messages(val messages: List<List<Int>>) : Result()

        /** The bus answered, but no ECU had data for this request. */
        data object NoData : Result()

        /** The adapter reported a condition instead of data. [word] is the adapter's text. */
        data class AdapterError(val kind: ElmErrorKind, val word: String) : Result()
    }

    private val ERRORS: List<Pair<String, ElmErrorKind>> = listOf(
        "UNABLE TO CONNECT" to ElmErrorKind.NO_VEHICLE,
        "BUS INIT" to ElmErrorKind.NO_VEHICLE, // only when followed by ERROR; checked below
        "CAN ERROR" to ElmErrorKind.BUS,
        "BUS ERROR" to ElmErrorKind.BUS,
        "BUS BUSY" to ElmErrorKind.BUS,
        "FB ERROR" to ElmErrorKind.BUS,
        "DATA ERROR" to ElmErrorKind.CORRUPT,
        "<DATA ERROR" to ElmErrorKind.CORRUPT,
        "RX ERROR" to ElmErrorKind.CORRUPT,
        "BUFFER FULL" to ElmErrorKind.CORRUPT,
        "STOPPED" to ElmErrorKind.STOPPED,
        "LV RESET" to ElmErrorKind.ADAPTER_RESET,
        "ACT ALERT" to ElmErrorKind.ADAPTER_RESET,
        "ERR" to ElmErrorKind.BUS,
        "?" to ElmErrorKind.UNKNOWN_COMMAND,
    )

    private val LENGTH_LINE = Regex("^[0-9A-F]{3}$")
    private val FRAME_LINE = Regex("^([0-9A-F]):(.*)$")
    private val HEX_ONLY = Regex("^[0-9A-F]+$")

    /** Splits raw adapter output into clean lines without the prompt, NULs or echo. */
    fun lines(raw: String, sentCommand: String? = null): List<String> {
        val echo = sentCommand?.replace(" ", "")?.uppercase()
        return raw
            .replace("\u0000", "")
            .replace(">", "")
            .split('\r', '\n')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .filterNot { echo != null && it.replace(" ", "") == echo }
            .filterNot { it.startsWith("SEARCHING") }
            .map { if (it.startsWith("BUS INIT:") && it.endsWith("OK")) "" else it }
            .filter { it.isNotEmpty() }
    }

    fun parse(raw: String, sentCommand: String? = null): Result {
        val lines = lines(raw, sentCommand)
        if (lines.isEmpty()) return Result.NoData
        for (line in lines) {
            if (line == "NO DATA") continue
            classify(line)?.let { return Result.AdapterError(it, line) }
        }
        val messages = mutableListOf<List<Int>>()
        var pending: MutableList<Int>? = null
        var expected = 0

        fun flush() {
            val open = pending ?: return
            if (open.isNotEmpty()) messages += open.take(if (expected > 0) expected else open.size)
            pending = null
            expected = 0
        }

        for (line in lines) {
            if (line == "NO DATA") continue
            val compact = line.replace(" ", "")
            when {
                LENGTH_LINE.matches(compact) && !line.contains(' ') -> {
                    flush()
                    expected = compact.toInt(16)
                    pending = mutableListOf()
                }
                FRAME_LINE.matches(compact) -> {
                    val body = FRAME_LINE.find(compact)!!.groupValues[2]
                    val bytes = hexBytes(body) ?: continue
                    val open = pending ?: mutableListOf<Int>().also { pending = it }
                    open += bytes
                    if (expected > 0 && open.size >= expected) flush()
                }
                HEX_ONLY.matches(compact) -> {
                    flush()
                    hexBytes(compact)?.let { messages += it }
                }
                else -> Unit // stray text such as a version banner; ignore it
            }
        }
        flush()
        return if (messages.isEmpty()) Result.NoData else Result.Messages(messages)
    }

    fun classify(line: String): ElmErrorKind? {
        if (line.startsWith("BUS INIT")) {
            return if (line.contains("ERROR")) ElmErrorKind.NO_VEHICLE else null
        }
        for ((word, kind) in ERRORS) {
            if (word == "BUS INIT") continue
            if (word == "?" || word == "ERR") {
                if (line == word || (word == "ERR" && line.startsWith("ERR") && line.drop(3).all { it.isDigit() })) {
                    return kind
                }
                continue
            }
            if (line.contains(word)) return kind
        }
        return null
    }

    private fun hexBytes(compact: String): List<Int>? {
        if (compact.isEmpty() || compact.length % 2 != 0 || !HEX_ONLY.matches(compact)) return null
        return compact.chunked(2).map { it.toInt(16) }
    }

    /**
     * Joins the trouble-code bytes of every ECU message for a mode 03/07/0A reply.
     * CAN replies carry a count byte after the service byte (odd data length);
     * K-line and J1850 replies do not (always three code pairs per line).
     */
    fun codePairs(messages: List<List<Int>>, positiveService: Int): List<Int> {
        val out = mutableListOf<Int>()
        for (message in messages) {
            if (message.isEmpty() || message[0] != positiveService) continue
            var data = message.drop(1)
            if (data.size % 2 == 1) data = data.drop(1)
            out += data
        }
        return out
    }
}

enum class ElmErrorKind {
    /** No ECU answered on any protocol. Usually ignition off or a bad OBD socket. */
    NO_VEHICLE,
    BUS,
    CORRUPT,
    STOPPED,
    ADAPTER_RESET,
    UNKNOWN_COMMAND,
}
