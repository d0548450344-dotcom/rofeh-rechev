package car.doctor.core.rules

import car.doctor.core.catalog.CANNOT_DETERMINE_HE
import car.doctor.core.catalog.EcuCatalog
import car.doctor.core.catalog.SUSPICION_HE
import car.doctor.core.catalog.describeDtc
import car.doctor.core.catalog.dtcStatusLabel
import car.doctor.core.catalog.ecuAccessLabel
import car.doctor.core.model.Confidence
import car.doctor.core.model.Dtc
import car.doctor.core.model.EcuAccess
import car.doctor.core.model.EcuId
import car.doctor.core.model.Finding
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.PidQuality
import car.doctor.core.model.PidValue
import car.doctor.core.model.ScanFacts
import car.doctor.core.model.Severity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

private const val EXCESS_MARGIN_KMH = 4.0

/** Expected peak-to-peak wheel-speed gap for the reported steer and brake. */
fun expectedWheelSpread(speedKmh: Double, steerDeg: Double, braking: Boolean): Double {
    val rad = abs(steerDeg) * Math.PI / 180.0
    val turn = speedKmh * sin(rad) * 0.55
    val brakeExtra = if (braking) speedKmh * 0.18 else 0.0
    return turn + brakeExtra
}

private data class Draft(
    val ruleId: String,
    val code: String? = null,
    val titleHe: String,
    val summaryHe: String,
    val severity: Severity,
    val confidence: Confidence,
    val claimHe: String,
    val evidence: List<String>,
    val conditions: List<String>,
    val nextStepHe: String,
    val ecuId: EcuId? = null,
)

private fun make(draft: Draft): Finding {
    val ecuKey = draft.ecuId?.name ?: "none"
    val codeKey = draft.code ?: draft.titleHe
    return Finding(
        id = "${draft.ruleId}:$ecuKey:$codeKey",
        ruleId = draft.ruleId,
        code = draft.code,
        titleHe = draft.titleHe,
        summaryHe = draft.summaryHe,
        severity = draft.severity,
        confidence = draft.confidence,
        claimHe = draft.claimHe,
        evidence = draft.evidence,
        conditions = draft.conditions,
        nextStepHe = draft.nextStepHe,
        ecuId = draft.ecuId,
    )
}

private fun pid(facts: ScanFacts, id: String): PidValue? = facts.pids.find { it.id == id }

private fun num(value: Double, digits: Int = 1): String =
    String.format(Locale.US, "%.${digits}f", value)

private val SEVERITY_RANK: Map<Severity, Int> = mapOf(
    Severity.CRITICAL to 0,
    Severity.HIGH to 1,
    Severity.MEDIUM to 2,
    Severity.LOW to 3,
    Severity.INFO to 4,
)

/**
 * Rule evaluation over facts already read from an adapter.
 * The fault-injection list is not a parameter, on purpose.
 */
object DiagnosticRules {
    fun evaluate(facts: ScanFacts, ignition: IgnitionState): List<Finding> {
        val findings = mutableListOf<Finding>()
        val anyChecked = facts.ecus.any { it.access == EcuAccess.CHECKED }
        if (!anyChecked) {
            findings += make(
                Draft(
                    ruleId = "no-coverage",
                    titleHe = "אין כיסוי לקביעה",
                    summaryHe = "אף יחידה לא הושלמה בסריקה הזו. לא ניתן לקבוע. זה לא מצב תקין ולא תקלה.",
                    severity = Severity.INFO,
                    confidence = Confidence.INSUFFICIENT,
                    claimHe = CANNOT_DETERMINE_HE,
                    evidence = facts.ecus.map { ecu ->
                        "${EcuCatalog.get(ecu.id).nameHe}: ${ecuAccessLabel(ecu.access)}"
                    },
                    conditions = listOf("אין יחידה במצב checked"),
                    nextStepHe = "להעלות הצתה לפחות ל-ON ולחזור על הסריקה. בלי תשובה מיחידה אין ממצא.",
                ),
            )
        }

        for (ecu in facts.ecus) {
            if (ecu.access != EcuAccess.INACCESSIBLE) continue
            val meta = EcuCatalog.get(ecu.id)
            findings += make(
                Draft(
                    ruleId = "ecu-no-response",
                    ecuId = ecu.id,
                    titleHe = "${meta.nameHe} לא הגיבה",
                    summaryHe = "${ecu.reasonHe} אי-תגובה בסריקה אחת אינה קובעת שהיחידה עצמה פגומה.",
                    severity = Severity.MEDIUM,
                    confidence = Confidence.HIGH,
                    claimHe = "היחידה לא הגיבה",
                    evidence = listOf("כתובת ${meta.address}", ecu.reasonHe),
                    conditions = listOf("המתאם מחובר והסריקה פנתה לכתובת הזו."),
                    nextStepHe = "לחזור על הסריקה כשההצתה במצב ON לפחות. אם אין תגובה שוב, במוסך בודקים הזנה וחיבור. לא מחליפים יחידה רק מזה.",
                ),
            )
        }

        if (facts.engineTimeout) {
            findings += make(
                Draft(
                    ruleId = "comm-timeout",
                    ecuId = EcuId.ENGINE,
                    titleHe = "פסק זמן מול יחידת המנוע",
                    summaryHe = "היחידה החלה לענות והתקשורת נקטעה לפני שהתקבלו קודים או נתונים. לא ידוע מה היה נקרא, ולכן לא נקבע מצב המנוע.",
                    severity = Severity.MEDIUM,
                    confidence = Confidence.HIGH,
                    claimHe = "פסק זמן בתקשורת",
                    evidence = listOf(
                        "יחידת המנוע: פסק זמן אחרי תחילת תשובה",
                        "לא התקבלו PID ולא DTC מהמנוע בסריקה הזו",
                    ),
                    conditions = listOf("חיבור המתאם הצליח", "תשובת המנוע לא הושלמה"),
                    nextStepHe = "לחזור על הסריקה. פסק זמן יחיד יכול להיות קשר. אם הוא חוזר, בודקים מתאם והזנת הצתה לפני שחושדים ביחידה.",
                ),
            )
        }

        pushVoltage(facts, ignition, findings)
        for (dtc in facts.dtcs) {
            findings += dtcFinding(dtc)
        }
        pushWheels(facts, findings)
        pushMissing(facts, findings)
        return findings.sortedBy { SEVERITY_RANK.getValue(it.severity) }
    }
}

private fun pushMissing(facts: ScanFacts, findings: MutableList<Finding>) {
    val missing = facts.pids.filter { it.expected && (it.quality != PidQuality.VALID || it.value == null) }
    if (missing.isEmpty()) return
    findings += make(
        Draft(
            ruleId = "signal-missing",
            ecuId = missing.first().ecuId,
            titleHe = "נתון צפוי לא נקרא",
            summaryHe = "אות שציפינו לקרוא לא חזר כערך. אי אפשר להשלים אותו, ואי אפשר לקרוא לחוסר הזה מצב תקין.",
            severity = Severity.INFO,
            confidence = Confidence.INSUFFICIENT,
            claimHe = CANNOT_DETERMINE_HE,
            evidence = missing.map { "${it.nameHe}: לא זמין" },
            conditions = listOf("האות מסומן כצפוי לרכב הזה", "לא התקבל ערך תקין"),
            nextStepHe = "לחזור על הסריקה. חוסר קריאה אינו אישור שהמערכת תקינה.",
        ),
    )
}

private fun pushVoltage(facts: ScanFacts, ignition: IgnitionState, findings: MutableList<Finding>) {
    val engine = pid(facts, "voltage")
    val bcm = pid(facts, "bcmVoltage")
    val engineOk = engine?.takeIf { it.quality == PidQuality.VALID && it.value != null }
    val bcmOk = bcm?.takeIf { it.quality == PidQuality.VALID && it.value != null }
    val engineValue = engineOk?.value
    val bcmValue = bcmOk?.value
    if (engineValue != null && bcmValue != null && abs(engineValue - bcmValue) > 0.5) {
        findings += make(
            Draft(
                ruleId = "voltage-disagree",
                titleHe = "שני מקורות מתח לא תואמים",
                summaryHe = "יחידת המנוע דיווחה ${num(engineValue)} V וה-BCM דיווח ${num(bcmValue)} V. מדגימה אחת לא ניתן לקבוע איזה מקור נכון.",
                severity = Severity.LOW,
                confidence = Confidence.INSUFFICIENT,
                claimHe = CANNOT_DETERMINE_HE,
                evidence = listOf("מנוע · ${num(engineValue)} V", "BCM · ${num(bcmValue)} V"),
                conditions = listOf("שתי קריאות תקינות באותה סריקה", "הפרש מעל 0.5 V"),
                nextStepHe = "למדוד מתח מצבר במוסך במקביל לקריאת היחידות. לא לבחור מקור לפי ניחוש.",
            ),
        )
    }

    val source = engineOk ?: bcmOk ?: return
    val value = source.value ?: return
    val fromEngine = source === engineOk
    val where = if (fromEngine) "מיחידת המנוע (PID 42)" else "מ-BCM בלבד, כי המנוע לא מסר מתח"
    val confidence = if (fromEngine) Confidence.HIGH else Confidence.MEDIUM
    val ecuId = if (fromEngine) EcuId.ENGINE else EcuId.BCM

    if (ignition == IgnitionState.RUNNING && value < 12.6) {
        findings += make(
            Draft(
                ruleId = "voltage-low",
                ecuId = ecuId,
                titleHe = "מתח נמוך כשהמנוע פועל",
                summaryHe = "נמדדו ${num(value)} V $where, בזמן שהמנוע במצב פועל. בטעינה שגרתית המתח בדרך כלל גבוה יותר. זו חריגת מדידה, לא קביעה איזה רכיב גרם לה.",
                severity = if (value < 12.0) Severity.HIGH else Severity.MEDIUM,
                confidence = confidence,
                claimHe = "חריגת מדידה",
                evidence = listOf("${num(value)} V", where, "מצב הצתה: מנוע פועל"),
                conditions = listOf("המנוע במצב RUNNING", "הקריאה סומנה כתקינה"),
                nextStepHe = "לבדוק במוסך מתח בזמן שהמנוע פועל, כולל חיבורי מצבר ועומס. הסריקה לא קובעת מה להחליף.",
            ),
        )
        return
    }

    if (ignition != IgnitionState.RUNNING && value < 11.8) {
        findings += make(
            Draft(
                ruleId = "voltage-low",
                ecuId = ecuId,
                titleHe = "מתח נמוך בלי מנוע פועל",
                summaryHe = "נמדדו ${num(value)} V $where, כשהמנוע אינו במצב פועל. זה יכול להתאים למצבר חלש, אבל לא נמדדה התנעה ולא עומס. לא נקבע הגורם.",
                severity = Severity.MEDIUM,
                confidence = confidence,
                claimHe = "חריגת מדידה",
                evidence = listOf("${num(value)} V", where, "מצב הצתה: ${ignition.name}"),
                conditions = listOf("המנוע אינו RUNNING", "הקריאה סומנה כתקינה"),
                nextStepHe = "למדוד מתח מצבר במנוחה ואחר כך בהתנעה. דגימה אחת בלי עומס לא מספיקה.",
            ),
        )
    }
}

private fun dtcFinding(dtc: Dtc): Finding {
    val known = describeDtc(dtc.code)
    val status = dtcStatusLabel(dtc.status)
    val ecuName = EcuCatalog.get(dtc.ecuId).nameHe
    val conditions = mutableListOf(
        "הקוד הוחזר על ידי היחידה בסריקה הזו.",
        if (dtc.status == car.doctor.core.model.DtcStatus.PERMANENT) {
            "קוד קבוע לא נמחק בסריקת קריאה, ובשלב הזה אין פעולת מחיקה בכלל."
        } else {
            "נוכחות קוד אינה זהה לתקלה פעילה ברגע הקריאה."
        },
    )
    var summary = "נמצא קוד $status ${dtc.code} ביחידת $ecuName. לפי מאגר כללי: ${known.descriptionHe} הקוד מתעד מה שהיחידה זיהתה. הוא לא מוכיח לבדו איזה חלק להחליף."
    if (dtc.ecuId == EcuId.SRS) {
        summary += " קוד במערכת הכריות דורש זהירות: לא לאבחן כריות במסך הזה ולא לאפס אותן."
    }
    val misfireCode = dtc.code == "P0300" || dtc.code == "P0301"
    return make(
        Draft(
            ruleId = if (misfireCode) "misfire-code" else "dtc",
            code = dtc.code,
            ecuId = dtc.ecuId,
            titleHe = "קוד ${dtc.code} $status",
            summaryHe = summary,
            severity = dtc.severity,
            confidence = Confidence.HIGH,
            claimHe = "הקוד נקרא מהיחידה",
            evidence = listOf(dtc.code, "סטטוס: $status", "יחידה: $ecuName", known.descriptionHe),
            conditions = conditions,
            nextStepHe = if (dtc.ecuId == EcuId.SRS) {
                "לפנות לבדיקת SRS מקצועית. לא מאפסים כריות ולא בודקים אותן לבד."
            } else {
                "לתעד את הקוד, הסטטוס והיחידה למוסך. הסריקה לא ממליצה על חלק חלופי."
            },
        ),
    )
}

private fun wheelValues(facts: ScanFacts): List<Double>? {
    val ids = listOf("wheelFl", "wheelFr", "wheelRl", "wheelRr")
    val values = mutableListOf<Double>()
    for (id in ids) {
        val sample = pid(facts, id) ?: return null
        val value = sample.value
        if (sample.quality != PidQuality.VALID || value == null) return null
        values += value
    }
    return values
}

private fun pushWheels(facts: ScanFacts, findings: MutableList<Finding>) {
    val values = wheelValues(facts) ?: return
    val speed = pid(facts, "speed") ?: return
    val steer = pid(facts, "steer") ?: return
    val brake = pid(facts, "brake") ?: return
    val speedValue = speed.value
    val steerValue = steer.value
    val brakeValue = brake.value
    if (speed.quality != PidQuality.VALID || speedValue == null) return
    if (steer.quality != PidQuality.VALID || steerValue == null) return
    if (brake.quality != PidQuality.VALID || brakeValue == null) return

    val actual = (values.maxOrNull() ?: return) - (values.minOrNull() ?: return)
    val braking = brakeValue == 1.0
    val expected = expectedWheelSpread(speedValue, steerValue, braking)
    val excess = actual - expected
    val evidence = listOf(
        "מהירות רכב ${num(speedValue, 0)} קמ״ש",
        "זווית הגה מדווחת ${num(steerValue)}°",
        if (braking) "הבלם לחוץ" else "הבלם לא לחוץ",
        "FL ${num(values[0])} · FR ${num(values[1])} · RL ${num(values[2])} · RR ${num(values[3])} קמ״ש",
        "הפרש נמדד ${num(actual)} · מעטפת ${num(expected)} קמ״ש",
    )

    if (speedValue < 15.0) {
        if (actual < 3.0) return
        findings += make(
            Draft(
                ruleId = "wheel-speed",
                ecuId = EcuId.ABS,
                titleHe = "הפרש גלגלים במהירות נמוכה",
                summaryHe = "יש הפרש בין הגלגלים, אבל המהירות נמוכה מכדי להשוות חיישנים. לא ניתן לקבוע. בפנייה, בבלימה או בעמידה הפרש כזה אינו ראיה.",
                severity = Severity.LOW,
                confidence = Confidence.INSUFFICIENT,
                claimHe = CANNOT_DETERMINE_HE,
                evidence = evidence,
                conditions = listOf("מהירות מתחת ל-15 קמ״ש", "דגימה בודדת"),
                nextStepHe = "לחזור על ההשוואה בשיוט ישר ויציב, בלי בלימה.",
            ),
        )
        return
    }

    if (excess <= EXCESS_MARGIN_KMH) return

    val straightish = speedValue >= 30.0 && abs(steerValue) <= 5.0 && !braking
    if (straightish) {
        findings += make(
            Draft(
                ruleId = "wheel-speed",
                ecuId = EcuId.ABS,
                titleHe = "חשד להפרש מהירות בין גלגלים",
                summaryHe = "נמצא הפרש בין מהירויות הגלגלים מעל המעטפת של נסיעה ישרה בלי בלימה. זה חשד. אין כאן זיהוי של רכיב פגום. נדרש אימות בדגימה נוספת באותם תנאים.",
                severity = if (excess > 10.0) Severity.HIGH else Severity.MEDIUM,
                confidence = Confidence.MEDIUM,
                claimHe = SUSPICION_HE,
                evidence = evidence,
                conditions = listOf(
                    "מהירות מעל 30 קמ״ש",
                    "זווית הגה מדווחת קטנה",
                    "הבלם לא לחוץ",
                    "דגימה בודדת, ולכן הביטחון בינוני",
                ),
                nextStepHe = "לאמת בשיוט ישר ויציב. עד אז לא מחליפים חיישן גלגל.",
            ),
        )
        return
    }

    findings += make(
        Draft(
            ruleId = "wheel-speed",
            ecuId = EcuId.ABS,
            titleHe = "הפרש גלגלים בתנאים לא חד-משמעיים",
            summaryHe = "ההפרש גדול מהמעטפת שחושבה לפי הזווית והבלם, אבל זו אינה נסיעה ישרה יציבה. זה חשד בלבד. לא ניתן לקבוע איזה אות חורג. נדרש אימות בנסיעה ישרה בלי בלימה.",
            severity = Severity.LOW,
            confidence = Confidence.LOW,
            claimHe = SUSPICION_HE,
            evidence = evidence,
            conditions = listOf("התנאים כוללים פנייה, בלימה או מהירות בינונית", "דגימה בודדת"),
            nextStepHe = "לא להסיק מסקנה עד שיוט ישר. פנייה ובלימה מייצרות הפרש טבעי.",
        ),
    )
}
