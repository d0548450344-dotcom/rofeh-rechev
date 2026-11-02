import assert from "node:assert/strict";
import test from "node:test";
import type { Vehicle } from "../domain/types.ts";
import { parseElm, codePairs, formatDtc } from "./elm-parser.ts";
import { Elm327Session, ElmLinkError, type ByteLink } from "./elm327.ts";
import { observedIgnition, probeLive, readLiveFacts } from "./live-adapter.ts";

/** A car behind an ELM327, at the text level. */
function fakeCar(replies: Record<string, string>, opts: { dropOn?: string } = {}) {
  const sent: string[] = [];
  let receiver: (bytes: Uint8Array) => void = () => undefined;
  let lost: () => void = () => undefined;
  let line = "";
  let closed = false;
  const link: ByteLink = {
    kind: "ble",
    name: "fake",
    async write(bytes) {
      for (const byte of bytes) {
        const c = String.fromCharCode(byte);
        if (c !== "\r") {
          line += c;
          continue;
        }
        const cmd = line;
        line = "";
        sent.push(cmd);
        if (cmd === opts.dropOn) {
          setTimeout(() => lost(), 1);
          return;
        }
        const body =
          replies[cmd] ?? (cmd === "ATZ" ? "ELM327 v2.1" : cmd.startsWith("AT") ? "OK" : "NO DATA");
        const text = `${body}\r\r>`;
        setTimeout(() => receiver(new TextEncoder().encode(text)), 1);
      }
    },
    onData(r) {
      receiver = r;
    },
    onLost(l) {
      lost = l;
    },
    async close() {
      closed = true;
    },
  };
  return { link, sent, isClosed: () => closed };
}

const RUNNING: Record<string, string> = {
  "0100": "SEARCHING...\r41 00 BE 3F A8 13",
  ATDPN: "A6",
  "010C": "41 0C 0C 80",
  "010D": "41 0D 00",
  "0105": "41 05 7B",
  "0111": "41 11 26",
  "0142": "41 42 37 DC",
  "0101": "41 01 81 07 65 00",
  "03": "43 01 03 00",
  "07": "47 00",
};

const vehicle: Vehicle = {
  id: "v1",
  nickname: "טסט",
  make: "טויוטה",
  model: "קורולה",
  year: 2016,
  fuel: "gasoline",
  vin: "",
  createdAt: "2026-01-01T00:00:00.000Z",
  sim: { ignition: "OFF", posture: "idle", faults: [], lostEcu: null },
};

test("parser: single, multi-frame and multi-ECU replies", () => {
  assert.deepEqual(parseElm("41 0C 1A F8\r\r>"), { kind: "messages", messages: [[0x41, 0x0c, 0x1a, 0xf8]] });
  const multi = parseElm("00A\r0: 43 04 01 33 03 00\r1: 01 71 C1 23 00 00\r>");
  assert.equal(multi.kind, "messages");
  if (multi.kind !== "messages") return;
  const pairs = codePairs(multi.messages, 0x43);
  const codes: string[] = [];
  for (let i = 0; i < pairs.length; i += 2) codes.push(formatDtc(pairs[i], pairs[i + 1]));
  assert.deepEqual(codes, ["P0133", "P0300", "P0171", "U0123"]);
  assert.deepEqual(parseElm("NO DATA\r>"), { kind: "no_data" });
  const off = parseElm("SEARCHING...\rUNABLE TO CONNECT\r>");
  assert.equal(off.kind === "adapter_error" && off.error, "no_vehicle");
});

test("a running car is scanned end to end", async () => {
  const car = fakeCar(RUNNING);
  const session = new Elm327Session(car.link);
  const probe = await probeLive(session);
  assert.equal(probe.ok, true);
  assert.match(probe.protocolHe ?? "", /CAN/);

  const facts = await readLiveFacts(session, vehicle, "2026-11-02T10:00:00.000Z");
  assert.deepEqual(facts.dtcs.map((d) => d.code), ["P0300"]);
  assert.equal(facts.pids.find((p) => p.id === "rpm")?.value, 800);
  assert.equal(facts.pids.find((p) => p.id === "voltage")?.value, 14.3);
  assert.equal(facts.readiness.find((m) => m.id === "misfire")?.status, "complete");
  assert.equal(facts.ecus.find((e) => e.id === "abs")?.access, "not_scanned");
  assert.equal(observedIgnition(facts), "RUNNING");
});

test("an unsupported PID is unavailable, not zero", async () => {
  const { "0111": _omit, ...rest } = RUNNING;
  const session = new Elm327Session(fakeCar(rest).link);
  await probeLive(session);
  const facts = await readLiveFacts(session, vehicle, "t");
  const throttle = facts.pids.find((p) => p.id === "throttle");
  assert.equal(throttle?.quality, "unavailable");
  assert.equal(throttle?.value, null);
});

test("ignition off gives a clear message and closes the link", async () => {
  const car = fakeCar({ "0100": "SEARCHING...\rUNABLE TO CONNECT" });
  const result = await probeLive(new Elm327Session(car.link));
  assert.equal(result.ok, false);
  assert.match(result.errorHe ?? "", /הרכב לא ענה/);
  assert.equal(car.isClosed(), true);
});

test("clear codes is never sent, and neither is an empty line", async () => {
  const car = fakeCar(RUNNING);
  const session = new Elm327Session(car.link);
  await probeLive(session);
  const refused = await session.request(0x04);
  assert.deepEqual(refused, { ok: false, reason: "unsupported", detail: "service-4" });
  await readLiveFacts(session, vehicle, "t");
  await session.close();
  assert.equal(car.sent.some((cmd) => cmd.replace(/ /g, "").startsWith("04")), false);
  assert.equal(car.sent.some((cmd) => cmd.length === 0), false);
});

test("link lost mid-scan yields a communication error, not a clean result", async () => {
  const session = new Elm327Session(fakeCar(RUNNING, { dropOn: "03" }).link);
  await probeLive(session);
  const facts = await readLiveFacts(session, vehicle, "t");
  assert.equal(facts.dtcs.length, 0);
  assert.ok(facts.ecus.every((e) => e.access === "comm_error"));
});

test("init errors are typed", async () => {
  const session = new Elm327Session(fakeCar({ ATE0: "?" }).link);
  await assert.rejects(session.init(), (error: unknown) => error instanceof ElmLinkError && error.reason === "not_elm");
});
