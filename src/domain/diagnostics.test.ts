import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { canIgnition, canSession } from "./machines.ts";
import { createSimulatedAdapter, readFacts } from "../simulation/adapter.ts";
import { evaluate } from "../engine/rules.ts";
import { assessLab, attachLabContext } from "../engine/lab.ts";
import { performQuickScan } from "../engine/quick-scan.ts";
import type { FaultId, Fuel, Posture, Vehicle } from "./types.ts";

function vehicle(
  sim: Partial<Vehicle["sim"]> = {},
  fuel: Fuel = "gasoline",
): Vehicle {
  return {
    id: "v1",
    nickname: "בדיקה",
    make: "טויוטה",
    model: "קורולה",
    year: 2016,
    fuel,
    vin: "",
    createdAt: "2026-01-01T00:00:00.000Z",
    sim: {
      ignition: "RUNNING",
      posture: "straight",
      faults: [],
      lostEcu: null,
      ...sim,
    },
  };
}

function scan(v: Vehicle) {
  const facts = readFacts(v, "2026-01-01T00:00:00.000Z");
  return { facts, findings: evaluate(facts, v.sim.ignition) };
}

describe("state machines", () => {
  it("rejects an illegal ignition jump", () => {
    assert.equal(canIgnition("OFF", "RUNNING"), false);
    assert.equal(canIgnition("OFF", "ACC"), true);
    assert.equal(canIgnition("STARTING", "RUNNING"), true);
  });

  it("rejects scanning before a connection", () => {
    assert.equal(canSession("DISCONNECTED", "SCANNING"), false);
    assert.equal(canSession("DISCONNECTED", "CONNECTING"), true);
    assert.equal(canSession("CONNECTED", "SCANNING"), true);
    assert.equal(canSession("SCANNING", "COMPLETE"), true);
  });
});

describe("read-only adapter", () => {
  it("exposes no write surface and no vehicle argument", () => {
    const adapter = createSimulatedAdapter(vehicle());
    const keys = Object.keys(adapter);
    assert.equal(adapter.readOnly, true);
    assert.equal(keys.includes("write"), false);
    assert.equal(keys.includes("clearDtc"), false);
    assert.equal(keys.includes("flash"), false);
    assert.deepEqual(keys.sort(), ["kind", "probe", "readFacts", "readOnly"].sort());
    assert.equal(adapter.probe.length, 0);
    assert.equal(adapter.readFacts.length, 1);
  });
});

describe("quick scan facts and rules", () => {
  it("opens a healthy straight scan without inventing a fault", () => {
    const { facts, findings } = scan(vehicle());
    assert.equal(findings.length, 0);
    assert.equal(facts.dtcs.length, 0);
    assert.equal(facts.ecus.filter((ecu) => ecu.access === "checked").length, 4);
    assert.ok(facts.gaps.length >= 7);
    assert.ok(facts.readiness.some((item) => item.status === "unsupported"));
  });

  it("does not answer ECUs when ignition is off", () => {
    const { facts, findings } = scan(vehicle({ ignition: "OFF" }));
    assert.ok(facts.ecus.every((ecu) => ecu.access === "not_scanned"));
    assert.equal(
      findings.some((item) => item.ruleId === "ecu-no-response"),
      false,
    );
    assert.equal(facts.pids.length, 0);
    assert.equal(probeOk(vehicle({ ignition: "OFF" })), false);
  });

  it("reports low voltage without naming a failed part", () => {
    const { findings } = scan(vehicle({ faults: ["low_battery"] }));
    const hit = findings.find((item) => item.ruleId === "voltage-low");
    assert.ok(hit);
    assert.equal(hit?.confidence, "high");
    assert.equal(hit?.summaryHe.includes("לא קביעה"), true);
    assert.equal(hit?.summaryHe.includes("אלטרנטור"), false);
  });

  it("flags high coolant as a measurement, not a cause", () => {
    const { findings } = scan(vehicle({ faults: ["high_coolant"] }));
    const hit = findings.find((item) => item.ruleId === "coolant-high");
    assert.equal(hit?.confidence, "high");
    assert.match(hit?.summaryHe ?? "", /לא נקבע/);
  });

  it("keeps a stored misfire code as a fact", () => {
    const { facts, findings } = scan(vehicle({ faults: ["misfire"] }));
    assert.ok(facts.dtcs.some((dtc) => dtc.code === "P0301" && dtc.status === "stored"));
    assert.ok(facts.dtcs.some((dtc) => dtc.code === "P0300" && dtc.status === "pending"));
    const fact = findings.find((item) => item.code === "P0301");
    assert.equal(fact?.claimHe, "הקוד נקרא מהיחידה");
    assert.equal(fact?.summaryHe.includes("לא מוכיח"), true);
  });

  it("suspects a wheel mismatch on a straight road and not in a turn", () => {
    const straight = scan(vehicle({ faults: ["wheel_speed_mismatch"], posture: "straight" }));
    const wheel = straight.findings.find((item) => item.ruleId === "wheel-speed");
    assert.equal(wheel?.confidence, "medium");
    assert.equal(wheel?.claimHe, "חשד");
    assert.equal(wheel?.summaryHe.includes("מקולקל"), false);

    const turn = scan(vehicle({ faults: ["wheel_speed_mismatch"], posture: "turn" }));
    const turned = turn.findings.find((item) => item.ruleId === "wheel-speed");
    assert.notEqual(turned?.confidence, "high");

    const natural = scan(vehicle({ posture: "turn" }));
    assert.equal(
      natural.findings.some((item) => item.ruleId === "wheel-speed" || item.ruleId === "steering-mismatch"),
      false,
    );
  });

  it("does not condemn wheels at idle", () => {
    const idle = scan(vehicle({ faults: ["wheel_speed_mismatch"], posture: "idle" }));
    const wheel = idle.findings.find((item) => item.ruleId === "wheel-speed");
    assert.ok(!wheel || wheel.confidence === "insufficient");
  });

  it("suspects steering disagreement only with enough speed", () => {
    const straight = scan(vehicle({ faults: ["steering_angle_anomaly"], posture: "straight" }));
    const hit = straight.findings.find((item) => item.ruleId === "steering-mismatch");
    assert.equal(hit?.claimHe, "חשד");
    assert.equal(hit?.confidence, "medium");

    const idle = scan(vehicle({ faults: ["steering_angle_anomaly"], posture: "idle" }));
    assert.equal(
      idle.findings.some((item) => item.ruleId === "steering-mismatch"),
      false,
    );
  });

  it("marks a lost ECU inaccessible and does not invent its data", () => {
    const { facts, findings } = scan(
      vehicle({ faults: ["lost_ecu"], lostEcu: "abs", posture: "straight" }),
    );
    assert.equal(facts.ecus.find((ecu) => ecu.id === "abs")?.access, "inaccessible");
    assert.equal(facts.pids.some((item) => item.ecuId === "abs"), false);
    assert.equal(facts.ecus.find((ecu) => ecu.id === "engine")?.access, "checked");
    assert.ok(findings.some((item) => item.ruleId === "ecu-no-response" && item.ecuId === "abs"));
    assert.equal(
      findings.some((item) => item.ruleId === "wheel-speed"),
      false,
    );
  });

  it("records a timeout without fabricating PIDs", () => {
    const { facts, findings } = scan(vehicle({ faults: ["comm_timeout", "misfire"] }));
    assert.equal(facts.ecus.find((ecu) => ecu.id === "engine")?.access, "comm_error");
    assert.equal(facts.pids.some((item) => item.ecuId === "engine"), false);
    assert.equal(facts.dtcs.some((item) => item.ecuId === "engine"), false);
    assert.ok(findings.some((item) => item.ruleId === "comm-timeout"));
    assert.equal(
      findings.some((item) => item.code === "P0301"),
      false,
    );
  });

  it("leaves a single missing sample undecided", () => {
    const { findings } = scan(vehicle({ faults: ["intermittent_sensor"] }));
    const hit = findings.find((item) => item.ruleId === "intermittent-sample");
    assert.equal(hit?.confidence, "insufficient");
    assert.equal(hit?.claimHe, "לא ניתן לקבוע");
  });

  it("refuses a root cause when several codes are stored", () => {
    const { facts, findings } = scan(vehicle({ faults: ["multiple_dtcs"] }));
    assert.ok(facts.dtcs.length >= 3);
    assert.ok(facts.dtcs.some((dtc) => dtc.status === "permanent"));
    const multi = findings.find((item) => item.ruleId === "multi-dtc");
    assert.equal(multi?.claimHe, "אין גורם שורש");
    assert.match(multi?.summaryHe ?? "", /לא נקבע/);
  });

  it("does not treat an electric profile like a gasoline engine", () => {
    const { facts } = scan(vehicle({}, "electric"));
    const coolant = facts.pids.find((item) => item.id === "coolant");
    assert.equal(coolant?.expected, false);
    assert.ok(facts.readiness.every((item) => item.status === "unsupported"));
  });
});

describe("lab honesty", () => {
  it("accepts a blocked wheel fault during a turn", () => {
    const v = vehicle({ faults: ["wheel_speed_mismatch"], posture: "turn" as Posture });
    const session = attachLabContext(
      performQuickScan({
        transport: createSimulatedAdapter(v),
        vehicleId: v.id,
        ignition: v.sim.ignition,
        posture: v.sim.posture,
        id: "s1",
        startedAt: "2026-01-01T00:00:00.000Z",
        finishedAt: "2026-01-01T00:00:01.000Z",
        durationMs: 1000,
      }),
      v.sim,
    );
    const checks = assessLab(session);
    assert.equal(checks[0]?.faultId, "wheel_speed_mismatch" satisfies FaultId);
    assert.equal(checks[0]?.met, true);
  });
});

function probeOk(v: Vehicle): boolean {
  return createSimulatedAdapter(v).probe().ok;
}
