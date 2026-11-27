package car.doctor.core.present

import car.doctor.core.catalog.CLEAR_SCAN_HE
import car.doctor.core.catalog.EcuCatalog
import car.doctor.core.catalog.GAP_BADGE_HE
import car.doctor.core.catalog.confidenceLabel
import car.doctor.core.catalog.dtcStatusLabel
import car.doctor.core.catalog.ecuAccessLabel
import car.doctor.core.catalog.ignitionLabel
import car.doctor.core.catalog.monitorLabel
import car.doctor.core.catalog.severityLabel
import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.EcuAccess
import car.doctor.core.model.PidQuality
import java.util.Locale
import kotlin.math.round

data class CoverageLine(
    val nameHe: String,
    val badgeHe: String,
    val detailHe: String,
)

data class FindingView(
    val titleHe: String,
    val summaryHe: String,
    val severityLabelHe: String,
    val confidenceLabelHe: String,
    val claimHe: String,
    val evidence: List<String>,
    val nextStepHe: String,
)

data class CodeLine(
    val code: String,
    val statusHe: String,
    val ecuHe: String,
    val descriptionHe: String,
)

data class SampleLine(
    val nameHe: String,
    val valueHe: String,
    val unit: String,
    val ecuHe: String,
    val qualityHe: String,
)

data class MonitorLine(
    val nameHe: String,
    val statusHe: String,
)

data class SessionView(
    val id: String,
    val headlineHe: String,
    val ignitionLabelHe: String,
    val finishedAt: String,
    val protocolHe: String?,
    val adapterLabelHe: String,
    val ecus: List<CoverageLine>,
    val gaps: List<CoverageLine>,
    val findings: List<FindingView>,
    val codes: List<CodeLine>,
    val codesNoteHe: String?,
    val samples: List<SampleLine>,
    val samplesNoteHe: String?,
    val monitors: List<MonitorLine>,
    val monitorsNoteHe: String?,
    /** Set on the verification scan right after clearing codes. */
    val clearNoteHe: String? = null,
    /** Deep scan: VIN line, comparison with the typed VIN, freeze-frame heading. */
    val deepNotesHe: List<String> = emptyList(),
    /** ABS / airbag modules, one block per module. Deep scans only. */
    val modulesHe: List<ModuleBlock> = emptyList(),
    val freezeFrame: List<SampleLine> = emptyList(),
)

/**
 * Turns a finished session into Hebrew lines.
 * It does not create findings and it does not look at raw codes to invent a claim.
 */
object SessionPresenter {
    fun present(session: DiagnosticSession): SessionView {
        val headline = if (session.clearedCodes.isNotEmpty()) {
            "סריקת אימות אחרי מחיקת קודים."
        } else if (session.kind == "deep" && session.findings.isEmpty()) {
            "בדיקה מעמיקה: לא נמצא ממצא בנתונים שנקראו."
        } else if (session.findings.isEmpty()) {
            CLEAR_SCAN_HE
        } else {
            "${session.findings.size} ממצאים לפי הנתונים שנבדקו."
        }
        val checked = session.ecus.any { it.access == EcuAccess.CHECKED }
        val failed = session.ecus.any { it.access == EcuAccess.COMM_ERROR }
        return buildView(session, headline, checked, failed).copy(
            clearNoteHe = clearNote(session),
            deepNotesHe = deepNotes(session),
            modulesHe = modules(session),
            freezeFrame = session.freezeFrame?.values.orEmpty().map(::sample),
        )
    }

    /** Only for deep scans. States what the car reported; never guesses a missing value. */
    fun deepNotes(session: DiagnosticSession): List<String> {
        if (session.kind != "deep") return emptyList()
        val notes = mutableListOf<String>()
        notes += session.vinRead?.let { "VIN שהרכב דיווח: $it" } ?: "הרכב לא החזיר VIN (רכבים ישנים ורכב מדומה לא תמיד מחזירים)."
        val permanent = session.dtcs.filter { it.status == car.doctor.core.model.DtcStatus.PERMANENT }
        if (permanent.isNotEmpty()) {
            notes += "קודים קבועים: ${permanent.joinToString(", ") { it.code }}. מחיקה לא מסירה אותם; הרכב מוחק אותם בעצמו אחרי שווידא שהתקלה תוקנה."
        }
        when (val frame = session.freezeFrame) {
            null -> notes += "אין freeze frame שמור (נשמר רק כשקוד שמור נרשם)."
            else -> notes += "freeze frame: מצב המנוע ברגע שנרשם ${frame.code ?: "קוד"}. עוזר להבין באילו תנאים התקלה קרתה."
        }
        return notes
    }

    /** States what each module answered. "Not reached" is never shown as "no faults". */
    fun modules(session: DiagnosticSession): List<ModuleBlock> {
        if (session.kind != "deep") return emptyList()
        if (session.modules.isEmpty()) {
            return listOf(
                ModuleBlock(
                    "ABS וכריות אוויר",
                    "לא נבדקו: אין כתובות מוכרות ליצרן הזה. נתמכים: טויוטה/לקסוס, יונדאי/קיה, קבוצת פולקסווגן, פורד/מאזדה.",
                    emptyList(),
                    warning = false,
                ),
            )
        }
        return session.modules.map { m ->
            val status = when {
                !m.reached -> "לא נקרא. ${m.noteHe ?: ""}".trim()
                m.faults.isEmpty() -> "נקרא · אין קודי תקלה."
                else -> "נקרא · ${m.faults.size} קודי תקלה. האפליקציה לא מוחקת קודים במודול הזה."
            }
            val lines = m.faults.map { f ->
                val state = listOfNotNull(
                    "פעיל עכשיו".takeIf { f.failingNow },
                    "מאושר".takeIf { f.confirmed },
                    "ממתין".takeIf { f.pending },
                ).joinToString(" · ")
                "${f.label} · ${f.descriptionHe}${if (state.isNotEmpty()) " · $state" else ""}"
            }
            ModuleBlock("${m.kind.labelHe} (${m.addressHe})", status, lines, warning = m.faults.isNotEmpty())
        }
    }

    private fun sample(pid: car.doctor.core.model.PidValue): SampleLine {
        val missing = pid.quality != PidQuality.VALID || pid.value == null
        return SampleLine(
            nameHe = pid.nameHe,
            valueHe = if (missing) "לא זמין" else formatSample(pid.value!!),
            unit = if (missing) "" else pid.unit,
            ecuHe = EcuCatalog.get(pid.ecuId).nameHe,
            qualityHe = if (missing) "לא זמין" else "נקרא",
        )
    }

    /** What clearing did, in plain words. Clearing hides a code; it never fixes the cause. */
    fun clearNote(session: DiagnosticSession): String? {
        if (session.clearedCodes.isEmpty()) return null
        val back = session.dtcs.map { it.code }.filter { it in session.clearedCodes }.distinct()
        val cleared = session.clearedCodes.joinToString(", ")
        return if (back.isEmpty()) {
            "נמחקו: $cleared. אף קוד לא חזר בסריקה המיידית. זה עדיין לא אומר שהתקלה תוקנה: " +
                "סורקים שוב אחרי כמה נסיעות. תקלה אמיתית תחזיר את הקוד ותדליק שוב את הנורה. " +
                "מוכנות המערכות התאפסה ותתמלא מחדש תוך כמה נסיעות."
        } else {
            "נמחקו: $cleared. חזרו מיד: ${back.joinToString(", ")}. כלומר התקלה עדיין פעילה, והמחיקה לא שינתה דבר."
        }
    }

    private fun buildView(session: DiagnosticSession, headline: String, checked: Boolean, failed: Boolean): SessionView {
        return SessionView(
            id = session.id,
            headlineHe = headline,
            ignitionLabelHe = ignitionLabel(session.ignition),
            finishedAt = session.finishedAt,
            protocolHe = session.protocolHe,
            adapterLabelHe = session.adapterLabelHe,
            ecus = session.ecus.map { ecu ->
                CoverageLine(
                    nameHe = EcuCatalog.get(ecu.id).nameHe,
                    badgeHe = ecuAccessLabel(ecu.access),
                    detailHe = ecu.reasonHe,
                )
            },
            gaps = session.gaps.map { gap ->
                CoverageLine(
                    nameHe = gap.nameHe,
                    badgeHe = GAP_BADGE_HE,
                    detailHe = gap.reasonHe,
                )
            },
            findings = session.findings.map { finding ->
                FindingView(
                    titleHe = finding.titleHe,
                    summaryHe = finding.summaryHe,
                    severityLabelHe = severityLabel(finding.severity),
                    confidenceLabelHe = confidenceLabel(finding.confidence),
                    claimHe = finding.claimHe,
                    evidence = finding.evidence,
                    nextStepHe = finding.nextStepHe,
                )
            },
            codes = session.dtcs.map { dtc ->
                CodeLine(
                    code = dtc.code,
                    statusHe = dtcStatusLabel(dtc.status),
                    ecuHe = EcuCatalog.get(dtc.ecuId).nameHe,
                    descriptionHe = dtc.descriptionHe,
                )
            },
            codesNoteHe = if (session.dtcs.isNotEmpty()) {
                null
            } else if (checked) {
                "לא נמצאו קודים בנתונים שנקראו."
            } else if (failed) {
                "לא נקראו קודים. הייתה שגיאת תקשורת."
            } else {
                "לא נקראו קודים. היחידות לא נבדקו."
            },
            samples = session.pids.map { pid ->
                val missing = pid.quality != PidQuality.VALID || pid.value == null
                SampleLine(
                    nameHe = pid.nameHe,
                    valueHe = if (missing) "לא זמין" else formatSample(pid.value!!),
                    unit = if (missing) "" else pid.unit,
                    ecuHe = EcuCatalog.get(pid.ecuId).nameHe,
                    qualityHe = if (missing) "לא זמין" else "נקרא",
                )
            },
            samplesNoteHe = if (session.pids.isNotEmpty()) {
                null
            } else if (failed) {
                "לא נקראו נתוני חיישנים. הייתה שגיאת תקשורת."
            } else {
                "לא נקראו נתוני חיישנים."
            },
            monitors = session.readiness.map { monitor ->
                MonitorLine(nameHe = monitor.nameHe, statusHe = monitorLabel(monitor.status))
            },
            monitorsNoteHe = if (session.readiness.isNotEmpty()) {
                null
            } else if (failed) {
                "מוכנות לא פוענחה. הייתה שגיאת תקשורת."
            } else {
                "אין נתוני מוכנות בסריקה הזו."
            },
        )
    }
}

private fun formatSample(value: Double): String {
    val rounded = round(value * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", rounded)
    }
}
/** One module in the result screen. */
data class ModuleBlock(val titleHe: String, val statusHe: String, val faultsHe: List<String>, val warning: Boolean)
