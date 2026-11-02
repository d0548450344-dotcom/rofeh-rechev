import { FAULT_CATALOG } from "../domain/catalog.ts";
import type { FaultId, Finding, ScanSession, VehicleSim } from "../domain/types.ts";

export interface LabCheck {
  faultId: FaultId;
  titleHe: string;
  met: boolean;
  detailHe: string;
}

function has(findings: Finding[], ruleId: string): Finding | undefined {
  return findings.find((item) => item.ruleId === ruleId);
}

function condemns(finding: Finding | undefined): boolean {
  if (!finding) return false;
  return finding.confidence === "high" && finding.claimHe === "חשד";
}

export function attachLabContext(session: ScanSession, sim: VehicleSim): ScanSession {
  return {
    ...session,
    labFaults: [...sim.faults],
    labLostEcu: sim.lostEcu,
  };
}

export function assessLab(session: ScanSession): LabCheck[] {
  const findings = session.findings;
  const speed = session.pids.find((item) => item.id === "speed");
  const steer = session.pids.find((item) => item.id === "steer");
  const brake = session.pids.find((item) => item.id === "brake");
  const speedV = speed?.quality === "valid" ? speed.value : null;
  const steerV = steer?.quality === "valid" ? steer.value : null;
  const braking = brake?.quality === "valid" ? brake.value === 1 : null;
  const absUp = session.ecus.some((ecu) => ecu.id === "abs" && ecu.access === "checked");
  const engineUp = session.ecus.some((ecu) => ecu.id === "engine" && ecu.access === "checked");

  return session.labFaults.map((faultId) => {
    const titleHe = FAULT_CATALOG.find((item) => item.id === faultId)?.titleHe ?? faultId;
    const check = judge(faultId, {
      findings,
      speedV,
      steerV,
      braking,
      absUp,
      engineUp,
      session,
    });
    return { faultId, titleHe, ...check };
  });
}

function judge(
  faultId: FaultId,
  ctx: {
    findings: Finding[];
    speedV: number | null;
    steerV: number | null;
    braking: boolean | null;
    absUp: boolean;
    engineUp: boolean;
    session: ScanSession;
  },
): { met: boolean; detailHe: string } {
  const { findings, session } = ctx;
  if (faultId === "low_battery") {
    const hit = has(findings, "voltage-low");
    return {
      met: Boolean(hit),
      detailHe: hit
        ? "המדידה סומנה כחריגה, בלי לקבוע איזה רכיב גרם לה."
        : "המתח החריג לא עלה בממצאים.",
    };
  }
  if (faultId === "high_coolant") {
    const hit = has(findings, "coolant-high");
    return {
      met: Boolean(hit),
      detailHe: hit
        ? "הטמפרטורה סומנה כחריגה, בלי לקבוע את הגורם."
        : "אין ערך טמפרטורה תקין לקריאה, ולכן הכלל לא קבע תקלה.",
    };
  }
  if (faultId === "misfire") {
    const hit = findings.some((item) => item.code === "P0301");
    return {
      met: hit,
      detailHe: hit
        ? "הקוד P0301 נקרא כעובדה. לא נקבע איזה חלק להחליף."
        : "הקוד לא נקרא. אם המנוע לא ענה, זו תוצאה צפויה.",
    };
  }
  if (faultId === "o2_anomaly") {
    const hit = findings.some((item) => item.code === "P0134") || Boolean(has(findings, "o2-extreme"));
    return {
      met: hit,
      detailHe: hit
        ? "יש קוד או ערך קיצוני. הביטחון נשאר בגדר חשד או עובדת קוד."
        : "לא נקראו נתוני חמצן ולא קוד.",
    };
  }
  if (faultId === "wheel_speed_mismatch") {
    const wheel = has(findings, "wheel-speed");
    if (!ctx.absUp) {
      return {
        met: !wheel,
        detailHe: "ABS לא נגיש, ולכן אין קביעה על מהירות גלגל. זו התוצאה הנכונה.",
      };
    }
    if (ctx.speedV !== null && ctx.speedV < 15) {
      return {
        met: !wheel || wheel.confidence === "insufficient",
        detailHe: "במהירות נמוכה המנוע לא אמור לקבוע שהחיישן פגום.",
      };
    }
    const ambiguous =
      ctx.braking === true || (ctx.steerV !== null && Math.abs(ctx.steerV) > 8);
    if (ambiguous) {
      return {
        met: !condemns(wheel) && (!wheel || wheel.confidence !== "high"),
        detailHe: wheel
          ? "בתנאי פנייה או בלימה יש לכל היותר חשד, לא פסק דין."
          : "ההפרש נשאר בתוך מעטפת הפנייה או הבלימה. לא הוכרזה תקלה, וזה נכון בלי נסיעה ישרה.",
      };
    }
    return {
      met: Boolean(wheel) && wheel?.confidence === "medium" && wheel.claimHe === "חשד",
      detailHe: "בשיוט ישר צפוי חשד בביטחון בינוני, לא זיהוי של רכיב פגום.",
    };
  }
  if (faultId === "steering_angle_anomaly") {
    const steer = has(findings, "steering-mismatch");
    if (!ctx.absUp || ctx.speedV === null || ctx.speedV < 40 || ctx.braking) {
      return {
        met: !condemns(steer),
        detailHe: "בלי מהירות יציבה ובלי בלם משוחרר אין קביעה על זווית ההגה.",
      };
    }
    return {
      met: Boolean(steer) && steer?.confidence === "medium" && steer.claimHe === "חשד",
      detailHe: "צפוי חשד לאי-התאמה בין האותות, לא זיהוי איזה חיישן שגוי.",
    };
  }
  if (faultId === "lost_ecu") {
    const target = session.labLostEcu ?? "engine";
    const hit = findings.some((item) => item.ruleId === "ecu-no-response" && item.ecuId === target);
    return {
      met: hit,
      detailHe: hit
        ? "תועדה אי-תגובה. לא נקבע שהיחידה פגומה."
        : "היחידה שנבחרה לא סומנה כלא נגישה.",
    };
  }
  if (faultId === "comm_timeout") {
    const hit = has(findings, "comm-timeout");
    return {
      met: Boolean(hit) && !ctx.engineUp,
      detailHe: hit
        ? "תועד פסק זמן, בלי להמציא נתונים שלא הגיעו."
        : "פסק הזמן לא תועד.",
    };
  }
  if (faultId === "intermittent_sensor") {
    const hit = has(findings, "intermittent-sample");
    return {
      met: Boolean(hit) && hit?.confidence === "insufficient",
      detailHe: "דגימה חסרה נשארת בגדר לא ניתן לקבוע.",
    };
  }
  const codes = findings.filter((item) => item.ruleId === "dtc").length;
  return {
    met: Boolean(has(findings, "multi-dtc")) && session.dtcs.length >= 3 && codes >= 3,
    detailHe: "כמה קודים נקראו, והמנוע סירב לבחור גורם שורש.",
  };
}
