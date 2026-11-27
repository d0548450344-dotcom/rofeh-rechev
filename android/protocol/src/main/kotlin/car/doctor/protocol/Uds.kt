package car.doctor.protocol

/**
 * One control module on an 11-bit CAN bus, addressed physically: requests go to [request],
 * answers come from [response] (usually request + 8).
 */
data class ModuleAddress(val request: Int, val response: Int) {
    init {
        require(request in 0x700..0x7FF && response in 0x700..0x7FF) { "11-bit diagnostic IDs only" }
        require(request != 0x7DF) { "0x7DF is the functional (broadcast) address" }
    }
}

/** Status of one module code, from the UDS / KWP status byte. */
data class ModuleCode(
    /** "C0035", "B1000"… */
    val code: String,
    /** UDS failure-type byte (ISO 15031-6), e.g. 0x1F. Null for KWP. */
    val failureType: Int?,
    val confirmed: Boolean,
    val pending: Boolean,
    val failingNow: Boolean,
)

/**
 * Read-only UDS (ISO 14229) and KWP2000 (ISO 14230) requests for modules outside the engine:
 * ABS, airbags. Only reporting sub-functions exist here; nothing writes, clears, resets,
 * opens a session or asks for security access.
 */
object Uds {
    /** Services [Elm327Protocol.requestModule] will send. */
    val MODULE_READ_SERVICES: Set<Int> = setOf(0x18, 0x19, 0x21, 0x22)

    /** UDS 0x19 sub-functions allowed: 0x01 count by status mask, 0x02 report by status mask. */
    val READ_DTC_SUBFUNCTIONS: Set<Int> = setOf(0x01, 0x02)

    /** Status bits kept as faults: testFailed (0), pendingDTC (2), confirmedDTC (3). */
    private const val FAULT_BITS = 0x0D

    init {
        check(MODULE_READ_SERVICES.none { it in WRITE_AND_FLASH_SERVICES || it == 0x04 })
    }

    /** UDS 19 02 FF: every code whose status has any bit set. Filtered to real faults on decode. */
    fun readDtcs(): ProtocolRequest = ProtocolRequest(0x19, listOf(0x02, 0xFF))

    /** KWP 18 02 FF 00: codes by status, all groups. For modules that do not speak UDS. */
    fun readDtcsKwp(): ProtocolRequest = ProtocolRequest(0x18, listOf(0x02, 0xFF, 0x00))

    fun readDataById(did: Int): ProtocolRequest = ProtocolRequest(0x22, listOf((did shr 8) and 0xFF, did and 0xFF))

    /** Payload of a positive 0x59 answer: [subfunction, availability mask, (b1 b2 b3 status)*]. */
    fun decodeDtcs(response: ProtocolResponse): ProtocolOutcome<List<ModuleCode>> {
        if (response.service != 0x59) return bad("service")
        val p = response.payload
        if (p.size < 2 || p[0] != 0x02) return bad("subfunction")
        val body = p.drop(2)
        if (body.size % 4 != 0) return bad("length")
        return ProtocolOutcome.Ok(
            body.chunked(4).filter { (it[3] and FAULT_BITS) != 0 && !(it[0] == 0 && it[1] == 0) }.map {
                ModuleCode(
                    code = code(it[0], it[1]),
                    failureType = it[2],
                    confirmed = it[3] and 0x08 != 0,
                    pending = it[3] and 0x04 != 0,
                    failingNow = it[3] and 0x01 != 0,
                )
            }.distinctBy { it.code to it.failureType },
        )
    }

    /** Payload of a positive 0x58 answer: [count, (hi lo status)*]. */
    fun decodeDtcsKwp(response: ProtocolResponse): ProtocolOutcome<List<ModuleCode>> {
        if (response.service != 0x58) return bad("service")
        val p = response.payload
        if (p.isEmpty()) return bad("length")
        val body = p.drop(1)
        if (body.size % 3 != 0) return bad("length")
        return ProtocolOutcome.Ok(
            body.chunked(3).filter { !(it[0] == 0 && it[1] == 0) }.map {
                // KWP status: bit 6 = stored ("present" varies by maker), bit 5/6 together = active.
                ModuleCode(code(it[0], it[1]), null, confirmed = true, pending = false, failingNow = it[2] and 0x40 != 0)
            }.distinctBy { it.code },
        )
    }

    /** Negative answer code from a 0x7F response, e.g. 0x11 service not supported. */
    fun negativeCode(response: ProtocolResponse): Int? =
        if (response.service == 0x7F && response.payload.size >= 2) response.payload[1] else null

    /** Same two-byte layout as OBD-II codes (SAE J2012): letter from the top two bits. */
    fun code(hi: Int, lo: Int): String {
        val letter = "PCBU"[(hi shr 6) and 0x3]
        return "%c%X%X%X%X".format(letter, (hi shr 4) and 0x3, hi and 0xF, (lo shr 4) and 0xF, lo and 0xF)
    }

    private fun bad(what: String): ProtocolOutcome<Nothing> =
        ProtocolOutcome.Failed(ProtocolFailure.FromProtocol(ProtocolError.UnexpectedResponse(what)))
}
