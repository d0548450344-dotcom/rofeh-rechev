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

const DTC_TEXT: Record<string, { descriptionHe: string; severity: Severity }> = {
  P0300: {
    descriptionHe: "החטאות בעירה ביותר מצילינדר אחד, או בלי שיוך לצילינדר.",
    severity: "high",
  },
  P0301: {
    descriptionHe: "החטאת בעירה ששויכה לצילינדר 1.",
    severity: "high",
  },
  P0171: {
    descriptionHe: "תערובת ענייה בבנק 1, לפי תיקון הדלק של היחידה.",
    severity: "medium",
  },
  P0420: {
    descriptionHe: "יעילות הממיר הקטליטי מתחת לסף בבנק 1.",
    severity: "medium",
  },
  P0134: {
    descriptionHe: "חיישן חמצן בנק 1 חיישן 1 לא הראה פעילות.",
    severity: "medium",
  },
  P0217: {
    descriptionHe: "טמפרטורת המנוע מעל סף ההגנה של היחידה.",
    severity: "high",
  },
  P0562: {
    descriptionHe: "מתח המערכת נמוך לפי היחידה.",
    severity: "medium",
  },
  C0035: {
    descriptionHe: "מעגל חיישן מהירות גלגל קדמי שמאלי.",
    severity: "medium",
  },
  B0001: {
    descriptionHe: "מעגל כרית אוויר קדמית של הנהג.",
    severity: "critical",
  },
};

export function describeDtc(code: string): { descriptionHe: string; severity: Severity } {
  return (
    DTC_TEXT[code] ?? {
      descriptionHe: "אין תיאור מאומת לקוד הזה במאגר המקומי. לא משויכת לו משמעות משוערת.",
      severity: "medium",
    }
  );
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
