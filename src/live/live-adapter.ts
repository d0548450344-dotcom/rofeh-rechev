import { describeDtc, vinNote } from "../domain/catalog.ts";
import type {
  ConnectResult,
  Dtc,
  DtcStatus,
  EcuId,
  EcuReport,
  IgnitionState,
  Monitor,
  PidSample,
  ScanFacts,
  SystemGap,
  Vehicle,
} from "../domain/types.ts";
import { busLabel, formatDtc } from "./elm-parser.ts";
import { ElmLinkError, type Elm327Session } from "./elm327.ts";

/*
 * Real-car scan over an Elm327Session. Mirrors android/live/.../Elm327ObdAdapter.kt.
 * Generic OBD-II only: live data, readiness, stored and pending codes.
 * ABS / airbag / body need manufacturer protocols and are reported as not scanned.
 */

export const RUNNING_RPM = 400;

export const LIVE_GAPS: SystemGap[] = [
  { id: "abs", nameHe: "ABS / ESC", reasonHe: "דורש פרוטוקול יצרן. מתאם OBD-II גנרי לא קורא את היחידה הזו.", coverage: "not_scanned" },
  { id: "srs", nameHe: "כריות אוויר / SRS", reasonHe: "דורש פרוטוקול יצרן. לא נקראו קודים ולא מצב כריות.", coverage: "not_scanned" },
  { id: "bcm", nameHe: "מרכב / BCM", reasonHe: "דורש פרוטוקול יצרן. היחידה לא נסרקה.", coverage: "not_scanned" },
  { id: "tcm", nameHe: "תיבת הילוכים / TCM", reasonHe: "רק קודי פליטה שהיא מדווחת דרך OBD-II מופיעים כאן.", coverage: "not_scanned" },
  { id: "tpms", nameHe: "לחץ אוויר בצמיגים", reasonHe: "לא נסרק. אין מזה מסקנה.", coverage: "not_scanned" },
];

interface PidSpec {
  pid: number;
  id: string;
  nameHe: string;
  unit: string;
  engineOnly: boolean;
  bytes: number;
  decode: (d: number[]) => number;
}

const LIVE_PIDS: PidSpec[] = [
  { pid: 0x0c, id: "rpm", nameHe: "סל״ד", unit: "rpm", engineOnly: true, bytes: 2, decode: ([a, b]) => (a * 256 + b) / 4 },
  { pid: 0x0d, id: "speed", nameHe: "מהירות רכב", unit: "קמ״ש", engineOnly: false, bytes: 1, decode: ([a]) => a },
  { pid: 0x05, id: "coolant", nameHe: "טמפרטורת קירור", unit: "°C", engineOnly: true, bytes: 1, decode: ([a]) => a - 40 },
  { pid: 0x11, id: "throttle", nameHe: "מצערת", unit: "%", engineOnly: true, bytes: 1, decode: ([a]) => (a * 100) / 255 },
  { pid: 0x42, id: "voltage", nameHe: "מתח יחידת בקרה", unit: "V", engineOnly: false, bytes: 2, decode: ([a, b]) => (a * 256 + b) / 1000 },
];

const round1 = (n: number) => Math.round(n * 10) / 10;

export function protocolLabel(session: Elm327Session): string {
  const id = session.identity;
  return `${busLabel(id?.protocolNumber ?? null)}${id?.banner ? ` · ${id.banner}` : ""}`;
}

/** Initialises the adapter. Never throws; failures become a Hebrew message. */
export async function probeLive(session: Elm327Session): Promise<ConnectResult> {
  try {
    await session.init();
    return { ok: true, protocolHe: protocolLabel(session), errorHe: null };
  } catch (error) {
    await session.close().catch(() => undefined);
    return { ok: false, protocolHe: null, errorHe: connectMessage(error) };
  }
}

export function connectMessage(error: unknown): string {
  if (error instanceof ElmLinkError) {
    switch (error.reason) {
      case "no_vehicle":
        return "המתאם מחובר, אבל הרכב לא ענה. בודקים שההצתה במצב ON (או שהמנוע פועל) ושהמתאם יושב היטב בשקע ה-OBD.";
      case "not_elm":
        return "המתאם דחה פקודת אתחול. ייתכן שזה לא מתאם ELM327 תואם.";
      case "timeout":
        return "המתאם מחובר אבל לא עונה לפקודות. ייתכן שזה לא מתאם ELM327, או שהוא תפוס על ידי אפליקציה אחרת.";
      case "lost":
        return "החיבור למתאם נותק.";
      default:
        return "הייתה תקלה בתקשורת עם הרכב. מנסים שוב, ואם זה חוזר מנתקים ומחברים את המתאם.";
    }
  }
  const message = error instanceof Error ? error.message : String(error);
  if (/cancel|NotFoundError|chosen/i.test(message)) return "לא נבחר מתאם.";
  if (message.includes("unsupported")) return "הדפדפן הזה לא תומך בחיבור כזה. משתמשים ב-Chrome או Edge, או באפליקציית ה-Android.";
  if (message.includes("no-obd-service")) return "המכשיר שנבחר לא נראה כמו מתאם OBD.";
  return "לא הצלחנו להתחבר למתאם. בודקים שהוא מחובר לרכב ודולק.";
}

/** Reads one snapshot of the car. Returns comm-error facts if the link drops midway. */
export async function readLiveFacts(session: Elm327Session, vehicle: Vehicle, readAt: string): Promise<ScanFacts> {
  const ice = vehicle.fuel !== "electric";
  const supported = session.identity?.supportedPids ?? new Set<number>();
  const pids: PidSample[] = [];

  for (const spec of LIVE_PIDS) {
    const expected = spec.engineOnly ? ice : true;
    const unavailable: PidSample = {
      id: spec.id,
      nameHe: spec.nameHe,
      value: null,
      unit: spec.unit,
      ecuId: "engine",
      quality: "unavailable",
      genericObd: true,
      expected,
      readAt,
    };
    if (supported.size > 0 && spec.pid <= 0x20 && !supported.has(spec.pid)) {
      pids.push(unavailable);
      continue;
    }
    const reply = await session.request(0x01, [spec.pid]);
    if (!reply.ok) {
      if (reply.reason === "lost" || reply.reason === "timeout") return commFailure(vehicle, readAt, reply.reason);
      pids.push(unavailable);
      continue;
    }
    const data = reply.payload.slice(1, 1 + spec.bytes);
    if (data.length < spec.bytes) {
      pids.push(unavailable);
      continue;
    }
    pids.push({ ...unavailable, value: round1(spec.decode(data)), quality: "valid" });
  }

  const readiness: Monitor[] = [];
  const ready = await session.request(0x01, [0x01]);
  if (!ready.ok && (ready.reason === "lost" || ready.reason === "timeout")) {
    return commFailure(vehicle, readAt, ready.reason);
  }
  if (ready.ok && ready.payload.length >= 5) {
    const [, , b, , d] = ready.payload;
    const monitor = (id: string, nameHe: string, bit: number): Monitor => {
      const isSupported = ((b >> bit) & 1) === 1;
      const complete = isSupported && ((d >> bit) & 1) === 0;
      return { id, nameHe, status: !isSupported ? "unsupported" : complete ? "complete" : "incomplete" };
    };
    readiness.push(monitor("misfire", "החטאות", 0), monitor("fuel", "מערכת דלק", 1), monitor("comprehensive", "רכיבים מקיפים", 2));
  }

  const dtcs: Dtc[] = [];
  for (const [service, status] of [
    [0x03, "stored"],
    [0x07, "pending"],
  ] as Array<[number, DtcStatus]>) {
    const reply = await session.request(service);
    if (!reply.ok) {
      if (reply.reason === "lost" || reply.reason === "timeout") return commFailure(vehicle, readAt, reply.reason);
      continue; // NO DATA to a code request means no codes.
    }
    const bytes = reply.payload;
    for (let i = 0; i + 1 < bytes.length; i += 2) {
      if (bytes[i] === 0 && bytes[i + 1] === 0) continue;
      const code = formatDtc(bytes[i], bytes[i + 1]);
      if (dtcs.some((dtc) => dtc.code === code && dtc.status === status)) continue;
      dtcs.push({ code, status, ecuId: "engine", ...describeDtc(code), readAt });
    }
  }

  const partial = pids.some((pid) => pid.expected && pid.quality !== "valid");
  const protocolHe = protocolLabel(session);
  return {
    readAt,
    fuel: vehicle.fuel,
    protocolHe,
    vinNoteHe: vinNote(vehicle.vin),
    ecus: [
      {
        id: "engine",
        access: "checked",
        reasonHe: partial
          ? "נענתה דרך OBD-II. חלק מהנתונים הצפויים לא נתמכים ברכב הזה, ולכן הכיסוי חלקי."
          : "נענתה דרך OBD-II. נקראו קודים, מוכנות ונתונים חיים פעם אחת.",
        protocolHe,
      },
      notScanned("abs", "ABS / ESC"),
      notScanned("bcm", "מחשב המרכב"),
      notScanned("srs", "מערכת כריות האוויר"),
    ],
    gaps: LIVE_GAPS,
    dtcs,
    pids,
    readiness,
    engineTimeout: false,
  };
}

/** What the car itself showed about ignition: RPM above idle means the engine runs. */
export function observedIgnition(facts: ScanFacts): IgnitionState | null {
  if (!facts.ecus.some((ecu) => ecu.id === "engine" && ecu.access === "checked")) return null;
  const rpm = facts.pids.find((pid) => pid.id === "rpm")?.value;
  return rpm != null && rpm >= RUNNING_RPM ? "RUNNING" : "ON";
}

function notScanned(id: EcuId, nameHe: string): EcuReport {
  return {
    id,
    access: "not_scanned",
    reasonHe: `${nameHe} דורש פרוטוקול יצרן. מתאם OBD-II גנרי לא שואל את היחידה הזו.`,
    protocolHe: null,
  };
}

function commFailure(vehicle: Vehicle, readAt: string, reason: "lost" | "timeout"): ScanFacts {
  const reasonHe =
    reason === "lost"
      ? "החיבור למתאם נותק באמצע הסריקה. לא נשמרו קודים ולא נתונים."
      : "המתאם הפסיק לענות. לא נשמרו קודים ולא נתונים.";
  return {
    readAt,
    fuel: vehicle.fuel,
    protocolHe: null,
    vinNoteHe: vinNote(vehicle.vin),
    ecus: (["engine", "abs", "bcm", "srs"] as EcuId[]).map((id) => ({ id, access: "comm_error", reasonHe, protocolHe: null })),
    gaps: LIVE_GAPS,
    dtcs: [],
    pids: [],
    readiness: [],
    engineTimeout: reason === "timeout",
  };
}
