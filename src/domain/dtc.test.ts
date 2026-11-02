import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { describeDtc, isKnownDtc } from "./catalog.ts";
import { DTC_DB } from "./dtc-db.generated.ts";
// @ts-expect-error plain .mjs build script without type declarations
import { KT_OUT, TS_OUT, loadCodes, renderKotlin, renderTs } from "../../scripts/gen-dtc.mjs";

test("generated tables match shared/dtc/generic-dtc-he.json", () => {
  const entries = loadCodes();
  assert.equal(readFileSync(TS_OUT, "utf8"), renderTs(entries), "run: node scripts/gen-dtc.mjs");
  assert.equal(readFileSync(KT_OUT, "utf8"), renderKotlin(entries), "run: node scripts/gen-dtc.mjs");
  assert.ok(Object.keys(DTC_DB).length >= 300);
});

test("the original nine descriptions are unchanged", () => {
  assert.deepEqual(describeDtc("P0300"), {
    descriptionHe: "החטאות בעירה ביותר מצילינדר אחד, או בלי שיוך לצילינדר.",
    severity: "high",
  });
  assert.equal(describeDtc("P0301").descriptionHe, "החטאת בעירה ששויכה לצילינדר 1.");
  assert.equal(describeDtc("P0217").severity, "high");
  assert.equal(describeDtc("B0001").severity, "critical");
});

test("common generic codes are described", () => {
  assert.match(describeDtc("P0304").descriptionHe, /צילינדר 4/);
  assert.match(describeDtc("P0442").descriptionHe, /דליפה קטנה/);
  assert.match(describeDtc("P0158").descriptionHe, /בנק 2 חיישן 2/);
  assert.match(describeDtc("U0100").descriptionHe, /אבד הקשר/);
  assert.equal(describeDtc("p0420").descriptionHe, describeDtc("P0420").descriptionHe);
  assert.equal(isKnownDtc("P0171"), true);
});

test("unknown codes are described by structure only, never guessed", () => {
  const manufacturer = describeDtc("P1234").descriptionHe;
  assert.match(manufacturer, /קוד יצרן/);
  assert.match(manufacturer, /לא אומתה/);
  const generic = describeDtc("P0399").descriptionHe;
  assert.match(generic, /קוד כללי/);
  assert.match(generic, /הצתה/);
  assert.match(describeDtc("C1A00").descriptionHe, /קוד יצרן של השלדה/);
  assert.match(describeDtc("U3000").descriptionHe, /טווח שמור/);
  assert.match(describeDtc("P3400").descriptionHe, /קוד כללי/);
  assert.match(describeDtc("XYZ").descriptionHe, /אין תיאור מאומת/);
  assert.equal(describeDtc("P1234").severity, "medium");
  assert.equal(isKnownDtc("P1234"), false);
});
