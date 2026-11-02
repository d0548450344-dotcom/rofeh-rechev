import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { CLEAR_SCAN_HE, ECU_ACCESS_LABEL } from "./catalog.ts";
import { canIgnition, canSession, disconnectPath, releasedSession } from "./machines.ts";
import { createSimulatedAdapter, readFacts } from "../simulation/adapter.ts";
import { evaluate } from "../engine/rules.ts";
import { attachLabContext } from "../engine/lab.ts";
import { performQuickScan } from "../engine/quick-scan.ts";
import { appendSession, emptyRecord, sessionsFor } from "../persistence/record.ts";
import { memoryRepository, webStorageRepository } from "../persistence/repository.ts";
import { shouldCommitScan } from "../session/scan-gate.ts";
import type { FaultId, IgnitionState, ScanSession, SessionState, Vehicle } from "./types.ts";

const WHEN = "2026-10-17T12:00:00.000Z";

function vehicle(sim: Partial<Vehicle["sim"]> = {}): Vehicle {
  return {
    id: "v1",
    nickname: "ביקורת",
    make: "טויוטה",
    model: "קורולה",
    year: 2016,
    fuel: "gasoline",
    vin: "",
    createdAt: WHEN,
    sim: { ignition: "RUNNING", posture: "straight", faults: [], lostEcu: null, ...sim },
  };
}

function quick(v: Vehicle, id: string): ScanSession {
  return attachLabContext(
    performQuickScan({
      transport: createSimulatedAdapter(v),
      vehicleId: v.id,
      ignition: v.sim.ignition,
      posture: v.sim.posture,
      id,
      startedAt: WHEN,
      finishedAt: WHEN,
      durationMs: 10,
    }),
    v.sim,
  );
}

describe("ignition transitions", () => {
  const legal: [IgnitionState, IgnitionState][] = [
    ["OFF", "ACC"],
    ["ACC", "ON"],
    ["ON", "STARTING"],
    ["STARTING", "RUNNING"],
    ["RUNNING", "OFF"],
    ["ACC", "OFF"],
    ["ON", "ACC"],
    ["STARTING", "ON"],
    ["RUNNING", "ON"],
  ];
  const illegal: [IgnitionState, IgnitionState][] = [
    ["OFF", "RUNNING"],
    ["OFF", "STARTING"],
    ["OFF", "ON"],
    ["RUNNING", "ACC"],
    ["RUNNING", "STARTING"],
    ["ACC", "RUNNING"],
    ["ACC", "STARTING"],
    ["STARTING", "ACC"],
  ];

  for (const [from, to] of legal) {
    it(`allows ${from} -> ${to}`, () => {
      assert.equal(canIgnition(from, to), true);
    });
  }
  for (const [from, to] of illegal) {
    it(`rejects ${from} -> ${to}`, () => {
      assert.equal(canIgnition(from, to), false);
    });
  }
});

describe("obd session transitions", () => {
  const legal: [SessionState, SessionState][] = [
    ["DISCONNECTED", "CONNECTING"],
    ["CONNECTING", "CONNECTED"],
    ["CONNECTING", "ERROR"],
    ["CONNECTED", "SCANNING"],
    ["CONNECTED", "DISCONNECTING"],
    ["SCANNING", "COMPLETE"],
    ["SCANNING", "ERROR"],
    ["SCANNING", "DISCONNECTING"],
    ["COMPLETE", "SCANNING"],
    ["COMPLETE", "DISCONNECTING"],
    ["DISCONNECTING", "DISCONNECTED"],
    ["ERROR", "DISCONNECTED"],
    ["ERROR", "CONNECTING"],
  ];
  const illegal: [SessionState, SessionState][] = [
    ["DISCONNECTED", "SCANNING"],
    ["DISCONNECTED", "CONNECTED"],
    ["DISCONNECTED", "COMPLETE"],
    ["SCANNING", "DISCONNECTED"],
    ["CONNECTED", "COMPLETE"],
    ["COMPLETE", "CONNECTED"],
    ["ERROR", "SCANNING"],
  ];

  for (const [from, to] of legal) {
    it(`allows ${from} -> ${to}`, () => {
      assert.equal(canSession(from, to), true);
    });
  }
  for (const [from, to] of illegal) {
    it(`rejects ${from} -> ${to}`, () => {
      assert.equal(canSession(from, to), false);
    });
  }
});

describe("mock link and coverage", () => {
  it("fails the connection when ignition cannot power OBD", () => {
    const result = createSimulatedAdapter(vehicle({ ignition: "OFF" })).probe();
    assert.equal(result.ok, false);
    assert.equal(result.protocolHe, null);
    assert.match(result.errorHe ?? "", /ON/);
  });

  it("discovers four ECUs and leaves the rest unscanned", () => {
    const facts = readFacts(vehicle(), WHEN);
    assert.deepEqual(
      facts.ecus.map((ecu) => ecu.access),
      ["checked", "checked", "checked", "checked"],
    );
    const tpms = facts.gaps.find((gap) => gap.id === "tpms");
    assert.equal(tpms?.coverage, "not_scanned");
    assert.ok(facts.gaps.every((gap) => gap.coverage === "not_scanned"));
    const findings = evaluate(facts, "RUNNING");
    assert.equal(findings.length, 0);
    assert.equal(findings.some((item) => item.titleHe.includes("TPMS")), false);
    assert.equal(ECU_ACCESS_LABEL.not_scanned, "לא נבדק");
    assert.equal(ECU_ACCESS_LABEL.inaccessible, "לא נגיש");
    assert.equal(ECU_ACCESS_LABEL.comm_error, "שגיאת תקשורת");
  });

  it("does not call an ignition-off read a clean bill of health", () => {
    const { findings } = {
      findings: evaluate(readFacts(vehicle({ ignition: "OFF" }), WHEN), "OFF"),
    };
    const hit = findings.find((item) => item.ruleId === "no-coverage");
    assert.equal(hit?.confidence, "insufficient");
    assert.equal(hit?.claimHe, "לא ניתן לקבוע");
    assert.equal(hit?.evidence.some((line) => line.includes("not_scanned")), false);
    assert.ok(hit?.evidence.some((line) => line.includes("לא נבדק")));
    assert.match(CLEAR_SCAN_HE, /לא נמצאו ממצאים לפי הנתונים שנבדקו/);
    assert.match(CLEAR_SCAN_HE, /לא אישור שהרכב תקין/);
  });
});

describe("structured facts", () => {
  it("stores a DTC with code, ECU, status, and read time", () => {
    const session = quick(vehicle({ faults: ["misfire"] }), "dtc-1");
    const dtc = session.dtcs.find((item) => item.code === "P0301");
    assert.ok(dtc);
    assert.equal(dtc?.ecuId, "engine");
    assert.equal(dtc?.status, "stored");
    assert.equal(dtc?.readAt, WHEN);
    const finding = session.findings.find((item) => item.code === "P0301");
    assert.equal(finding?.source, "rule");
    assert.equal(finding?.ruleId, "misfire-code");
    assert.ok(finding?.severity);
    assert.ok(finding?.confidence);
    assert.ok((finding?.evidence.length ?? 0) > 0);
    assert.equal(finding?.summaryHe.includes("מקולקל"), false);
  });

  it("stores a PID with id, value, unit, ECU, and read time", () => {
    const session = quick(vehicle(), "pid-1");
    const rpm = session.pids.find((item) => item.id === "rpm");
    assert.equal(typeof rpm?.value, "number");
    assert.equal(rpm?.unit, "rpm");
    assert.equal(rpm?.ecuId, "engine");
    assert.equal(rpm?.readAt, WHEN);
    assert.equal(session.dtcs.length, 0);
  });

  it("returns no PIDs and no invented DTCs on a timeout", () => {
    const session = quick(vehicle({ faults: ["comm_timeout", "misfire"] }), "timeout");
    assert.equal(session.ecus.find((ecu) => ecu.id === "engine")?.access, "comm_error");
    assert.equal(session.pids.some((item) => item.ecuId === "engine"), false);
    assert.equal(session.dtcs.some((item) => item.ecuId === "engine"), false);
    assert.ok(session.findings.some((item) => item.ruleId === "comm-timeout"));
  });

  it("marks a lost ECU inaccessible and keeps the others", () => {
    const session = quick(vehicle({ faults: ["lost_ecu"], lostEcu: "abs" }), "lost");
    assert.equal(session.ecus.find((ecu) => ecu.id === "abs")?.access, "inaccessible");
    assert.equal(session.pids.some((item) => item.ecuId === "abs"), false);
    assert.equal(session.ecus.find((ecu) => ecu.id === "engine")?.access, "checked");
  });
});

describe("fault injection is data, and history keeps both scans", () => {
  it("drops the misfire on the next scan after the fault is removed", () => {
    const injected = vehicle({ faults: ["misfire"] });
    const first = quick(injected, "with-fault");
    const cleared = vehicle({ faults: [] });
    const second = quick(cleared, "without-fault");
    assert.ok(first.dtcs.some((dtc) => dtc.code === "P0301"));
    assert.equal(second.dtcs.length, 0);
    assert.equal(second.findings.length, 0);
    assert.deepEqual(second.labFaults, []);

    let history = appendSession(emptyRecord().sessions, first);
    history = appendSession(history, second);
    history = appendSession(history, second);
    assert.equal(history.length, 2);
    assert.equal(history[0]?.id, "without-fault");
    assert.ok(history[1]?.dtcs.some((dtc) => dtc.code === "P0301"));
    assert.equal(sessionsFor(history, "v1").length, 2);
    assert.ok(sessionsFor(history, "missing").length === 0);
  });

  it("round-trips an empty history and rejects corrupt storage", () => {
    const repo = memoryRepository();
    assert.equal(repo.load(), null);
    const record = { ...emptyRecord(), vehicles: [vehicle()], selectedId: "v1" };
    repo.save(record);
    const loaded = repo.load();
    assert.equal(loaded?.sessions.length, 0);
    assert.equal(loaded?.vehicles[0]?.nickname, "ביקורת");
    loaded!.vehicles[0]!.nickname = "שונה בזיכרון";
    assert.equal(repo.load()?.vehicles[0]?.nickname, "ביקורת");

    const broken = webStorageRepository({ getItem: () => "{", setItem() {} }, "k");
    assert.equal(broken.load(), null);
  });

  it("reloads the same scan after a storage round trip", () => {
    const mem = new Map<string, string>();
    const storage = {
      getItem: (key: string) => mem.get(key) ?? null,
      setItem: (key: string, value: string) => {
        mem.set(key, value);
      },
    };
    const session = quick(vehicle({ faults: ["low_battery"] }), "kept");
    webStorageRepository(storage, "garage").save({
      ...emptyRecord(),
      vehicles: [vehicle()],
      sessions: [session],
      selectedId: "v1",
    });
    const loaded = webStorageRepository(storage, "garage").load();
    assert.equal(loaded?.sessions.length, 1);
    assert.equal(loaded?.sessions[0]?.id, "kept");
    assert.equal(loaded?.sessions[0]?.pids.find((item) => item.id === "voltage")?.value, 11.2);
    assert.ok(loaded?.sessions[0]?.findings.some((item) => item.ruleId === "voltage-low"));
  });
});

describe("named faults are rules on facts, and they leave when the injection leaves", () => {
  const cases: { fault: FaultId; lost?: Vehicle["sim"]["lostEcu"]; ruleId: string }[] = [
    { fault: "low_battery", ruleId: "voltage-low" },
    { fault: "high_coolant", ruleId: "coolant-high" },
    { fault: "misfire", ruleId: "misfire-code" },
    { fault: "wheel_speed_mismatch", ruleId: "wheel-speed" },
    { fault: "lost_ecu", lost: "abs", ruleId: "ecu-no-response" },
    { fault: "comm_timeout", ruleId: "comm-timeout" },
  ];

  for (const item of cases) {
    it(`${item.fault} is visible only while injected`, () => {
      const on = quick(
        vehicle({ faults: [item.fault], lostEcu: item.lost ?? null, posture: "straight" }),
        `${item.fault}-on`,
      );
      const off = quick(vehicle({ posture: "straight" }), `${item.fault}-off`);
      const hit = on.findings.find((finding) => finding.ruleId === item.ruleId);
      assert.ok(hit);
      assert.ok(hit?.severity);
      assert.ok(hit?.confidence);
      assert.ok((hit?.evidence.length ?? 0) > 0);
      assert.equal(hit?.summaryHe.includes("מקולקל"), false);
      assert.equal(hit?.claimHe === "החיישן מקולקל", false);
      assert.equal(
        off.findings.some((finding) => finding.ruleId === item.ruleId && finding.ecuId === hit?.ecuId),
        false,
      );
    });
  }
});

describe("link release stays on the session machine", () => {
  it("closes a live link only through a legal disconnect", () => {
    assert.deepEqual(disconnectPath("CONNECTED"), ["DISCONNECTING", "DISCONNECTED"]);
    assert.deepEqual(disconnectPath("COMPLETE"), ["DISCONNECTING", "DISCONNECTED"]);
    assert.deepEqual(disconnectPath("SCANNING"), ["DISCONNECTING", "DISCONNECTED"]);
    assert.deepEqual(disconnectPath("ERROR"), ["DISCONNECTED"]);
    assert.equal(disconnectPath("CONNECTING"), null);
    assert.equal(releasedSession("CONNECTED"), "DISCONNECTED");
    assert.equal(releasedSession("CONNECTING"), null);
    assert.equal(canSession("CONNECTING", "DISCONNECTED"), false);
    assert.equal(canSession("CONNECTED", "DISCONNECTED"), false);
    assert.equal(canSession("CONNECTED", "DISCONNECTING"), true);
    assert.equal(canSession("DISCONNECTING", "DISCONNECTED"), true);
  });
});

describe("negative session gates", () => {
  it("does not keep a scan that was disconnected mid-read", () => {
    assert.equal(shouldCommitScan({ epochAtStart: 3, epochNow: 4, session: "SCANNING" }), false);
    assert.equal(
      shouldCommitScan({ epochAtStart: 3, epochNow: 3, session: "DISCONNECTING" }),
      false,
    );
    assert.equal(shouldCommitScan({ epochAtStart: 3, epochNow: 3, session: "SCANNING" }), true);
  });
});
