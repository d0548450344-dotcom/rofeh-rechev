import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

const CORE = [
  "domain/types.ts",
  "domain/machines.ts",
  "domain/catalog.ts",
  "engine/rules.ts",
  "engine/quick-scan.ts",
  "engine/lab.ts",
  "transport/obd-transport.ts",
  "simulation/adapter.ts",
  "app/link.ts",
  "session/scan-gate.ts",
  "persistence/record.ts",
  "persistence/repository.ts",
  "live/elm-parser.ts",
  "live/elm327.ts",
  "live/live-adapter.ts",
  "live/web-links.ts",
];

const UI = [
  "components/bench.tsx",
  "components/garage-home.tsx",
  "components/history.tsx",
  "components/report.tsx",
  "components/shell.tsx",
];

describe("the screen does not diagnose", () => {
  for (const file of UI) {
    it(file, () => {
      const text = readFileSync(join(root, file), "utf8");
      assert.doesNotMatch(
        text,
        /evaluate\(|readFacts\(|readLiveFacts\(|createSimulatedAdapter|performQuickScan|sessionFromFacts|Elm327Session|localStorage/,
      );
    });
  }
});

describe("core stays free of the web shell", () => {
  for (const file of CORE) {
    it(file, () => {
      const text = readFileSync(join(root, file), "utf8");
      assert.doesNotMatch(text, /localStorage|document\.|window\.|performance\.|from "react"|from "zustand"/);
    });
  }
});
