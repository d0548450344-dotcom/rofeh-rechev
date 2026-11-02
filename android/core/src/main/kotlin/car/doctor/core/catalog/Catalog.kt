package car.doctor.core.catalog

import car.doctor.core.model.Confidence
import car.doctor.core.model.DtcStatus
import car.doctor.core.model.Ecu
import car.doctor.core.model.EcuAccess
import car.doctor.core.model.EcuId
import car.doctor.core.model.FaultId
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.MonitorStatus
import car.doctor.core.model.ObdSessionState
import car.doctor.core.model.Posture
import car.doctor.core.model.Severity
import car.doctor.core.model.SystemGap

object EcuCatalog {
    private val all: Map<EcuId, Ecu> = mapOf(
        EcuId.ENGINE to Ecu(
            EcuId.ENGINE,
            "מנוע",
            "7E0",
            "ISO 15765-4 CAN · 11-bit · 500 kbit/s (מדומה)",
        ),
        EcuId.ABS to Ecu(
            EcuId.ABS,
            "ABS / ESC",
            "760",
            "UDS מדומה על CAN · לא OBD-II גנרי",
        ),
        EcuId.BCM to Ecu(
            EcuId.BCM,
            "BCM · מרכב",
            "726",
            "UDS מדומה על CAN · לא OBD-II גנרי",
        ),
        EcuId.SRS to Ecu(
            EcuId.SRS,
            "SRS · כריות אוויר",
            "737",
            "UDS מדומה על CAN · לא OBD-II גנרי",
        ),
    )

    fun get(id: EcuId): Ecu = all.getValue(id)

    fun every(): List<Ecu> = EcuId.entries.map { get(it) }
}

val OUT_OF_SCOPE: List<SystemGap> = listOf(
    SystemGap("tcm", "תיבת הילוכים / TCM", "אין פרוטוקול יצרן בשלב הזה. המערכת לא נסרקה."),
    SystemGap(
        "eps",
        "הגה כוח / EPS",
        "יחידת ההגה לא נכללה בסריקה. אם מופיעה זווית הגה, היא הגיעה רק מערוץ ABS המדומה.",
    ),
    SystemGap(
        "tpms",
        "לחץ אוויר בצמיגים",
        "יחידת TPMS לא נסרקה. לא נקרא לחץ ולא נקראה טמפרטורת צמיג, ואין מזה מסקנה.",
    ),
    SystemGap("hvac", "מיזוג", "יחידת המיזוג לא נכללה בסריקה."),
    SystemGap("cluster", "לוח מחוונים", "לוח המחוונים לא נסרק."),
    SystemGap("parking", "חיישני חניה", "חיישני החניה לא נכללו בסריקה."),
    SystemGap("adas", "ADAS", "מערכות עזר לנהג לא נסרקו, ולא הוסק מהן דבר."),
)

data class DtcText(val descriptionHe: String, val severity: Severity)

private val DTC_SYSTEM_HE = mapOf(
    'P' to "מערכת ההנעה (מנוע ותיבת הילוכים)",
    'C' to "השלדה (בלמים, היגוי, מתלים)",
    'B' to "המרכב (כריות אוויר, נוחות, תאורה)",
    'U' to "התקשורת בין יחידות הבקרה",
)

/** Third character of a P0 / P1 / P2 code: the subsystem (SAE J2012). */
private val POWERTRAIN_AREA_HE = mapOf(
    '0' to "מדידת דלק ואוויר ובקרת פליטות",
    '1' to "מדידת דלק ואוויר",
    '2' to "מדידת דלק ואוויר (מזרקים)",
    '3' to "מערכת ההצתה והחטאות בעירה",
    '4' to "בקרת פליטות",
    '5' to "מהירות רכב, בקרת סרק וכניסות עזר",
    '6' to "מחשב הרכב ויציאות עזר",
    '7' to "תיבת ההילוכים",
    '8' to "תיבת ההילוכים",
    '9' to "תיבת ההילוכים",
)

private val DTC_FORMAT = Regex("^[PCBU][0-3][0-9A-F]{3}$")

/**
 * Description of a trouble code. Known generic codes come from the shared database
 * (DtcDatabase.kt, generated). Anything else is described only by what the code's
 * structure says, and says plainly that the meaning is unverified.
 * Keep in step with describeDtc in src/domain/catalog.ts.
 */
fun describeDtc(code: String): DtcText {
    val upper = code.trim().uppercase()
    return overlayDtc(upper) ?: DTC_DB[upper] ?: DtcText(describeByStructure(upper), Severity.MEDIUM)
}

fun isKnownDtc(code: String): Boolean {
    val upper = code.trim().uppercase()
    return overlayDtc(upper) != null || DTC_DB.containsKey(upper)
}

private fun describeByStructure(code: String): String {
    if (!DTC_FORMAT.matches(code)) {
        return "אין תיאור מאומת לקוד הזה במאגר המקומי. לא משויכת לו משמעות משוערת."
    }
    val system = DTC_SYSTEM_HE.getValue(code[0])
    val lead = "הקוד לא נמצא במאגר המקומי. לפי מבנה הקוד בלבד:"
    return when (dtcKind(code)) {
        DtcKind.MANUFACTURER -> "$lead קוד יצרן של $system. המשמעות המדויקת תלויה ביצרן הרכב ולא אומתה."
        DtcKind.RESERVED -> "$lead קוד של $system בטווח שמור. המשמעות לא אומתה."
        DtcKind.GENERIC -> {
            val area = if (code[0] == 'P') POWERTRAIN_AREA_HE[code[2]] else null
            val where = area?.let { ", בתחום $it" }.orEmpty()
            "$lead קוד כללי (תקני) של $system$where. המשמעות המדויקת לא אומתה."
        }
    }
}

private enum class DtcKind { GENERIC, MANUFACTURER, RESERVED }

private fun dtcKind(code: String): DtcKind {
    val d1 = code[1]
    if (code[0] == 'P') {
        return when {
            d1 == '0' || d1 == '2' -> DtcKind.GENERIC
            d1 == '1' -> DtcKind.MANUFACTURER
            code[2] == '4' -> DtcKind.GENERIC // P34xx is SAE, the rest of P3 is manufacturer
            else -> DtcKind.MANUFACTURER
        }
    }
    return when (d1) {
        '0' -> DtcKind.GENERIC
        '3' -> DtcKind.RESERVED
        else -> DtcKind.MANUFACTURER
    }
}

fun ignitionLabel(state: IgnitionState): String = when (state) {
    IgnitionState.OFF -> "כבוי"
    IgnitionState.ACC -> "אביזרים"
    IgnitionState.ON -> "הצתה"
    IgnitionState.STARTING -> "התנעה"
    IgnitionState.RUNNING -> "מנוע פועל"
}

fun sessionLabel(state: ObdSessionState): String = when (state) {
    ObdSessionState.DISCONNECTED -> "מנותק"
    ObdSessionState.CONNECTING -> "מתחבר"
    ObdSessionState.CONNECTED -> "מחובר"
    ObdSessionState.SCANNING -> "סורק"
    ObdSessionState.COMPLETE -> "הושלם"
    ObdSessionState.DISCONNECTING -> "מתנתק"
    ObdSessionState.ERROR -> "שגיאה"
}

fun postureLabel(posture: Posture): String = when (posture) {
    Posture.IDLE -> "סרק"
    Posture.STRAIGHT -> "שיוט ישר"
    Posture.TURN -> "פנייה"
    Posture.BRAKE -> "בלימה"
}

fun fuelLabel(fuel: Fuel): String = when (fuel) {
    Fuel.GASOLINE -> "בנזין"
    Fuel.DIESEL -> "דיזל"
    Fuel.HYBRID -> "היברידי"
    Fuel.ELECTRIC -> "חשמלי"
}

fun dtcStatusLabel(status: DtcStatus): String = when (status) {
    DtcStatus.STORED -> "שמור"
    DtcStatus.PENDING -> "ממתין"
    DtcStatus.PERMANENT -> "קבוע"
}

fun severityLabel(severity: Severity): String = when (severity) {
    Severity.INFO -> "מידע"
    Severity.LOW -> "נמוכה"
    Severity.MEDIUM -> "בינונית"
    Severity.HIGH -> "גבוהה"
    Severity.CRITICAL -> "דחופה"
}

fun confidenceLabel(confidence: Confidence): String = when (confidence) {
    Confidence.INSUFFICIENT -> "לא ניתן לקבוע"
    Confidence.LOW -> "ביטחון נמוך"
    Confidence.MEDIUM -> "ביטחון בינוני"
    Confidence.HIGH -> "ביטחון גבוה"
}

fun ecuAccessLabel(access: EcuAccess): String = when (access) {
    EcuAccess.CHECKED -> "נבדק"
    EcuAccess.INACCESSIBLE -> "לא נגיש"
    EcuAccess.NOT_SCANNED -> "לא נבדק"
    EcuAccess.COMM_ERROR -> "שגיאת תקשורת"
}

fun monitorLabel(status: MonitorStatus): String = when (status) {
    MonitorStatus.COMPLETE -> "הושלם"
    MonitorStatus.INCOMPLETE -> "לא הושלם"
    MonitorStatus.UNSUPPORTED -> "לא נתמך"
}

fun faultLabel(id: FaultId): String = when (id) {
    FaultId.LOW_BATTERY -> "מתח נמוך"
    FaultId.HIGH_COOLANT -> "טמפרטורת קירור גבוהה"
    FaultId.MISFIRE -> "החטאת בעירה"
    FaultId.O2_ANOMALY -> "חיישן חמצן"
    FaultId.WHEEL_SPEED_MISMATCH -> "הפרש מהירות גלגל"
    FaultId.STEERING_ANGLE_ANOMALY -> "זווית הגה לא תואמת"
    FaultId.LOST_ECU -> "יחידה לא עונה"
    FaultId.COMM_TIMEOUT -> "פסק זמן מנוע"
    FaultId.INTERMITTENT_SENSOR -> "חיישן בלי קריאה"
    FaultId.MULTIPLE_DTCS -> "כמה קודים"
}

const val CLEAR_SCAN_HE =
    "לא נמצאו ממצאים לפי הנתונים שנבדקו. זה לא אישור שהרכב תקין לחלוטין, ולא ציון."

const val GAP_BADGE_HE = "לא בסריקה"

const val CANNOT_DETERMINE_HE = "לא ניתן לקבוע"

const val SUSPICION_HE = "חשד"

fun vinNote(vin: String): String {
    val clean = vin.trim().uppercase()
    if (clean.isEmpty()) {
        return "VIN לא הוזן בפרופיל ולא נקרא מהרכב. הזיהוי הוא לפי מה שהוקלד ידנית."
    }
    return "VIN $clean הוזן ידנית בפרופיל. הוא לא אומת מול Mode 09 ולא מול רכב אמיתי."
}
