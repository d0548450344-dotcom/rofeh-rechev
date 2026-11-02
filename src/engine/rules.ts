import { ECU_ACCESS_LABEL, ECU_META, describeDtc } from "../domain/catalog.ts";
import type {
  Confidence,
  Dtc,
  EcuId,
  Finding,
  IgnitionState,
  PidSample,
  ScanFacts,
  Severity,
} from "../domain/types.ts";

const EXCESS_MARGIN_KMH = 4;

/** Expected peak-to-peak wheel-speed gap for the reported steer and brake. */
export function expectedWheelSpread(
  speedKmh: number,
  steerDeg: number,
  braking: boolean,
): number {
  const rad = (Math.abs(steerDeg) * Math.PI) / 180;
  const turn = speedKmh * Math.sin(rad) * 0.55;
  const brakeExtra = braking ? speedKmh * 0.18 : 0;
  return turn + brakeExtra;
}

interface Draft {
  ruleId: string;
  code?: string | null;
  titleHe: string;
  summaryHe: string;
  severity: Severity;
  confidence: Confidence;
  claimHe: string;
  evidence: string[];
  conditions: string[];
  nextStepHe: string;
  ecuId?: EcuId | null;
}

function make(draft: Draft): Finding {
  const ecuId = draft.ecuId ?? null;
  return {
    id: `${draft.ruleId}:${ecuId ?? "none"}:${draft.code ?? draft.titleHe}`,
    ruleId: draft.ruleId,
    code: draft.code ?? null,
    titleHe: draft.titleHe,
    summaryHe: draft.summaryHe,
    severity: draft.severity,
    confidence: draft.confidence,
    claimHe: draft.claimHe,
    evidence: draft.evidence,
    conditions: draft.conditions,
    nextStepHe: draft.nextStepHe,
    source: "rule",
    ecuId,
  };
}

function pid(facts: ScanFacts, id: string): PidSample | undefined {
  return facts.pids.find((item) => item.id === id);
}

function checked(facts: ScanFacts, id: EcuId): boolean {
  return facts.ecus.some((ecu) => ecu.id === id && ecu.access === "checked");
}

function num(value: number, digits = 1): string {
  return value.toFixed(digits);
}

const SEVERITY_RANK: Record<Severity, number> = {
  critical: 0,
  high: 1,
  medium: 2,
  low: 3,
  info: 4,
};

export function evaluate(facts: ScanFacts, ignition: IgnitionState): Finding[] {
  const findings: Finding[] = [];
  const anyChecked = facts.ecus.some((ecu) => ecu.access === "checked");
  if (!anyChecked) {
    findings.push(
      make({
        ruleId: "no-coverage",
        titleHe: "אין כיסוי לקביעה",
        summaryHe:
          "אף יחידה לא הושלמה בסריקה הזו. לא ניתן לקבוע. זה לא מצב תקין ולא תקלה.",
        severity: "info",
        confidence: "insufficient",
        claimHe: "לא ניתן לקבוע",
        evidence: facts.ecus.map(
          (ecu) => `${ECU_META[ecu.id].nameHe}: ${ECU_ACCESS_LABEL[ecu.access]}`,
        ),
        conditions: ["אין יחידה במצב checked"],
        nextStepHe: "להעלות הצתה לפחות ל-ON ולחזור על הסריקה. בלי תשובה מיחידה אין ממצא.",
      }),
    );
  }

  for (const ecu of facts.ecus) {
    if (ecu.access !== "inaccessible") continue;
    const name = ECU_META[ecu.id].nameHe;
    findings.push(
      make({
        ruleId: "ecu-no-response",
        ecuId: ecu.id,
        titleHe: `${name} לא הגיבה`,
        summaryHe: `${ecu.reasonHe} אי-תגובה בסריקה אחת אינה קובעת שהיחידה עצמה פגומה.`,
        severity: "medium",
        confidence: "high",
        claimHe: "היחידה לא הגיבה",
        evidence: [`כתובת ${ECU_META[ecu.id].address}`, ecu.reasonHe],
        conditions: ["המתאם מחובר והסריקה פנתה לכתובת הזו."],
        nextStepHe:
          "לחזור על הסריקה כשההצתה במצב ON לפחות. אם אין תגובה שוב, במוסך בודקים הזנה וחיבור. לא מחליפים יחידה רק מזה.",
      }),
    );
  }

  if (facts.engineTimeout) {
    findings.push(
      make({
        ruleId: "comm-timeout",
        ecuId: "engine",
        titleHe: "פסק זמן מול יחידת המנוע",
        summaryHe:
          "היחידה החלה לענות והתקשורת נקטעה לפני שהתקבלו קודים או נתונים. לא ידוע מה היה נקרא, ולכן לא נקבע מצב המנוע.",
        severity: "medium",
        confidence: "high",
        claimHe: "פסק זמן בתקשורת",
        evidence: ["יחידת המנוע: פסק זמן אחרי תחילת תשובה", "לא התקבלו PID ולא DTC מהמנוע בסריקה הזו"],
        conditions: ["חיבור המתאם הצליח", "תשובת המנוע לא הושלמה"],
        nextStepHe:
          "לחזור על הסריקה. פסק זמן יחיד יכול להיות קשר. אם הוא חוזר, בודקים מתאם והזנת הצתה לפני שחושדים ביחידה.",
      }),
    );
  }

  pushVoltage(facts, ignition, findings);
  pushCoolant(facts, ignition, findings);
  pushIntermittent(facts, findings);

  for (const dtc of facts.dtcs) {
    findings.push(dtcFinding(dtc, facts));
  }

  if (facts.dtcs.length >= 3) {
    findings.push(
      make({
        ruleId: "multi-dtc",
        titleHe: `${facts.dtcs.length} קודים בלי גורם שורש`,
        summaryHe: `נקראו ${facts.dtcs.length} קודים. אין כלל שמקשר ביניהם לגורם אחד, ולכן לא נקבע מה לטפל קודם ואין דירוג אוטומטי.`,
        severity: "medium",
        confidence: "medium",
        claimHe: "אין גורם שורש",
        evidence: facts.dtcs.map((dtc) => `${dtc.code} · ${dtc.status} · ${ECU_META[dtc.ecuId].nameHe}`),
        conditions: ["לפחות שלושה קודים נקראו בפועל בסריקה הזו."],
        nextStepHe: "להעביר למוסך את רשימת הקודים והיחידות. הסריקה לא בוחרת חלק להחלפה.",
      }),
    );
  }

  pushO2(facts, findings);
  pushWheels(facts, findings);
  pushSteering(facts, findings);

  findings.sort((a, b) => SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity]);
  return findings;
}

function pushVoltage(facts: ScanFacts, ignition: IgnitionState, findings: Finding[]) {
  const engine = pid(facts, "voltage");
  const bcm = pid(facts, "bcmVoltage");
  const engineOk = engine?.quality === "valid" && engine.value !== null ? engine : undefined;
  const bcmOk = bcm?.quality === "valid" && bcm.value !== null ? bcm : undefined;

  if (engineOk && bcmOk && engineOk.value !== null && bcmOk.value !== null) {
    const gap = Math.abs(engineOk.value - bcmOk.value);
    if (gap > 0.5) {
      findings.push(
        make({
          ruleId: "voltage-disagree",
          titleHe: "שני מקורות מתח לא תואמים",
          summaryHe: `יחידת המנוע דיווחה ${num(engineOk.value)} V וה-BCM דיווח ${num(bcmOk.value)} V. מדגימה אחת לא ניתן לקבוע איזה מקור נכון.`,
          severity: "low",
          confidence: "insufficient",
          claimHe: "לא ניתן לקבוע",
          evidence: [
            `מנוע · ${num(engineOk.value)} V`,
            `BCM · ${num(bcmOk.value)} V`,
          ],
          conditions: ["שתי קריאות תקינות באותה סריקה", "הפרש מעל 0.5 V"],
          nextStepHe: "למדוד מתח מצבר במוסך במקביל לקריאת היחידות. לא לבחור מקור לפי ניחוש.",
        }),
      );
    }
  }

  const source = engineOk ?? bcmOk;
  if (!source || source.value === null) return;
  const value = source.value;
  const fromEngine = source === engineOk;
  const where = fromEngine ? "מיחידת המנוע (PID 42)" : "מ-BCM בלבד, כי המנוע לא מסר מתח";
  const confidence: Confidence = fromEngine ? "high" : "medium";

  if (ignition === "RUNNING" && value < 12.6) {
    findings.push(
      make({
        ruleId: "voltage-low",
        ecuId: fromEngine ? "engine" : "bcm",
        titleHe: "מתח נמוך כשהמנוע פועל",
        summaryHe: `נמדדו ${num(value)} V ${where}, בזמן שהמנוע במצב פועל. בטעינה שגרתית המתח בדרך כלל גבוה יותר. זו חריגת מדידה, לא קביעה איזה רכיב גרם לה.`,
        severity: value < 12 ? "high" : "medium",
        confidence,
        claimHe: "חריגת מדידה",
        evidence: [`${num(value)} V`, where, "מצב הצתה: מנוע פועל"],
        conditions: ["המנוע במצב RUNNING", "הקריאה סומנה כתקינה"],
        nextStepHe:
          "לבדוק במוסך מתח בזמן שהמנוע פועל, כולל חיבורי מצבר ועומס. הסריקה לא קובעת מה להחליף.",
      }),
    );
    return;
  }

  if (ignition !== "RUNNING" && value < 11.8) {
    findings.push(
      make({
        ruleId: "voltage-low",
        ecuId: fromEngine ? "engine" : "bcm",
        titleHe: "מתח נמוך בלי מנוע פועל",
        summaryHe: `נמדדו ${num(value)} V ${where}, כשהמנוע אינו במצב פועל. זה יכול להתאים למצבר חלש, אבל לא נמדדה התנעה ולא עומס. לא נקבע הגורם.`,
        severity: "medium",
        confidence,
        claimHe: "חריגת מדידה",
        evidence: [`${num(value)} V`, where, `מצב הצתה: ${ignition}`],
        conditions: ["המנוע אינו RUNNING", "הקריאה סומנה כתקינה"],
        nextStepHe: "למדוד מתח מצבר במנוחה ואחר כך בהתנעה. דגימה אחת בלי עומס לא מספיקה.",
      }),
    );
  }
}

function pushCoolant(facts: ScanFacts, ignition: IgnitionState, findings: Finding[]) {
  const sample = pid(facts, "coolant");
  if (!sample || sample.quality !== "valid" || sample.value === null || !sample.expected) return;
  if (sample.value < 108) return;
  const running = ignition === "RUNNING";
  findings.push(
    make({
      ruleId: "coolant-high",
      ecuId: "engine",
      titleHe: "טמפרטורת קירור גבוהה",
      summaryHe: `טמפרטורת נוזל הקירור ${num(sample.value, 0)} °C. זה מעל טווח עבודה שגרתי. לא נקבע אם הגורם הוא מאוורר, תרמוסטט, חוסר נוזל או החיישן.`,
      severity: sample.value >= 115 ? "critical" : "high",
      confidence: running ? "high" : "medium",
      claimHe: "חריגת מדידה",
      evidence: [`נוזל קירור ${num(sample.value, 0)} °C`, `מצב הצתה: ${ignition}`],
      conditions: [running ? "המנוע פועל" : "המנוע אינו במצב פועל, ולכן הביטחון נמוך יותר"],
      nextStepHe:
        "אם הטמפרטורה נשארת גבוהה, עוצרים ולא ממשיכים לנסוע. במוסך: מפלס, מאוורר ותרמוסטט. לא מאבחנים אטם ראש מדגימה אחת.",
    }),
  );
}

function pushIntermittent(facts: ScanFacts, findings: Finding[]) {
  for (const sample of facts.pids) {
    if (sample.quality !== "unavailable" || !sample.expected) continue;
    if (!checked(facts, sample.ecuId)) continue;
    findings.push(
      make({
        ruleId: "intermittent-sample",
        ecuId: sample.ecuId,
        titleHe: `אין קריאה: ${sample.nameHe}`,
        summaryHe: `${sample.nameHe} לא החזיר ערך בסריקה הזו, למרות שהיחידה נענתה. דגימה חסרה אחת אינה קובעת שהחיישן פגום. לא ניתן לקבוע.`,
        severity: "low",
        confidence: "insufficient",
        claimHe: "לא ניתן לקבוע",
        evidence: [`${sample.nameHe}: אין ערך`, `${ECU_META[sample.ecuId].nameHe} נענתה`],
        conditions: ["היחידה סומנה כנבדקת", "האות צפוי ברכב הזה", "דגימה בודדת"],
        nextStepHe: "לחזור על הסריקה. רק אם החסר חוזר יש טעם לבדוק את מעגל החיישן.",
      }),
    );
  }
}

function dtcFinding(dtc: Dtc, facts: ScanFacts): Finding {
  const known = describeDtc(dtc.code);
  const status =
    dtc.status === "stored" ? "שמור" : dtc.status === "pending" ? "ממתין" : "קבוע";
  const ecuName = ECU_META[dtc.ecuId].nameHe;
  const conditions = [
    "הקוד הוחזר על ידי היחידה בסריקה הזו.",
    dtc.status === "permanent"
      ? "קוד קבוע לא נמחק בסריקת קריאה, ובשלב הזה אין פעולת מחיקה בכלל."
      : "נוכחות קוד אינה זהה לתקלה פעילה ברגע הקריאה.",
  ];
  if (facts.fuel === "electric" && dtc.ecuId === "engine" && dtc.code.startsWith("P")) {
    conditions.push(
      "בפרופיל הרכב סוג ההנעה הוא חשמלי. קוד של מנוע בעירה לא צפוי — ייתכן פרופיל שגוי או הזרקת מעבדה.",
    );
  }
  let summary = `נמצא קוד ${status} ${dtc.code} ביחידת ${ecuName}. לפי מאגר כללי: ${known.descriptionHe} הקוד מתעד מה שהיחידה זיהתה. הוא לא מוכיח לבדו איזה חלק להחליף.`;
  if (dtc.ecuId === "srs") {
    summary +=
      " קוד במערכת הכריות דורש זהירות: לא לאבחן כריות במסך הזה ולא לאפס אותן.";
  }
  const misfireCode = dtc.code === "P0300" || dtc.code === "P0301";
  return make({
    ruleId: misfireCode ? "misfire-code" : "dtc",
    code: dtc.code,
    ecuId: dtc.ecuId,
    titleHe: `קוד ${dtc.code} ${status}`,
    summaryHe: summary,
    severity: dtc.severity,
    confidence: "high",
    claimHe: "הקוד נקרא מהיחידה",
    evidence: [`${dtc.code}`, `סטטוס: ${status}`, `יחידה: ${ecuName}`, known.descriptionHe],
    conditions,
    nextStepHe:
      dtc.ecuId === "srs"
        ? "לפנות לבדיקת SRS מקצועית. לא מאפסים כריות ולא בודקים אותן לבד."
        : "לתעד את הקוד, הסטטוס והיחידה למוסך. הסריקה לא ממליצה על חלק חלופי.",
  });
}

function pushO2(facts: ScanFacts, findings: Finding[]) {
  const sample = pid(facts, "o2");
  if (!sample || sample.quality !== "valid" || sample.value === null || !sample.expected) return;
  if (sample.value > 0.05 && sample.value < 0.95) return;
  const related = facts.dtcs.some((dtc) => /^P01[3-6]/.test(dtc.code));
  if (related) {
    findings.push(
      make({
        ruleId: "o2-extreme",
        ecuId: "engine",
        code: "P013x",
        titleHe: "חשד מתואם בחיישן חמצן",
        summaryHe: `חיישן החמצן נמדד ב-${num(sample.value)} V, ויש קוד שקשור לחיישן חמצן. זה חשד מתואם, לא הוכחה שהחיישן עצמו פגום. ייתכנו חיווט, דלק או אוויר.`,
        severity: "medium",
        confidence: "medium",
        claimHe: "חשד מתואם",
        evidence: [`O2 בנק 1 חיישן 1 · ${num(sample.value)} V`, "קיים קוד בטווח P0130–P0167"],
        conditions: ["דגימה בודדת, בלי צפייה במחזור עשיר/עני", "הקוד והערך נקראו באותה סריקה"],
        nextStepHe: "במוסך בודקים את אות החיישן לאורך זמן, לא מחליפים אותו רק בגלל הדגימה הזו.",
      }),
    );
    return;
  }
  findings.push(
    make({
      ruleId: "o2-extreme",
      ecuId: "engine",
      titleHe: "ערך חמצן קיצוני בלי קוד",
      summaryHe: `חיישן החמצן נמדד ב-${num(sample.value)} V בדגימה בודדת, בלי קוד משויך. לא ניתן לקבוע אם זו נקודה תקינה במחזור או חריגה.`,
      severity: "low",
      confidence: "insufficient",
      claimHe: "לא ניתן לקבוע",
      evidence: [`O2 בנק 1 חיישן 1 · ${num(sample.value)} V`, "אין קוד חמצן בסריקה הזו"],
      conditions: ["דגימה אחת אינה מראה מחזור"],
      nextStepHe: "לא להסיק מסקנה. אם התלונה חוזרת, נדרשת הקלטה של האות ולא ערך בודד.",
    }),
  );
}

function wheelValues(facts: ScanFacts): number[] | null {
  const ids = ["wheelFl", "wheelFr", "wheelRl", "wheelRr"] as const;
  const values: number[] = [];
  for (const id of ids) {
    const sample = pid(facts, id);
    if (!sample || sample.quality !== "valid" || sample.value === null) return null;
    values.push(sample.value);
  }
  return values;
}

function pushWheels(facts: ScanFacts, findings: Finding[]) {
  const values = wheelValues(facts);
  const speed = pid(facts, "speed");
  const steer = pid(facts, "steer");
  const brake = pid(facts, "brake");
  if (!values || !speed || !steer || !brake) return;
  if (speed.quality !== "valid" || speed.value === null) return;
  if (steer.quality !== "valid" || steer.value === null) return;
  if (brake.quality !== "valid" || brake.value === null) return;

  const actual = Math.max(...values) - Math.min(...values);
  const braking = brake.value === 1;
  const expected = expectedWheelSpread(speed.value, steer.value, braking);
  const excess = actual - expected;
  const evidence = [
    `מהירות רכב ${num(speed.value, 0)} קמ״ש`,
    `זווית הגה מדווחת ${num(steer.value)}°`,
    braking ? "הבלם לחוץ" : "הבלם לא לחוץ",
    `FL ${num(values[0])} · FR ${num(values[1])} · RL ${num(values[2])} · RR ${num(values[3])} קמ״ש`,
    `הפרש נמדד ${num(actual)} · מעטפת ${num(expected)} קמ״ש`,
  ];

  if (speed.value < 15) {
    if (actual < 3) return;
    findings.push(
      make({
        ruleId: "wheel-speed",
        ecuId: "abs",
        titleHe: "הפרש גלגלים במהירות נמוכה",
        summaryHe:
          "יש הפרש בין הגלגלים, אבל המהירות נמוכה מכדי להשוות חיישנים. לא ניתן לקבוע. בפנייה, בבלימה או בעמידה הפרש כזה אינו ראיה.",
        severity: "low",
        confidence: "insufficient",
        claimHe: "לא ניתן לקבוע",
        evidence,
        conditions: ["מהירות מתחת ל-15 קמ״ש", "דגימה בודדת"],
        nextStepHe: "לחזור על ההשוואה בשיוט ישר ויציב, בלי בלימה.",
      }),
    );
    return;
  }

  if (excess <= EXCESS_MARGIN_KMH) return;

  const straightish = speed.value >= 30 && Math.abs(steer.value) <= 5 && !braking;
  if (straightish) {
    findings.push(
      make({
        ruleId: "wheel-speed",
        ecuId: "abs",
        titleHe: "חשד להפרש מהירות בין גלגלים",
        summaryHe:
          "נמצא הפרש בין מהירויות הגלגלים מעל המעטפת של נסיעה ישרה בלי בלימה. זה חשד. אין כאן זיהוי של רכיב פגום. נדרש אימות בדגימה נוספת באותם תנאים.",
        severity: excess > 10 ? "high" : "medium",
        confidence: "medium",
        claimHe: "חשד",
        evidence,
        conditions: [
          "מהירות מעל 30 קמ״ש",
          "זווית הגה מדווחת קטנה",
          "הבלם לא לחוץ",
          "דגימה בודדת, ולכן הביטחון בינוני",
        ],
        nextStepHe: "לאמת בשיוט ישר ויציב. עד אז לא מחליפים חיישן גלגל.",
      }),
    );
    return;
  }

  findings.push(
    make({
      ruleId: "wheel-speed",
      ecuId: "abs",
      titleHe: "הפרש גלגלים בתנאים לא חד-משמעיים",
      summaryHe:
        "ההפרש גדול מהמעטפת שחושבה לפי הזווית והבלם, אבל זו אינה נסיעה ישרה יציבה. זה חשד בלבד. לא ניתן לקבוע איזה אות חורג. נדרש אימות בנסיעה ישרה בלי בלימה.",
      severity: "low",
      confidence: "low",
      claimHe: "חשד",
      evidence,
      conditions: ["התנאים כוללים פנייה, בלימה או מהירות בינונית", "דגימה בודדת"],
      nextStepHe: "לא להסיק מסקנה עד שיוט ישר. פנייה ובלימה מייצרות הפרש טבעי.",
    }),
  );
}

function pushSteering(facts: ScanFacts, findings: Finding[]) {
  const values = wheelValues(facts);
  const speed = pid(facts, "speed");
  const steer = pid(facts, "steer");
  const brake = pid(facts, "brake");
  if (!values || !speed || !steer || !brake) return;
  if (speed.quality !== "valid" || speed.value === null) return;
  if (steer.quality !== "valid" || steer.value === null) return;
  if (brake.quality !== "valid" || brake.value === null) return;
  if (brake.value === 1) return;
  if (speed.value < 40 || Math.abs(steer.value) < 15) return;

  const actual = Math.max(...values) - Math.min(...values);
  const expected = expectedWheelSpread(speed.value, steer.value, false);
  if (actual + 1.5 >= expected * 0.35) return;

  findings.push(
    make({
      ruleId: "steering-mismatch",
      ecuId: "abs",
      titleHe: "חשד לאי-התאמה בין הגה לגלגלים",
      summaryHe:
        "זווית ההגה המדווחת מתאימה לפנייה, אבל מהירויות הגלגלים כמעט שוות. זה חשד לאי-התאמה בין האותות, לא קביעה איזה חיישן שגוי. נדרש אימות מול זווית ההגה בפועל.",
      severity: "medium",
      confidence: "medium",
      claimHe: "חשד",
      evidence: [
        `זווית מדווחת ${num(steer.value)}°`,
        `מהירות ${num(speed.value, 0)} קמ״ש`,
        `הפרש גלגלים ${num(actual)} קמ״ש מול מעטפת ${num(expected)}`,
      ],
      conditions: ["מהירות מעל 40 קמ״ש", "הבלם לא לחוץ", "דגימה בודדת", "EPS עצמה לא נסרקה"],
      nextStepHe:
        "להשוות את זווית ההגה המוצגת להגה בפועל בנסיעה ישרה. יחידת EPS לא הייתה נגישה, אז אין ממצא עליה.",
    }),
  );
}
