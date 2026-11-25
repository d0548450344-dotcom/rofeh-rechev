package car.doctor.simulation

import car.doctor.core.model.FaultId
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.NewVehicle
import car.doctor.core.persistence.SnapshotGarageRepository
import car.doctor.core.session.BenchEffect
import car.doctor.core.session.IdSource
import car.doctor.core.session.IsoClock
import car.doctor.core.session.VehicleBench
import car.doctor.core.visit.VisitPresenter
import car.doctor.transport.MockTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class VisitRig {
    private var tick = 0
    private var n = 0
    val repo = SnapshotGarageRepository()
    val bench = VehicleBench(
        repository = repo,
        adapters = MockObdAdapterFactory(MockTransport()),
        clock = IsoClock { "2026-11-25T10:%02d:00.000Z".format(tick++ % 60) },
        ids = IdSource { prefix -> "$prefix-${++n}" },
    )

    init {
        bench.addVehicle(NewVehicle("טסט", "טויוטה", "קורולה", 2016, Fuel.GASOLINE, ""))
        bench.setIgnition(IgnitionState.ACC)
        bench.setIgnition(IgnitionState.ON)
    }

    fun scan() {
        if (!bench.snapshot().capabilities.canScan) bench.connect()
        check(bench.quickScan() is BenchEffect.Accepted)
    }

    fun view() = VisitPresenter.present(bench.visits().first(), bench.sessions())
}

class GarageVisitTest {
    @Test
    fun aNewCodeAfterTheGarageIsFlagged() {
        val rig = VisitRig()
        rig.scan()
        assertTrue(rig.bench.snapshot().capabilities.canStartVisit)
        assertTrue(rig.bench.startGarageVisit("מוסך הגליל", "החלפת שמן ומסנן") is BenchEffect.Accepted)
        assertTrue(rig.view().open)

        rig.bench.disconnect()
        rig.bench.toggleFault(FaultId.MISFIRE) // something changed at the garage
        rig.scan()
        assertTrue(rig.bench.snapshot().capabilities.canFinishVisit)
        assertTrue(rig.bench.finishGarageVisit("הוחלף שמן") is BenchEffect.Accepted)

        val view = rig.view()
        assertFalse(view.open)
        assertTrue(view.warning)
        assertTrue(view.newCodes.isNotEmpty())
        assertTrue(view.summaryHe.first().contains("הופיעו קודים"))
        assertEquals("הוחלף שמן", view.reportedWorkHe)
    }

    @Test
    fun aRepairedFaultShowsAsGone() {
        val rig = VisitRig()
        rig.bench.toggleFault(FaultId.MISFIRE)
        rig.scan()
        rig.bench.startGarageVisit("מוסך", "תיקון החטאות")
        rig.bench.disconnect()
        rig.bench.toggleFault(FaultId.MISFIRE) // repaired
        rig.scan()
        rig.bench.finishGarageVisit("")

        val view = rig.view()
        assertTrue(view.goneCodes.isNotEmpty())
        assertTrue(view.newCodes.isEmpty())
        assertTrue(view.summaryHe.any { it.contains("נראה שהתקלה טופלה") })
    }

    @Test
    fun theVisitNeedsAScanBeforeAndANewerScanAfter() {
        val rig = VisitRig()
        rig.bench.connect()
        assertTrue(rig.bench.startGarageVisit("מוסך", "בדיקה") is BenchEffect.Rejected)
        rig.bench.quickScan()
        assertTrue(rig.bench.startGarageVisit("", "בדיקה") is BenchEffect.Rejected)
        assertTrue(rig.bench.startGarageVisit("מוסך", " ") is BenchEffect.Rejected)
        assertTrue(rig.bench.startGarageVisit("מוסך", "בדיקה") is BenchEffect.Accepted)
        // Same scan cannot be both before and after.
        assertFalse(rig.bench.snapshot().capabilities.canFinishVisit)
        assertTrue(rig.bench.finishGarageVisit("") is BenchEffect.Rejected)
        // Only one open visit at a time.
        assertTrue(rig.bench.startGarageVisit("מוסך אחר", "משהו") is BenchEffect.Rejected)
    }

    @Test
    fun scansAVisitPointsToSurviveHistoryTrimming() {
        val rig = VisitRig()
        rig.scan()
        rig.bench.startGarageVisit("מוסך", "טיפול")
        val beforeId = rig.bench.visits().first().beforeSessionId
        repeat(car.doctor.core.persistence.HISTORY_LIMIT + 5) { rig.bench.quickScan() }
        assertTrue(rig.bench.sessions().any { it.id == beforeId })
    }

    @Test
    fun aFinishedVisitCanBeSavedAsALinkedService() {
        val rig = VisitRig()
        rig.scan()
        rig.bench.startGarageVisit("מוסך הגליל", "טיפול 15,000")
        rig.bench.disconnect()
        rig.scan()
        // an impossible reading is refused and the visit stays open
        val bad = car.doctor.core.care.ServiceDraft(car.doctor.core.care.ServiceKind.PERIODIC, -5)
        assertTrue(rig.bench.finishGarageVisit("הוחלף שמן", bad) is BenchEffect.Rejected)
        assertTrue(rig.bench.visits().first().isOpen)

        val draft = car.doctor.core.care.ServiceDraft(car.doctor.core.care.ServiceKind.PERIODIC, 120_000)
        assertTrue(rig.bench.finishGarageVisit("הוחלף שמן", draft) is BenchEffect.Accepted)
        val visit = rig.bench.visits().first()
        val service = rig.bench.services().single()
        assertEquals(service.id, visit.serviceId)
        assertEquals(visit.id, service.visitId)
        assertEquals("מוסך הגליל", service.garageName)
        assertEquals("2026-11-25", service.date)
        assertEquals("הוחלף שמן", service.noteHe)
        assertEquals(120_000, rig.bench.carePlan()!!.lastServiceReading)
        assertTrue(VisitPresenter.present(visit, rig.bench.sessions(), rig.bench.services()).serviceHe!!.contains("120,000"))

        // deleting the service unlinks the visit but keeps the before/after comparison
        rig.bench.removeService(service.id)
        assertNull(rig.bench.visits().first().serviceId)
        assertFalse(rig.bench.visits().first().isOpen)
    }
}
