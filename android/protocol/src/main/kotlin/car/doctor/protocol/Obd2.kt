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

    /** Clear codes are out of scope. A profile that lists the mode is still refused. */
    fun clearCodes(capabilities: ProtocolCapabilities): ProtocolOutcome<ProtocolRequest> {
        if (ObdMode.CLEAR_CODES in capabilities.supportedModes) return unsupported("clear-codes")
        return unsupported("clear-codes")
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
        val value = when (pid) {
            ObdPid.ENGINE_RPM -> ((used[0] shl 8) + used[1]) / 4.0
            ObdPid.VEHICLE_SPEED -> used[0].toDouble()
            ObdPid.COOLANT_TEMP -> used[0] - 40.0
            ObdPid.CONTROL_MODULE_VOLTAGE -> ((used[0] shl 8) + used[1]) / 1000.0
            ObdPid.THROTTLE_POSITION -> used[0] * 100.0 / 255.0
            ObdPid.MONITOR_STATUS -> error("handled above")
        }
        val formula = when (pid) {
            ObdPid.ENGINE_RPM -> PidFormula.ENGINE_RPM
            ObdPid.VEHICLE_SPEED -> PidFormula.VEHICLE_SPEED
            ObdPid.COOLANT_TEMP -> PidFormula.COOLANT_TEMP
            ObdPid.CONTROL_MODULE_VOLTAGE -> PidFormula.CONTROL_MODULE_VOLTAGE
            ObdPid.THROTTLE_POSITION -> PidFormula.THROTTLE_POSITION
            ObdPid.MONITOR_STATUS -> error("handled above")
        }
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
