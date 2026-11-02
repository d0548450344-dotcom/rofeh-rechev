package car.doctor.simulation

import car.doctor.core.adapter.AdapterCapability
import car.doctor.core.adapter.TransportKind
import car.doctor.core.catalog.CLEAR_SCAN_HE
import car.doctor.core.model.AdapterKind
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
import car.doctor.core.session.BenchEffect
import car.doctor.core.session.IdSource
import car.doctor.core.session.IsoClock
import car.doctor.core.session.VehicleBench
import car.doctor.transport.MockTransport
import car.doctor.transport.TransportFailure
import car.doctor.transport.TransportOutcome
import car.doctor.transport.TransportReconnector
import car.doctor.transport.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityReportingTest {
    @Test
    fun mockDeclaresOnlyWhatItImplements() {
        val adapter = MockObdAdapter(demoVehicle(), MockTransport())
        val caps = adapter.info().capabilities
        assertEquals(
            setOf(
                AdapterCapability.READ_DTC,
                AdapterCapability.READ_PID,
                AdapterCapability.READ_READINESS,
                AdapterCapability.MULTI_ECU,
            ),
            caps,
        )
        assertFalse(caps.contains(AdapterCapability.READ_FREEZE_FRAME))
        assertFalse(caps.contains(AdapterCapability.CAN))
        assertFalse(caps.contains(AdapterCapability.ISO_TP))
        assertFalse(caps.contains(AdapterCapability.UDS))
        assertNull(adapter.rejectIfUnsupported(AdapterCapability.READ_DTC))
        val refused = adapter.rejectIfUnsupported(AdapterCapability.UDS)
        assertTrue(refused is TransportFailure.UnsupportedOperation)
    }
}

class AdapterInformationTest {
    @Test
    fun mockIdentityIsExplicitAndHasNoFirmwareClaim() {
        val info = MockObdAdapter(demoVehicle(), MockTransport()).info()
        assertEquals(MOCK_ADAPTER_ID, info.adapterId)
        assertEquals("MOCK-OBD-001", info.adapterId)
        assertEquals(AdapterKind.SIMULATED, info.adapterType)
        assertNull(info.firmwareVersion)
        assertEquals(TransportKind.MOCK, info.transportType)
    }
}

class MockTransportIntegrationTest {
    @Test
    fun failedLinkDoesNotBecomeAScanAndALaterConnectDoes() {
        val rig = rig()
        reachRunning(rig.bench)
        rig.transport.failNextConnections(1)
        val failed = rig.bench.connect() as BenchEffect.Accepted
        assertEquals(ObdSessionState.ERROR, failed.snapshot.session)
        assertTrue(failed.snapshot.errorHe!!.contains("נכשל"))
        assertFalse(failed.snapshot.errorHe!!.contains("ConnectionFailed"))
        assertFalse(failed.snapshot.errorHe!!.contains("mock-connection-failed"))
        assertEquals(0, rig.repo.sessionsFor(rig.bench.snapshot().vehicle!!.id).size)
        assertEquals(TransportState.ERROR, rig.transport.connectionState())

        val connected = rig.bench.connect() as BenchEffect.Accepted
        assertEquals(ObdSessionState.CONNECTED, connected.snapshot.session)
        assertTrue(rig.transport.isConnected())
        val scanned = rig.bench.quickScan() as BenchEffect.Accepted
        assertEquals(ObdSessionState.COMPLETE, scanned.snapshot.session)
        val session = rig.latest()
        assertTrue(session.pids.any { it.id == "rpm" })
        assertEquals(0, session.dtcs.size)
        assertEquals(CLEAR_SCAN_HE, SessionPresenter.present(session).headlineHe)
    }

    @Test
    fun aDownLinkDoesNotInventCodes() {
        val transport = MockTransport()
        val adapter = MockObdAdapter(demoVehicle(IgnitionState.RUNNING), transport)
        val facts = adapter.readFacts(WHEN)
        assertTrue(facts.dtcs.isEmpty())
        assertTrue(facts.pids.isEmpty())
        assertTrue(facts.ecus.all { it.access == EcuAccess.COMM_ERROR })
        assertTrue(facts.ecus.all { it.reasonHe.contains("מנותק") })
        assertFalse(facts.ecus.any { it.reasonHe.contains("Disconnected") })
    }
}

class QuickScanAfterReconnectTest {
    @Test
    fun scanWorksAgainAfterTheLinkIsRestored() {
        val rig = rig()
        reachRunning(rig.bench)
        assertTrue(rig.bench.connect() is BenchEffect.Accepted)
        assertTrue(rig.bench.quickScan() is BenchEffect.Accepted)
        rig.transport.dropLink()
        assertFalse(rig.transport.isConnected())
        val recovered = TransportReconnector(rig.transport, maxAttempts = 3).recover()
        assertTrue(recovered is TransportOutcome.Ok)
        val second = rig.bench.quickScan() as BenchEffect.Accepted
        assertEquals(ObdSessionState.COMPLETE, second.snapshot.session)
        assertTrue(rig.latest().pids.isNotEmpty())
        assertEquals(2, rig.bench.snapshot().historyIds.size)
    }
}

class Phase2RegressionTest {
    @Test
    fun ignitionConnectScanHistoryThenDisconnectAndScanAgain() {
        val rig = rig()
        val added = rig.bench.addVehicle(
            NewVehicle(
                nickname = "קורולה",
                make = "טויוטה",
                model = "קורולה",
                year = 2016,
                fuel = Fuel.GASOLINE,
                vin = "",
            ),
        )
        assertTrue(added is BenchEffect.Accepted)
        assertEquals(IgnitionState.OFF, rig.bench.snapshot().ignition)
        assertTrue(rig.bench.setIgnition(IgnitionState.RUNNING) is BenchEffect.Rejected)
        for (step in listOf(
            IgnitionState.ACC,
            IgnitionState.ON,
            IgnitionState.STARTING,
            IgnitionState.RUNNING,
        )) {
            assertTrue(rig.bench.setIgnition(step) is BenchEffect.Accepted)
        }

        val connected = rig.bench.connect() as BenchEffect.Accepted
        assertEquals(ObdSessionState.CONNECTED, connected.snapshot.session)
        assertTrue(rig.transport.isConnected())
        rig.bench.toggleFault(FaultId.MISFIRE)
        val scanned = rig.bench.quickScan() as BenchEffect.Accepted
        assertEquals(ObdSessionState.COMPLETE, scanned.snapshot.session)
        val first = rig.latest()
        assertTrue(first.dtcs.any { it.code == "P0301" })
        assertTrue(first.pids.any { it.id == "rpm" })
        assertEquals("misfire-code", first.findings.single { it.code == "P0301" }.ruleId)
        assertEquals(listOf(first.id), rig.bench.snapshot().historyIds)

        val disconnected = rig.bench.disconnect() as BenchEffect.Accepted
        assertEquals(ObdSessionState.DISCONNECTED, disconnected.snapshot.session)
        assertEquals(
            listOf(ObdSessionState.DISCONNECTING, ObdSessionState.DISCONNECTED),
            rig.bench.lastTransitionTrace,
        )
        assertFalse(rig.transport.isConnected())
        assertEquals(TransportState.DISCONNECTED, rig.transport.connectionState())
        val refused = rig.bench.quickScan() as BenchEffect.Rejected
        assertTrue(refused.messageHe.contains("סריקה דורשת חיבור"))

        rig.bench.toggleFault(FaultId.MISFIRE)
        val again = rig.bench.connect() as BenchEffect.Accepted
        assertEquals(ObdSessionState.CONNECTED, again.snapshot.session)
        assertTrue(rig.bench.quickScan() is BenchEffect.Accepted)
        val second = rig.latest()
        assertTrue(second.dtcs.isEmpty())
        assertTrue(second.pids.isNotEmpty())
        assertTrue(second.findings.isEmpty())
        assertEquals(CLEAR_SCAN_HE, SessionPresenter.present(second).headlineHe)
        assertEquals(2, rig.repo.sessionsFor(rig.bench.snapshot().vehicle!!.id).size)
    }
}

private const val WHEN = "2026-10-22T12:00:00.000Z"

private class Rig(
    val bench: VehicleBench,
    val repo: SnapshotGarageRepository,
    val transport: MockTransport,
) {
    fun latest() = repo.sessionsFor(bench.snapshot().vehicle!!.id).first()
}

private fun rig(): Rig {
    val repo = SnapshotGarageRepository()
    val transport = MockTransport()
    var scans = 0
    var vehicles = 0
    val bench = VehicleBench(
        repository = repo,
        adapters = MockObdAdapterFactory(transport),
        clock = IsoClock { WHEN },
        ids = IdSource { prefix ->
            if (prefix == "scan") "scan-${++scans}" else "${prefix}-${++vehicles}"
        },
    )
    return Rig(bench, repo, transport)
}

private fun reachRunning(bench: VehicleBench) {
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
    for (step in listOf(
        IgnitionState.ACC,
        IgnitionState.ON,
        IgnitionState.STARTING,
        IgnitionState.RUNNING,
    )) {
        check(bench.setIgnition(step) is BenchEffect.Accepted)
    }
}

private fun demoVehicle(ignition: IgnitionState = IgnitionState.RUNNING) = Vehicle(
    id = "v1",
    nickname = "קורולה",
    make = "טויוטה",
    model = "קורולה",
    year = 2016,
    fuel = Fuel.GASOLINE,
    vin = "",
    createdAt = WHEN,
    sim = VehicleSim(ignition = ignition),
)
