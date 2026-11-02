import type { ScanSession, Vehicle } from "../domain/types.ts";

export const HISTORY_LIMIT = 40;
export const STORAGE_KEY = "mechdoc-phase0-v1";

export interface ResourceSnapshot {
  at: string;
  heapMb: number | null;
  usedJsMb: number | null;
  limitJsMb: number | null;
  scans: number;
  vehicles: number;
  noteHe: string;
}

export interface GarageRecord {
  vehicles: Vehicle[];
  sessions: ScanSession[];
  selectedId: string | null;
  resources: ResourceSnapshot[];
}

export function emptyRecord(): GarageRecord {
  return { vehicles: [], sessions: [], selectedId: null, resources: [] };
}

/** Newest first. The same id is ignored. Older sessions are not rewritten. */
export function appendSession(
  sessions: ScanSession[],
  session: ScanSession,
  limit = HISTORY_LIMIT,
): ScanSession[] {
  if (sessions.some((item) => item.id === session.id)) return sessions;
  return [session, ...sessions].slice(0, limit);
}

export function sessionsFor(sessions: ScanSession[], vehicleId: string): ScanSession[] {
  return sessions.filter((session) => session.vehicleId === vehicleId);
}
