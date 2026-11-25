package car.doctor.core.visit

import car.doctor.core.care.ServiceRecord
import car.doctor.core.catalog.describeDtc
import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.EcuAccess
import car.doctor.core.model.MonitorStatus
import car.doctor.core.model.PidQuality
import kotlinx.serialization.Serializable

/**
 * One trip to a garage or any car service provider.
 * Opened right after a scan ("before"), closed right after a later scan ("after").
 * The two sessions are compared, so the owner can see whether anything changed
 * that the planned work does not explain.
 */
@Serializable
data class GarageVisit(
    val id: String,
    val vehicleId: String,
    val garageName: String,
    /** What the owner was told would be done, in their words. */
    val plannedWork: String,
    val openedAt: String,
    val beforeSessionId: String,
    val afterSessionId: String? = null,
    val closedAt: String? = null,
    /** What the owner was told was actually done, if they add it when closing. */
    val reportedWork: String = "",
    /** The service record this visit was saved as, if the owner chose to. */
    val serviceId: String? = null,
) {
    val isOpen: Boolean get() = afterSessionId == null
}

/** A code that differs between the two scans, with its description. */
data class CodeChange(val code: String, val descriptionHe: String)

/**
 * Facts only. No guess about who caused what: a new code after a visit is something to
 * ask the garage about, not proof of damage.
 */
data class VisitComparison(
    val newCodes: List<CodeChange>,
    val goneCodes: List<CodeChange>,
    val stillCodes: List<CodeChange>,
    /** Monitors that were complete before and are not now: typical after clearing codes or a battery disconnect. */
    val readinessReset: List<String>,
    /** Systems answered before but not after, or the other way round. */
    val coverageChangedHe: List<String>,
    val voltageBefore: Double?,
    val voltageAfter: Double?,
    val summaryHe: List<String>,
) {
    val hasWarnings: Boolean get() = newCodes.isNotEmpty() || (goneCodes.isNotEmpty() && readinessReset.isNotEmpty())
}

object VisitComparer {
    fun compare(before: DiagnosticSession, after: DiagnosticSession): VisitComparison {
        val beforeCodes = before.dtcs.map { it.code }.distinct()
        val afterCodes = after.dtcs.map { it.code }.distinct()
        fun change(code: String) = CodeChange(code, describeDtc(code).descriptionHe)

        val newCodes = (afterCodes - beforeCodes.toSet()).map(::change)
        val goneCodes = (beforeCodes - afterCodes.toSet()).map(::change)
        val stillCodes = afterCodes.filter { it in beforeCodes }.map(::change)

        val afterMonitors = after.readiness.associateBy { it.id }
        val readinessReset = before.readiness
            .filter { it.status == MonitorStatus.COMPLETE && afterMonitors[it.id]?.status == MonitorStatus.INCOMPLETE }
            .map { it.nameHe }

        val coverage = mutableListOf<String>()
        val afterEcus = after.ecus.associateBy { it.id }
        for (ecu in before.ecus) {
            val later = afterEcus[ecu.id] ?: continue
            val wasChecked = ecu.access == EcuAccess.CHECKED
            val isChecked = later.access == EcuAccess.CHECKED
            if (wasChecked && !isChecked) coverage += "${ecuName(ecu.id.name)} ענתה לפני המוסך ולא ענתה אחריו."
            if (!wasChecked && isChecked) coverage += "${ecuName(ecu.id.name)} לא ענתה לפני המוסך וענתה אחריו."
        }

        fun voltage(session: DiagnosticSession) = session.pids
            .firstOrNull { it.id == "voltage" && it.quality == PidQuality.VALID }?.value

        val summary = mutableListOf<String>()
        if (newCodes.isNotEmpty()) {
            summary += "אחרי המוסך הופיעו קודים שלא היו לפני: ${newCodes.joinToString(", ") { it.code }}. " +
                "כדאי לשאול את המוסך אם הם קשורים לעבודה שנעשתה. זה לא מוכיח שנגרם נזק."
        }
        if (goneCodes.isNotEmpty()) {
            summary += if (readinessReset.isNotEmpty()) {
                "הקודים ${goneCodes.joinToString(", ") { it.code }} נעלמו, אבל מוכנות המערכות התאפסה. " +
                    "ייתכן שהקודים נמחקו ולא תוקנו. סורקים שוב אחרי כמה נסיעות: תקלה אמיתית תחזור."
            } else {
                "הקודים ${goneCodes.joinToString(", ") { it.code }} לא מופיעים יותר, והמוכנות לא התאפסה. נראה שהתקלה טופלה."
            }
        } else if (readinessReset.isNotEmpty()) {
            summary += "מוכנות המערכות התאפסה (${readinessReset.joinToString(", ")}). זה קורה אחרי מחיקת קודים או ניתוק מצבר."
        }
        if (stillCodes.isNotEmpty()) {
            summary += "הקודים ${stillCodes.joinToString(", ") { it.code }} היו לפני המוסך ועדיין קיימים."
        }
        summary += coverage
        if (summary.isEmpty()) {
            summary += "אין הבדל בקודים ובמוכנות בין הסריקה שלפני המוסך לזו שאחריו."
        }
        summary += "ההשוואה כוללת רק מה שמתאם OBD-II קורא: מנוע ופליטות. ABS, כריות אוויר ומרכב לא נבדקו."

        return VisitComparison(
            newCodes = newCodes,
            goneCodes = goneCodes,
            stillCodes = stillCodes,
            readinessReset = readinessReset,
            coverageChangedHe = coverage,
            voltageBefore = voltage(before),
            voltageAfter = voltage(after),
            summaryHe = summary,
        )
    }

    private fun ecuName(id: String): String = when (id) {
        "ENGINE" -> "יחידת המנוע"
        "ABS" -> "יחידת ה-ABS"
        "BCM" -> "מחשב המרכב"
        "SRS" -> "יחידת כריות האוויר"
        else -> "יחידה $id"
    }
}

/** Ready-to-show text for one visit. Screens only lay this out. */
data class VisitView(
    val id: String,
    val titleHe: String,
    val statusHe: String,
    val plannedWorkHe: String,
    val reportedWorkHe: String?,
    val open: Boolean,
    val warning: Boolean,
    val summaryHe: List<String>,
    val newCodes: List<CodeChange>,
    val goneCodes: List<CodeChange>,
    val stillCodes: List<CodeChange>,
    val voltageHe: String?,
    val beforeSessionId: String,
    val afterSessionId: String?,
    /** "נרשם כטיפול: …" when the visit is linked to a service record. */
    val serviceHe: String? = null,
)

object VisitPresenter {
    fun present(visit: GarageVisit, sessions: List<DiagnosticSession>, services: List<ServiceRecord> = emptyList()): VisitView {
        val service = visit.serviceId?.let { id -> services.firstOrNull { it.id == id } }
        val before = sessions.firstOrNull { it.id == visit.beforeSessionId }
        val after = visit.afterSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
        val comparison = if (before != null && after != null) VisitComparer.compare(before, after) else null
        val date = visit.openedAt.take(10)
        val status = when {
            visit.isOpen -> "פתוח: אחרי היציאה מהמוסך סורקים וסוגרים את הביקור."
            comparison == null -> "אחת הסריקות כבר לא שמורה, ולכן אין השוואה."
            comparison.hasWarnings -> "יש הבדלים שכדאי לברר עם המוסך."
            else -> "לא נמצאו הבדלים חשודים."
        }
        val voltage = comparison?.let { c ->
            if (c.voltageBefore != null && c.voltageAfter != null) "מתח: ${c.voltageBefore} V לפני, ${c.voltageAfter} V אחרי." else null
        }
        return VisitView(
            id = visit.id,
            titleHe = "${visit.garageName} · $date",
            statusHe = status,
            plannedWorkHe = visit.plannedWork,
            reportedWorkHe = visit.reportedWork.ifBlank { null },
            open = visit.isOpen,
            warning = comparison?.hasWarnings == true,
            summaryHe = comparison?.summaryHe.orEmpty(),
            newCodes = comparison?.newCodes.orEmpty(),
            goneCodes = comparison?.goneCodes.orEmpty(),
            stillCodes = comparison?.stillCodes.orEmpty(),
            voltageHe = voltage,
            beforeSessionId = visit.beforeSessionId,
            afterSessionId = visit.afterSessionId,
            serviceHe = service?.let { r ->
                "נרשם כטיפול: ${r.kind.labelHe}" + (r.reading?.let { " · %,d %s".format(it, r.unit.shortHe) } ?: "")
            },
        )
    }
}
