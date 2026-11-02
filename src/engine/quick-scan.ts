import type { ObdTransport } from "../transport/obd-transport.ts";
import type { IgnitionState, Posture, ScanSession } from "../domain/types.ts";
import { evaluate } from "./rules.ts";

export function performQuickScan(args: {
  transport: ObdTransport;
  vehicleId: string;
  ignition: IgnitionState;
  posture: Posture;
  id: string;
  startedAt: string;
  finishedAt: string;
  durationMs: number;
}): ScanSession {
  const facts = args.transport.readFacts(args.finishedAt);
  const findings = evaluate(facts, args.ignition);
  return {
    id: args.id,
    vehicleId: args.vehicleId,
    kind: "quick",
    adapterKind: args.transport.kind,
    adapterLabelHe:
      args.transport.kind === "simulated" ? "מתאם OBD מדומה" : args.transport.kind,
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
