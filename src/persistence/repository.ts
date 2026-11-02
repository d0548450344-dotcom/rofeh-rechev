import type { GarageRecord, ResourceSnapshot } from "./record.ts";
import type { ScanSession, Vehicle } from "../domain/types.ts";

export interface KeyValueStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export interface GarageRepository {
  load(): GarageRecord | null;
  save(record: GarageRecord): void;
}

export function memoryRepository(seed: GarageRecord | null = null): GarageRepository {
  let current = seed ? clone(seed) : null;
  return {
    load: () => (current ? clone(current) : null),
    save: (record) => {
      current = clone(record);
    },
  };
}

export function webStorageRepository(storage: KeyValueStore, key: string): GarageRepository {
  return {
    load() {
      const raw = storage.getItem(key);
      if (!raw) return null;
      try {
        return coerce(JSON.parse(raw) as unknown);
      } catch {
        return null;
      }
    },
    save(record) {
      storage.setItem(key, JSON.stringify(record));
    },
  };
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function coerce(parsed: unknown): GarageRecord | null {
  if (!parsed || typeof parsed !== "object") return null;
  const root = parsed as { state?: Partial<GarageRecord>; vehicles?: unknown };
  const body = Array.isArray(root.vehicles) ? (root as Partial<GarageRecord>) : root.state;
  if (!body || !Array.isArray(body.vehicles) || !Array.isArray(body.sessions)) return null;
  return {
    vehicles: body.vehicles as Vehicle[],
    sessions: body.sessions as ScanSession[],
    selectedId: typeof body.selectedId === "string" ? body.selectedId : null,
    resources: Array.isArray(body.resources) ? (body.resources as ResourceSnapshot[]) : [],
  };
}
