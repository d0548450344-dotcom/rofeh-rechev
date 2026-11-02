package car.doctor.simulation

import car.doctor.core.adapter.AdapterCapability
import car.doctor.core.catalog.CANNOT_DETERMINE_HE
import car.doctor.core.catalog.CLEAR_SCAN_HE
import car.doctor.core.model.EcuAccess
import car.doctor.core.model.FaultId
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.NewVehicle
import car.doctor.core.model.ObdSessionState
import car.doctor.core.model.Vehicle
import car.doctor.core.model.VehicleSim
import car.doctor.core.persistence.SnapshotGarageRepository
import car.doctor.core.present.SessionPresenter
import car.doctor.core.rules.DiagnosticRules
import car.doctor.core.session.BenchEffect
import car.doctor.core.session.IdSource
import car.doctor.core.session.IsoClock
import car.doctor.core.session.VehicleBench
import car.doctor.protocol.MockProtocol
import car.doctor.protocol.Obd2Profile
import car.doctor.protocol.ProtocolCapabilities
import car.doctor.protocol.ProtocolEvent
import car.doctor.protocol.ProtocolState
import car.doctor.transport.MockTransport
import car.doctor.transport.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterProtocolIntegrationTest {
    @Test
    fun quickScanGoesThroughTheProtocolAndKeepsTheSameFacts() {
        val rig = rig()
        reachRunning(rig.bench)
        val connected = rig.bench.connect() as BenchEffect.Accepted
        assertEquals(ObdSessionState.CONNECTED, connected.snapshot.session)
        assertEquals(ProtocolState.READY, rig.protocol.state())
        assertTrue(rig.transport.isConnected())
        assertEquals(Obd2Profile.foundation, rig.protocol.capabilities)

        rig.bench.toggleFault(FaultId.MISFIRE)
        val scanned = rig.bench.quickScan() as BenchEffect.Accepted
        assertEquals(ObdSessionState.COMPLETE, scanned.snapshot.session)
        val session = rig.latest()
        assertTrue(session.dtcs.any { it.code == "P0301" })
        assertEquals("rpm", session.pids.single { it.id == "rpm" }.unit)
        assertEquals("סל״ד", session.pids.single { it.id == "rpm" }.nameHe)
        assertEquals("misfire-code", session.findings.single { it.code == "P0301" }.ruleId)
        assertTrue(rig.protocol.events().any { it is ProtocolEvent.RequestStarted })
        assertTrue(rig.protocol.events().any { it is ProtocolEvent.ResponseReceived })
        assertTrue(rig.protocol.events().none { it is ProtocolEvent.Failed })
    }

    @Test
    fun ignitionOffNeverOpensTheProtocol() {
        val rig = rig()
        rig.bench.addVehicle(demoInput())
        val failed = rig.bench.connect() as BenchEffect.Accepted
        assertEquals(ObdSessionState.ERROR, failed.snapshot.session)
        assertTrue(failed.snapshot.errorHe!!.contains("ON"))
        assertFalse(rig.transport.isConnected())
        assertEquals(ProtocolState.DISCONNECTED, rig.protocol.state())
        assertEquals(TransportState.DISCONNECTED, rig.transport.connectionState())
        assertTrue(rig.protocol.events().isEmpty())
    }
}

class ProtocolErrorPropagationTest {
    @Test
    fun aProtocolErrorDoesNotBecomeAHealthyScan() {
        val rig = runningRig()
        rig.protocol.malformNextResponse()
        val scanned = rig.bench.quickScan() as BenchEffect.Accepted
        val session = rig.latest()
        assertEquals(ObdSessionState.COMPLETE, scanned.snapshot.session)
        assertTrue(session.dtcs.isEmpty())
        assertTrue(session.pids.isEmpty())
        assertTrue(session.ecus.all { it.access == EcuAccess.COMM_ERROR })
        assertTrue(session.ecus.all { it.reasonHe.contains("פרוטוקול") })
        assertFalse(session.ecus.any { it.reasonHe.contains("ProtocolError") })
        assertFalse(session.ecus.any { it.reasonHe.contains("Malformed") })
        assertTrue(session.findings.any { it.ruleId == "no-coverage" })
        assertEquals(CANNOT_DETERMINE_HE, session.findings.first { it.ruleId == "no-coverage" }.claimHe)
        val headline = SessionPresenter.present(session).headlineHe
        assertNotEquals(CLEAR_SCAN_HE, headline)
        assertFalse(headline.contains("הרכב תקין"))
        assertFalse(session.engineTimeout())
    }

    @Test
    fun aTimeoutDoesNotBecomeNoCodes() {
        val rig = runningRig()
        rig.protocol.timeoutNextRequest()
        rig.bench.quickScan()
        val session = rig.latest()
        assertTrue(session.dtcs.isEmpty())
        assertTrue(session.pids.isEmpty())
        assertTrue(session.ecus.all { it.reasonHe.contains("פסק זמן") })
        assertFalse(session.ecus.any { it.reasonHe.contains("Timeout") })
        assertTrue(session.findings.any { it.ruleId == "no-coverage" })
        assertTrue(session.findings.any { it.ruleId == "comm-timeout" })
        assertNotEquals(CLEAR_SCAN_HE, SessionPresenter.present(session).headlineHe)
        assertTrue(session.readiness.isEmpty())
    }

    @Test
    fun aTransportFailureStaysACommunicationFailure() {
        val rig = runningRig()
        rig.transport.failNextSend()
        rig.bench.quickScan()
        val session = rig.latest()
        assertTrue(session.ecus.all { it.access == EcuAccess.COMM_ERROR })
        assertTrue(session.ecus.all { it.reasonHe.contains("תקשורת") })
        assertFalse(session.ecus.any { it.reasonHe.contains("TransportError") })
        assertTrue(session.dtcs.isEmpty())
        assertTrue(session.findings.any { it.ruleId == "no-coverage" })
        assertFalse(session.findings.any { it.ruleId == "comm-timeout" })
        assertNotEquals(CLEAR_SCAN_HE, SessionPresenter.present(session).headlineHe)
        assertEquals(ProtocolState.ERROR, rig.protocol.state())
    }

    @Test
    fun disconnectMidScanIsNotAClearResult() {
        val rig = runningRig()
        rig.protocol.disconnectDuringNextRequest()
        rig.bench.quickScan()
        val session = rig.latest()
        assertFalse(rig.transport.isConnected())
        assertEquals(ProtocolState.DISCONNECTED, rig.protocol.state())
        assertTrue(session.ecus.all { it.access == EcuAccess.COMM_ERROR })
        assertTrue(session.ecus.all { it.reasonHe.contains("מנותק") })
        assertTrue(session.dtcs.isEmpty())
        assertTrue(session.pids.isEmpty())
        assertTrue(session.findings.any { it.ruleId == "no-coverage" })
        assertNotEquals(CLEAR_SCAN_HE, SessionPresenter.present(session).headlineHe)
        assertFalse(SessionPresenter.present(session).headlineHe.contains("הרכב תקין"))
    }
}

class CapabilitySeparationTest {
    @Test
    fun adapterCapabilitiesDoNotGrantProtocolCapabilities() {
        val vehicle = Vehicle(
            id = "v1",
            nickname = "קורולה",
            make = "טויוטה",
            model = "קורולה",
            year = 2016,
            fuel = Fuel.GASOLINE,
            vin = "",
            createdAt = WHEN,
            sim = VehicleSim(ignition = IgnitionState.RUNNING),
        )
        val protocol = MockProtocol(MockTransport(), ProtocolCapabilities.NONE)
        val adapter = MockObdAdapter(vehicle, protocol)
        assertTrue(adapter.info().capabilities.contains(AdapterCapability.READ_DTC))
        assertTrue(adapter.info().capabilities.contains(AdapterCapability.READ_PID))
        assertFalse(protocol.capabilities.supportsDtc)
        assertFalse(protocol.capabilities.supportsObd2)
        val facts = adapter.readFacts(WHEN)
        assertTrue(facts.dtcs.isEmpty())
        assertTrue(facts.pids.isEmpty())
        assertTrue(facts.ecus.all { it.access == EcuAccess.COMM_ERROR })
        val findings = DiagnosticRules.evaluate(facts, IgnitionState.RUNNING)
        assertTrue(findings.any { it.ruleId == "no-coverage" })
        assertFalse(findings.any { it.ruleId == "misfire-code" })
    }
}

class Phase3RegressionTest {
    @Test
    fun connectScanDisconnectReconnectScan() {
        val rig = rig()
        reachRunning(rig.bench)
        assertTrue(rig.bench.connect() is BenchEffect.Accepted)
        assertTrue(rig.bench.quickScan() is BenchEffect.Accepted)
        assertEquals(CLEAR_SCAN_HE, SessionPresenter.present(rig.latest()).headlineHe)
        assertTrue(rig.bench.disconnect() is BenchEffect.Accepted)
        assertEquals(ProtocolState.DISCONNECTED, rig.protocol.state())
        assertFalse(rig.transport.isConnected())
        val refused = rig.bench.quickScan() as BenchEffect.Rejected
        assertTrue(refused.messageHe.contains("סריקה דורשת חיבור"))

        assertTrue(rig.bench.connect() is BenchEffect.Accepted)
        rig.bench.toggleFault(FaultId.LOW_BATTERY)
        assertTrue(rig.bench.quickScan() is BenchEffect.Accepted)
        val second = rig.latest()
        assertTrue(second.dtcs.any { it.code == "P0562" })
        assertTrue(second.pids.any { it.id == "voltage" })
        assertEquals(2, rig.bench.snapshot().historyIds.size)
        assertEquals(ProtocolState.READY, rig.protocol.state())
    }
}

private const val WHEN = "2026-10-22T12:00:00.000Z"

private class Phase3Rig(
    val bench: VehicleBench,
    val repo: SnapshotGarageRepository,
    val transport: MockTransport,
    val protocol: MockProtocol,
) {
    fun latest() = repo.sessionsFor(bench.snapshot().vehicle!!.id).first()
}

private fun rig(): Phase3Rig {
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
    return Phase3Rig(bench, repo, transport, factory.protocol)
}

private fun runningRig(): Phase3Rig {
    val rig = rig()
    reachRunning(rig.bench)
    check(rig.bench.connect() is BenchEffect.Accepted)
    return rig
}

private fun reachRunning(bench: VehicleBench) {
    bench.addVehicle(demoInput())
    for (step in listOf(
        IgnitionState.ACC,
        IgnitionState.ON,
        IgnitionState.STARTING,
        IgnitionState.RUNNING,
    )) {
        check(bench.setIgnition(step) is BenchEffect.Accepted)
    }
}

private fun demoInput() = NewVehicle(
    nickname = "קורולה",
    make = "טויוטה",
    model = "קורולה",
    year = 2016,
    fuel = Fuel.GASOLINE,
    vin = "",
)

private fun car.doctor.core.model.DiagnosticSession.engineTimeout(): Boolean =
    findings.any { it.ruleId == "comm-timeout" }
