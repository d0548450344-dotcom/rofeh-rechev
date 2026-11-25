package car.doctor.protocol

/**
 * OBD-II foundation on top of [DiagnosticProtocol].
 * Decodes structured numbers. It does not decide that a vehicle is healthy,
 * and it does not clear codes, flash, or open security access.
 */
enum class ObdMode(val service: Int) {
    CURRENT_DATA(0x01),
    FREEZE_FRAME(0x02),
    STORED_CODES(0x03),
    CLEAR_CODES(0x04),
    PENDING_CODES(0x07),
    VEHICLE_INFO(0x09),
    PERMANENT_CODES(0x0A),
}

enum class ObdPid(val code: Int, val label: String, val unit: String, val byteCount: Int) {
    MONITOR_STATUS(0x01, "Monitor status", "bitmap", 4),
    COOLANT_TEMP(0x05, "Coolant Temperature", "°C", 1),
    ENGINE_RPM(0x0C, "Engine RPM", "rpm", 2),
    VEHICLE_SPEED(0x0D, "Vehicle Speed", "km/h", 1),
    THROTTLE_POSITION(0x11, "Throttle Position", "%", 1),
    CONTROL_MODULE_VOLTAGE(0x42, "Control Module Voltage", "V", 2),

    // Deep scan (SAE J1979 standard formulas)
    FREEZE_DTC(0x02, "DTC that caused freeze frame", "dtc", 2),
    ENGINE_LOAD(0x04, "Calculated Engine Load", "%", 1),
    SHORT_FUEL_TRIM_B1(0x06, "Short Term Fuel Trim Bank 1", "%", 1),
    LONG_FUEL_TRIM_B1(0x07, "Long Term Fuel Trim Bank 1", "%", 1),
    INTAKE_MAP(0x0B, "Intake Manifold Pressure", "kPa", 1),
    TIMING_ADVANCE(0x0E, "Timing Advance", "°", 1),
    INTAKE_AIR_TEMP(0x0F, "Intake Air Temperature", "°C", 1),
    MAF_RATE(0x10, "MAF Air Flow Rate", "g/s", 2),
    RUN_TIME(0x1F, "Run Time Since Engine Start", "s", 2),
    DISTANCE_WITH_MIL(0x21, "Distance Traveled With MIL On", "km", 2),
    FUEL_LEVEL(0x2F, "Fuel Tank Level", "%", 1),
    DISTANCE_SINCE_CLEAR(0x31, "Distance Since Codes Cleared", "km", 2),
    AMBIENT_AIR_TEMP(0x46, "Ambient Air Temperature", "°C", 1),
    ;

    companion object {
        fun fromCode(code: Int): ObdPid? = entries.find { it.code == code }
    }
}

enum class PidFormula(val expression: String) {
    ENGINE_RPM("((A*256)+B)/4"),
    VEHICLE_SPEED("A"),
    COOLANT_TEMP("A-40"),
    THROTTLE_POSITION("A*100/255"),
    CONTROL_MODULE_VOLTAGE("((A*256)+B)/1000"),
    PERCENT("A*100/255"),
    FUEL_TRIM("(A-128)*100/128"),
    RAW_A("A"),
    TEMPERATURE("A-40"),
    TIMING("A/2-64"),
    MAF("((A*256)+B)/100"),
    WORD("(A*256)+B"),
}

data class PidReading(
    val pid: ObdPid,
    val mode: ObdMode,
    val raw: List<Int>,
    val value: Double,
    val unit: String,
    val formula: PidFormula,
)

/** A code as carried on the wire. Not a finding and not a repair instruction. */
data class TroubleCode(
    val code: String,
    val raw: Int,
    val origin: ObdMode,
)

data class ReadinessReport(
    val milOn: Boolean,
    val emissionCodeCount: Int,
    val sparkIgnition: Boolean,
    val misfireSupported: Boolean,
    val misfireComplete: Boolean,
    val fuelSupported: Boolean,
    val fuelComplete: Boolean,
    val componentsSupported: Boolean,
    val componentsComplete: Boolean,
    val raw: List<Int>,
)

/**
 * Proof that the user went through the clear-codes warnings. It carries no authority
 * beyond mode 04: flashing, coding and security access stay impossible.
 */
data class ClearCodesConsent(
    val confirmedAt: String,
    val understoodCodesReturn: Boolean,
    val understoodReadinessReset: Boolean,
) {
    init {
        require(understoodCodesReturn && understoodReadinessReset) { "both warnings must be acknowledged" }
    }
}

/**
 * Services that write to a controller: reprogramming (flashing / tuning), coding,
 * security access, routines, resets, session changes and UDS code clearing.
 * No protocol in this app may send them. Listed so tests can prove it.
 */
val WRITE_AND_FLASH_SERVICES: Set<Int> = setOf(
    0x10, // diagnostic session control (programming session)
    0x11, // ECU reset
    0x14, // UDS clear diagnostic information
    0x27, // security access (seed / key)
    0x28, // communication control
    0x2E, // write data by identifier (coding)
    0x2F, // input / output control
    0x31, // routine control (erase memory, checks)
    0x34, // request download (flash)
    0x35, // request upload
    0x36, // transfer data (flash)
    0x37, // request transfer exit
    0x3B, // KWP write data by local identifier
    0x3D, // write memory by address
    0x85, // control DTC setting
)

/** Value and formula for one PID's data bytes (SAE J1979). */
internal fun decodeValue(pid: ObdPid, used: List<Int>): Pair<Double, PidFormula> {
    fun word() = ((used[0] shl 8) + used[1]).toDouble()
    return when (pid) {
        ObdPid.ENGINE_RPM -> word() / 4.0 to PidFormula.ENGINE_RPM
        ObdPid.VEHICLE_SPEED -> used[0].toDouble() to PidFormula.VEHICLE_SPEED
        ObdPid.COOLANT_TEMP -> used[0] - 40.0 to PidFormula.COOLANT_TEMP
        ObdPid.CONTROL_MODULE_VOLTAGE -> word() / 1000.0 to PidFormula.CONTROL_MODULE_VOLTAGE
        ObdPid.THROTTLE_POSITION -> used[0] * 100.0 / 255.0 to PidFormula.THROTTLE_POSITION
        ObdPid.ENGINE_LOAD, ObdPid.FUEL_LEVEL -> used[0] * 100.0 / 255.0 to PidFormula.PERCENT
        ObdPid.SHORT_FUEL_TRIM_B1, ObdPid.LONG_FUEL_TRIM_B1 -> (used[0] - 128) * 100.0 / 128.0 to PidFormula.FUEL_TRIM
        ObdPid.INTAKE_MAP -> used[0].toDouble() to PidFormula.RAW_A
        ObdPid.TIMING_ADVANCE -> used[0] / 2.0 - 64.0 to PidFormula.TIMING
        ObdPid.INTAKE_AIR_TEMP, ObdPid.AMBIENT_AIR_TEMP -> used[0] - 40.0 to PidFormula.TEMPERATURE
        ObdPid.MAF_RATE -> word() / 100.0 to PidFormula.MAF
        ObdPid.RUN_TIME, ObdPid.DISTANCE_WITH_MIL, ObdPid.DISTANCE_SINCE_CLEAR -> word() to PidFormula.WORD
        ObdPid.MONITOR_STATUS, ObdPid.FREEZE_DTC -> error("not a measured value")
    }
}

object Obd2Profile {
    val foundation: ProtocolCapabilities = ProtocolCapabilities(
        supportsObd2 = true,
        supportedModes = setOf(
            ObdMode.CURRENT_DATA,
            ObdMode.STORED_CODES,
            ObdMode.PENDING_CODES,
        ),
        supportedPids = setOf(
            ObdPid.MONITOR_STATUS,
            ObdPid.COOLANT_TEMP,
            ObdPid.ENGINE_RPM,
            ObdPid.VEHICLE_SPEED,
            ObdPid.THROTTLE_POSITION,
            ObdPid.CONTROL_MODULE_VOLTAGE,
        ),
        supportsDtc = true,
        supportsReadiness = true,
    )

    /**
     * Deep scan: freeze frame (02), vehicle info / VIN (09), permanent codes (0A) and more
     * live sensors. All read-only.
     */
    val deep: ProtocolCapabilities = foundation.copy(
        supportedModes = foundation.supportedModes + setOf(ObdMode.FREEZE_FRAME, ObdMode.VEHICLE_INFO, ObdMode.PERMANENT_CODES),
        supportedPids = ObdPid.entries.toSet(),
    )

    /** Foundation plus mode 04. Still no write, flash or security services. */
    val withClear: ProtocolCapabilities = foundation.copy(
        supportedModes = foundation.supportedModes + ObdMode.CLEAR_CODES,
    )
}

object Obd2 {
    private const val POSITIVE_OFFSET = 0x40
    private const val NEGATIVE = 0x7F

    fun requestPid(pid: ObdPid, capabilities: ProtocolCapabilities): ProtocolOutcome<ProtocolRequest> {
        if (!capabilities.supportsObd2 || ObdMode.CURRENT_DATA !in capabilities.supportedModes) {
            return unsupported("mode-01")
        }
        if (pid !in capabilities.supportedPids) return unsupported("pid")
        return ProtocolOutcome.Ok(ProtocolRequest(ObdMode.CURRENT_DATA.service, listOf(pid.code)))
    }

    fun requestStoredCodes(capabilities: ProtocolCapabilities): ProtocolOutcome<ProtocolRequest> {
        if (!capabilities.supportsDtc || ObdMode.STORED_CODES !in capabilities.supportedModes) {
            return unsupported("stored-codes")
        }
        return ProtocolOutcome.Ok(ProtocolRequest(ObdMode.STORED_CODES.service))
    }

    fun requestPendingCodes(capabilities: ProtocolCapabilities): ProtocolOutcome<ProtocolRequest> {
        if (!capabilities.supportsDtc || ObdMode.PENDING_CODES !in capabilities.supportedModes) {
            return unsupported("pending-codes")
        }
        return ProtocolOutcome.Ok(ProtocolRequest(ObdMode.PENDING_CODES.service))
    }

    fun requestReadiness(capabilities: ProtocolCapabilities): ProtocolOutcome<ProtocolRequest> {
        if (!capabilities.supportsReadiness) return unsupported("readiness")
        return requestPid(ObdPid.MONITOR_STATUS, capabilities)
    }

    /** Mode 02, frame 0: the value a PID had when the first stored code was set. */
    fun requestFreezeFrame(pid: ObdPid, capabilities: ProtocolCapabilities): ProtocolOutcome<ProtocolRequest> {
        if (ObdMode.FREEZE_FRAME !in capabilities.supportedModes) return unsupported("freeze-frame")
        if (pid == ObdPid.MONITOR_STATUS) return unsupported("pid")
        return ProtocolOutcome.Ok(ProtocolRequest(ObdMode.FREEZE_FRAME.service, listOf(pid.code, 0x00)))
    }

    /** Mode 02 answer: [pid, frame, data...]. Null value for the "no freeze frame" DTC 0000. */
    fun decodeFreezeFrame(response: ProtocolResponse): ProtocolOutcome<PidReading> {
        negative(response)?.let { return ProtocolOutcome.Failed(ProtocolFailure.FromProtocol(it)) }
        if (response.service != ObdMode.FREEZE_FRAME.service + POSITIVE_OFFSET) {
            return protocol(ProtocolError.UnexpectedResponse("service"))
        }
        val pid = response.payload.getOrNull(0)?.let(ObdPid::fromCode) ?: return protocol(ProtocolError.Undecodable("pid"))
        if (pid == ObdPid.FREEZE_DTC || pid == ObdPid.MONITOR_STATUS) return protocol(ProtocolError.UnexpectedResponse("pid"))
        val data = response.payload.drop(2)
        if (data.size < pid.byteCount) return protocol(ProtocolError.MissingPayload("pid-data"))
        val used = data.take(pid.byteCount)
        val (value, formula) = decodeValue(pid, used)
        return ProtocolOutcome.Ok(PidReading(pid, ObdMode.FREEZE_FRAME, used, value, pid.unit, formula))
    }

    /** Mode 02 PID 02: which code froze the frame. Null when no frame is stored (0000). */
    fun decodeFreezeFrameDtc(response: ProtocolResponse): ProtocolOutcome<String?> {
        negative(response)?.let { return ProtocolOutcome.Failed(ProtocolFailure.FromProtocol(it)) }
        if (response.service != ObdMode.FREEZE_FRAME.service + POSITIVE_OFFSET || response.payload.getOrNull(0) != 0x02) {
            return protocol(ProtocolError.UnexpectedResponse("service"))
        }
        val data = response.payload.drop(2)
        if (data.size < 2) return protocol(ProtocolError.MissingPayload("freeze-dtc"))
        if (data[0] == 0 && data[1] == 0) return ProtocolOutcome.Ok(null)
        return ProtocolOutcome.Ok(formatCode(data[0], data[1]))
    }

    /** Mode 09 PID 02: the VIN the engine ECU reports. */
    fun requestVin(capabilities: ProtocolCapabilities): ProtocolOutcome<ProtocolRequest> {
        if (ObdMode.VEHICLE_INFO !in capabilities.supportedModes) return unsupported("vehicle-info")
        return ProtocolOutcome.Ok(ProtocolRequest(ObdMode.VEHICLE_INFO.service, listOf(0x02)))
    }

    /**
     * Keeps the 17 VIN characters from a mode 09 answer, whether it came as one CAN message
     * (with a count byte) or as several K-line lines (with sequence bytes). Null if what
     * remains is not a plausible VIN.
     */
    fun decodeVin(response: ProtocolResponse): ProtocolOutcome<String?> {
        negative(response)?.let { return ProtocolOutcome.Failed(ProtocolFailure.FromProtocol(it)) }
        if (response.service != ObdMode.VEHICLE_INFO.service + POSITIVE_OFFSET) {
            return protocol(ProtocolError.UnexpectedResponse("service"))
        }
        val chars = response.payload.drop(1)
            .filter { it in 0x30..0x39 || it in 0x41..0x5A }
            .map { it.toChar() }
            .joinToString("")
        val vin = chars.takeLast(17)
        return ProtocolOutcome.Ok(vin.takeIf { VIN.matches(it) })
    }

    fun requestPermanentCodes(capabilities: ProtocolCapabilities): ProtocolOutcome<ProtocolRequest> {
        if (ObdMode.PERMANENT_CODES !in capabilities.supportedModes) return unsupported("permanent-codes")
        return ProtocolOutcome.Ok(ProtocolRequest(ObdMode.PERMANENT_CODES.service))
    }

    private val VIN = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    /**
     * Mode 04: clear stored and pending codes, freeze frame and readiness.
     * Only a profile that explicitly lists [ObdMode.CLEAR_CODES] may build it, and the
     * caller must hold a [ClearCodesConsent] (the user confirmed twice).
     */
    fun clearCodes(
        capabilities: ProtocolCapabilities,
        consent: ClearCodesConsent?,
    ): ProtocolOutcome<ProtocolRequest> {
        if (consent == null) return unsupported("clear-codes-without-consent")
        if (ObdMode.CLEAR_CODES !in capabilities.supportedModes) return unsupported("clear-codes")
        return ProtocolOutcome.Ok(ProtocolRequest(ObdMode.CLEAR_CODES.service))
    }

    /** Positive answer is service 0x44. A negative answer carries the ECU's reason (NRC). */
    fun decodeClear(response: ProtocolResponse): ProtocolOutcome<Unit> {
        if (response.service == NEGATIVE) {
            val nrc = response.payload.getOrNull(1) ?: 0
            return protocol(ProtocolError.UnexpectedResponse("negative-clear-%02X".format(nrc)))
        }
        if (response.service != ObdMode.CLEAR_CODES.service + POSITIVE_OFFSET) {
            return protocol(ProtocolError.UnexpectedResponse("service"))
        }
        return ProtocolOutcome.Ok(Unit)
    }

    fun decodePid(response: ProtocolResponse): ProtocolOutcome<PidReading> {
        negative(response)?.let { return ProtocolOutcome.Failed(ProtocolFailure.FromProtocol(it)) }
        if (response.service != ObdMode.CURRENT_DATA.service + POSITIVE_OFFSET) {
            return protocol(ProtocolError.UnexpectedResponse("service"))
        }
        if (response.payload.isEmpty()) return protocol(ProtocolError.MissingPayload("pid"))
        val pid = ObdPid.fromCode(response.payload[0]) ?: return protocol(ProtocolError.Undecodable("pid"))
        if (pid == ObdPid.MONITOR_STATUS) return protocol(ProtocolError.UnexpectedResponse("readiness-pid"))
        val data = response.payload.drop(1)
        if (data.size < pid.byteCount) return protocol(ProtocolError.MissingPayload("pid-data"))
        val used = data.take(pid.byteCount)
        if (pid == ObdPid.FREEZE_DTC) return protocol(ProtocolError.UnexpectedResponse("freeze-dtc-pid"))
        val (value, formula) = decodeValue(pid, used)
        return ProtocolOutcome.Ok(
            PidReading(
                pid = pid,
                mode = ObdMode.CURRENT_DATA,
                raw = used,
                value = value,
                unit = pid.unit,
                formula = formula,
            ),
        )
    }

    fun decodeStoredCodes(response: ProtocolResponse): ProtocolOutcome<List<TroubleCode>> =
        decodeCodes(response, ObdMode.STORED_CODES)

    fun decodePendingCodes(response: ProtocolResponse): ProtocolOutcome<List<TroubleCode>> =
        decodeCodes(response, ObdMode.PENDING_CODES)

    /** Mode 0A: codes only the car itself can erase, after it confirms the repair. */
    fun decodePermanentCodes(response: ProtocolResponse): ProtocolOutcome<List<TroubleCode>> =
        decodeCodes(response, ObdMode.PERMANENT_CODES)

    fun decodeReadiness(response: ProtocolResponse): ProtocolOutcome<ReadinessReport> {
        negative(response)?.let { return ProtocolOutcome.Failed(ProtocolFailure.FromProtocol(it)) }
        if (response.service != ObdMode.CURRENT_DATA.service + POSITIVE_OFFSET) {
            return protocol(ProtocolError.UnexpectedResponse("service"))
        }
        if (response.payload.isEmpty()) return protocol(ProtocolError.MissingPayload("readiness"))
        if (response.payload[0] != ObdPid.MONITOR_STATUS.code) {
            return protocol(ProtocolError.UnexpectedResponse("pid"))
        }
        val data = response.payload.drop(1)
        if (data.size < ObdPid.MONITOR_STATUS.byteCount) {
            return protocol(ProtocolError.MissingPayload("readiness-data"))
        }
        val used = data.take(4)
        val a = used[0]
        val b = used[1]
        val d = used[3]
        val supported = { bit: Int -> (b shr bit) and 1 == 1 }
        val complete = { bit: Int -> supported(bit) && (d shr bit) and 1 == 0 }
        return ProtocolOutcome.Ok(
            ReadinessReport(
                milOn = (a shr 7) and 1 == 1,
                emissionCodeCount = a and 0x7F,
                sparkIgnition = (b shr 3) and 1 == 0,
                misfireSupported = supported(0),
                misfireComplete = complete(0),
                fuelSupported = supported(1),
                fuelComplete = complete(1),
                componentsSupported = supported(2),
                componentsComplete = complete(2),
                raw = used,
            ),
        )
    }

    private fun decodeCodes(response: ProtocolResponse, origin: ObdMode): ProtocolOutcome<List<TroubleCode>> {
        negative(response)?.let { return ProtocolOutcome.Failed(ProtocolFailure.FromProtocol(it)) }
        val expectedService = origin.service + POSITIVE_OFFSET
        if (response.service != expectedService) return protocol(ProtocolError.UnexpectedResponse("service"))
        val payload = response.payload
        if (payload.size % 2 != 0) return protocol(ProtocolError.UnexpectedLength("dtc-pairs"))
        val codes = mutableListOf<TroubleCode>()
        var index = 0
        while (index < payload.size) {
            val hi = payload[index]
            val lo = payload[index + 1]
            index += 2
            if (hi == 0 && lo == 0) continue
            val raw = (hi shl 8) or lo
            codes += TroubleCode(code = formatCode(hi, lo), raw = raw, origin = origin)
        }
        return ProtocolOutcome.Ok(codes)
    }

    private fun formatCode(hi: Int, lo: Int): String {
        val type = when ((hi shr 6) and 0x3) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val d1 = (hi shr 4) and 0x3
        val d2 = hi and 0xF
        val d3 = (lo shr 4) and 0xF
        val d4 = lo and 0xF
        return "$type$d1${hex(d2)}${hex(d3)}${hex(d4)}"
    }

    private fun hex(nibble: Int): String = nibble.toString(16).uppercase()

    private fun negative(response: ProtocolResponse): ProtocolError? {
        if (response.service != NEGATIVE) return null
        return ProtocolError.UnexpectedResponse("negative")
    }

    private fun unsupported(detail: String): ProtocolOutcome<ProtocolRequest> =
        protocol(ProtocolError.Unsupported(detail))

    private fun <T> protocol(error: ProtocolError): ProtocolOutcome<T> =
        ProtocolOutcome.Failed(ProtocolFailure.FromProtocol(error))
}
