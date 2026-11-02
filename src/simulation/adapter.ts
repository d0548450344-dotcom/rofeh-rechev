import { ECU_META, OUT_OF_SCOPE, describeDtc, vinNote } from "../domain/catalog.ts";
import { ignitionAllowsObd } from "../domain/machines.ts";
import type {
  ConnectResult,
  Dtc,
  DtcStatus,
  EcuAccess,
  EcuId,
  EcuReport,
  Fuel,
  IgnitionState,
  Monitor,
  PidSample,
  Posture,
  ScanFacts,
  Vehicle,
} from "../domain/types.ts";
import type { ObdTransport } from "../transport/obd-transport.ts";

interface Dynamics {
  speed: number;
  rpm: number;
  steer: number;
  brake: boolean;
  throttle: number;
  load: number;
  coolant: number;
  voltage: number;
  o2: number;
  intake: number;
}

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

function dynamics(posture: Posture, ignition: IgnitionState, ice: boolean): Dynamics {
  if (ignition !== "RUNNING") {
    return {
      speed: 0,
      rpm: ignition === "STARTING" ? 220 : 0,
      steer: 0,
      brake: false,
      throttle: ignition === "STARTING" ? 8 : 0,
      load: 0,
      coolant: 46,
      voltage: 12.5,
      o2: 0.45,
      intake: 28,
    };
  }
  const table: Record<Posture, Dynamics> = {
    idle: {
      speed: 0,
      rpm: 780,
      steer: 0,
      brake: false,
      throttle: 2.4,
      load: 18,
      coolant: 90,
      voltage: 14.2,
      o2: 0.61,
      intake: 32,
    },
    straight: {
      speed: 62,
      rpm: 2140,
      steer: 0.6,
      brake: false,
      throttle: 21,
      load: 41,
      coolant: 93,
      voltage: 14.1,
      o2: 0.58,
      intake: 30,
    },
    turn: {
      speed: 34,
      rpm: 1760,
      steer: 22,
      brake: false,
      throttle: 16,
      load: 33,
      coolant: 92,
      voltage: 14.1,
      o2: 0.55,
      intake: 29,
    },
    brake: {
      speed: 48,
      rpm: 1380,
      steer: 1.2,
      brake: true,
      throttle: 0,
      load: 11,
      coolant: 94,
      voltage: 14,
      o2: 0.52,
      intake: 27,
    },
  };
  const row = table[posture];
  if (!ice) {
    return { ...row, rpm: 0, coolant: 46, o2: 0.45, intake: 28, load: 0, throttle: 0 };
  }
  return row;
}

function wheels(speed: number, steerDeg: number, brake: boolean, mismatch: boolean) {
  const rad = (steerDeg * Math.PI) / 180;
  const delta = speed * Math.sin(rad) * 0.55;
  const left = delta * 0.5;
  const right = -delta * 0.5;
  let fl = speed + left;
  let fr = speed + right;
  let rl = speed + left * 0.7;
  let rr = speed + right * 0.7;
  if (brake) {
    fl -= 1.1;
    fr -= 0.6;
    rl -= 1.4;
    rr -= 0.8;
  }
  if (mismatch) fl -= Math.max(7, speed * 0.11);
  const clip = (value: number) => round1(Math.max(0, value));
  return { fl: clip(fl), fr: clip(fr), rl: clip(rl), rr: clip(rr) };
}

function makeDtc(code: string, status: DtcStatus, ecuId: EcuId): Omit<Dtc, "readAt"> {
  const known = describeDtc(code);
  return {
    code,
    status,
    ecuId,
    descriptionHe: known.descriptionHe,
    severity: known.severity,
  };
}

function addDtc(list: Omit<Dtc, "readAt">[], dtc: Omit<Dtc, "readAt">) {
  const exists = list.some(
    (item) => item.code === dtc.code && item.status === dtc.status && item.ecuId === dtc.ecuId,
  );
  if (!exists) list.push(dtc);
}

function sample(
  id: string,
  nameHe: string,
  value: number,
  unit: string,
  ecuId: EcuId,
  genericObd: boolean,
  expected: boolean,
  quality: PidSample["quality"] = "valid",
): Omit<PidSample, "readAt"> {
  return {
    id,
    nameHe,
    value: quality === "valid" ? round1(value) : null,
    unit,
    ecuId,
    quality,
    genericObd,
    expected,
  };
}

function unavailable(
  id: string,
  nameHe: string,
  unit: string,
  ecuId: EcuId,
  genericObd: boolean,
  expected: boolean,
): Omit<PidSample, "readAt"> {
  return sample(id, nameHe, 0, unit, ecuId, genericObd, expected, "unavailable");
}

function readiness(fuel: Fuel, faults: Vehicle["sim"]["faults"], engineOk: boolean): Monitor[] {
  if (!engineOk) return [];
  if (fuel === "electric") {
    return [
      { id: "comprehensive", nameHe: "רכיבים מקיפים", status: "unsupported" },
      { id: "misfire", nameHe: "החטאות", status: "unsupported" },
      { id: "fuel", nameHe: "מערכת דלק", status: "unsupported" },
      { id: "catalyst", nameHe: "ממיר קטליטי", status: "unsupported" },
      { id: "o2", nameHe: "חיישן חמצן", status: "unsupported" },
    ];
  }
  const misfireBad = faults.includes("misfire") || faults.includes("multiple_dtcs");
  const o2Bad = faults.includes("o2_anomaly");
  const multi = faults.includes("multiple_dtcs");
  if (fuel === "diesel") {
    return [
      { id: "misfire", nameHe: "החטאות", status: misfireBad ? "incomplete" : "complete" },
      { id: "fuel", nameHe: "מערכת דלק", status: multi ? "incomplete" : "complete" },
      { id: "comprehensive", nameHe: "רכיבים מקיפים", status: multi ? "incomplete" : "complete" },
      { id: "egr", nameHe: "EGR", status: "complete" },
      { id: "pm", nameHe: "מסנן חלקיקים", status: "complete" },
      { id: "boost", nameHe: "לחץ הגדשה", status: "unsupported" },
      { id: "catalyst", nameHe: "ממיר קטליטי (ניצוץ)", status: "unsupported" },
    ];
  }
  return [
    { id: "misfire", nameHe: "החטאות", status: misfireBad ? "incomplete" : "complete" },
    { id: "fuel", nameHe: "מערכת דלק", status: multi ? "incomplete" : "complete" },
    { id: "comprehensive", nameHe: "רכיבים מקיפים", status: "complete" },
    { id: "catalyst", nameHe: "ממיר קטליטי", status: multi ? "incomplete" : "complete" },
    { id: "evap", nameHe: "נידוף דלק", status: "complete" },
    { id: "o2", nameHe: "חיישן חמצן", status: o2Bad ? "incomplete" : "complete" },
    { id: "o2heater", nameHe: "מחמם חיישן חמצן", status: "complete" },
    { id: "egr", nameHe: "EGR", status: "unsupported" },
    { id: "air", nameHe: "אוויר משני", status: "unsupported" },
  ];
}

function ecuReport(id: EcuId, access: EcuAccess, reasonHe: string): EcuReport {
  return {
    id,
    access,
    reasonHe,
    protocolHe: access === "checked" ? ECU_META[id].protocolHe : null,
  };
}

function unpoweredFacts(vehicle: Vehicle, readAt: string): ScanFacts {
  const reason = "אין הזנת הצתה. OBD דורש לפחות מצב ON, ולכן היחידה לא נשאלה באמת.";
  return {
    readAt,
    fuel: vehicle.fuel,
    protocolHe: null,
    vinNoteHe: vinNote(vehicle.vin),
    ecus: (["engine", "abs", "bcm", "srs"] as const).map((id) => ecuReport(id, "not_scanned", reason)),
    gaps: OUT_OF_SCOPE,
    dtcs: [],
    pids: [],
    readiness: [],
    engineTimeout: false,
  };
}

export function probe(vehicle: Vehicle): ConnectResult {
  if (!ignitionAllowsObd(vehicle.sim.ignition)) {
    return {
      ok: false,
      protocolHe: null,
      errorHe: "אין תקשורת. OBD דורש לפחות מצב הצתה ON. כרגע ההצתה לא מספיקה.",
    };
  }
  const lost =
    vehicle.sim.faults.includes("lost_ecu") ? (vehicle.sim.lostEcu ?? "engine") : null;
  if (lost === "engine") {
    return {
      ok: true,
      protocolHe: null,
      errorHe: null,
    };
  }
  if (vehicle.sim.faults.includes("comm_timeout")) {
    return {
      ok: true,
      protocolHe: "ISO 15765-4 CAN · 11-bit · 500 kbit/s (מדומה, עם פסקי זמן)",
      errorHe: null,
    };
  }
  return {
    ok: true,
    protocolHe: ECU_META.engine.protocolHe,
    errorHe: null,
  };
}

export function readFacts(vehicle: Vehicle, readAt: string): ScanFacts {
  if (!ignitionAllowsObd(vehicle.sim.ignition)) return unpoweredFacts(vehicle, readAt);

  const { ignition, posture, faults } = vehicle.sim;
  const lost = faults.includes("lost_ecu") ? (vehicle.sim.lostEcu ?? "engine") : null;
  const timeout = faults.includes("comm_timeout");
  const ice = vehicle.fuel !== "electric";
  const dyn = dynamics(posture, ignition, ice);
  let voltage = dyn.voltage;
  let coolant = dyn.coolant;
  let o2 = dyn.o2;
  let reportedSteer = dyn.steer;
  if (faults.includes("low_battery")) voltage = 11.2;
  if (faults.includes("high_coolant") && ice) coolant = 116;
  if (faults.includes("o2_anomaly") && ice) o2 = 0.02;
  if (faults.includes("steering_angle_anomaly")) reportedSteer = round1(dyn.steer + 24);
  const wheel = wheels(
    dyn.speed,
    dyn.steer,
    dyn.brake,
    faults.includes("wheel_speed_mismatch"),
  );

  const dtcs: Omit<Dtc, "readAt">[] = [];
  const engineHidden = lost === "engine" || timeout;
  if (!engineHidden) {
    if (faults.includes("misfire")) {
      addDtc(dtcs, makeDtc("P0301", "stored", "engine"));
      addDtc(dtcs, makeDtc("P0300", "pending", "engine"));
    }
    if (faults.includes("o2_anomaly") && ice) addDtc(dtcs, makeDtc("P0134", "pending", "engine"));
    if (faults.includes("low_battery")) addDtc(dtcs, makeDtc("P0562", "pending", "engine"));
    if (faults.includes("high_coolant") && ice) addDtc(dtcs, makeDtc("P0217", "stored", "engine"));
    if (faults.includes("multiple_dtcs")) {
      addDtc(dtcs, makeDtc("P0300", "stored", "engine"));
      addDtc(dtcs, makeDtc("P0171", "stored", "engine"));
      addDtc(dtcs, makeDtc("P0420", "permanent", "engine"));
    }
  }
  if (lost !== "abs" && faults.includes("multiple_dtcs")) {
    addDtc(dtcs, makeDtc("C0035", "stored", "abs"));
  }
  if (lost !== "srs" && faults.includes("multiple_dtcs")) {
    addDtc(dtcs, makeDtc("B0001", "stored", "srs"));
  }

  const ecus: EcuReport[] = [];
  const pids: Omit<PidSample, "readAt">[] = [];

  if (lost === "engine") {
    ecus.push(ecuReport("engine", "inaccessible", "אין תגובה מכתובת 7E0. לא נקראו קודים ולא נתונים."));
  } else if (timeout) {
    ecus.push(
      ecuReport("engine", "comm_error", "היחידה החלה לענות ואז נפסק הזמן. הנתונים והקודים לא הושלמו."),
    );
  } else {
    ecus.push(ecuReport("engine", "checked", "נענתה. נקראו קודים, מוכנות ונתוני PID שנדגמו פעם אחת."));
    const intermittent = faults.includes("intermittent_sensor") && ice;
    pids.push(
      ice
        ? sample("rpm", "סל״ד", dyn.rpm, "rpm", "engine", true, true)
        : unavailable("rpm", "סל״ד", "rpm", "engine", true, false),
      sample("speed", "מהירות רכב", dyn.speed, "קמ״ש", "engine", true, true),
      intermittent
        ? unavailable("coolant", "טמפרטורת קירור", "°C", "engine", true, true)
        : ice
          ? sample("coolant", "טמפרטורת קירור", coolant, "°C", "engine", true, true)
          : unavailable("coolant", "טמפרטורת קירור", "°C", "engine", true, false),
      ice
        ? sample("load", "עומס מחושב", dyn.load, "%", "engine", true, true)
        : unavailable("load", "עומס מחושב", "%", "engine", true, false),
      ice
        ? sample("throttle", "מצערת", dyn.throttle, "%", "engine", true, true)
        : unavailable("throttle", "מצערת", "%", "engine", true, false),
      sample("voltage", "מתח יחידת בקרה", voltage, "V", "engine", true, true),
      ice
        ? sample("o2", "חמצן בנק 1 חיישן 1", o2, "V", "engine", true, true)
        : unavailable("o2", "חמצן בנק 1 חיישן 1", "V", "engine", true, false),
      ice
        ? sample("intake", "טמפרטורת יניקה", dyn.intake, "°C", "engine", true, true)
        : unavailable("intake", "טמפרטורת יניקה", "°C", "engine", true, false),
    );
  }

  if (lost === "abs") {
    ecus.push(ecuReport("abs", "inaccessible", "אין תגובה מכתובת 760. מהירויות גלגל וזווית הגה לא נקראו."));
  } else {
    ecus.push(ecuReport("abs", "checked", "נענתה בערוץ יצרן מדומה. זה לא OBD-II גנרי."));
    pids.push(
      sample("wheelFl", "מהירות גלגל קדמי שמאל", wheel.fl, "קמ״ש", "abs", false, true),
      sample("wheelFr", "מהירות גלגל קדמי ימין", wheel.fr, "קמ״ש", "abs", false, true),
      sample("wheelRl", "מהירות גלגל אחורי שמאל", wheel.rl, "קמ״ש", "abs", false, true),
      sample("wheelRr", "מהירות גלגל אחורי ימין", wheel.rr, "קמ״ש", "abs", false, true),
      sample("steer", "זווית הגה דרך ABS", reportedSteer, "°", "abs", false, true),
      sample("brake", "מתג בלם", dyn.brake ? 1 : 0, "bool", "abs", false, true),
    );
  }

  if (lost === "bcm") {
    ecus.push(ecuReport("bcm", "inaccessible", "אין תגובה מכתובת 726. מתח המרכב לא נקרא."));
  } else {
    ecus.push(ecuReport("bcm", "checked", "נענתה. נקרא מתח מרכב בלבד."));
    pids.push(sample("bcmVoltage", "מתח מרכב", voltage, "V", "bcm", false, true));
  }

  if (lost === "srs") {
    ecus.push(ecuReport("srs", "inaccessible", "אין תגובה מכתובת 737. לא נקראו קודי כריות."));
  } else {
    ecus.push(ecuReport("srs", "checked", "נענתה לקריאת קודים בלבד. לא נקרא מצב כרית ולא בוצע איפוס."));
  }

  const engineOk = lost !== "engine" && !timeout;
  return {
    readAt,
    fuel: vehicle.fuel,
    protocolHe: engineOk
      ? ECU_META.engine.protocolHe
      : timeout
        ? "ISO 15765-4 CAN · 11-bit · 500 kbit/s (מדומה, פסק זמן)"
        : null,
    vinNoteHe: vinNote(vehicle.vin),
    ecus,
    gaps: OUT_OF_SCOPE,
    dtcs: dtcs
      .filter((dtc) => ecus.some((ecu) => ecu.id === dtc.ecuId && ecu.access === "checked"))
      .map((dtc) => ({ ...dtc, readAt })),
    pids: pids.map((pid) => ({ ...pid, readAt })),
    readiness: readiness(vehicle.fuel, faults, engineOk),
    engineTimeout: timeout && lost !== "engine",
  };
}

export function createSimulatedAdapter(vehicle: Vehicle): ObdTransport {
  return {
    kind: "simulated",
    readOnly: true,
    probe: () => probe(vehicle),
    readFacts: (readAt) => readFacts(vehicle, readAt),
  };
}
