import type { ObdTransport } from "../transport/obd-transport.ts";
import type { AdapterKind, IgnitionState, Posture, ScanFacts, ScanSession } from "../domain/types.ts";
import { evaluate } from "./rules.ts";

export const ADAPTER_LABEL: Record<AdapterKind, string> = {
  simulated: "מתאם OBD מדומה",
  ble: "מתאם Bluetooth LE",
  classic: "מתאם Bluetooth",
  wifi: "מתאם Wi-Fi",
  serial: "מתאם USB / יציאה טורית",
};

interface ScanMeta {
  vehicleId: string;
  ignition: IgnitionState;
  posture: Posture;
  id: string;
  startedAt: string;
  finishedAt: string;
  durationMs: number;
}

/** Facts → rules → session. Shared by the simulated and the real-adapter paths. */
export function sessionFromFacts(
  facts: ScanFacts,
  adapterKind: AdapterKind,
  args: ScanMeta,
): ScanSession {
  const findings = evaluate(facts, args.ignition);
  return {
    id: args.id,
    vehicleId: args.vehicleId,
    kind: "quick",
    adapterKind,
    adapterLabelHe: ADAPTER_LABEL[adapterKind],
    startedAt: args.startedAt,
    finishedAt: args.finishedAt,
    durationMs: args.durationMs,
    ignition: args.ignition,
    posture: args.posture,
    protocolHe: facts.protocolHe,
    vinNoteHe: facts.vinNoteHe,
    ecus: facts.ecus,
    gaps: facts.gaps,
    dtcs: facts.dtcs,
    pids: facts.pids,
    readiness: facts.readiness,
    findings,
    labFaults: [],
    labLostEcu: null,
    errorHe: null,
  };
}

export function performQuickScan(args: ScanMeta & { transport: ObdTransport }): ScanSession {
  const facts = args.transport.readFacts(args.finishedAt);
  return sessionFromFacts(facts, args.transport.kind, args);
}
