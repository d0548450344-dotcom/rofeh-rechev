package car.doctor.simulation

import car.doctor.core.catalog.CLEAR_SCAN_HE
import car.doctor.core.model.Coverage
import car.doctor.core.model.EcuAccess
import car.doctor.core.model.EcuId
import car.doctor.core.model.FaultId
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.NewVehicle
import car.doctor.core.model.ObdSessionState
import car.doctor.core.model.Posture
import car.doctor.core.persistence.SnapshotGarageRepository
import car.doctor.core.present.SessionPresenter
import car.doctor.core.session.BenchEffect
import car.doctor.core.session.IdSource
import car.doctor.core.session.IsoClock
import car.doctor.core.session.VehicleBench
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockAdapterConnectionTest {
    @Test
    fun connectionFailsBelowOnAndSucceedsWhenRunning() {
        val fx = fixture()
        addDemo(fx)
        val failed = fx.bench.connect() as BenchEffect.Accepted
        assertEquals(ObdSessionState.ERROR, failed.snapshot.session)
        assertEquals(
            listOf(ObdSessionState.CONNECTING, ObdSessionState.ERROR),
            fx.bench.lastTransitionTrace,
        )
        assertTrue(failed.snapshot.errorHe!!.contains("ON"))
        assertFalse(failed.snapshot.capabilities.canScan)

        reachRunning(fx.bench)
        val connected = fx.bench.connect() as BenchEffect.Accepted
        assertEquals(ObdSessionState.CONNECTED, connected.snapshot.session)
        assertEquals(
            listOf(ObdSessionState.CONNECTING, ObdSessionState.CONNECTED),
            fx.bench.lastTransitionTrace,
        )
        assertNotNull(connected.snapshot.protocolHe)
        assertTrue(connected.snapshot.capabilities.canScan)
        assertEquals(IgnitionState.RUNNING, fx.bench.snapshot().ignition)
    }
}

class EcuDiscoveryTest {
    @Test
    fun quickScanDiscoversFourEcusAndLeavesTheRestUnscanned() {
        val fx = runningFixture()
        val effect = fx.bench.quickScan() as BenchEffect.Accepted
        assertEquals(ObdSessionState.COMPLETE, effect.snapshot.session)
        val session = fx.latest()
        assertEquals(
            listOf(EcuAccess.CHECKED, EcuAccess.CHECKED, EcuAccess.CHECKED, EcuAccess.CHECKED),
            session.ecus.map { it.access },
        )
        assertEquals(
            listOf(EcuId.ENGINE, EcuId.ABS, EcuId.BCM, EcuId.SRS),
            session.ecus.map { it.id },
        )
        assertTrue(session.gaps.all { it.coverage == Coverage.NOT_SCANNED })
        assertNotNull(session.gaps.find { it.id == "tpms" })
        assertTrue(session.findings.none { it.titleHe.contains("TPMS") })
        assertEquals(CLEAR_SCAN_HE, SessionPresenter.present(session).headlineHe)
        val rpm = session.pids.single { it.id == "rpm" }
        assertEquals(EcuId.ENGINE, rpm.ecuId)
        assertEquals("rpm", rpm.unit)
        assertEquals(WHEN, rpm.readAt)
        assertNotNull(rpm.value)
        assertEquals(0, session.dtcs.size)
    }
}

class QuickScanPipelineTest {
    @Test
    fun scanGoesThroughAdapterThenRulesAndKeepsHistoryHonest() {
        val fx = runningFixture()
        fx.bench.quickScan()
        assertTrue(fx.latest().findings.isEmpty())
        assertEquals(listOf("scan-1"), fx.bench.snapshot().historyIds)

        fx.bench.toggleFault(FaultId.MISFIRE)
        assertEquals(listOf("scan-1"), fx.bench.snapshot().historyIds)

        fx.bench.quickScan()
        val misfire = fx.latest()
        val p0301 = misfire.dtcs.single { it.code == "P0301" }
        assertEquals(EcuId.ENGINE, p0301.ecuId)
        assertEquals("stored", p0301.status.name.lowercase())
        assertEquals(WHEN, p0301.readAt)
        val finding = misfire.findings.single { it.code == "P0301" }
        assertEquals("misfire-code", finding.ruleId)
        assertEquals("הקוד נקרא מהיחידה", finding.claimHe)
        assertFalse(finding.summaryHe.contains("מקולקל"))
        assertTrue(misfire.labFaults.contains(FaultId.MISFIRE))
        assertTrue(misfire.dtcs.any { it.code == "P0300" })

        fx.bench.toggleFault(FaultId.MISFIRE)
        fx.bench.quickScan()
        val cleared = fx.latest()
        assertTrue(cleared.findings.isEmpty())
        assertTrue(cleared.dtcs.isEmpty())
        assertEquals(CLEAR_SCAN_HE, SessionPresenter.present(cleared).headlineHe)
        assertEquals(3, fx.bench.snapshot().historyIds.size)

        val disconnected = fx.bench.disconnect() as BenchEffect.Accepted
        assertEquals(ObdSessionState.DISCONNECTED, disconnected.snapshot.session)
        assertEquals(
            listOf(ObdSessionState.DISCONNECTING, ObdSessionState.DISCONNECTED),
            fx.bench.lastTransitionTrace,
        )
        val refused = fx.bench.quickScan() as BenchEffect.Rejected
        assertTrue(refused.messageHe.contains("סריקה דורשת חיבור"))
        assertEquals(3, fx.repo.sessionsFor(fx.bench.snapshot().vehicle!!.id).size)
    }
}

class InjectedFaultPipelineTest {
    @Test
    fun lowBatteryAndWheelMismatchStayInsideTheAdapter() {
        val battery = runningFixture(Posture.IDLE)
        battery.bench.toggleFault(FaultId.LOW_BATTERY)
        battery.bench.quickScan()
        val low = battery.latest()
        assertEquals(11.2, low.pids.single { it.id == "voltage" }.value!!, 0.0)
        val voltageFinding = low.findings.single { it.ruleId == "voltage-low" }
        assertEquals("חריגת מדידה", voltageFinding.claimHe)
        assertTrue(low.dtcs.any { it.code == "P0562" })
        assertTrue(low.findings.none { it.summaryHe.contains("תקול") })

        val wheels = runningFixture(Posture.STRAIGHT)
        wheels.bench.toggleFault(FaultId.WHEEL_SPEED_MISMATCH)
        wheels.bench.quickScan()
        val suspicion = wheels.latest().findings.single { it.ruleId == "wheel-speed" }
        assertEquals("חשד", suspicion.claimHe)
        assertTrue(suspicion.summaryHe.contains("אין כאן זיהוי של רכיב פגום"))

        val idle = runningFixture(Posture.IDLE)
        idle.bench.toggleFault(FaultId.WHEEL_SPEED_MISMATCH)
        idle.bench.quickScan()
        assertTrue(idle.latest().findings.none { it.ruleId == "wheel-speed" })
    }
}

private const val WHEN = "2026-10-22T12:00:00.000Z"

private class Fixture(
    val bench: VehicleBench,
    val repo: SnapshotGarageRepository,
) {
    fun latest() = repo.sessionsFor(bench.snapshot().vehicle!!.id).first()
}

private fun fixture(): Fixture {
    val repo = SnapshotGarageRepository()
    var scans = 0
    var vehicles = 0
    val bench = VehicleBench(
        repository = repo,
        adapters = MockObdAdapterFactory(),
        clock = IsoClock { WHEN },
        ids = IdSource { prefix ->
            if (prefix == "scan") "scan-${++scans}" else "${prefix}-${++vehicles}"
        },
    )
    return Fixture(bench, repo)
}

private fun addDemo(fx: Fixture) {
    val effect = fx.bench.addVehicle(
        NewVehicle(
            nickname = "קורולה",
            make = "טויוטה",
            model = "קורולה",
            year = 2016,
            fuel = Fuel.GASOLINE,
            vin = "",
        ),
    )
    assertTrue(effect is BenchEffect.Accepted)
    assertEquals(IgnitionState.OFF, fx.bench.snapshot().ignition)
}

private fun reachRunning(bench: VehicleBench) {
    val skipped = bench.setIgnition(IgnitionState.RUNNING)
    assertTrue(skipped is BenchEffect.Rejected)
    assertEquals(IgnitionState.OFF, bench.snapshot().ignition)
    for (step in listOf(
        IgnitionState.ACC,
        IgnitionState.ON,
        IgnitionState.STARTING,
        IgnitionState.RUNNING,
    )) {
        assertTrue(step.name, bench.setIgnition(step) is BenchEffect.Accepted)
    }
}

private fun runningFixture(posture: Posture = Posture.STRAIGHT): Fixture {
    val fx = fixture()
    addDemo(fx)
    reachRunning(fx.bench)
    assertTrue(fx.bench.setPosture(posture) is BenchEffect.Accepted)
    assertTrue(fx.bench.connect() is BenchEffect.Accepted)
    assertEquals(ObdSessionState.CONNECTED, fx.bench.snapshot().session)
    return fx
}
