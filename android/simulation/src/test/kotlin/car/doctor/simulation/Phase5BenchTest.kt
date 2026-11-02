package car.doctor.simulation

import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.NewVehicle
import car.doctor.core.model.ObdSessionState
import car.doctor.core.persistence.SnapshotGarageRepository
import car.doctor.core.session.BenchEffect
import car.doctor.core.session.IdSource
import car.doctor.core.session.IsoClock
import car.doctor.core.session.VehicleBench
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleProfileTest {
    @Test
    fun twoProfilesKeepSeparateScansAndANewBenchReloadsTheSelection() {
        val repo = SnapshotGarageRepository()
        val first = bench(repo)
        assertTrue(first.addVehicle(demo("קורולה")) is BenchEffect.Accepted)
        reachRunning(first)
        assertTrue(first.connect() is BenchEffect.Accepted)
        assertTrue(first.quickScan() is BenchEffect.Accepted)
        val corollaId = first.snapshot().vehicle!!.id
        assertEquals(1, first.sessions().size)

        assertTrue(first.addVehicle(demo("גולף")) is BenchEffect.Accepted)
        assertEquals(ObdSessionState.DISCONNECTED, first.snapshot().session)
        assertTrue(first.sessions().isEmpty())
        assertEquals(2, first.snapshot().vehicles.size)

        val back = first.selectVehicle(corollaId) as BenchEffect.Accepted
        assertEquals("קורולה", back.snapshot.vehicle?.nickname)
        assertEquals(1, first.sessions().size)
        assertEquals(listOf("scan-1"), first.snapshot().historyIds)

        val missing = first.selectVehicle("missing") as BenchEffect.Rejected
        assertTrue(missing.messageHe.contains("לא נמצא"))

        val again = bench(repo)
        assertEquals(corollaId, again.snapshot().vehicle?.id)
        assertEquals(listOf("scan-1"), again.sessions().map { it.id })
        assertEquals(IgnitionState.RUNNING, again.snapshot().ignition)
    }
}

class IgnitionDropPresentationTest {
    @Test
    fun protocolLabelClearsWhenIgnitionFallsBelowOn() {
        val bench = bench(SnapshotGarageRepository())
        assertTrue(bench.addVehicle(demo("בדיקה")) is BenchEffect.Accepted)
        assertTrue(bench.setIgnition(IgnitionState.ACC) is BenchEffect.Accepted)
        assertTrue(bench.setIgnition(IgnitionState.ON) is BenchEffect.Accepted)
        val connected = bench.connect() as BenchEffect.Accepted
        assertEquals(ObdSessionState.CONNECTED, connected.snapshot.session)
        assertNotNull(connected.snapshot.protocolHe)

        val dropped = bench.setIgnition(IgnitionState.ACC) as BenchEffect.Accepted
        assertEquals(ObdSessionState.DISCONNECTED, dropped.snapshot.session)
        assertNull(dropped.snapshot.protocolHe)
        assertFalse(dropped.snapshot.capabilities.canScan)
        assertTrue(dropped.snapshot.errorHe!!.contains("ON"))
    }
}

private fun bench(repo: SnapshotGarageRepository): VehicleBench {
    var scans = 0
    var vehicles = 0
    return VehicleBench(
        repository = repo,
        adapters = MockObdAdapterFactory(),
        clock = IsoClock { "2026-10-22T12:00:00.000Z" },
        ids = IdSource { prefix ->
            if (prefix == "scan") "scan-${++scans}" else "${prefix}-${++vehicles}"
        },
    )
}

private fun reachRunning(bench: VehicleBench) {
    for (step in listOf(
        IgnitionState.ACC,
        IgnitionState.ON,
        IgnitionState.STARTING,
        IgnitionState.RUNNING,
    )) {
        check(bench.setIgnition(step) is BenchEffect.Accepted)
    }
}

private fun demo(nickname: String) = NewVehicle(
    nickname = nickname,
    make = "יצרן",
    model = "דגם",
    year = 2016,
    fuel = Fuel.GASOLINE,
    vin = "",
)
