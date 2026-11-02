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

private val DTC_TEXT: Map<String, DtcText> = mapOf(
    "P0300" to DtcText("החטאות בעירה ביותר מצילינדר אחד, או בלי שיוך לצילינדר.", Severity.HIGH),
    "P0301" to DtcText("החטאת בעירה ששויכה לצילינדר 1.", Severity.HIGH),
    "P0171" to DtcText("תערובת ענייה בבנק 1, לפי תיקון הדלק של היחידה.", Severity.MEDIUM),
    "P0420" to DtcText("יעילות הממיר הקטליטי מתחת לסף בבנק 1.", Severity.MEDIUM),
    "P0134" to DtcText("חיישן חמצן בנק 1 חיישן 1 לא הראה פעילות.", Severity.MEDIUM),
    "P0217" to DtcText("טמפרטורת המנוע מעל סף ההגנה של היחידה.", Severity.HIGH),
    "P0562" to DtcText("מתח המערכת נמוך לפי היחידה.", Severity.MEDIUM),
    "C0035" to DtcText("מעגל חיישן מהירות גלגל קדמי שמאלי.", Severity.MEDIUM),
    "B0001" to DtcText("מעגל כרית אוויר קדמית של הנהג.", Severity.CRITICAL),
)

fun describeDtc(code: String): DtcText = DTC_TEXT[code]
    ?: DtcText("אין תיאור מאומת לקוד הזה במאגר המקומי. לא משויכת לו משמעות משוערת.", Severity.MEDIUM)

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
