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
)

/**
 * Turns a finished session into Hebrew lines.
 * It does not create findings and it does not look at raw codes to invent a claim.
 */
object SessionPresenter {
    fun present(session: DiagnosticSession): SessionView {
        val headline = if (session.findings.isEmpty()) {
            CLEAR_SCAN_HE
        } else {
            "${session.findings.size} ממצאים לפי הנתונים שנבדקו."
        }
        val checked = session.ecus.any { it.access == EcuAccess.CHECKED }
        val failed = session.ecus.any { it.access == EcuAccess.COMM_ERROR }
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