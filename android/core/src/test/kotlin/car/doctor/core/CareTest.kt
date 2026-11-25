package car.doctor.core

import car.doctor.core.care.CarePlan
import car.doctor.core.care.ReminderKind
import car.doctor.core.care.ServiceKind
import car.doctor.core.care.Reminders
import car.doctor.core.care.TrendDirection
import car.doctor.core.care.Trends
import car.doctor.core.model.AdapterKind
import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.Dtc
import car.doctor.core.model.DtcStatus
import car.doctor.core.model.EcuId
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.PidQuality
import car.doctor.core.model.PidValue
import car.doctor.core.model.Posture
import car.doctor.core.model.Severity
import car.doctor.core.visit.GarageVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun pid(id: String, v: Double) = PidValue(id, id, v, "", EcuId.ENGINE, PidQuality.VALID, true, true, "x")

private fun session(
    day: String,
    rpm: Double = 800.0,
    volts: Double = 14.2,
    codes: List<String> = emptyList(),
    kind: String = "quick",
    cleared: List<String> = emptyList(),
) = DiagnosticSession(
    id = "s$day", vehicleId = "v", kind = kind, adapterKind = AdapterKind.CLASSIC, adapterLabelHe = "",
    startedAt = "${day}T10:00:00Z", finishedAt = "${day}T10:00:05Z", durationMs = 5000,
    ignition = IgnitionState.ON, posture = Posture.values().first(), protocolHe = null, vinNoteHe = "",
    ecus = emptyList(), gaps = emptyList(),
    dtcs = codes.map { Dtc(it, DtcStatus.STORED, EcuId.ENGINE, "תיאור $it", Severity.MEDIUM, "x") },
    pids = listOf(pid("rpm", rpm), pid("voltage", volts)), readiness = emptyList(), findings = emptyList(),
    clearedCodes = cleared,
)

class CareTest {
    @Test
    fun serviceOverdueAndSoonByDate() {
        val overdue = Reminders.forVehicle(CarePlan(lastServiceDate = "2025-10-01", serviceEveryMonths = 12), emptyList(), emptyList(), "2026-11-25T08:00:00Z")
        assertTrue(overdue.any { it.kind == ReminderKind.SERVICE_DUE && it.urgent })
        val soon = Reminders.forVehicle(CarePlan(lastServiceDate = "2025-12-10"), emptyList(), emptyList(), "2026-11-25T08:00:00Z")
        assertTrue(soon.any { it.kind == ReminderKind.SERVICE_SOON })
        val later = Reminders.forVehicle(CarePlan(lastServiceDate = "2026-06-01", checkEveryDays = 0), emptyList(), emptyList(), "2026-05-25T08:00:00Z")
        assertTrue(later.isEmpty())
    }

    @Test
    fun serviceByKmUsesTheHigherOfTypedAndRead() {
        val plan = CarePlan(checkEveryDays = 0, serviceEveryMonths = 0, intervalAmount = 15000, lastServiceReading = 100_000, currentReading = 110_000)
        assertTrue(Reminders.forVehicle(plan, emptyList(), emptyList(), "2026-05-25T08:00:00Z").isEmpty())
        val read = session("2026-05-24").let { it.copy(pids = it.pids + pid("odometer", 114_200.0)) }
        val r = Reminders.forVehicle(plan, listOf(read), emptyList(), "2026-05-25T08:00:00Z")
        assertEquals(ReminderKind.SERVICE_SOON, r.single().kind)
        assertTrue(r.single().titleHe.contains("800"))
        val due = Reminders.forVehicle(plan.copy(currentReading = 116_000), emptyList(), emptyList(), "2026-05-25T08:00:00Z")
        assertTrue(due.single().urgent)
    }

    @Test
    fun winterReminderUntilAWinterServiceIsSaved() {
        val r = Reminders.forVehicle(CarePlan(checkEveryDays = 0, serviceEveryMonths = 0), emptyList(), emptyList(), "2026-10-20T08:00:00Z")
        assertTrue(r.any { it.kind == ReminderKind.WINTER })
        val done = CarePlan(checkEveryDays = 0, serviceEveryMonths = 0, serviceKind = ServiceKind.WINTER, lastServiceDate = "2026-10-05")
        assertTrue(Reminders.forVehicle(done, emptyList(), emptyList(), "2026-10-20T08:00:00Z").isEmpty())
    }

    @Test
    fun followUpAfterClearReplacesRoutine() {
        val s = listOf(session("2026-11-20", kind = "post-clear", cleared = listOf("P0300")))
        val r = Reminders.forVehicle(CarePlan(checkEveryDays = 3), s, emptyList(), "2026-11-25T08:00:00Z")
        assertEquals(ReminderKind.FOLLOW_UP_AFTER_CLEAR, r.first().kind)
        assertFalse(r.any { it.kind == ReminderKind.ROUTINE_SCAN })
    }

    @Test
    fun routineAndOpenVisit() {
        val s = listOf(session("2026-11-10"))
        val visit = GarageVisit("g1", "v", "מוסך", "שמן", "2026-11-20T09:00:00Z", "s2026-11-10")
        val r = Reminders.forVehicle(CarePlan(checkEveryDays = 7), s, listOf(visit), "2026-11-25T08:00:00Z")
        assertTrue(r.any { it.kind == ReminderKind.ROUTINE_SCAN })
        assertTrue(r.any { it.kind == ReminderKind.OPEN_VISIT })
    }

    @Test
    fun voltageTrendUsesRunningScansOnly() {
        val newestFirst = listOf(
            session("2026-11-24", volts = 13.4),
            session("2026-11-23", rpm = 0.0, volts = 12.1), // engine off: ignored
            session("2026-11-17", volts = 13.6),
            session("2026-11-10", volts = 14.1),
            session("2026-11-03", volts = 14.3),
        ).reversed().reversed()
        val t = Trends.forVehicle(newestFirst)
        val v = t.pids.first { it.id == "voltage" }
        assertEquals(4, v.readings)
        assertEquals(TrendDirection.DOWN, v.direction)
        assertNotNull(v.warningHe)
    }

    @Test
    fun codeThatReturnsAfterClearIsFlagged() {
        val newestFirst = listOf(
            session("2026-11-24", codes = listOf("P0300")),
            session("2026-11-20", kind = "post-clear", cleared = listOf("P0300")),
            session("2026-11-19", codes = listOf("P0300")),
        )
        val t = Trends.forVehicle(newestFirst)
        val c = t.recurring.single()
        assertEquals("P0300", c.code)
        assertTrue(c.returnedAfterClear)
        assertEquals(2, c.sessions)
    }

    @Test
    fun tooFewScansSaysSo() {
        val t = Trends.forVehicle(listOf(session("2026-11-24")))
        assertTrue(t.pids.isEmpty())
        assertTrue(t.noteHe.contains("לפחות"))
    }

    private fun bench(): car.doctor.core.session.VehicleBench {
        var n = 0
        val b = car.doctor.core.session.VehicleBench(
            repository = car.doctor.core.persistence.SnapshotGarageRepository(),
            adapters = car.doctor.core.adapter.ObdAdapterFactory { error("no adapter in this test") },
            clock = car.doctor.core.session.IsoClock { "2026-11-25T10:00:00.000Z" },
            ids = car.doctor.core.session.IdSource { p -> "$p-${++n}" },
        )
        b.addVehicle(car.doctor.core.model.NewVehicle("טסט", "טויוטה", "קורולה", 2016, car.doctor.core.model.Fuel.GASOLINE, ""))
        return b
    }

    @Test
    fun everySavedServiceIsKeptAndTheNewestDrivesReminders() {
        val b = bench()
        b.setCarePlan(CarePlan(lastServiceDate = "2025-06-01", lastServiceReading = 90_000), "מוסך א")
        b.setCarePlan(CarePlan(lastServiceDate = "2026-06-01", lastServiceReading = 105_000, serviceKind = ServiceKind.BASELINE))
        // re-saving the same service updates it instead of adding a duplicate
        b.setCarePlan(CarePlan(lastServiceDate = "2026-06-01", lastServiceReading = 105_500, serviceKind = ServiceKind.BASELINE))
        // an older service added later goes into the history only
        b.setCarePlan(CarePlan(lastServiceDate = "2024-06-01", lastServiceReading = 75_000))
        val history = b.services()
        assertEquals(listOf("2026-06-01", "2025-06-01", "2024-06-01"), history.map { it.date })
        assertEquals(105_500, history.first().reading)
        assertEquals("מוסך א", history[1].garageName)
        assertEquals("2026-06-01", b.carePlan()!!.lastServiceDate)
        assertEquals(ServiceKind.BASELINE, b.carePlan()!!.serviceKind)
        assertEquals(105_500, b.carePlan()!!.lastServiceReading)

        b.removeService(history.first().id)
        assertEquals("2025-06-01", b.carePlan()!!.lastServiceDate)
        assertEquals(90_000, b.carePlan()!!.lastServiceReading)
    }

    @Test
    fun aFutureServiceDateIsRejected() {
        val b = bench()
        assertTrue(b.setCarePlan(CarePlan(lastServiceDate = "2026-12-01")) is car.doctor.core.session.BenchEffect.Rejected)
        assertTrue(b.services().isEmpty())
    }
}
