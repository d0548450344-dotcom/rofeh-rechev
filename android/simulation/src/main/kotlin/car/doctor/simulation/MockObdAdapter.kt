package car.doctor.simulation

import car.doctor.core.adapter.AdapterCapability
import car.doctor.core.adapter.AdapterInfo
import car.doctor.core.adapter.ObdAdapter
import car.doctor.core.adapter.ObdAdapterFactory
import car.doctor.core.adapter.TransportKind
import car.doctor.core.catalog.EcuCatalog
import car.doctor.core.catalog.OUT_OF_SCOPE
import car.doctor.core.catalog.describeDtc
import car.doctor.core.catalog.vinNote
import car.doctor.core.machine.IgnitionStateMachine
import car.doctor.core.model.AdapterKind
import car.doctor.core.model.ClearOutcome
import car.doctor.core.model.ConnectResult
import car.doctor.core.model.Dtc
import car.doctor.core.model.DtcStatus
import car.doctor.core.model.EcuAccess
import car.doctor.core.model.EcuId
import car.doctor.core.model.EcuReport
import car.doctor.core.model.Fuel
import car.doctor.core.model.Monitor
import car.doctor.core.model.MonitorStatus
import car.doctor.core.model.PidQuality
import car.doctor.core.model.PidValue
import car.doctor.core.model.ScanFacts
import car.doctor.core.model.Vehicle
import car.doctor.protocol.ClearCodesConsent
import car.doctor.protocol.DiagnosticProtocol
import car.doctor.protocol.MockProtocol
import car.doctor.protocol.Obd2
import car.doctor.protocol.Obd2Profile
import car.doctor.protocol.ObdMode
import car.doctor.protocol.ObdPid
import car.doctor.protocol.ProtocolError
import car.doctor.protocol.ProtocolFailure
import car.doctor.protocol.ProtocolOutcome
import car.doctor.protocol.ProtocolRequest
import car.doctor.transport.MockTransport
import car.doctor.transport.ObdTransport
import car.doctor.transport.PipeResponder
import car.doctor.transport.TransportFailure
import kotlin.math.round

/**
 * Read-only adapter. Measurements come back as protocol responses and are decoded.
 * The lab scenario stays on the other side of the byte pipe. This class does not
 * invent RPM, codes, or readiness from the simulator fields.
 */
class MockObdAdapter(
    private val vehicle: Vehicle,
    private val protocol: DiagnosticProtocol,
) : ObdAdapter {
    constructor(vehicle: Vehicle, transport: ObdTransport) : this(
        vehicle,
        MockProtocol(transport, Obd2Profile.withClear),
    )

    private var memoAt: String? = null
    private var memo: ScanFacts? = null
    override val kind: AdapterKind = AdapterKind.SIMULATED
    override val readOnly: Boolean = true

    override fun info(): AdapterInfo = AdapterInfo(
        adapterId = MOCK_ADAPTER_ID,
        adapterType = AdapterKind.SIMULATED,
        firmwareVersion = null,
        capabilities = MOCK_CAPABILITIES,
        transportType = TransportKind.MOCK,
    )

    override fun probe(): ConnectResult {
        if (!IgnitionStateMachine.allowsObd(vehicle.sim.ignition)) {
            return ConnectResult(
                ok = false,
                protocolHe = null,
                errorHe = "אין תקשורת. OBD דורש לפחות מצב הצתה ON. כרגע ההצתה לא מספיקה.",
            )
        }
        when (val linked = protocol.connect()) {
            is ProtocolOutcome.Failed -> return ConnectResult(
                ok = false,
                protocolHe = null,
                errorHe = linkMessage(linked.failure),
            )
            is ProtocolOutcome.Ok -> Unit
        }
        return ConnectResult(
            ok = true,
            protocolHe = EcuCatalog.get(EcuId.ENGINE).protocolHe,
            errorHe = null,
        )
    }

    override fun discoverEcus(readAt: String): List<EcuReport> = snapshot(readAt).ecus

    override fun readDtcs(readAt: String): List<Dtc> = snapshot(readAt).dtcs

    override fun readPids(readAt: String): List<PidValue> = snapshot(readAt).pids

    override fun readFacts(readAt: String): ScanFacts = snapshot(readAt)

    override fun clearCodes(confirmedAt: String): ClearOutcome {
        when (val guard = clearGuard(confirmedAt)) {
            null -> Unit
            else -> return guard
        }
        val consent = ClearCodesConsent(confirmedAt, understoodCodesReturn = true, understoodReadinessReset = true)
        val request = when (val built = Obd2.clearCodes(protocol.capabilities, consent)) {
            is ProtocolOutcome.Failed -> return ClearOutcome.Refused("המתאם המדומה לא מאפשר מחיקה בפרופיל הזה.")
            is ProtocolOutcome.Ok -> built.value
        }
        val response = when (val asked = ask(request)) {
            is Read.Stop -> return ClearOutcome.Failed("המחיקה לא אושרה: הייתה שגיאת תקשורת. ייתכן שחלק מהיחידות לא נמחקו.")
            is Read.Value -> asked.value
        }
        memo = null
        memoAt = null
        return when (Obd2.decodeClear(response)) {
            is ProtocolOutcome.Ok -> ClearOutcome.Cleared
            is ProtocolOutcome.Failed -> ClearOutcome.Refused(ENGINE_RUNNING_HE)
        }
    }

    /** Checks on the (simulated) car itself: engine stopped, vehicle still. */
    private fun clearGuard(readAt: String): ClearOutcome? {
        val rpm = readGuardPid(ObdPid.ENGINE_RPM, readAt)
        val speed = readGuardPid(ObdPid.VEHICLE_SPEED, readAt)
        if (speed == null) return ClearOutcome.Refused("לא הצלחנו לוודא שהרכב עומד. לא בוצעה מחיקה.")
        if (speed > 0.0) return ClearOutcome.Refused("הרכב בתנועה. עוצרים לפני מחיקת קודים.")
        if (rpm != null && rpm > 0.0) return ClearOutcome.Refused(ENGINE_RUNNING_HE)
        return null
    }

    private fun readGuardPid(pid: ObdPid, readAt: String): Double? {
        val request = (Obd2.requestPid(pid, protocol.capabilities) as? ProtocolOutcome.Ok)?.value ?: return null
        val response = (ask(request) as? Read.Value)?.value ?: return null
        return (Obd2.decodePid(response) as? ProtocolOutcome.Ok)?.value?.value
    }

    override fun release() {
        protocol.disconnect()
    }

    fun rejectIfUnsupported(capability: AdapterCapability): TransportFailure? {
        if (capability in info().capabilities) return null
        return TransportFailure.UnsupportedOperation(capability.name)
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
        if (!IgnitionStateMachine.allowsObd(vehicle.sim.ignition)) {
            val reason = "אין הזנת הצתה. OBD דורש לפחות מצב ON, ולכן היחידה לא נשאלה באמת."
            return ScanFacts(
                readAt = readAt,
                fuel = vehicle.fuel,
                protocolHe = null,
                vinNoteHe = vinNote(vehicle.vin),
                ecus = EcuId.entries.map { report(it, EcuAccess.NOT_SCANNED, reason) },
                gaps = OUT_OF_SCOPE,
                dtcs = emptyList(),
                pids = emptyList(),
                readiness = emptyList(),
                engineTimeout = false,
            )
        }

        val ice = vehicle.fuel != Fuel.ELECTRIC
        val pids = mutableListOf<PidValue>()
        val dtcs = mutableListOf<Dtc>()

        fun takePid(step: Read<PidValue>): ScanFacts? = when (step) {
            is Read.Stop -> commFailure(readAt, step.failure)
            is Read.Value -> {
                pids += step.value
                null
            }
        }

        takePid(readObdPid(ObdPid.ENGINE_RPM, "rpm", "סל״ד", "rpm", ice, readAt))?.let { return it }
        takePid(readObdPid(ObdPid.VEHICLE_SPEED, "speed", "מהירות רכב", "קמ״ש", true, readAt))?.let { return it }
        takePid(readObdPid(ObdPid.COOLANT_TEMP, "coolant", "טמפרטורת קירור", "°C", ice, readAt))?.let { return it }
        takePid(readObdPid(ObdPid.THROTTLE_POSITION, "throttle", "מצערת", "%", ice, readAt))?.let { return it }
        takePid(readObdPid(ObdPid.CONTROL_MODULE_VOLTAGE, "voltage", "מתח יחידת בקרה", "V", true, readAt))?.let { return it }

        val readiness = when (val ready = readReadiness()) {
            is Read.Stop -> return commFailure(readAt, ready.failure)
            is Read.Value -> ready.value
        }
        when (val stored = readCodes(stored = true, readAt)) {
            is Read.Stop -> return commFailure(readAt, stored.failure)
            is Read.Value -> dtcs += stored.value
        }
        when (val pending = readCodes(stored = false, readAt)) {
            is Read.Stop -> return commFailure(readAt, pending.failure)
            is Read.Value -> dtcs += pending.value
        }

        takePid(readChannel(SimChannel.WHEEL_FL, "wheelFl", "מהירות גלגל קדמי שמאל", "קמ״ש", EcuId.ABS, readAt))?.let { return it }
        takePid(readChannel(SimChannel.WHEEL_FR, "wheelFr", "מהירות גלגל קדמי ימין", "קמ״ש", EcuId.ABS, readAt))?.let { return it }
        takePid(readChannel(SimChannel.WHEEL_RL, "wheelRl", "מהירות גלגל אחורי שמאל", "קמ״ש", EcuId.ABS, readAt))?.let { return it }
        takePid(readChannel(SimChannel.WHEEL_RR, "wheelRr", "מהירות גלגל אחורי ימין", "קמ״ש", EcuId.ABS, readAt))?.let { return it }
        takePid(readChannel(SimChannel.STEER, "steer", "זווית הגה דרך ABS", "°", EcuId.ABS, readAt))?.let { return it }
        takePid(readChannel(SimChannel.BRAKE, "brake", "מתג בלם", "bool", EcuId.ABS, readAt))?.let { return it }
        takePid(readChannel(SimChannel.BCM_VOLTAGE, "bcmVoltage", "מתח מרכב", "V", EcuId.BCM, readAt))?.let { return it }
        when (val srs = ask(SimChannel.request(SimChannel.SRS_CODES))) {
            is Read.Stop -> return commFailure(readAt, srs.failure)
            is Read.Value -> when (val decoded = SimChannel.decode(srs.value)) {
                is ProtocolOutcome.Failed -> return commFailure(readAt, decoded.failure)
                is ProtocolOutcome.Ok -> Unit
            }
        }

        val partial = pids.any { it.expected && it.quality != PidQuality.VALID }
        val engineReason = if (partial) {
            "נענתה חלקית. יש PID צפוי שלא חזר כערך, ולכן אין כיסוי מלא."
        } else {
            "נענתה. נקראו קודים, מוכנות ונתוני PID שנדגמו פעם אחת."
        }
        return ScanFacts(
            readAt = readAt,
            fuel = vehicle.fuel,
            protocolHe = EcuCatalog.get(EcuId.ENGINE).protocolHe,
            vinNoteHe = vinNote(vehicle.vin),
            ecus = listOf(
                report(EcuId.ENGINE, EcuAccess.CHECKED, engineReason),
                report(EcuId.ABS, EcuAccess.CHECKED, "נענתה בערוץ יצרן מדומה. זה לא OBD-II גנרי."),
                report(EcuId.BCM, EcuAccess.CHECKED, "נענתה. נקרא מתח מרכב בלבד."),
                report(EcuId.SRS, EcuAccess.CHECKED, "נענתה לקריאת קודים בלבד. לא נקרא מצב כרית ולא בוצע איפוס."),
            ),
            gaps = OUT_OF_SCOPE,
            dtcs = dtcs,
            pids = pids,
            readiness = readiness,
            engineTimeout = false,
        )
    }

    private fun readObdPid(
        pid: ObdPid,
        id: String,
        nameHe: String,
        unit: String,
        expected: Boolean,
        readAt: String,
    ): Read<PidValue> {
        val request = when (val built = Obd2.requestPid(pid, protocol.capabilities)) {
            is ProtocolOutcome.Failed -> return Read.Stop(built.failure)
            is ProtocolOutcome.Ok -> built.value
        }
        val response = when (val asked = ask(request)) {
            is Read.Stop -> return asked
            is Read.Value -> asked.value
        }
        return when (val decoded = Obd2.decodePid(response)) {
            is ProtocolOutcome.Ok -> Read.Value(
                sample(id, nameHe, decoded.value.value, unit, EcuId.ENGINE, true, expected, readAt),
            )
            is ProtocolOutcome.Failed -> if (isNegative(decoded.failure)) {
                Read.Value(unavailable(id, nameHe, unit, EcuId.ENGINE, true, expected, readAt))
            } else {
                Read.Stop(decoded.failure)
            }
        }
    }

    private fun readReadiness(): Read<List<Monitor>> {
        val request = when (val built = Obd2.requestReadiness(protocol.capabilities)) {
            is ProtocolOutcome.Failed -> return Read.Stop(built.failure)
            is ProtocolOutcome.Ok -> built.value
        }
        val response = when (val asked = ask(request)) {
            is Read.Stop -> return asked
            is Read.Value -> asked.value
        }
        return when (val decoded = Obd2.decodeReadiness(response)) {
            is ProtocolOutcome.Failed -> Read.Stop(decoded.failure)
            is ProtocolOutcome.Ok -> {
                val report = decoded.value
                Read.Value(
                    listOf(
                        monitor("misfire", "החטאות", report.misfireSupported, report.misfireComplete),
                        monitor("fuel", "מערכת דלק", report.fuelSupported, report.fuelComplete),
                        monitor("comprehensive", "רכיבים מקיפים", report.componentsSupported, report.componentsComplete),
                    ),
                )
            }
        }
    }

    private fun readCodes(stored: Boolean, readAt: String): Read<List<Dtc>> {
        val built = if (stored) {
            Obd2.requestStoredCodes(protocol.capabilities)
        } else {
            Obd2.requestPendingCodes(protocol.capabilities)
        }
        val request = when (built) {
            is ProtocolOutcome.Failed -> return Read.Stop(built.failure)
            is ProtocolOutcome.Ok -> built.value
        }
        val response = when (val asked = ask(request)) {
            is Read.Stop -> return asked
            is Read.Value -> asked.value
        }
        val decoded = if (stored) Obd2.decodeStoredCodes(response) else Obd2.decodePendingCodes(response)
        return when (decoded) {
            is ProtocolOutcome.Failed -> Read.Stop(decoded.failure)
            is ProtocolOutcome.Ok -> Read.Value(
                decoded.value.map { code ->
                    val status = if (code.origin == ObdMode.PENDING_CODES) DtcStatus.PENDING else DtcStatus.STORED
                    dtc(code.code, status, EcuId.ENGINE, readAt)
                },
            )
        }
    }

    private fun readChannel(
        id: Int,
        pidId: String,
        nameHe: String,
        unit: String,
        ecuId: EcuId,
        readAt: String,
    ): Read<PidValue> {
        val response = when (val asked = ask(SimChannel.request(id))) {
            is Read.Stop -> return asked
            is Read.Value -> asked.value
        }
        return when (val decoded = SimChannel.decode(response)) {
            is ProtocolOutcome.Ok -> Read.Value(
                sample(pidId, nameHe, decoded.value.value, unit, ecuId, false, true, readAt),
            )
            is ProtocolOutcome.Failed -> if (isNegative(decoded.failure)) {
                Read.Value(unavailable(pidId, nameHe, unit, ecuId, false, true, readAt))
            } else {
                Read.Stop(decoded.failure)
            }
        }
    }

    private fun ask(request: ProtocolRequest): Read<car.doctor.protocol.ProtocolResponse> =
        when (val outcome = protocol.request(request)) {
            is ProtocolOutcome.Failed -> Read.Stop(outcome.failure)
            is ProtocolOutcome.Ok -> Read.Value(outcome.value)
        }

    private fun commFailure(readAt: String, failure: ProtocolFailure): ScanFacts {
        val reason = when (failure) {
            is ProtocolFailure.FromTransport -> when (failure.error) {
                is TransportFailure.Timeout -> "פסק זמן. לא נקראו קודים ולא נתונים."
                is TransportFailure.ProtocolError -> "שגיאת פרוטוקול. לא נקראו קודים ולא נתונים."
                is TransportFailure.Disconnected -> "המתאם מנותק. לא נקראו קודים ולא נתונים."
                else -> "שגיאת תקשורת. לא נקראו קודים ולא נתונים."
            }
            is ProtocolFailure.FromProtocol -> when (failure.error) {
                is ProtocolError.Timeout -> "פסק זמן בפרוטוקול. לא נקראו קודים ולא נתונים."
                else -> "תשובת פרוטוקול לא תקינה. לא נקראו קודים ולא נתונים."
            }
        }
        return ScanFacts(
            readAt = readAt,
            fuel = vehicle.fuel,
            protocolHe = null,
            vinNoteHe = vinNote(vehicle.vin),
            ecus = EcuId.entries.map { report(it, EcuAccess.COMM_ERROR, reason) },
            gaps = OUT_OF_SCOPE,
            dtcs = emptyList(),
            pids = emptyList(),
            readiness = emptyList(),
            engineTimeout = isTimeout(failure),
        )
    }
}

private sealed class Read<out T> {
    class Value<T>(val value: T) : Read<T>()
    class Stop(val failure: ProtocolFailure) : Read<Nothing>()
}

private fun isNegative(failure: ProtocolFailure): Boolean {
    val error = (failure as? ProtocolFailure.FromProtocol)?.error
    return error is ProtocolError.UnexpectedResponse && error.detail == "negative"
}

private fun isTimeout(failure: ProtocolFailure): Boolean = when (failure) {
    is ProtocolFailure.FromTransport -> failure.error is TransportFailure.Timeout
    is ProtocolFailure.FromProtocol -> failure.error is ProtocolError.Timeout
}

private fun linkMessage(failure: ProtocolFailure): String = when (failure) {
    is ProtocolFailure.FromTransport -> linkMessage(failure.error)
    is ProtocolFailure.FromProtocol -> "שגיאת פרוטוקול במתאם המדומה. זו אינה תקלת חיבור."
}

private fun linkMessage(error: TransportFailure): String = when (error) {
    is TransportFailure.ConnectionFailed -> "החיבור המדומה נכשל. לא נוצר קשר עם המתאם."
    is TransportFailure.Timeout -> "פסק זמן בחיבור המדומה. לא התקבלה תשובה."
    is TransportFailure.Disconnected -> "המתאם המדומה מנותק."
    is TransportFailure.UnsupportedOperation -> "הפעולה אינה נתמכת במתאם המדומה."
    is TransportFailure.InvalidState -> "מצב החיבור אינו מאפשר את הפעולה."
    is TransportFailure.TransportError -> "שגיאת תעבורה במתאם המדומה."
    is TransportFailure.ProtocolError -> "שגיאת פרוטוקול במתאם המדומה. זו אינה תקלת חיבור."
}

private fun monitor(id: String, nameHe: String, supported: Boolean, complete: Boolean): Monitor {
    val status = when {
        !supported -> MonitorStatus.UNSUPPORTED
        complete -> MonitorStatus.COMPLETE
        else -> MonitorStatus.INCOMPLETE
    }
    return Monitor(id, nameHe, status)
}

private fun report(id: EcuId, access: EcuAccess, reasonHe: String): EcuReport = EcuReport(
    id = id,
    access = access,
    reasonHe = reasonHe,
    protocolHe = if (access == EcuAccess.CHECKED) EcuCatalog.get(id).protocolHe else null,
)

private fun dtc(code: String, status: DtcStatus, ecuId: EcuId, readAt: String): Dtc {
    val known = describeDtc(code)
    return Dtc(
        code = code,
        status = status,
        ecuId = ecuId,
        descriptionHe = known.descriptionHe,
        severity = known.severity,
        readAt = readAt,
    )
}

private fun sample(
    id: String,
    nameHe: String,
    value: Double,
    unit: String,
    ecuId: EcuId,
    generic: Boolean,
    expected: Boolean,
    readAt: String,
): PidValue = PidValue(
    id = id,
    nameHe = nameHe,
    value = round1(value),
    unit = unit,
    ecuId = ecuId,
    quality = PidQuality.VALID,
    genericObd = generic,
    expected = expected,
    readAt = readAt,
)

private fun unavailable(
    id: String,
    nameHe: String,
    unit: String,
    ecuId: EcuId,
    generic: Boolean,
    expected: Boolean,
    readAt: String,
): PidValue = PidValue(
    id = id,
    nameHe = nameHe,
    value = null,
    unit = unit,
    ecuId = ecuId,
    quality = PidQuality.UNAVAILABLE,
    genericObd = generic,
    expected = expected,
    readAt = readAt,
)

private fun round1(value: Double): Double = round(value * 10.0) / 10.0

private const val ENGINE_RUNNING_HE =
    "המנוע פועל. מכבים את המנוע, משאירים את ההצתה במצב ON, ואז מוחקים. רוב היחידות מסרבות למחוק כשהמנוע מסתובב."

const val MOCK_ADAPTER_ID = "MOCK-OBD-001"

val MOCK_CAPABILITIES: Set<AdapterCapability> = setOf(
    AdapterCapability.READ_DTC,
    AdapterCapability.READ_PID,
    AdapterCapability.READ_READINESS,
    AdapterCapability.MULTI_ECU,
)

class MockObdAdapterFactory(
    val transport: ObdTransport = MockTransport(),
) : ObdAdapterFactory {
    private var pictured: Vehicle? = null
    val protocol: MockProtocol = MockProtocol(transport, Obd2Profile.withClear)

    init {
        if (transport is MockTransport) {
            transport.responder = PipeResponder { request ->
                val vehicle = pictured ?: return@PipeResponder null
                MockEcu.reply(vehicle, request)
            }
        }
    }

    override fun open(vehicle: Vehicle): ObdAdapter {
        pictured = vehicle
        return MockObdAdapter(vehicle, protocol)
    }
}
