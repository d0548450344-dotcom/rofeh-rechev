package car.doctor.core.care

import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.DtcStatus
import car.doctor.core.model.PidQuality
import car.doctor.core.visit.GarageVisit
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.round

enum class ServiceKind(val labelHe: String) {
    PERIODIC("טיפול תקופתי"),
    WINTER("טיפול הכנה לחורף"),

    /** A full catch-up service, usually after buying a used car, that resets the service history. */
    BASELINE("טיפול יישור קו"),
}

enum class IntervalUnit(val labelHe: String, val shortHe: String, val pidId: String) {
    KM("ק״מ", "ק״מ", "odometer"),
    ENGINE_HOURS("שעות מנוע", "שעות", "engine_hours"),
}

/**
 * What the user wants to be reminded about for one car. Dates and readings are typed by the user.
 * Many cars do not report the odometer or engine hours over OBD-II, so the current reading is
 * typed too; when a deep scan does read it from the car, the higher of the two is used.
 */
@Serializable
data class CarePlan(
    /** How often the user wants a routine scan. 0 = never remind. */
    val checkEveryDays: Int = 7,
    /** yyyy-MM-dd of the last (or current) service, as typed. Null = not set. */
    val lastServiceDate: String? = null,
    val serviceKind: ServiceKind = ServiceKind.PERIODIC,
    val intervalUnit: IntervalUnit = IntervalUnit.KM,
    /** Km or engine hours between services. */
    val intervalAmount: Int = 15000,
    /** Odometer or engine hours at the last service. */
    val lastServiceReading: Int? = null,
    /** Odometer or engine hours now, as typed by the user. */
    val currentReading: Int? = null,
    /** Also by time, whichever comes first. 0 = by reading only. */
    val serviceEveryMonths: Int = 12,
    /** Free text for the user ("שמן + פילטרים"). Never computed. */
    val serviceNoteHe: String = "",
) {
    fun nextServiceDate(): LocalDate? {
        if (serviceEveryMonths <= 0) return null
        return lastServiceDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.plusMonths(serviceEveryMonths.coerceIn(1, 60).toLong())
    }

    fun nextServiceReading(): Int? = lastServiceReading?.let { it + intervalAmount }
}

/** One service the car had, as the owner recorded it. Kept for the car's whole life. */
@Serializable
data class ServiceRecord(
    val id: String,
    val vehicleId: String,
    /** yyyy-MM-dd */
    val date: String,
    val kind: ServiceKind,
    val unit: IntervalUnit = IntervalUnit.KM,
    /** Odometer or engine hours at the service, if known. */
    val reading: Int? = null,
    val garageName: String = "",
    val noteHe: String = "",
    /** The before/after garage visit this service came from, if any. */
    val visitId: String? = null,
)

/** What the owner fills in when a finished garage visit should also be saved as a service. */
data class ServiceDraft(val kind: ServiceKind, val reading: Int?)

enum class ReminderKind { SERVICE_DUE, SERVICE_SOON, WINTER, FOLLOW_UP_AFTER_CLEAR, ROUTINE_SCAN, OPEN_VISIT }

data class Reminder(val kind: ReminderKind, val titleHe: String, val detailHe: String, val urgent: Boolean)

object Reminders {
    const val SERVICE_WARN_DAYS = 30L
    const val FOLLOW_UP_DAYS = 3L
    const val OPEN_VISIT_DAYS = 2L

    /** Newest-first sessions and visits for one vehicle. [now] is an ISO instant. */
    /** The highest reading known: typed by the user, or read from the car in a scan. */
    fun currentReading(plan: CarePlan, sessions: List<DiagnosticSession>): Int? {
        val fromCar = sessions.firstNotNullOfOrNull { s ->
            s.pids.firstOrNull { it.id == plan.intervalUnit.pidId && it.value != null }?.value?.toInt()
        }
        return listOfNotNull(plan.currentReading, fromCar).maxOrNull()
    }

    fun forVehicle(plan: CarePlan, sessions: List<DiagnosticSession>, visits: List<GarageVisit>, now: String): List<Reminder> {
        val today = day(now) ?: return emptyList()
        val out = mutableListOf<Reminder>()
        val unit = plan.intervalUnit.shortHe

        val nextReading = plan.nextServiceReading()
        val current = currentReading(plan, sessions)
        if (nextReading != null && current != null) {
            val left = nextReading - current
            val warnAt = maxOf(plan.intervalAmount / 10, 1)
            when {
                left < 0 -> out += Reminder(
                    ReminderKind.SERVICE_DUE, "הטיפול עבר ב-${-left} $unit",
                    "הטיפול היה אמור להיות ב-$nextReading $unit, ועכשיו $current. מומלץ לסרוק לפני הכניסה למוסך ולפתוח ביקור.",
                    urgent = true,
                )
                left <= warnAt -> out += Reminder(
                    ReminderKind.SERVICE_SOON, "טיפול בעוד $left $unit",
                    "טיפול מתוכנן ב-$nextReading $unit (עכשיו $current). לפני הכניסה: סריקה ופתיחת ביקור במוסך.",
                    urgent = false,
                )
            }
        }

        if (today.monthValue in 10..11) {
            val doneThisYear = plan.serviceKind == ServiceKind.WINTER &&
                plan.lastServiceDate?.let { runCatching { LocalDate.parse(it).year }.getOrNull() } == today.year
            if (!doneThisYear) {
                out += Reminder(
                    ReminderKind.WINTER, "טיפול הכנה לחורף",
                    "לפני הגשמים: מגבים, צמיגים, מצבר, אורות ונוזל קירור. אם כבר נעשה, שומרים טיפול מסוג ״הכנה לחורף״.",
                    urgent = false,
                )
            }
        }

        if (out.none { it.kind == ReminderKind.SERVICE_DUE || it.kind == ReminderKind.SERVICE_SOON }) plan.nextServiceDate()?.let { due ->
            val left = ChronoUnit.DAYS.between(today, due)
            val note = plan.serviceNoteHe.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            when {
                left < 0 -> out += Reminder(
                    ReminderKind.SERVICE_DUE, "הטיפול התקופתי עבר",
                    "הטיפול היה אמור להיות ב-$due$note. מומלץ לסרוק לפני הכניסה למוסך ולפתוח ביקור.", urgent = true,
                )
                left <= SERVICE_WARN_DAYS -> out += Reminder(
                    ReminderKind.SERVICE_SOON, "טיפול בעוד $left ימים",
                    "טיפול מתוכנן ב-$due$note. לפני הכניסה: סריקה ופתיחת ביקור במוסך.", urgent = false,
                )
            }
        }

        val latest = sessions.firstOrNull()
        if (latest != null && latest.kind == "post-clear" && latest.clearedCodes.isNotEmpty()) {
            val since = daysBetween(latest.finishedAt, today)
            if (since != null && since >= FOLLOW_UP_DAYS) {
                out += Reminder(
                    ReminderKind.FOLLOW_UP_AFTER_CLEAR, "סריקת מעקב אחרי מחיקה",
                    "לפני $since ימים נמחקו ${latest.clearedCodes.joinToString(", ")}. סריקה עכשיו תראה אם התקלה חזרה.",
                    urgent = true,
                )
            }
        }

        visits.firstOrNull { it.isOpen }?.let { visit ->
            val since = daysBetween(visit.openedAt, today)
            if (since != null && since >= OPEN_VISIT_DAYS) {
                out += Reminder(
                    ReminderKind.OPEN_VISIT, "ביקור פתוח: ${visit.garageName}",
                    "הביקור פתוח $since ימים. אחרי היציאה מהמוסך: סריקה וסגירת הביקור, כדי להשוות לפני/אחרי.",
                    urgent = false,
                )
            }
        }

        if (plan.checkEveryDays > 0 && out.none { it.kind == ReminderKind.FOLLOW_UP_AFTER_CLEAR }) {
            val since = latest?.let { daysBetween(it.finishedAt, today) }
            if (since == null || since >= plan.checkEveryDays) {
                out += Reminder(
                    ReminderKind.ROUTINE_SCAN, "הגיע זמן לסריקה תקופתית",
                    if (since == null) "עוד אין סריקה שמורה לרכב הזה." else "הסריקה האחרונה הייתה לפני $since ימים.",
                    urgent = false,
                )
            }
        }
        return out.sortedByDescending { it.urgent }
    }

    internal fun day(iso: String): LocalDate? =
        runCatching { Instant.parse(iso).atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()

    private fun daysBetween(iso: String, today: LocalDate): Long? = day(iso)?.let { ChronoUnit.DAYS.between(it, today) }
}

enum class TrendDirection { UP, DOWN, STEADY }

data class PidTrend(
    val id: String,
    val nameHe: String,
    val unit: String,
    val first: Double,
    val last: Double,
    val readings: Int,
    val direction: TrendDirection,
    /** Set only when the change is large enough to be worth a look. */
    val warningHe: String?,
)

data class RecurringCode(val code: String, val descriptionHe: String, val sessions: Int, val returnedAfterClear: Boolean)

data class TrendReport(
    val scansUsed: Int,
    val pids: List<PidTrend>,
    val recurring: List<RecurringCode>,
    val noteHe: String,
)

/**
 * Long-term view across saved scans. Compares like with like: only scans with the engine
 * running (rpm above idle floor) are used for live values, since engine-off readings
 * (battery voltage especially) are not comparable with engine-on ones.
 * These are trends to watch, not diagnoses.
 */
object Trends {
    const val MIN_READINGS = 3
    private const val RUNNING_RPM = 400.0

    private data class Rule(val id: String, val threshold: Double, val warn: (Double, Double) -> String?)

    private val RULES = listOf(
        Rule("voltage", 0.3) { a, b ->
            if (b < a - 0.3) "המתח בזמן שהמנוע עובד ירד מ-${r1(a)} ל-${r1(b)} V. כדאי לבדוק אלטרנטור ומצבר." else null
        },
        Rule("coolant", 5.0) { a, b ->
            if (b > a + 5 && b >= 100) "טמפרטורת הקירור עלתה מ-${r1(a)} ל-${r1(b)} °C. כדאי לבדוק נוזל קירור, טרמוסטט ומאוורר." else null
        },
        Rule("ltft1", 3.0) { a, b ->
            if (abs(b) > abs(a) + 3 && abs(b) >= 8) "תיקון הדלק הארוך זז מ-${r1(a)}% ל-${r1(b)}%. המחשב מפצה יותר ויותר: דליפת ואקום, מד אוויר או מזרקים." else null
        },
        Rule("stft1", 5.0) { _, _ -> null },
        Rule("load", 5.0) { _, _ -> null },
    )

    /** Newest-first sessions of one vehicle. */
    fun forVehicle(sessions: List<DiagnosticSession>): TrendReport {
        val chronological = sessions.filter { it.errorHe == null && it.pids.isNotEmpty() }.reversed()
        val running = chronological.filter { s ->
            (s.pids.firstOrNull { it.id == "rpm" }?.value ?: 0.0) > RUNNING_RPM
        }
        val pidTrends = RULES.mapNotNull { rule ->
            val series = running.mapNotNull { s ->
                s.pids.firstOrNull { it.id == rule.id && it.quality == PidQuality.VALID && it.value != null }
            }
            if (series.size < MIN_READINGS) return@mapNotNull null
            val third = (series.size / 3).coerceAtLeast(1)
            val a = series.take(third).mapNotNull { it.value }.average()
            val b = series.takeLast(third).mapNotNull { it.value }.average()
            val direction = when {
                b - a > rule.threshold -> TrendDirection.UP
                a - b > rule.threshold -> TrendDirection.DOWN
                else -> TrendDirection.STEADY
            }
            PidTrend(rule.id, series.last().nameHe, series.last().unit, r1(a), r1(b), series.size, direction, rule.warn(a, b))
        }

        val recurring = chronological
            .flatMap { s -> s.dtcs.filter { it.status != DtcStatus.PERMANENT }.map { it.code }.distinct().map { it to s } }
            .groupBy({ it.first }, { it.second })
            .filter { (_, list) -> list.size >= 2 }
            .map { (code, list) ->
                val returned = chronological.any { s ->
                    s.kind == "post-clear" && code in s.clearedCodes &&
                        chronological.dropWhile { it !== s }.any { later -> later.dtcs.any { it.code == code } }
                }
                val desc = chronological.last { s -> s.dtcs.any { it.code == code } }.dtcs.first { it.code == code }.descriptionHe
                RecurringCode(code, desc, list.size, returned)
            }
            .sortedWith(compareByDescending<RecurringCode> { it.returnedAfterClear }.thenByDescending { it.sessions })

        val note = when {
            chronological.isEmpty() -> "אין עדיין סריקות שמורות."
            running.size < MIN_READINGS ->
                "מגמות של נתונים חיים צריכות לפחות $MIN_READINGS סריקות עם מנוע מונע (יש ${running.size}). קודים חוזרים נבדקים בכל הסריקות."
            else -> "מבוסס על ${running.size} סריקות עם מנוע מונע. אלה מגמות למעקב, לא אבחנה."
        }
        return TrendReport(chronological.size, pidTrends, recurring, note)
    }

    private fun r1(v: Double) = round(v * 10) / 10
}
