package car.doctor.simulation

import car.doctor.core.catalog.CANNOT_DETERMINE_HE
import car.doctor.core.catalog.CLEAR_SCAN_HE
import car.doctor.core.catalog.GAP_BADGE_HE
import car.doctor.core.catalog.ecuAccessLabel
import car.doctor.core.model.Confidence
import car.doctor.core.model.Coverage
import car.doctor.core.model.DtcStatus
import car.doctor.core.model.EcuAccess
import car.doctor.core.model.EcuId
import car.doctor.core.model.FaultId
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.MonitorStatus
import car.doctor.core.model.NewVehicle
import car.doctor.core.model.PidQuality
import car.doctor.core.model.Posture
import car.doctor.core.persistence.SnapshotGarageRepository
import car.doctor.core.present.SessionPresenter
import car.doctor.core.rules.DiagnosticRules
import car.doctor.core.session.BenchEffect
import car.doctor.core.session.IdSource
import car.doctor.core.session.IsoClock
import car.doctor.core.session.VehicleBench
import car.doctor.protocol.FrameCodec
import car.doctor.protocol.FrameParse
import car.doctor.protocol.Obd2
import car.doctor.protocol.ObdMode
import car.doctor.protocol.ObdPid
import car.doctor.protocol.PidFormula
import car.doctor.protocol.ProtocolError
import car.doctor.protocol.ProtocolEvent
import car.doctor.protocol.ProtocolFailure
import car.doctor.protocol.ProtocolOutcome
import car.doctor.protocol.ProtocolResponse
import car.doctor.transport.MockTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdFixtureDecodingTest {
    @Test
    fun standardPidsRoundTripThroughBytes() {
        assertPid(ObdFixtures.rpm(800.0), ObdPid.ENGINE_RPM, 800.0, "rpm", PidFormula.ENGINE_RPM)
        assertPid(ObdFixtures.speed(62.0), ObdPid.VEHICLE_SPEED, 62.0, "km/h", PidFormula.VEHICLE_SPEED)
        assertPid(ObdFixtures.coolant(90.0), ObdPid.COOLANT_TEMP, 90.0, "°C", PidFormula.COOLANT_TEMP)
        assertPid(ObdFixtures.throttleRaw(255), ObdPid.THROTTLE_POSITION, 100.0, "%", PidFormula.THROTTLE_POSITION)
        assertPid(ObdFixtures.voltage(14.0), ObdPid.CONTROL_MODULE_VOLTAGE, 14.0, "V", PidFormula.CONTROL_MODULE_VOLTAGE)
    }

    @Test
    fun readinessComesFromTheMonitorBitmap() {
        val healthy = parsed(ObdFixtures.readiness(storedCount = 0, misfireComplete = true, ice = true))
        val report = (Obd2.decodeReadiness(healthy) as ProtocolOutcome.Ok).value
        assertFalse(report.milOn)
        assertEquals(0, report.emissionCodeCount)
        assertTrue(report.misfireSupported)
        assertTrue(report.misfireComplete)
        assertTrue(report.fuelComplete)
        assertTrue(report.componentsComplete)

        val open = parsed(ObdFixtures.readiness(storedCount = 1, misfireComplete = false, ice = true))
        val flagged = (Obd2.decodeReadiness(open) as ProtocolOutcome.Ok).value
        assertFalse(flagged.milOn)
        assertEquals(1, flagged.emissionCodeCount)
        assertTrue(flagged.misfireSupported)
        assertFalse(flagged.misfireComplete)

        val missing = Obd2.decodeReadiness(response(0x41, listOf(0x01, 0x00))) as ProtocolOutcome.Failed
        assertTrue((missing.failure as ProtocolFailure.FromProtocol).error is ProtocolError.MissingPayload)
    }

    @Test
    fun storedAndPendingCodesKeepOriginAndRaw() {
        val stored = (Obd2.decodeStoredCodes(parsed(ObdFixtures.stored(listOf("P0301", "P0300")))) as ProtocolOutcome.Ok).value
        assertEquals(listOf("P0301", "P0300"), stored.map { it.code })
        assertEquals(0x0301, stored[0].raw)
        assertTrue(stored.all { it.origin == ObdMode.STORED_CODES })

        val pending = (Obd2.decodePendingCodes(parsed(ObdFixtures.pending(listOf("P0562")))) as ProtocolOutcome.Ok).value
        assertEquals("P0562", pending.single().code)
        assertEquals(ObdMode.PENDING_CODES, pending.single().origin)
        assertEquals(0x0562, pending.single().raw)

        val none = (Obd2.decodeStoredCodes(parsed(ObdFixtures.stored(emptyList()))) as ProtocolOutcome.Ok).value
        assertTrue(none.isEmpty())
    }
}

class ObdNegativeDecodingTest {
    @Test
    fun unsupportedAndMalformedResponsesDoNotInventData() {
        val negative = Obd2.decodePid(parsed(ObdFixtures.negative(0x01))) as ProtocolOutcome.Failed
        val rejected = (negative.failure as ProtocolFailure.FromProtocol).error
        assertTrue(rejected is ProtocolError.UnexpectedResponse)
        assertEquals("negative", (rejected as ProtocolError.UnexpectedResponse).detail)

        val unknown = Obd2.decodePid(response(0x41, listOf(0xFF, 0x01))) as ProtocolOutcome.Failed
        assertTrue((unknown.failure as ProtocolFailure.FromProtocol).error is ProtocolError.Undecodable)

        val short = Obd2.decodePid(response(0x41, listOf(0x0C, 0x10))) as ProtocolOutcome.Failed
        assertTrue((short.failure as ProtocolFailure.FromProtocol).error is ProtocolError.MissingPayload)

        val broken = FrameCodec.parse(ObdFixtures.malformed().map { it.toInt() and 0xFF }) as FrameParse.Bad
        assertTrue(broken.error is ProtocolError.MalformedFrame)

        val odd = Obd2.decodeStoredCodes(response(0x43, listOf(0x03))) as ProtocolOutcome.Failed
        assertTrue((odd.failure as ProtocolFailure.FromProtocol).error is ProtocolError.UnexpectedLength)
        val negativeCodes = Obd2.decodeStoredCodes(parsed(ObdFixtures.negative(0x03))) as ProtocolOutcome.Failed
        assertTrue((negativeCodes.failure as ProtocolFailure.FromProtocol).error is ProtocolError.UnexpectedResponse)
    }
}

class QuickScanProtocolChainTest {
    @Test
    fun quickScanValuesMatchTheDecodedFrames() {
        val rig = running(Posture.STRAIGHT)
        assertTrue(rig.bench.quickScan() is BenchEffect.Accepted)
        val session = rig.latest()
        val vehicle = rig.bench.snapshot().vehicle!!
        val rpmFrame = MockEcu.reply(vehicle, FrameCodec.encode(0x01, listOf(0x0C)).toByteArray())!!
        val decoded = (Obd2.decodePid(parsed(rpmFrame)) as ProtocolOutcome.Ok).value
        val rpm = session.pids.single { it.id == "rpm" }
        assertEquals(decoded.value, rpm.value!!, 0.001)
        assertEquals(2140.0, rpm.value!!, 0.001)
        assertEquals("rpm", rpm.unit)
        assertEquals("סל״ד", rpm.nameHe)
        assertEquals(62.0, session.pids.single { it.id == "speed" }.value!!, 0.001)
        assertEquals(93.0, session.pids.single { it.id == "coolant" }.value!!, 0.001)
        assertEquals(14.1, session.pids.single { it.id == "voltage" }.value!!, 0.001)
        assertEquals(PidQuality.VALID, session.pids.single { it.id == "throttle" }.quality)
        assertTrue(session.pids.single { it.id == "wheelFl" }.genericObd.not())
        assertEquals(MonitorStatus.COMPLETE, session.readiness.single { it.id == "misfire" }.status)
        assertEquals(0, session.dtcs.size)
        assertEquals(16, rig.protocol.events().count { it is ProtocolEvent.ResponseReceived })
        assertTrue(rig.protocol.events().none { it is ProtocolEvent.Failed })
        assertEquals(CLEAR_SCAN_HE, SessionPresenter.present(session).headlineHe)
        assertTrue(session.gaps.all { it.coverage == Coverage.NOT_SCANNED })
        assertTrue(SessionPresenter.present(session).gaps.all { it.badgeHe == GAP_BADGE_HE })
        assertTrue(session.ecus.all { it.access == EcuAccess.CHECKED })
    }

    @Test
    fun rulesSeeDecodedMisfireCodesAndNotBytes() {
        val rig = running(Posture.IDLE)
        rig.bench.toggleFault(FaultId.MISFIRE)
        rig.bench.quickScan()
        val session = rig.latest()
        val stored = session.dtcs.single { it.code == "P0301" }
        assertEquals(DtcStatus.STORED, stored.status)
        assertEquals(EcuId.ENGINE, stored.ecuId)
        val pending = session.dtcs.single { it.code == "P0300" }
        assertEquals(DtcStatus.PENDING, pending.status)
        val again = DiagnosticRules.evaluate(rig.bench.snapshot().let { session.toFacts() }, session.ignition)
        val finding = again.single { it.code == "P0301" }
        assertEquals("misfire-code", finding.ruleId)
        assertEquals("הקוד נקרא מהיחידה", finding.claimHe)
        assertEquals(Confidence.HIGH, finding.confidence)
        assertTrue(finding.evidence.any { it.contains("P0301") })
        assertFalse(finding.evidence.any { it.contains("0x") })
        assertEquals(MonitorStatus.INCOMPLETE, session.readiness.single { it.id == "misfire" }.status)
        assertEquals(MonitorStatus.COMPLETE, session.readiness.single { it.id == "fuel" }.status)
    }

    @Test
    fun lowBatteryAndWheelsStillComeFromDecodedFrames() {
        val battery = running(Posture.IDLE)
        battery.bench.toggleFault(FaultId.LOW_BATTERY)
        battery.bench.quickScan()
        val low = battery.latest()
        assertEquals(11.2, low.pids.single { it.id == "voltage" }.value!!, 0.0)
        assertEquals(11.2, low.pids.single { it.id == "bcmVoltage" }.value!!, 0.0)
        assertEquals(DtcStatus.PENDING, low.dtcs.single { it.code == "P0562" }.status)
        assertEquals("voltage-low", low.findings.single { it.ruleId == "voltage-low" }.ruleId)

        val wheels = running(Posture.STRAIGHT)
        wheels.bench.toggleFault(FaultId.WHEEL_SPEED_MISMATCH)
        wheels.bench.quickScan()
        val suspicion = wheels.latest().findings.single { it.ruleId == "wheel-speed" }
        assertEquals("חשד", suspicion.claimHe)
        val flFrame = MockEcu.reply(
            wheels.bench.snapshot().vehicle!!,
            FrameCodec.encode(SimChannel.SERVICE, listOf(SimChannel.WHEEL_FL)).toByteArray(),
        )!!
        val fl = (SimChannel.decode(parsed(flFrame)) as ProtocolOutcome.Ok).value.value
        assertEquals(fl, wheels.latest().pids.single { it.id == "wheelFl" }.value!!, 0.001)
    }
}

class QuickScanFailureCoverageTest {
    @Test
    fun timeoutTransportAndDisconnectAreNotACleanBill() {
        val timeout = running(Posture.STRAIGHT)
        timeout.protocol.timeoutNextRequest()
        timeout.bench.quickScan()
        assertNotClear(timeout.latest())
        assertTrue(timeout.latest().findings.any { it.ruleId == "comm-timeout" })
        assertTrue(timeout.latest().dtcs.isEmpty())
        assertTrue(timeout.latest().pids.isEmpty())
        assertTrue(timeout.latest().readiness.isEmpty())

        val transport = running(Posture.STRAIGHT)
        transport.transport.failNextSend()
        transport.bench.quickScan()
        assertNotClear(transport.latest())
        assertTrue(transport.latest().ecus.all { it.reasonHe.contains("תקשורת") })
        assertFalse(transport.latest().findings.any { it.ruleId == "comm-timeout" })

        val dropped = running(Posture.STRAIGHT)
        dropped.protocol.disconnectDuringNextRequest()
        dropped.bench.quickScan()
        assertNotClear(dropped.latest())
        assertTrue(dropped.latest().ecus.all { it.access == EcuAccess.COMM_ERROR })
        assertTrue(dropped.latest().ecus.all { ecuAccessLabel(it.access) == "שגיאת תקשורת" })
        assertFalse(dropped.transport.isConnected())
    }

    @Test
    fun aMalformedFrameIsNotNoFaults() {
        val rig = running(Posture.STRAIGHT)
        rig.protocol.malformNextResponse()
        rig.bench.quickScan()
        val session = rig.latest()
        assertNotClear(session)
        assertTrue(session.ecus.all { it.reasonHe.contains("פרוטוקול") })
        assertFalse(session.ecus.any { it.reasonHe.contains("Malformed") })
        assertEquals(CANNOT_DETERMINE_HE, session.findings.first { it.ruleId == "no-coverage" }.claimHe)
        assertTrue(session.gaps.all { it.coverage == Coverage.NOT_SCANNED })
    }

    @Test
    fun anUnsupportedExpectedPidIsPartialNotHealthy() {
        val rig = running(Posture.STRAIGHT)
        rig.bench.toggleFault(FaultId.INTERMITTENT_SENSOR)
        rig.bench.quickScan()
        val session = rig.latest()
        val coolant = session.pids.single { it.id == "coolant" }
        assertEquals(PidQuality.UNAVAILABLE, coolant.quality)
        assertEquals(null, coolant.value)
        assertEquals(62.0, session.pids.single { it.id == "speed" }.value!!, 0.001)
        assertTrue(session.dtcs.isEmpty())
        val missing = session.findings.single { it.ruleId == "signal-missing" }
        assertEquals(CANNOT_DETERMINE_HE, missing.claimHe)
        assertTrue(missing.evidence.any { it.contains("טמפרטורת קירור") })
        assertNotClear(session)
        assertEquals(EcuAccess.CHECKED, session.ecus.single { it.id == EcuId.ENGINE }.access)
        assertTrue(session.ecus.single { it.id == EcuId.ENGINE }.reasonHe.contains("חלקית"))
        assertTrue(session.gaps.all { it.coverage == Coverage.NOT_SCANNED })
    }
}

private const val WHEN = "2026-10-22T12:00:00.000Z"

private class Phase4Rig(
    val bench: VehicleBench,
    val repo: SnapshotGarageRepository,
    val transport: MockTransport,
    val protocol: car.doctor.protocol.MockProtocol,
) {
    fun latest() = repo.sessionsFor(bench.snapshot().vehicle!!.id).first()
}

private fun running(posture: Posture): Phase4Rig {
    val repo = SnapshotGarageRepository()
    val transport = MockTransport()
    val factory = MockObdAdapterFactory(transport)
    var scans = 0
    var vehicles = 0
    val bench = VehicleBench(
        repository = repo,
        adapters = factory,
        clock = IsoClock { WHEN },
        ids = IdSource { prefix ->
            if (prefix == "scan") "scan-${++scans}" else "${prefix}-${++vehicles}"
        },
    )
    bench.addVehicle(
        NewVehicle(
            nickname = "קורולה",
            make = "טויוטה",
            model = "קורולה",
            year = 2016,
            fuel = Fuel.GASOLINE,
            vin = "",
        ),
    )
    for (step in listOf(IgnitionState.ACC, IgnitionState.ON, IgnitionState.STARTING, IgnitionState.RUNNING)) {
        check(bench.setIgnition(step) is BenchEffect.Accepted)
    }
    check(bench.setPosture(posture) is BenchEffect.Accepted)
    check(bench.connect() is BenchEffect.Accepted)
    return Phase4Rig(bench, repo, transport, factory.protocol)
}

private fun assertNotClear(session: car.doctor.core.model.DiagnosticSession) {
    val headline = SessionPresenter.present(session).headlineHe
    assertNotEquals(CLEAR_SCAN_HE, headline)
    assertFalse(headline.contains("הרכב תקין"))
    assertFalse(headline.contains("לא נמצאו תקלות"))
    assertTrue(session.findings.isNotEmpty())
}

private fun assertPid(bytes: ByteArray, pid: ObdPid, value: Double, unit: String, formula: PidFormula) {
    val decoded = Obd2.decodePid(parsed(bytes)) as ProtocolOutcome.Ok
    assertEquals(pid, decoded.value.pid)
    assertEquals(ObdMode.CURRENT_DATA, decoded.value.mode)
    assertEquals(value, decoded.value.value, 0.001)
    assertEquals(unit, decoded.value.unit)
    assertEquals(formula, decoded.value.formula)
    assertTrue(decoded.value.raw.isNotEmpty())
}

private fun parsed(bytes: ByteArray): ProtocolResponse {
    val raw = bytes.map { it.toInt() and 0xFF }
    val frame = (FrameCodec.parse(raw) as FrameParse.Ok).frame
    return ProtocolResponse(service = frame.service, payload = frame.payload, frame = frame)
}

private fun response(service: Int, payload: List<Int>): ProtocolResponse = parsed(ObdFixtures.frame(service, payload))

private fun List<Int>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }

private fun car.doctor.core.model.DiagnosticSession.toFacts() = car.doctor.core.model.ScanFacts(
    readAt = finishedAt,
    fuel = Fuel.GASOLINE,
    protocolHe = protocolHe,
    vinNoteHe = vinNoteHe,
    ecus = ecus,
    gaps = gaps,
    dtcs = dtcs,
    pids = pids,
    readiness = readiness,
    engineTimeout = findings.any { it.ruleId == "comm-timeout" },
)
