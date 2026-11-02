import { DTC_DB } from "./dtc-db.generated.ts";
import type {
  Confidence,
  DtcStatus,
  EcuAccess,
  EcuId,
  FaultId,
  Fuel,
  IgnitionState,
  Posture,
  SessionState,
  Severity,
  SystemGap,
} from "./types.ts";

export const ECU_META: Record<
  EcuId,
  { nameHe: string; address: string; protocolHe: string }
> = {
  engine: {
    nameHe: "מנוע",
    address: "7E0",
    protocolHe: "ISO 15765-4 CAN · 11-bit · 500 kbit/s (מדומה)",
  },
  abs: {
    nameHe: "ABS / ESC",
    address: "760",
    protocolHe: "UDS מדומה על CAN · לא OBD-II גנרי",
  },
  bcm: {
    nameHe: "BCM · מרכב",
    address: "726",
    protocolHe: "UDS מדומה על CAN · לא OBD-II גנרי",
  },
  srs: {
    nameHe: "SRS · כריות אוויר",
    address: "737",
    protocolHe: "UDS מדומה על CAN · לא OBD-II גנרי",
  },
};

export const OUT_OF_SCOPE: SystemGap[] = [
  {
    id: "tcm",
    nameHe: "תיבת הילוכים / TCM",
    reasonHe: "אין פרוטוקול יצרן בשלב הזה. המערכת לא נסרקה.",
    coverage: "not_scanned",
  },
  {
    id: "eps",
    nameHe: "הגה כוח / EPS",
    reasonHe: "יחידת ההגה לא נכללה בסריקה. אם מופיעה זווית הגה, היא הגיעה רק מערוץ ABS המדומה.",
    coverage: "not_scanned",
  },
  {
    id: "tpms",
    nameHe: "לחץ אוויר בצמיגים",
    reasonHe: "יחידת TPMS לא נסרקה. לא נקרא לחץ ולא נקראה טמפרטורת צמיג, ואין מזה מסקנה.",
    coverage: "not_scanned",
  },
  {
    id: "hvac",
    nameHe: "מיזוג",
    reasonHe: "יחידת המיזוג לא נכללה בסריקה.",
    coverage: "not_scanned",
  },
  {
    id: "cluster",
    nameHe: "לוח מחוונים",
    reasonHe: "לוח המחוונים לא נסרק.",
    coverage: "not_scanned",
  },
  {
    id: "parking",
    nameHe: "חיישני חניה",
    reasonHe: "חיישני החניה לא נכללו בסריקה.",
    coverage: "not_scanned",
  },
  {
    id: "adas",
    nameHe: "ADAS",
    reasonHe: "מערכות עזר לנהג לא נסרקו, ולא הוסק מהן דבר.",
    coverage: "not_scanned",
  },
];

const DTC_SYSTEM_HE: Record<string, string> = {
  P: "מערכת ההנעה (מנוע ותיבת הילוכים)",
  C: "השלדה (בלמים, היגוי, מתלים)",
  B: "המרכב (כריות אוויר, נוחות, תאורה)",
  U: "התקשורת בין יחידות הבקרה",
};

/** Third character of a P0 / P1 / P2 code: the subsystem (SAE J2012). */
const POWERTRAIN_AREA_HE: Record<string, string> = {
  "0": "מדידת דלק ואוויר ובקרת פליטות",
  "1": "מדידת דלק ואוויר",
  "2": "מדידת דלק ואוויר (מזרקים)",
  "3": "מערכת ההצתה והחטאות בעירה",
  "4": "בקרת פליטות",
  "5": "מהירות רכב, בקרת סרק וכניסות עזר",
  "6": "מחשב הרכב ויציאות עזר",
  "7": "תיבת ההילוכים",
  "8": "תיבת ההילוכים",
  "9": "תיבת ההילוכים",
};

const DTC_FORMAT = /^[PCBU][0-3][0-9A-F]{3}$/;

/**
 * Description of a trouble code. Known generic codes come from the shared database.
 * Anything else is described only by what the code's structure says (system, generic
 * or manufacturer-specific, subsystem) and says plainly that the meaning is unverified.
 */
export function describeDtc(code: string): { descriptionHe: string; severity: Severity } {
  const upper = code.trim().toUpperCase();
  const known = DTC_DB[upper];
  if (known) return { descriptionHe: known[0], severity: known[1] };
  return { descriptionHe: describeByStructure(upper), severity: "medium" };
}

export function isKnownDtc(code: string): boolean {
  return DTC_DB[code.trim().toUpperCase()] !== undefined;
}

function describeByStructure(code: string): string {
  if (!DTC_FORMAT.test(code)) {
    return "אין תיאור מאומת לקוד הזה במאגר המקומי. לא משויכת לו משמעות משוערת.";
  }
  const system = DTC_SYSTEM_HE[code[0]];
  const kind = dtcKind(code);
  const lead = "הקוד לא נמצא במאגר המקומי. לפי מבנה הקוד בלבד:";
  if (kind === "manufacturer") {
    return `${lead} קוד יצרן של ${system}. המשמעות המדויקת תלויה ביצרן הרכב ולא אומתה.`;
  }
  if (kind === "reserved") {
    return `${lead} קוד של ${system} בטווח שמור. המשמעות לא אומתה.`;
  }
  const area = code[0] === "P" ? POWERTRAIN_AREA_HE[code[2]] : undefined;
  const where = area ? `, בתחום ${area}` : "";
  return `${lead} קוד כללי (תקני) של ${system}${where}. המשמעות המדויקת לא אומתה.`;
}

function dtcKind(code: string): "generic" | "manufacturer" | "reserved" {
  const d1 = code[1];
  if (code[0] === "P") {
    if (d1 === "0" || d1 === "2") return "generic";
    if (d1 === "1") return "manufacturer";
    return code[2] === "4" ? "generic" : "manufacturer"; // P34xx is SAE, the rest of P3 is manufacturer
  }
  if (d1 === "0") return "generic";
  if (d1 === "3") return "reserved";
  return "manufacturer";
}

export const IGNITION_LABEL: Record<IgnitionState, string> = {
  OFF: "כבוי",
  ACC: "אביזרים",
  ON: "הצתה",
  STARTING: "התנעה",
  RUNNING: "מנוע פועל",
};

export const SESSION_LABEL: Record<SessionState, string> = {
  DISCONNECTED: "מנותק",
  CONNECTING: "מתחבר",
  CONNECTED: "מחובר",
  SCANNING: "סורק",
  COMPLETE: "הושלם",
  DISCONNECTING: "מתנתק",
  ERROR: "שגיאה",
};

export const POSTURE_LABEL: Record<Posture, string> = {
  idle: "סרק",
  straight: "שיוט ישר",
  turn: "פנייה",
  brake: "בלימה",
};

export const FUEL_LABEL: Record<Fuel, string> = {
  gasoline: "בנזין",
  diesel: "דיזל",
  hybrid: "היברידי",
  electric: "חשמלי",
};

export const DTC_STATUS_LABEL: Record<DtcStatus, string> = {
  stored: "שמור",
  pending: "ממתין",
  permanent: "קבוע",
};

export const SEVERITY_LABEL: Record<Severity, string> = {
  info: "מידע",
  low: "נמוכה",
  medium: "בינונית",
  high: "גבוהה",
  critical: "דחופה",
};

export const CONFIDENCE_LABEL: Record<Confidence, string> = {
  insufficient: "לא ניתן לקבוע",
  low: "ביטחון נמוך",
  medium: "ביטחון בינוני",
  high: "ביטחון גבוה",
};

export const ECU_ACCESS_LABEL: Record<EcuAccess, string> = {
  checked: "נבדק",
  inaccessible: "לא נגיש",
  not_scanned: "לא נבדק",
  comm_error: "שגיאת תקשורת",
};

export const CLEAR_SCAN_HE =
  "לא נמצאו ממצאים לפי הנתונים שנבדקו. זה לא אישור שהרכב תקין לחלוטין, ולא ציון.";

export const GAP_BADGE_HE = "לא בסריקה";

export const MONITOR_STATUS_LABEL = {
  complete: "הושלם",
  incomplete: "לא הושלם",
  unsupported: "לא נתמך",
} as const;

export const FAULT_CATALOG: { id: FaultId; titleHe: string; detailHe: string }[] = [
  {
    id: "low_battery",
    titleHe: "מתח נמוך",
    detailHe: "מתח סביב 11.2 V. אם המנוע עונה, נוסף גם קוד ממתין P0562.",
  },
  {
    id: "high_coolant",
    titleHe: "טמפרטורת קירור גבוהה",
    detailHe: "נוזל קירור סביב 116 °C, וקוד P0217 אם המנוע עונה.",
  },
  {
    id: "misfire",
    titleHe: "החטאת בעירה",
    detailHe: "קוד שמור P0301 וקוד ממתין P0300. בלי קביעה איזה חלק גרם לזה.",
  },
  {
    id: "o2_anomaly",
    titleHe: "חיישן חמצן",
    detailHe: "קריאה בקצה הטווח וקוד ממתין P0134.",
  },
  {
    id: "wheel_speed_mismatch",
    titleHe: "הפרש מהירות גלגל",
    detailHe: "גלגל קדמי שמאלי איטי מהשאר. בלי קוד. בפנייה או בבלימה זה עלול להיבלע.",
  },
  {
    id: "steering_angle_anomaly",
    titleHe: "זווית הגה לא תואמת",
    detailHe: "ההגה מדווח פנייה בזמן שהגלגלים נשארים לפי תנאי הנסיעה.",
  },
  {
    id: "lost_ecu",
    titleHe: "יחידה לא עונה",
    detailHe: "היחידה שנבחרה לא מגיבה. שאר היחידות נשארות.",
  },
  {
    id: "comm_timeout",
    titleHe: "פסק זמן מנוע",
    detailHe: "יחידת המנוע נקטעת לפני נתונים וקודים.",
  },
  {
    id: "intermittent_sensor",
    titleHe: "חיישן בלי קריאה",
    detailHe: "טמפרטורת הקירור חסרה בדגימה הזו בלבד.",
  },
  {
    id: "multiple_dtcs",
    titleHe: "כמה קודים",
    detailHe: "P0300, P0171, P0420 קבוע, C0035 ו-B0001. בלי גורם שורש.",
  },
];

export function vinNote(vin: string): string {
  const clean = vin.trim().toUpperCase();
  if (!clean) {
    return "VIN לא הוזן בפרופיל ולא נקרא מהרכב. הזיהוי הוא לפי מה שהוקלד ידנית.";
  }
  return `VIN ${clean} הוזן ידנית בפרופיל. הוא לא אומת מול Mode 09 ולא מול רכב אמיתי.`;
}
