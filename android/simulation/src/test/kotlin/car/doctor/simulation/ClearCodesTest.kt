package car.doctor.simulation

import car.doctor.core.model.ClearConfirmation
import car.doctor.core.model.FaultId
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.NewVehicle
import car.doctor.core.model.ObdSessionState
import car.doctor.core.persistence.SnapshotGarageRepository
import car.doctor.core.present.SessionPresenter
import car.doctor.core.session.BenchEffect
import car.doctor.core.session.IdSource
import car.doctor.core.session.IsoClock
import car.doctor.core.session.VehicleBench
import car.doctor.transport.MockTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val BOTH = ClearConfirmation(understoodCodesReturn = true, understoodReadinessReset = true)

private class ClearRig {
    var scans = 0
    val bench = VehicleBench(
        repository = SnapshotGarageRepository(),
        adapters = MockObdAdapterFactory(MockTransport()),
        clock = IsoClock { "2026-11-02T10:00:00.000Z" },
        ids = IdSource { prefix -> if (prefix == "scan") "scan-${++scans}" else "$prefix-1" },
    )

    init {
        bench.addVehicle(NewVehicle("טסט", "טויוטה", "קורולה", 2016, Fuel.GASOLINE, ""))
    }

    fun ignite(vararg steps: IgnitionState) = steps.forEach { check(bench.setIgnition(it) is BenchEffect.Accepted) }

    fun latest() = bench.sessions().first()
}

/** A car with a misfire code, scanned with the engine off (ignition ON). */
private fun scannedWithCode(): ClearRig = ClearRig().apply {
    ignite(IgnitionState.ACC, IgnitionState.ON)
    bench.toggleFault(FaultId.MISFIRE)
    bench.connect()
    check(bench.quickScan() is BenchEffect.Accepted)
}

class ClearCodesTest {
    @Test
    fun clearingRunsAVerificationScanAndKeepsThePreviousScan() {
        val rig = scannedWithCode()
        val before = rig.latest()
        assertTrue(before.dtcs.isNotEmpty())
        assertTrue(rig.bench.snapshot().capabilities.canClear)

        assertTrue(rig.bench.clearCodes(BOTH) is BenchEffect.Accepted)

        val after = rig.latest()
        assertEquals("post-clear", after.kind)
        assertEquals(before.id, after.clearedFromSessionId)
        assertEquals(before.dtcs.map { it.code }.distinct(), after.clearedCodes)
        assertTrue("the scan before clearing stays in history", rig.bench.sessions().any { it.id == before.id })
        assertEquals(ObdSessionState.COMPLETE, rig.bench.snapshot().session)
    }

    @Test
    fun aRealFaultSetsItsCodeAgainAndTheReportSaysSo() {
        val rig = scannedWithCode()
        rig.bench.clearCodes(BOTH)

        val note = SessionPresenter.present(rig.latest()).clearNoteHe!!
        assertTrue(note, note.contains("חזרו מיד"))
        assertTrue(note, note.contains("התקלה עדיין פעילה"))
    }

    @Test
    fun whenNothingComesBackTheReportStillSaysToRescanLater() {
        val rig = scannedWithCode()
        rig.bench.toggleFault(FaultId.MISFIRE) // the "repair"
        rig.bench.clearCodes(BOTH)

        val after = rig.latest()
        assertTrue(after.dtcs.none { it.code in after.clearedCodes })
        val note = SessionPresenter.present(after).clearNoteHe!!
        assertTrue(note, note.contains("סורקים שוב אחרי כמה נסיעות"))
    }

    @Test
    fun refusedWhileTheEngineRuns() {
        val rig = ClearRig()
        rig.ignite(IgnitionState.ACC, IgnitionState.ON, IgnitionState.STARTING, IgnitionState.RUNNING)
        rig.bench.toggleFault(FaultId.MISFIRE)
        rig.bench.connect()
        rig.bench.quickScan()
        val scans = rig.bench.sessions().size

        val effect = rig.bench.clearCodes(BOTH) as BenchEffect.Rejected
        assertTrue(effect.messageHe, effect.messageHe.contains("המנוע פועל"))
        assertEquals(scans, rig.bench.sessions().size)
    }

    @Test
    fun refusedWithoutBothAcknowledgements() {
        val rig = scannedWithCode()
        val effect = rig.bench.clearCodes(ClearConfirmation(understoodCodesReturn = true, understoodReadinessReset = false))
        assertTrue(effect is BenchEffect.Rejected)
        assertEquals(1, rig.bench.sessions().size)
    }

    @Test
    fun refusedWithoutAFreshScanOnThisLink() {
        val rig = scannedWithCode()
        rig.bench.disconnect()
        rig.bench.connect()
        assertFalse(rig.bench.snapshot().capabilities.canClear)
        assertTrue(rig.bench.clearCodes(BOTH) is BenchEffect.Rejected)
    }

    @Test
    fun refusedWhenTheScanFoundNoCodes() {
        val rig = ClearRig()
        rig.ignite(IgnitionState.ACC, IgnitionState.ON)
        rig.bench.connect()
        rig.bench.quickScan()
        assertFalse(rig.bench.snapshot().capabilities.canClear)
        val effect = rig.bench.clearCodes(BOTH) as BenchEffect.Rejected
        assertTrue(effect.messageHe.contains("אין קודים"))
    }
}
