package car.doctor.core.care

import car.doctor.core.model.AdapterKind
import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.MonitorStatus
import car.doctor.core.model.Vehicle
import car.doctor.core.visit.GarageVisit
import car.doctor.core.visit.VisitComparer
import java.time.temporal.ChronoUnit

/** One fault code from the car's history and the receipt that covers its repair. */
data class CodeHistory(
    val code: String,
    val descriptionHe: String,
    val firstSeen: String,
    val lastSeen: String,
    /** The receipt-backed service dated on or after the code first appeared, if any. */
    val repair: ServiceRecord?,
)

data class SaleReport(
    val vehicleHe: String,
    val vinRead: String?,
    val vinNoteHe: String?,
    val basisDate: String,
    val odometerHe: String?,
    val readinessHe: List<String>,
    /** ABS / airbags: read and clean, or not read. */
    val modulesHe: List<String> = emptyList(),
    val codes: List<CodeHistory>,
    val services: List<ServiceRecord>,
    val visitsHe: List<String>,
    val realScans: Int,
    val generatedAt: String,
    val disclaimersHe: List<String>,
)

/** Either the report, or the list of things that still block it. */
data class SaleReportCheck(val report: SaleReport?, val blockersHe: List<String>)

/**
 * The owner's rule: a sale report is issued only for a car whose faults were repaired and proven.
 *  1. The newest scan from a real adapter is a deep scan, at most [MAX_AGE_DAYS] old.
 *  2. That scan has no stored, pending or permanent codes. Permanent codes clear only when the
 *     car itself has confirmed the repair, so this cannot be faked by clearing codes.
 *  3. Every readiness monitor the car supports is complete. Clearing codes resets them, so a
 *     car cleared just before the sale shows as not ready.
 *  4. Every code that ever appeared in a real scan is covered by a service with a receipt photo,
 *     dated on or after the day the code first appeared.
 * Simulator scans never count.
 */
object SaleReports {
    const val MAX_AGE_DAYS = 14L

    fun check(
        vehicle: Vehicle,
        sessions: List<DiagnosticSession>,
        services: List<ServiceRecord>,
        visits: List<GarageVisit>,
        now: String,
    ): SaleReportCheck {
        val today = Reminders.day(now)
        val real = sessions.filter { it.adapterKind != AdapterKind.SIMULATED && it.errorHe == null }
        val basis = real.firstOrNull()
            ?: return SaleReportCheck(null, listOf("צריך בדיקה מעמיקה ממתאם אמיתי. סריקות מהמדמה לא נספרות."))

        val blockers = mutableListOf<String>()
        if (basis.kind != "deep") blockers += "הסריקה האחרונה צריכה להיות בדיקה מעמיקה."
        val age = Reminders.day(basis.finishedAt)?.let { d -> today?.let { ChronoUnit.DAYS.between(d, it) } }
        if (age == null || age > MAX_AGE_DAYS) blockers += "הבדיקה המעמיקה צריכה להיות מ-$MAX_AGE_DAYS הימים האחרונים."
        if (basis.dtcs.isNotEmpty()) {
            blockers += "ברכב עדיין יש קודים: ${basis.dtcs.map { it.code }.distinct().joinToString(", ")}. מתקנים, ואז סורקים שוב."
        }
        basis.modules.filter { it.faults.isNotEmpty() }.forEach { m ->
            blockers += "ב${m.kind.labelHe} יש קודים: ${m.faults.joinToString(", ") { it.label }}. מתקנים במוסך, ואז בדיקה מעמיקה שוב."
        }
        val supported = basis.readiness.filter { it.status != MonitorStatus.UNSUPPORTED }
        val notReady = supported.filter { it.status == MonitorStatus.INCOMPLETE }
        when {
            supported.isEmpty() -> blockers += "הרכב לא דיווח על בדיקות המוכנות שלו."
            notReady.isNotEmpty() -> blockers +=
                "בדיקות המוכנות עוד לא הושלמו: ${notReady.joinToString(", ") { it.nameHe }}. נוסעים כמה ימים בתנאים רגילים וסורקים שוב."
        }

        val history = codeHistory(real, services)
        history.filter { it.repair == null }.forEach {
            blockers += "קוד ${it.code} (הופיע ב-${it.firstSeen}): חסר טיפול עם צילום קבלה מאותו יום ואילך."
        }
        if (blockers.isNotEmpty()) return SaleReportCheck(null, blockers)

        val odometer = basis.pids.firstOrNull { it.id == "odometer" && it.value != null }?.value
        val report = SaleReport(
            vehicleHe = "${vehicle.make} ${vehicle.model} ${vehicle.year}".trim(),
            vinRead = basis.vinRead,
            vinNoteHe = basis.vinNoteHe,
            basisDate = basis.finishedAt.take(10),
            odometerHe = odometer?.let { "%,d ק״מ (נקרא מהרכב)".format(it.toLong()) }
                ?: vehicle.care.currentReading?.let { "%,d %s (הוקלד על ידי הבעלים)".format(it, vehicle.care.intervalUnit.shortHe) },
            readinessHe = supported.map { "${it.nameHe}: הושלמה" },
            codes = history,
            services = services,
            visitsHe = visits.filter { !it.isOpen }.mapNotNull { v -> visitLine(v, sessions) },
            realScans = real.size,
            generatedAt = now,
            modulesHe = basis.modules.map { m ->
                "${m.kind.labelHe}: " + if (m.reached) "נקרא, אין קודי תקלה" else "לא נקרא"
            },
            disclaimersHe = listOf(
                if (basis.modules.any { it.reached }) {
                    "המנוע והפליטה נבדקו בנתוני OBD-II כלליים; ABS וכריות אוויר נקראו רק במה שמפורט למעלה. מערכות מרכב לא נבדקו."
                } else {
                    "הדו״ח מבוסס על נתוני OBD-II כלליים של המנוע והפליטה. ABS, כריות אוויר ומערכות מרכב לא נבדקו."
                },
                "צילומי הקבלות צורפו על ידי הבעלים. האפליקציה לא מאמתת את אמיתותן.",
                "היסטוריה כוללת רק סריקות שנעשו באפליקציה הזו. מה שקרה לפני כן לא ידוע לה.",
                "מומלץ לקונה לבצע בדיקה עצמאית לפני הקנייה.",
            ),
        )
        return SaleReportCheck(report, emptyList())
    }

    /** Every code seen in real scans (oldest first) and the receipt-backed service that covers it. */
    fun codeHistory(real: List<DiagnosticSession>, services: List<ServiceRecord>): List<CodeHistory> {
        val withReceipt = services.filter { it.receiptFile != null }.sortedBy { it.date }
        return real.reversed()
            .flatMap { s -> s.dtcs.map { it to s.finishedAt.take(10) } }
            .groupBy { it.first.code }
            .map { (code, seen) ->
                val first = seen.minOf { it.second }
                CodeHistory(
                    code = code,
                    descriptionHe = seen.last().first.descriptionHe,
                    firstSeen = first,
                    lastSeen = seen.maxOf { it.second },
                    repair = withReceipt.firstOrNull { it.date >= first },
                )
            }
            .sortedBy { it.firstSeen }
    }

    private fun visitLine(visit: GarageVisit, sessions: List<DiagnosticSession>): String? {
        val before = sessions.firstOrNull { it.id == visit.beforeSessionId } ?: return null
        val after = sessions.firstOrNull { it.id == visit.afterSessionId } ?: return null
        val c = VisitComparer.compare(before, after)
        val verdict = if (c.hasWarnings) "נמצאו הבדלים" else "ללא הבדלים חשודים"
        return "${visit.openedAt.take(10)} · ${visit.garageName} · לפני/אחרי: $verdict"
    }
}
