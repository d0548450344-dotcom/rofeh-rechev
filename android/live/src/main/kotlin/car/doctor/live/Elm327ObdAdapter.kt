package car.doctor.live

import car.doctor.core.adapter.AdapterCapability
import car.doctor.core.adapter.AdapterInfo
import car.doctor.core.adapter.ObdAdapter
import car.doctor.core.adapter.ObdAdapterFactory
import car.doctor.core.adapter.TransportKind
import car.doctor.core.catalog.describeDtc
import car.doctor.core.catalog.vinNote
import car.doctor.core.model.AdapterKind
import car.doctor.core.model.ConnectResult
import car.doctor.core.model.Dtc
import car.doctor.core.model.DtcStatus
import car.doctor.core.model.EcuAccess
import car.doctor.core.model.EcuId
import car.doctor.core.model.EcuReport
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.Monitor
import car.doctor.core.model.MonitorStatus
import car.doctor.core.model.PidQuality
import car.doctor.core.model.PidValue
import car.doctor.core.model.ScanFacts
import car.doctor.core.model.SystemGap
import car.doctor.core.model.Vehicle
import car.doctor.protocol.Elm327Protocol
import car.doctor.protocol.ElmErrorKind
import car.doctor.protocol.Obd2
import car.doctor.protocol.ObdMode
import car.doctor.protocol.ObdPid
import car.doctor.protocol.ProtocolError
import car.doctor.protocol.ProtocolFailure
import car.doctor.protocol.ProtocolOutcome
import car.doctor.protocol.ProtocolRequest
import car.doctor.protocol.ProtocolResponse
import car.doctor.transport.ObdTransport
import car.doctor.transport.TransportFailure
import kotlin.math.round

/**
 * A real car behind an ELM327 adapter (Bluetooth Classic, BLE or Wi-Fi).
 *
 * Reads generic OBD-II only: live data (mode 01), readiness, stored and pending
 * codes. ABS, body and airbag controllers need manufacturer protocols and are
 * reported as not scanned rather than guessed. Nothing here clears codes.
 */
class Elm327ObdAdapter(
    private val vehicle: Vehicle,
    private val protocol: Elm327Protocol,
    override val kind: AdapterKind,
) : ObdAdapter {
    override val readOnly: Boolean = true

    private var memoAt: String? = null
    private var memo: ScanFacts? = null

    override fun info(): AdapterInfo = AdapterInfo(
        adapterId = protocol.identity?.banner ?: "ELM327",
        adapterType = kind,
        firmwareVersion = protocol.identity?.banner,
        capabilities = setOf(
            AdapterCapability.READ_DTC,
            AdapterCapability.READ_PID,
            AdapterCapability.READ_READINESS,
        ),
        transportType = when (kind) {
            AdapterKind.BLE -> TransportKind.BLE
            AdapterKind.CLASSIC -> TransportKind.CLASSIC
            AdapterKind.WIFI -> TransportKind.WIFI
            AdapterKind.SIMULATED -> TransportKind.MOCK
        },
    )

    override fun probe(): ConnectResult = when (val linked = protocol.connect()) {
        is ProtocolOutcome.Ok -> ConnectResult(ok = true, protocolHe = protocolLabel(), errorHe = null)
        is ProtocolOutcome.Failed -> ConnectResult(
            ok = false,
            protocolHe = null,
            errorHe = connectMessage(linked.failure, protocol.lastAdapterError),
        )
    }

    override fun discoverEcus(readAt: String): List<EcuReport> = snapshot(readAt).ecus
    override fun readDtcs(readAt: String): List<Dtc> = snapshot(readAt).dtcs
    override fun readPids(readAt: String): List<PidValue> = snapshot(readAt).pids
    override fun readFacts(readAt: String): ScanFacts = snapshot(readAt)

    override fun observedIgnition(readAt: String): IgnitionState? {
        val facts = snapshot(readAt)
        if (facts.ecus.none { it.id == EcuId.ENGINE && it.access == EcuAccess.CHECKED }) return null
        val rpm = facts.pids.firstOrNull { it.id == "rpm" }?.value
        return if (rpm != null && rpm >= RUNNING_RPM) IgnitionState.RUNNING else IgnitionState.ON
    }

    override fun release() {
        protocol.disconnect()
    }

    private fun snapshot(readAt: String): ScanFacts {
        val cached = memo
        if (cached != null && memoAt == readAt) return cached
        val built = load(readAt)
        memoAt = readAt
        memo = built
        return built
    }

    private fun load(readAt: String): ScanFacts {
        val ice = vehicle.fuel != Fuel.ELECTRIC
        val pids = mutableListOf<PidValue>()
        for (spec in LIVE_PIDS) {
            val expected = if (spec.engineOnly) ice else true
            when (val read = readPid(spec, expected, readAt)) {
                is Step.Stop -> return commFailure(readAt, read.failure)
                is Step.Value -> pids += read.value
            }
        }

        val readiness = when (val read = readReadiness()) {
            is Step.Stop -> return commFailure(readAt, read.failure)
            is Step.Value -> read.value
        }

        val dtcs = mutableListOf<Dtc>()
        for ((mode, status) in listOf(ObdMode.STORED_CODES to DtcStatus.STORED, ObdMode.PENDING_CODES to DtcStatus.PENDING)) {
            when (val read = readCodes(mode, status, readAt)) {
                is Step.Stop -> return commFailure(readAt, read.failure)
                is Step.Value -> dtcs += read.value
            }
        }

        val partial = pids.any { it.expected && it.quality != PidQuality.VALID }
        val engineReason = if (partial) {
            "נענתה דרך OBD-II. חלק מהנתונים הצפויים לא נתמכים ברכב הזה, ולכן הכיסוי חלקי."
        } else {
            "נענתה דרך OBD-II. נקראו קודים, מוכנות ונתונים חיים פעם אחת."
        }
        return ScanFacts(
            readAt = readAt,
            fuel = vehicle.fuel,
            protocolHe = protocolLabel(),
            vinNoteHe = vinNote(vehicle.vin),
            ecus = listOf(
                EcuReport(EcuId.ENGINE, EcuAccess.CHECKED, engineReason, protocolLabel()),
                notScanned(EcuId.ABS, "ABS / ESC"),
                notScanned(EcuId.BCM, "מחשב המרכב"),
                notScanned(EcuId.SRS, "מערכת כריות האוויר"),
            ),
            gaps = LIVE_GAPS,
            dtcs = dtcs.distinctBy { it.code to it.status },
            pids = pids,
            readiness = readiness,
            engineTimeout = false,
        )
    }

    private fun readPid(spec: PidSpec, expected: Boolean, readAt: String): Step<PidValue> {
        val supported = protocol.identity?.supportedPids.orEmpty()
        if (supported.isNotEmpty() && spec.pid.code <= 0x20 && spec.pid.code !in supported) {
            return Step.Value(unavailable(spec, expected, readAt))
        }
        val request = when (val built = Obd2.requestPid(spec.pid, protocol.capabilities)) {
            is ProtocolOutcome.Failed -> return Step.Value(unavailable(spec, expected, readAt))
            is ProtocolOutcome.Ok -> built.value
        }
        val response = when (val asked = ask(request)) {
            is Step.Stop -> return if (isSoft(asked.failure)) Step.Value(unavailable(spec, expected, readAt)) else asked
            is Step.Value -> asked.value
        }
        return when (val decoded = Obd2.decodePid(response)) {
            is ProtocolOutcome.Ok -> Step.Value(
                PidValue(
                    id = spec.id,
                    nameHe = spec.nameHe,
                    value = round1(decoded.value.value),
                    unit = spec.unit,
                    ecuId = EcuId.ENGINE,
                    quality = PidQuality.VALID,
                    genericObd = true,
                    expected = expected,
                    readAt = readAt,
                ),
            )
            is ProtocolOutcome.Failed -> Step.Value(unavailable(spec, expected, readAt))
        }
    }

    private fun readReadiness(): Step<List<Monitor>> {
        val request = when (val built = Obd2.requestReadiness(protocol.capabilities)) {
            is ProtocolOutcome.Failed -> return Step.Value(emptyList())
            is ProtocolOutcome.Ok -> built.value
        }
        val response = when (val asked = ask(request)) {
            is Step.Stop -> return if (isSoft(asked.failure)) Step.Value(emptyList()) else asked
            is Step.Value -> asked.value
        }
        return when (val decoded = Obd2.decodeReadiness(response)) {
            is ProtocolOutcome.Failed -> Step.Value(emptyList())
            is ProtocolOutcome.Ok -> {
                val r = decoded.value
                Step.Value(
                    listOf(
                        monitor("misfire", "החטאות", r.misfireSupported, r.misfireComplete),
                        monitor("fuel", "מערכת דלק", r.fuelSupported, r.fuelComplete),
                        monitor("comprehensive", "רכיבים מקיפים", r.componentsSupported, r.componentsComplete),
                    ),
                )
            }
        }
    }

    private fun readCodes(mode: ObdMode, status: DtcStatus, readAt: String): Step<List<Dtc>> {
        val response = when (val asked = ask(ProtocolRequest(mode.service))) {
            // NO DATA to a code request means the ECU has none to report.
            is Step.Stop -> return if (isSoft(asked.failure)) Step.Value(emptyList()) else asked
            is Step.Value -> asked.value
        }
        val decoded = if (mode == ObdMode.STORED_CODES) Obd2.decodeStoredCodes(response) else Obd2.decodePendingCodes(response)
        return when (decoded) {
            is ProtocolOutcome.Failed -> Step.Stop(decoded.failure)
            is ProtocolOutcome.Ok -> Step.Value(
                decoded.value.map { code ->
                    val known = describeDtc(code.code)
                    Dtc(
                        code = code.code,
                        status = status,
                        ecuId = EcuId.ENGINE,
                        descriptionHe = known.descriptionHe,
                        severity = known.severity,
                        readAt = readAt,
                    )
                },
            )
        }
    }

    private fun ask(request: ProtocolRequest): Step<ProtocolResponse> = when (val outcome = protocol.request(request)) {
        is ProtocolOutcome.Failed -> Step.Stop(outcome.failure)
        is ProtocolOutcome.Ok -> Step.Value(outcome.value)
    }

    private fun commFailure(readAt: String, failure: ProtocolFailure): ScanFacts {
        val reason = when (failure) {
            is ProtocolFailure.FromTransport -> when (failure.error) {
                is TransportFailure.Disconnected -> "החיבור למתאם נותק באמצע הסריקה. לא נשמרו קודים ולא נתונים."
                is TransportFailure.Timeout -> "המתאם הפסיק לענות. לא נשמרו קודים ולא נתונים."
                else -> "שגיאת תקשורת עם המתאם. לא נשמרו קודים ולא נתונים."
            }
            is ProtocolFailure.FromProtocol -> when (failure.error) {
                is ProtocolError.Timeout -> "פסק זמן מול הרכב. לא נשמרו קודים ולא נתונים."
                else -> "הרכב החזיר תשובה לא תקינה. לא נשמרו קודים ולא נתונים."
            }
        }
        return ScanFacts(
            readAt = readAt,
            fuel = vehicle.fuel,
            protocolHe = null,
            vinNoteHe = vinNote(vehicle.vin),
            ecus = EcuId.entries.map { EcuReport(it, EcuAccess.COMM_ERROR, reason, null) },
            gaps = LIVE_GAPS,
            dtcs = emptyList(),
            pids = emptyList(),
            readiness = emptyList(),
            engineTimeout = isTimeout(failure),
        )
    }

    private fun protocolLabel(): String {
        val id = protocol.identity
        val bus = busLabel(id?.protocolNumber)
        val chip = id?.banner?.let { " · $it" }.orEmpty()
        return "$bus$chip"
    }

    private fun unavailable(spec: PidSpec, expected: Boolean, readAt: String) = PidValue(
        id = spec.id,
        nameHe = spec.nameHe,
        value = null,
        unit = spec.unit,
        ecuId = EcuId.ENGINE,
        quality = PidQuality.UNAVAILABLE,
        genericObd = true,
        expected = expected,
        readAt = readAt,
    )

    private sealed class Step<out T> {
        class Value<T>(val value: T) : Step<T>()
        class Stop(val failure: ProtocolFailure) : Step<Nothing>()
    }

    private data class PidSpec(
        val pid: ObdPid,
        val id: String,
        val nameHe: String,
        val unit: String,
        val engineOnly: Boolean,
    )

    companion object {
        const val RUNNING_RPM = 400.0

        private val LIVE_PIDS = listOf(
            PidSpec(ObdPid.ENGINE_RPM, "rpm", "סל״ד", "rpm", engineOnly = true),
            PidSpec(ObdPid.VEHICLE_SPEED, "speed", "מהירות רכב", "קמ״ש", engineOnly = false),
            PidSpec(ObdPid.COOLANT_TEMP, "coolant", "טמפרטורת קירור", "°C", engineOnly = true),
            PidSpec(ObdPid.THROTTLE_POSITION, "throttle", "מצערת", "%", engineOnly = true),
            PidSpec(ObdPid.CONTROL_MODULE_VOLTAGE, "voltage", "מתח יחידת בקרה", "V", engineOnly = false),
        )

        val LIVE_GAPS: List<SystemGap> = listOf(
            SystemGap("abs", "ABS / ESC", "דורש פרוטוקול יצרן. מתאם OBD-II גנרי לא קורא את היחידה הזו."),
            SystemGap("srs", "כריות אוויר / SRS", "דורש פרוטוקול יצרן. לא נקראו קודים ולא מצב כריות."),
            SystemGap("bcm", "מרכב / BCM", "דורש פרוטוקול יצרן. היחידה לא נסרקה."),
            SystemGap("tcm", "תיבת הילוכים / TCM", "רק קודי פליטה שהיא מדווחת דרך OBD-II מופיעים כאן."),
            SystemGap("tpms", "לחץ אוויר בצמיגים", "לא נסרק. אין מזה מסקנה."),
        )

        fun busLabel(number: Char?): String = when (number?.uppercaseChar()) {
            '1' -> "SAE J1850 PWM"
            '2' -> "SAE J1850 VPW"
            '3' -> "ISO 9141-2"
            '4' -> "ISO 14230-4 KWP · אתחול איטי"
            '5' -> "ISO 14230-4 KWP · אתחול מהיר"
            '6' -> "ISO 15765-4 CAN · 11-bit · 500 kbit/s"
            '7' -> "ISO 15765-4 CAN · 29-bit · 500 kbit/s"
            '8' -> "ISO 15765-4 CAN · 11-bit · 250 kbit/s"
            '9' -> "ISO 15765-4 CAN · 29-bit · 250 kbit/s"
            'A' -> "SAE J1939 CAN"
            'B', 'C' -> "CAN מוגדר משתמש"
            else -> "OBD-II · פרוטוקול לא זוהה"
        }
    }
}

/**
 * Hands the bench one adapter per link. The bench calls [open] several times per
 * session (connect, scan, release); every call must reach the same protocol.
 */
class Elm327AdapterFactory(
    private val kind: AdapterKind,
    transport: ObdTransport,
) : ObdAdapterFactory {
    val protocol: Elm327Protocol = Elm327Protocol(transport)

    override fun open(vehicle: Vehicle): ObdAdapter = Elm327ObdAdapter(vehicle, protocol, kind)
}

private fun notScanned(id: EcuId, nameHe: String) = EcuReport(
    id = id,
    access = EcuAccess.NOT_SCANNED,
    reasonHe = "$nameHe דורש פרוטוקול יצרן. מתאם OBD-II גנרי לא שואל את היחידה הזו.",
    protocolHe = null,
)

private fun monitor(id: String, nameHe: String, supported: Boolean, complete: Boolean) = Monitor(
    id = id,
    nameHe = nameHe,
    status = when {
        !supported -> MonitorStatus.UNSUPPORTED
        complete -> MonitorStatus.COMPLETE
        else -> MonitorStatus.INCOMPLETE
    },
)

/** "No data" and "not supported" are answers about the car, not a broken link. */
private fun isSoft(failure: ProtocolFailure): Boolean {
    val error = (failure as? ProtocolFailure.FromProtocol)?.error ?: return false
    return error is ProtocolError.MissingPayload || error is ProtocolError.Unsupported ||
        (error is ProtocolError.UnexpectedResponse && error.detail == "negative")
}

private fun isTimeout(failure: ProtocolFailure): Boolean = when (failure) {
    is ProtocolFailure.FromTransport -> failure.error is TransportFailure.Timeout
    is ProtocolFailure.FromProtocol -> failure.error is ProtocolError.Timeout
}

private fun connectMessage(failure: ProtocolFailure, adapterError: ElmErrorKind?): String {
    if (adapterError == ElmErrorKind.NO_VEHICLE) {
        return "המתאם מחובר, אבל הרכב לא ענה. בודקים שההצתה במצב ON (או שהמנוע פועל) ושהמתאם יושב היטב בשקע ה-OBD."
    }
    if (adapterError != null) {
        return "המתאם מחובר, אבל הייתה תקלה בתקשורת עם הרכב. מנסים שוב, ואם זה חוזר מנתקים ומחברים את המתאם."
    }
    return when (failure) {
        is ProtocolFailure.FromTransport -> when (failure.error) {
            is TransportFailure.ConnectionFailed ->
                "לא הצלחנו להתחבר למתאם. בודקים שהוא מחובר לרכב, דולק, ושהטלפון מצומד אליו (בבלוטות׳) או מחובר לרשת שלו (ב-Wi-Fi)."
            is TransportFailure.Timeout -> "המתאם לא ענה בזמן."
            is TransportFailure.Disconnected -> "החיבור למתאם נותק."
            else -> "שגיאת תקשורת עם המתאם."
        }
        is ProtocolFailure.FromProtocol -> when (failure.error) {
            is ProtocolError.Timeout -> "המתאם מחובר אבל לא עונה לפקודות. ייתכן שזה לא מתאם ELM327, או שהוא תפוס על ידי אפליקציה אחרת."
            is ProtocolError.Unsupported -> "המתאם דחה פקודת אתחול. ייתכן שזה לא מתאם ELM327 תואם."
            else -> "המתאם החזיר תשובה לא צפויה בזמן האתחול."
        }
    }
}

private fun round1(value: Double): Double = round(value * 10.0) / 10.0
