package car.doctor.core

import car.doctor.core.catalog.CLEAR_SCAN_HE
import car.doctor.core.catalog.ecuAccessLabel
import car.doctor.core.machine.IgnitionStateMachine
import car.doctor.core.machine.ObdSessionStateMachine
import car.doctor.core.machine.shouldCommitScan
import car.doctor.core.model.AdapterKind
import car.doctor.core.model.Confidence
import car.doctor.core.model.Dtc
import car.doctor.core.model.DtcStatus
import car.doctor.core.model.EcuAccess
import car.doctor.core.model.EcuId
import car.doctor.core.model.EcuReport
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.ObdSessionState
import car.doctor.core.model.PidQuality
import car.doctor.core.model.PidValue
import car.doctor.core.model.Posture
import car.doctor.core.model.ScanFacts
import car.doctor.core.model.Severity
import car.doctor.core.model.Vehicle
import car.doctor.core.model.VehicleSim
import car.doctor.core.persistence.HISTORY_LIMIT
import car.doctor.core.persistence.SnapshotGarageRepository
import car.doctor.core.present.SessionPresenter
import car.doctor.core.rules.DiagnosticRules
import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.Finding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleModelTest {
    @Test
    fun vehicleStartsOffAndKeepsTheTypedVin() {
        val vehicle = Vehicle(
            id = "v1",
            nickname = "קורולה",
            make = "טויוטה",
            model = "קורולה",
            year = 2016,
            fuel = Fuel.GASOLINE,
            vin = "HAND-VIN",
            createdAt = WHEN,
        )
        assertEquals(IgnitionState.OFF, vehicle.sim.ignition)
        assertEquals(Posture.IDLE, vehicle.sim.posture)
        assertTrue(vehicle.sim.faults.isEmpty())
        assertEquals("HAND-VIN", vehicle.vin)
        assertNull(vehicle.sim.lostEcu)
    }
}

class DtcModelTest {
    @Test
    fun dtcKeepsCodeEcuStatusAndReadTime() {
        val dtc = Dtc(
            code = "P0301",
            status = DtcStatus.STORED,
            ecuId = EcuId.ENGINE,
            descriptionHe = "החטאת בעירה ששויכה לצילינדר 1.",
            severity = Severity.HIGH,
            readAt = WHEN,
        )
        assertEquals("P0301", dtc.code)
        assertEquals(EcuId.ENGINE, dtc.ecuId)
        assertEquals(DtcStatus.STORED, dtc.status)
        assertEquals(WHEN, dtc.readAt)
        assertFalse(dtc.descriptionHe.contains("מקולקל"))
    }
}

class PidModelTest {
    @Test
    fun pidKeepsIdValueUnitEcuAndReadTime() {
        val pid = PidValue(
            id = "rpm",
            nameHe = "סל״ד",
            value = 780.0,
            unit = "rpm",
            ecuId = EcuId.ENGINE,
            quality = PidQuality.VALID,
            genericObd = true,
            expected = true,
            readAt = WHEN,
        )
        assertEquals("rpm", pid.id)
        assertEquals(780.0, pid.value!!, 0.0)
        assertEquals("rpm", pid.unit)
        assertEquals(EcuId.ENGINE, pid.ecuId)
        assertEquals(WHEN, pid.readAt)
        assertEquals(PidQuality.VALID, pid.quality)
    }
}

class IgnitionStateMachineTest {
    @Test
    fun allowsTheKeyCycleAndRealReversals() {
        val legal = listOf(
            IgnitionState.OFF to IgnitionState.ACC,
            IgnitionState.ACC to IgnitionState.ON,
            IgnitionState.ON to IgnitionState.STARTING,
            IgnitionState.STARTING to IgnitionState.RUNNING,
            IgnitionState.RUNNING to IgnitionState.OFF,
            IgnitionState.ACC to IgnitionState.OFF,
            IgnitionState.ON to IgnitionState.ACC,
            IgnitionState.STARTING to IgnitionState.ON,
            IgnitionState.RUNNING to IgnitionState.ON,
        )
        for ((from, to) in legal) {
            val machine = IgnitionStateMachine(from)
            assertTrue("$from -> $to", machine.transition(to))
            assertEquals(to, machine.state)
        }
    }

    @Test
    fun rejectsSkippedIgnitionTransitions() {
        val illegal = listOf(
            IgnitionState.OFF to IgnitionState.RUNNING,
            IgnitionState.OFF to IgnitionState.STARTING,
            IgnitionState.OFF to IgnitionState.ON,
            IgnitionState.RUNNING to IgnitionState.ACC,
            IgnitionState.RUNNING to IgnitionState.STARTING,
            IgnitionState.ACC to IgnitionState.RUNNING,
            IgnitionState.ACC to IgnitionState.STARTING,
            IgnitionState.STARTING to IgnitionState.ACC,
        )
        for ((from, to) in illegal) {
            val machine = IgnitionStateMachine(from)
            assertFalse("$from -> $to", machine.transition(to))
            assertEquals(from, machine.state)
        }
        assertFalse(IgnitionStateMachine.allowsObd(IgnitionState.OFF))
        assertFalse(IgnitionStateMachine.allowsObd(IgnitionState.ACC))
        assertTrue(IgnitionStateMachine.allowsObd(IgnitionState.ON))
        assertTrue(IgnitionStateMachine.allowsObd(IgnitionState.RUNNING))
    }
}

class ObdSessionStateMachineTest {
    @Test
    fun allowsLegalSessionEdges() {
        val legal = listOf(
            ObdSessionState.DISCONNECTED to ObdSessionState.CONNECTING,
            ObdSessionState.CONNECTING to ObdSessionState.CONNECTED,
            ObdSessionState.CONNECTING to ObdSessionState.ERROR,
            ObdSessionState.CONNECTED to ObdSessionState.SCANNING,
            ObdSessionState.CONNECTED to ObdSessionState.DISCONNECTING,
            ObdSessionState.SCANNING to ObdSessionState.COMPLETE,
            ObdSessionState.SCANNING to ObdSessionState.ERROR,
            ObdSessionState.SCANNING to ObdSessionState.DISCONNECTING,
            ObdSessionState.COMPLETE to ObdSessionState.SCANNING,
            ObdSessionState.COMPLETE to ObdSessionState.DISCONNECTING,
            ObdSessionState.DISCONNECTING to ObdSessionState.DISCONNECTED,
            ObdSessionState.ERROR to ObdSessionState.DISCONNECTED,
            ObdSessionState.ERROR to ObdSessionState.CONNECTING,
        )
        for ((from, to) in legal) {
            val machine = ObdSessionStateMachine(from)
            assertTrue("$from -> $to", machine.transition(to))
            assertEquals(to, machine.state)
        }
    }

    @Test
    fun rejectsIllegalSessionJumps() {
        val illegal = listOf(
            ObdSessionState.DISCONNECTED to ObdSessionState.SCANNING,
            ObdSessionState.DISCONNECTED to ObdSessionState.CONNECTED,
            ObdSessionState.DISCONNECTED to ObdSessionState.COMPLETE,
            ObdSessionState.SCANNING to ObdSessionState.DISCONNECTED,
            ObdSessionState.CONNECTED to ObdSessionState.COMPLETE,
            ObdSessionState.CONNECTED to ObdSessionState.DISCONNECTED,
            ObdSessionState.COMPLETE to ObdSessionState.CONNECTED,
            ObdSessionState.ERROR to ObdSessionState.SCANNING,
            ObdSessionState.CONNECTING to ObdSessionState.DISCONNECTED,
        )
        for ((from, to) in illegal) {
            val machine = ObdSessionStateMachine(from)
            assertFalse("$from -> $to", machine.transition(to))
            assertEquals(from, machine.state)
        }
    }

    @Test
    fun disconnectFromConnectedVisitsDisconnecting() {
        val machine = ObdSessionStateMachine(ObdSessionState.CONNECTED)
        val path = machine.disconnectPath()
        assertEquals(
            listOf(ObdSessionState.DISCONNECTING, ObdSessionState.DISCONNECTED),
            path,
        )
        val seen = mutableListOf(machine.state)
        for (step in path!!) {
            assertTrue(machine.transition(step))
            seen += machine.state
        }
        assertEquals(
            listOf(
                ObdSessionState.CONNECTED,
                ObdSessionState.DISCONNECTING,
                ObdSessionState.DISCONNECTED,
            ),
            seen,
        )
        assertNull(ObdSessionStateMachine.disconnectPath(ObdSessionState.CONNECTING))
        assertEquals(
            listOf(ObdSessionState.DISCONNECTED),
            ObdSessionStateMachine.disconnectPath(ObdSessionState.ERROR),
        )
    }

    @Test
    fun scanIsCommittedOnlyWhileThatAttemptIsStillScanning() {
        assertTrue(shouldCommitScan(3, 3, ObdSessionState.SCANNING))
        assertFalse(shouldCommitScan(3, 4, ObdSessionState.SCANNING))
        assertFalse(shouldCommitScan(3, 3, ObdSessionState.DISCONNECTING))
    }
}

class MisfireRuleTest {
    @Test
    fun p0301UsesTheMisfireRuleWithoutNamingABrokenPart() {
        val findings = DiagnosticRules.evaluate(facts(dtc = stored("P0301")), IgnitionState.RUNNING)
        val hit = findings.single { it.code == "P0301" }
        assertEquals("misfire-code", hit.ruleId)
        assertEquals("הקוד נקרא מהיחידה", hit.claimHe)
        assertEquals(Confidence.HIGH, hit.confidence)
        assertEquals(Severity.HIGH, hit.severity)
        assertTrue(hit.evidence.isNotEmpty())
        assertFalse(hit.summaryHe.contains("מקולקל"))
        assertFalse(hit.summaryHe.contains("החלף"))
        assertFalse(hit.claimHe.contains("רכיב"))
    }
}

class LowBatteryRuleTest {
    @Test
    fun lowVoltageIsAMeasurementNotANamedPart() {
        val findings = DiagnosticRules.evaluate(
            facts(
                pids = listOf(
                    pid("voltage", 11.2, "V", EcuId.ENGINE),
                    pid("bcmVoltage", 11.2, "V", EcuId.BCM),
                ),
            ),
            IgnitionState.RUNNING,
        )
        val hit = findings.single { it.ruleId == "voltage-low" }
        assertEquals("חריגת מדידה", hit.claimHe)
        assertEquals(Confidence.HIGH, hit.confidence)
        assertTrue(hit.summaryHe.contains("לא קביעה איזה רכיב"))
        assertFalse(hit.summaryHe.contains("אלטרנטור תקול"))
        assertFalse(hit.summaryHe.contains("מצבר תקול"))
    }
}

class WheelSpeedSuspicionTest {
    @Test
    fun straightLineGapIsOnlyASuspicion() {
        val findings = DiagnosticRules.evaluate(
            facts(
                pids = listOf(
                    pid("speed", 62.0, "קמ״ש", EcuId.ENGINE),
                    pid("steer", 0.6, "°", EcuId.ABS),
                    pid("brake", 0.0, "bool", EcuId.ABS),
                    pid("wheelFl", 55.2, "קמ״ש", EcuId.ABS),
                    pid("wheelFr", 61.8, "קמ״ש", EcuId.ABS),
                    pid("wheelRl", 62.1, "קמ״ש", EcuId.ABS),
                    pid("wheelRr", 61.9, "קמ״ש", EcuId.ABS),
                ),
            ),
            IgnitionState.RUNNING,
        )
        val hit = findings.single { it.ruleId == "wheel-speed" }
        assertEquals("חשד", hit.claimHe)
        assertEquals(Confidence.MEDIUM, hit.confidence)
        assertTrue(hit.summaryHe.contains("אין כאן זיהוי של רכיב פגום"))
        assertFalse(hit.summaryHe.contains("חיישן פגום"))
    }
}

class CoverageHonestyTest {
    @Test
    fun noCoverageUsesHebrewAndAClearScanIsNotACleanBill() {
        val findings = DiagnosticRules.evaluate(
            facts(access = EcuAccess.NOT_SCANNED, ecusChecked = false),
            IgnitionState.OFF,
        )
        val hit = findings.single { it.ruleId == "no-coverage" }
        assertEquals("לא ניתן לקבוע", hit.claimHe)
        assertEquals(Confidence.INSUFFICIENT, hit.confidence)
        assertTrue(hit.evidence.any { it.contains("לא נבדק") })
        assertFalse(hit.evidence.any { it.contains("not_scanned") })
        assertEquals("לא נבדק", ecuAccessLabel(EcuAccess.NOT_SCANNED))
        assertEquals("לא נגיש", ecuAccessLabel(EcuAccess.INACCESSIBLE))
        assertEquals("שגיאת תקשורת", ecuAccessLabel(EcuAccess.COMM_ERROR))
        assertTrue(CLEAR_SCAN_HE.startsWith("לא נמצאו ממצאים לפי הנתונים שנבדקו."))
        assertFalse(CLEAR_SCAN_HE.contains("הרכב תקין."))
        val presented = SessionPresenter.present(sampleSession(id = "s", findings = emptyList()))
        assertEquals(CLEAR_SCAN_HE, presented.headlineHe)
    }
}

class HistoryRepositoryTest {
    @Test
    fun historyIsNewestFirstDedupedAndCapped() {
        val repo = SnapshotGarageRepository()
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
        repo.upsertVehicle(vehicle)
        repo.select(vehicle.id)
        repo.appendSession(sampleSession("a"))
        repo.appendSession(sampleSession("a"))
        repo.appendSession(sampleSession("b"))
        assertEquals(listOf("b", "a"), repo.sessionsFor("v1").map { it.id })
        repeat(50) { index -> repo.appendSession(sampleSession("n$index")) }
        assertEquals(HISTORY_LIMIT, repo.sessionsFor("v1").size)
        assertEquals("n49", repo.sessionsFor("v1").first().id)
        val other = sampleSession("other", vehicleId = "v2")
        repo.appendSession(other)
        assertTrue(repo.sessionsFor("v1").none { it.id == "other" })
        assertEquals(listOf(other.id), repo.sessionsFor("v2").map { it.id })
    }
}

internal const val WHEN = "2026-10-22T12:00:00.000Z"

internal fun sampleSession(
    id: String,
    vehicleId: String = "v1",
    findings: List<Finding> = emptyList(),
) = DiagnosticSession(
    id = id,
    vehicleId = vehicleId,
    adapterKind = AdapterKind.SIMULATED,
    adapterLabelHe = "מתאם OBD מדומה",
    startedAt = WHEN,
    finishedAt = WHEN,
    durationMs = 1,
    ignition = IgnitionState.RUNNING,
    posture = Posture.STRAIGHT,
    protocolHe = null,
    vinNoteHe = "ידני",
    ecus = emptyList(),
    gaps = emptyList(),
    dtcs = emptyList(),
    pids = emptyList(),
    readiness = emptyList(),
    findings = findings,
)

private fun stored(code: String) = Dtc(
    code = code,
    status = DtcStatus.STORED,
    ecuId = EcuId.ENGINE,
    descriptionHe = "תיאור",
    severity = Severity.HIGH,
    readAt = WHEN,
)

private fun pid(id: String, value: Double, unit: String, ecuId: EcuId) = PidValue(
    id = id,
    nameHe = id,
    value = value,
    unit = unit,
    ecuId = ecuId,
    quality = PidQuality.VALID,
    genericObd = ecuId == EcuId.ENGINE,
    expected = true,
    readAt = WHEN,
)

private fun facts(
    dtc: Dtc? = null,
    pids: List<PidValue> = emptyList(),
    access: EcuAccess = EcuAccess.CHECKED,
    ecusChecked: Boolean = true,
): ScanFacts {
    val ecuAccess = if (ecusChecked) access else EcuAccess.NOT_SCANNED
    return ScanFacts(
        readAt = WHEN,
        fuel = Fuel.GASOLINE,
        protocolHe = if (ecuAccess == EcuAccess.CHECKED) "מדומה" else null,
        vinNoteHe = "ידני",
        ecus = EcuId.entries.map { id ->
            EcuReport(id, if (id == EcuId.ENGINE || ecusChecked) ecuAccess else ecuAccess, "סיבה", null)
        },
        gaps = emptyList(),
        dtcs = listOfNotNull(dtc),
        pids = pids,
        readiness = emptyList(),
        engineTimeout = false,
    )
}
