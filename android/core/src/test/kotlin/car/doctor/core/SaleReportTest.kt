package car.doctor.core

import car.doctor.core.care.SaleReports
import car.doctor.core.care.ServiceKind
import car.doctor.core.care.ServiceRecord
import car.doctor.core.model.AdapterKind
import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.Dtc
import car.doctor.core.model.DtcStatus
import car.doctor.core.model.EcuId
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.Monitor
import car.doctor.core.model.MonitorStatus
import car.doctor.core.model.Posture
import car.doctor.core.model.Severity
import car.doctor.core.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val CAR = Vehicle("v", "קורולה", "טויוטה", "קורולה", 2016, Fuel.GASOLINE, "", "2026-01-01T00:00:00Z")
private const val NOW = "2026-11-26T10:00:00Z"

private fun scan(
    day: String,
    codes: List<Pair<String, DtcStatus>> = emptyList(),
    kind: String = "deep",
    ready: Boolean = true,
    adapter: AdapterKind = AdapterKind.CLASSIC,
) = DiagnosticSession(
    id = "s$day$kind", vehicleId = "v", kind = kind, adapterKind = adapter, adapterLabelHe = "",
    startedAt = "${day}T10:00:00Z", finishedAt = "${day}T10:00:05Z", durationMs = 5000,
    ignition = IgnitionState.ON, posture = Posture.values().first(), protocolHe = null, vinNoteHe = "",
    ecus = emptyList(), gaps = emptyList(),
    dtcs = codes.map { (c, st) -> Dtc(c, st, EcuId.ENGINE, "תיאור $c", Severity.MEDIUM, "x") },
    pids = emptyList(),
    readiness = listOf(
        Monitor("cat", "ממיר קטליטי", if (ready) MonitorStatus.COMPLETE else MonitorStatus.INCOMPLETE),
        Monitor("evap", "אידוי דלק", MonitorStatus.UNSUPPORTED),
    ),
    findings = emptyList(),
)

private fun service(date: String, receipt: Boolean) =
    ServiceRecord("svc$date", "v", date, ServiceKind.PERIODIC, receiptFile = if (receipt) "r.jpg" else null, garageName = "מוסך")

class SaleReportTest {
    @Test
    fun simulatorScansNeverCount() {
        val r = SaleReports.check(CAR, listOf(scan("2026-11-25", adapter = AdapterKind.SIMULATED)), emptyList(), emptyList(), NOW)
        assertNull(r.report)
        assertTrue(r.blockersHe.single().contains("מתאם אמיתי"))
    }

    @Test
    fun aCleanReadyDeepScanWithNoHistoryGivesAReport() {
        val r = SaleReports.check(CAR, listOf(scan("2026-11-25")), emptyList(), emptyList(), NOW)
        assertNotNull(r.report)
        assertEquals(listOf("ממיר קטליטי: הושלמה"), r.report!!.readinessHe)
    }

    @Test
    fun codesStillPresentOrPermanentBlock() {
        val r = SaleReports.check(CAR, listOf(scan("2026-11-25", listOf("P0420" to DtcStatus.PERMANENT))), emptyList(), emptyList(), NOW)
        assertTrue(r.blockersHe.any { it.contains("P0420") && it.contains("עדיין") })
    }

    @Test
    fun clearedJustBeforeSaleShowsAsNotReady() {
        val r = SaleReports.check(CAR, listOf(scan("2026-11-25", ready = false)), emptyList(), emptyList(), NOW)
        assertTrue(r.blockersHe.any { it.contains("ממיר קטליטי") })
    }

    @Test
    fun aPastCodeNeedsAReceiptDatedAfterItAppeared() {
        val history = listOf(scan("2026-11-25"), scan("2026-10-01", listOf("P0300" to DtcStatus.STORED), kind = "quick"))
        val noReceipt = SaleReports.check(CAR, history, listOf(service("2026-10-05", receipt = false)), emptyList(), NOW)
        assertTrue(noReceipt.blockersHe.any { it.contains("P0300") })
        val tooEarly = SaleReports.check(CAR, history, listOf(service("2026-09-01", receipt = true)), emptyList(), NOW)
        assertTrue(tooEarly.blockersHe.any { it.contains("P0300") })
        val ok = SaleReports.check(CAR, history, listOf(service("2026-10-05", receipt = true)), emptyList(), NOW)
        assertNotNull(ok.report)
        assertEquals("svc2026-10-05", ok.report!!.codes.single().repair!!.id)
    }

    @Test
    fun theBasisMustBeARecentDeepScan() {
        val quick = SaleReports.check(CAR, listOf(scan("2026-11-25", kind = "quick")), emptyList(), emptyList(), NOW)
        assertTrue(quick.blockersHe.any { it.contains("בדיקה מעמיקה") })
        val old = SaleReports.check(CAR, listOf(scan("2026-10-01")), emptyList(), emptyList(), NOW)
        assertTrue(old.blockersHe.any { it.contains("14") })
    }
}
