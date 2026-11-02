import { create } from "zustand";
import { assessLab, attachLabContext, type LabCheck } from "@/engine/lab";
import { performQuickScan, sessionFromFacts } from "@/engine/quick-scan";
import { Elm327Session } from "@/live/elm327";
import {
  ENGINE_RUNNING_HE,
  clearLive,
  connectMessage,
  observedIgnition,
  probeLive,
  readLiveFacts,
} from "@/live/live-adapter";
import { openBleLink, openSerialLink } from "@/live/web-links";
import { openVehicleLink } from "@/app/link";
import { canIgnition, canSession, disconnectPath, ignitionAllowsObd } from "@/domain/machines";
import { appendSession, type ResourceSnapshot } from "@/persistence/record";
import { browserRepository } from "@/persistence/browser";
import { shouldCommitScan } from "@/session/scan-gate";
import type {
  ConnectResult,
  EcuId,
  FaultId,
  Fuel,
  IgnitionState,
  Posture,
  ScanSession,
  SessionState,
  Vehicle,
} from "@/domain/types";

export interface LiveState {
  session: SessionState;
  protocolHe: string | null;
  errorHe: string | null;
  busy: boolean;
  latestId: string | null;
  scanEpoch: number;
  /** The scan made on the current link. Clearing codes is allowed only right after it. */
  scanThisLink?: string | null;
}

/** Where the bench reads from. Real adapters need Chrome / Edge (Web Bluetooth or Web Serial). */
export type AdapterMode = "simulated" | "ble" | "serial";

export interface GarageState {
  adapterMode: AdapterMode;
  setAdapterMode: (mode: AdapterMode) => void;
  vehicles: Vehicle[];
  sessions: ScanSession[];
  selectedId: string | null;
  live: LiveState;
  resources: ResourceSnapshot[];
  hydrated: boolean;
  hydrate: () => void;
  addVehicle: (input: Omit<Vehicle, "id" | "createdAt" | "sim">) => string;
  selectVehicle: (id: string) => void;
  removeVehicle: (id: string) => void;
  setIgnition: (to: IgnitionState) => string | null;
  setPosture: (posture: Posture) => void;
  toggleFault: (fault: FaultId) => void;
  setLostEcu: (ecu: EcuId) => void;
  connect: () => Promise<void>;
  disconnect: () => Promise<void>;
  quickScan: () => Promise<void>;
  /** Clears codes after two acknowledgements. Returns a Hebrew reason when refused, null on success. */
  clearCodes: (understoodCodesReturn: boolean, understoodReadinessReset: boolean) => Promise<string | null>;
  sampleResources: () => void;
}

/** The open real-adapter link, if any. Not persisted: a reload starts disconnected. */
let liveLink: Elm327Session | null = null;

function dropLiveLink(): void {
  const link = liveLink;
  liveLink = null;
  if (link) void link.close();
}

function uid(prefix: string): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `${prefix}-${crypto.randomUUID()}`;
  }
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function selected(state: { vehicles: Vehicle[]; selectedId: string | null }): Vehicle | undefined {
  return state.vehicles.find((vehicle) => vehicle.id === state.selectedId);
}

function patchVehicle(
  vehicles: Vehicle[],
  id: string,
  patch: (vehicle: Vehicle) => Vehicle,
): Vehicle[] {
  return vehicles.map((vehicle) => (vehicle.id === id ? patch(vehicle) : vehicle));
}

function idleLive(latestId: string | null = null, scanEpoch = 0): LiveState {
  return {
    session: "DISCONNECTED",
    protocolHe: null,
    errorHe: null,
    busy: false,
    latestId,
    scanEpoch,
  };
}

export function latestFor(sessions: ScanSession[], vehicleId: string | null): ScanSession | null {
  if (!vehicleId) return null;
  return sessions.find((session) => session.vehicleId === vehicleId) ?? null;
}

/** True right after a scan on this link that found codes. */
export function canClearCodes(state: Pick<GarageState, "live" | "sessions">): boolean {
  if (state.live.busy || state.live.session !== "COMPLETE" || !state.live.scanThisLink) return false;
  const scan = state.sessions.find((s) => s.id === state.live.scanThisLink);
  return Boolean(scan && scan.dtcs.length > 0);
}

export function labFor(session: ScanSession | null): LabCheck[] {
  if (!session || session.errorHe || session.labFaults.length === 0) return [];
  return assessLab(session);
}

function heapMb(): Pick<ResourceSnapshot, "heapMb" | "usedJsMb" | "limitJsMb"> {
  const memory = (
    performance as Performance & {
      memory?: { usedJSHeapSize: number; jsHeapSizeLimit: number };
    }
  ).memory;
  if (!memory) {
    return { heapMb: null, usedJsMb: null, limitJsMb: null };
  }
  const used = memory.usedJSHeapSize / (1024 * 1024);
  const limit = memory.jsHeapSizeLimit / (1024 * 1024);
  return {
    heapMb: Math.round(used * 10) / 10,
    usedJsMb: Math.round(used * 10) / 10,
    limitJsMb: Math.round(limit),
  };
}

export const useGarage = create<GarageState>()((set, get) => ({
      adapterMode: "simulated",
      setAdapterMode: (mode) => {
        if (get().live.busy || mode === get().adapterMode) return;
        dropLiveLink();
        set((state) => ({ adapterMode: mode, live: idleLive(state.live.latestId, state.live.scanEpoch) }));
      },
      vehicles: [],
      sessions: [],
      selectedId: null,
      live: idleLive(),
      resources: [],
      hydrated: false,

      hydrate: () => {
        if (get().hydrated) return;
        const record = browserRepository().load();
        set({
          vehicles: record?.vehicles ?? [],
          sessions: record?.sessions ?? [],
          selectedId: record?.selectedId ?? null,
          resources: record?.resources ?? [],
          hydrated: true,
          live: idleLive(),
        });
      },

      addVehicle: (input) => {
        const id = uid("veh");
        const vehicle: Vehicle = {
          ...input,
          id,
          createdAt: new Date().toISOString(),
          sim: { ignition: "OFF", posture: "idle", faults: [], lostEcu: null },
        };
        dropLiveLink();
        set((state) => ({
          vehicles: [vehicle, ...state.vehicles],
          selectedId: id,
          live: idleLive(),
        }));
        return id;
      },

      selectVehicle: (id) => {
        if (id !== get().selectedId) dropLiveLink();
        set({ selectedId: id, live: idleLive() });
      },

      removeVehicle: (id) => {
        set((state) => {
          const vehicles = state.vehicles.filter((vehicle) => vehicle.id !== id);
          return {
            vehicles,
            sessions: state.sessions.filter((session) => session.vehicleId !== id),
            selectedId:
              state.selectedId === id ? (vehicles[0]?.id ?? null) : state.selectedId,
            live: state.selectedId === id ? idleLive() : state.live,
          };
        });
      },

      setIgnition: (to) => {
        const vehicle = selected(get());
        if (!vehicle) return "אין רכב נבחר.";
        if (get().live.busy) return "יש פעולה באמצע. ממתינים שתסתיים.";
        if (!canIgnition(vehicle.sim.ignition, to)) {
          return `אי אפשר לעבור מ-${vehicle.sim.ignition} ל-${to}.`;
        }
        const live = get().live;
        const powerLoss = !ignitionAllowsObd(to) && live.session !== "DISCONNECTED";
        const path = powerLoss ? disconnectPath(live.session) : null;
        if (powerLoss && path === null) {
          return "אי אפשר לסגור את החיבור מהמצב הנוכחי. אין מסלול ניתוק חוקי.";
        }
        const closed = path && path.length > 0 ? path[path.length - 1] : "DISCONNECTED";
        set((state) => ({
          vehicles: patchVehicle(state.vehicles, vehicle.id, (current) => ({
            ...current,
            sim: { ...current.sim, ignition: to },
          })),
          live: powerLoss
            ? {
                ...idleLive(state.live.latestId, state.live.scanEpoch),
                session: closed ?? "DISCONNECTED",
              }
            : { ...state.live, errorHe: null },
        }));
        return powerLoss ? "ההצתה ירדה מתחת ל-ON. החיבור המדומה נסגר במסלול ניתוק." : null;
      },

      setPosture: (posture) => {
        const vehicle = selected(get());
        if (!vehicle || get().live.busy) return;
        set((state) => ({
          vehicles: patchVehicle(state.vehicles, vehicle.id, (current) => ({
            ...current,
            sim: { ...current.sim, posture },
          })),
        }));
      },

      toggleFault: (fault) => {
        const vehicle = selected(get());
        if (!vehicle || get().live.busy) return;
        set((state) => ({
          vehicles: patchVehicle(state.vehicles, vehicle.id, (current) => {
            const has = current.sim.faults.includes(fault);
            const faults = has
              ? current.sim.faults.filter((item) => item !== fault)
              : [...current.sim.faults, fault];
            return {
              ...current,
              sim: {
                ...current.sim,
                faults,
                lostEcu: faults.includes("lost_ecu")
                  ? (current.sim.lostEcu ?? "engine")
                  : null,
              },
            };
          }),
        }));
      },

      setLostEcu: (ecu) => {
        const vehicle = selected(get());
        if (!vehicle || get().live.busy) return;
        set((state) => ({
          vehicles: patchVehicle(state.vehicles, vehicle.id, (current) => ({
            ...current,
            sim: { ...current.sim, lostEcu: ecu },
          })),
        }));
      },

      connect: async () => {
        const vehicle = selected(get());
        const live = get().live;
        if (!vehicle || live.busy) return;
        if (!canSession(live.session, "CONNECTING")) {
          set((state) => ({
            live: {
              ...state.live,
              errorHe: "אי אפשר להתחבר מהמצב הנוכחי. קודם מתנתקים.",
            },
          }));
          return;
        }
        set({
          live: {
            ...live,
            session: "CONNECTING",
            busy: true,
            errorHe: null,
            protocolHe: null,
          },
        });
        const mode = get().adapterMode;
        if (mode !== "simulated") {
          // The browser's device picker must open before any other await.
          let result: ConnectResult;
          try {
            const link = await (mode === "ble" ? openBleLink() : openSerialLink());
            dropLiveLink();
            liveLink = new Elm327Session(link);
            result = await probeLive(liveLink);
          } catch (error) {
            result = { ok: false, protocolHe: null, errorHe: connectMessage(error) };
          }
          if (!result.ok) liveLink = null;
          set({
            live: {
              session: result.ok ? "CONNECTED" : "ERROR",
              protocolHe: result.ok ? result.protocolHe : null,
              errorHe: result.ok ? null : result.errorHe,
              busy: false,
              latestId: get().live.latestId,
              scanEpoch: get().live.scanEpoch,
            },
          });
          return;
        }
        await wait(420);
        const current = selected(get());
        if (!current) {
          set({ live: idleLive() });
          return;
        }
        const result = openVehicleLink(current).probe();
        if (!result.ok) {
          set({
            live: {
              session: "ERROR",
              protocolHe: null,
              errorHe: result.errorHe,
              busy: false,
              latestId: get().live.latestId,
              scanEpoch: get().live.scanEpoch,
            },
          });
          return;
        }
        set({
          live: {
            session: "CONNECTED",
            protocolHe: result.protocolHe,
            errorHe: null,
            busy: false,
            latestId: get().live.latestId,
            scanEpoch: get().live.scanEpoch,
          },
        });
      },

      disconnect: async () => {
        const live = get().live;
        dropLiveLink();
        if (live.session === "DISCONNECTED" || live.session === "DISCONNECTING") return;
        if (live.session === "ERROR") {
          set({ live: idleLive(live.latestId, live.scanEpoch) });
          return;
        }
        if (live.session === "SCANNING") {
          const epoch = live.scanEpoch + 1;
          set({
            live: {
              ...live,
              scanEpoch: epoch,
              session: "DISCONNECTING",
              busy: true,
              errorHe: "הסריקה הופסקה לפני שהושלמה. לא נשמרה תוצאה חלקית.",
            },
          });
          await wait(180);
          if (get().live.scanEpoch !== epoch) return;
          set({ live: idleLive(get().live.latestId, epoch) });
          return;
        }
        if (live.busy || !canSession(live.session, "DISCONNECTING")) return;
        set({ live: { ...live, session: "DISCONNECTING", busy: true, errorHe: null } });
        await wait(240);
        set({ live: idleLive(get().live.latestId, get().live.scanEpoch) });
      },

      quickScan: async () => {
        const vehicle = selected(get());
        const live = get().live;
        if (!vehicle || live.busy) return;
        if (!canSession(live.session, "SCANNING")) {
          set((state) => ({
            live: {
              ...state.live,
              errorHe: "סריקה דורשת חיבור. קודם מתחברים למתאם.",
            },
          }));
          return;
        }
        const started = Date.now();
        const epoch = live.scanEpoch + 1;
        set({
          live: { ...live, session: "SCANNING", busy: true, errorHe: null, scanEpoch: epoch },
        });
        if (get().adapterMode !== "simulated") {
          const link = liveLink;
          const current = selected(get());
          if (!link || !current) {
            set((state) => ({
              live: { ...state.live, session: "ERROR", busy: false, errorHe: "החיבור למתאם נסגר. מתחברים שוב." },
            }));
            return;
          }
          const finishedAt = new Date().toISOString();
          const facts = await readLiveFacts(link, current, finishedAt);
          const after = get().live;
          if (!shouldCommitScan({ epochAtStart: epoch, epochNow: after.scanEpoch, session: after.session })) {
            return;
          }
          const finished = Date.now();
          const session = sessionFromFacts(facts, link.link.kind, {
            vehicleId: current.id,
            ignition: observedIgnition(facts) ?? current.sim.ignition,
            posture: current.sim.posture,
            id: uid("scan"),
            startedAt: new Date(started).toISOString(),
            finishedAt,
            durationMs: finished - started,
          });
          const failed = facts.ecus.every((ecu) => ecu.access === "comm_error");
          if (failed) dropLiveLink();
          set((state) => ({
            sessions: appendSession(state.sessions, session),
            live: {
              session: failed ? "ERROR" : "COMPLETE",
              protocolHe: failed ? null : session.protocolHe,
              errorHe: failed ? "החיבור למתאם נקטע באמצע הסריקה. מתחברים שוב." : null,
              busy: false,
              latestId: session.id,
              scanEpoch: epoch,
              scanThisLink: failed ? null : session.id,
            },
          }));
          get().sampleResources();
          return;
        }
        await wait(680);
        const now = get().live;
        if (!shouldCommitScan({ epochAtStart: epoch, epochNow: now.scanEpoch, session: now.session })) {
          return;
        }
        const current = selected(get());
        if (!current) return;
        const finished = Date.now();
        const session = attachLabContext(
          performQuickScan({
            transport: openVehicleLink(current),
            vehicleId: current.id,
            ignition: current.sim.ignition,
            posture: current.sim.posture,
            id: uid("scan"),
            startedAt: new Date(started).toISOString(),
            finishedAt: new Date(finished).toISOString(),
            durationMs: finished - started,
          }),
          current.sim,
        );
        set((state) => ({
          sessions: appendSession(state.sessions, session),
          live: {
            session: "COMPLETE",
            protocolHe: session.protocolHe,
            errorHe: null,
            busy: false,
            latestId: session.id,
            scanEpoch: epoch,
            scanThisLink: session.id,
          },
        }));
        get().sampleResources();
      },

      clearCodes: async (understoodCodesReturn, understoodReadinessReset) => {
        if (!understoodCodesReturn || !understoodReadinessReset) return "צריך לאשר את שתי האזהרות לפני מחיקה.";
        const vehicle = selected(get());
        const live = get().live;
        if (!vehicle || live.busy) return "יש פעולה באמצע. ממתינים שתסתיים.";
        if (live.session !== "COMPLETE") return "מחיקה אפשרית רק מיד אחרי סריקה, כשהמתאם עדיין מחובר.";
        const prior = get().sessions.find((s) => s.id === live.scanThisLink && s.vehicleId === vehicle.id);
        if (!prior) return "סורקים קודם, כדי שהקודים יישמרו לפני המחיקה.";
        if (prior.dtcs.length === 0) return "אין קודים למחוק בסריקה האחרונה.";
        const cleared = [...new Set(prior.dtcs.map((dtc) => dtc.code))];
        const mode = get().adapterMode;

        set({ live: { ...live, busy: true, errorHe: null } });
        const started = Date.now();
        const meta = (ignition: IgnitionState) => ({
          vehicleId: vehicle.id,
          ignition,
          posture: vehicle.sim.posture,
          id: uid("scan"),
          startedAt: new Date(started).toISOString(),
          finishedAt: new Date().toISOString(),
          durationMs: Date.now() - started,
        });
        let session: ScanSession;
        if (mode === "simulated") {
          // Like a real ECU: refuse while the engine turns or the car moves. The injected
          // fault stays, so its code comes back on the verification scan.
          const refusal =
            vehicle.sim.ignition === "RUNNING" || vehicle.sim.ignition === "STARTING"
              ? ENGINE_RUNNING_HE
              : vehicle.sim.posture !== "idle"
                ? "הרכב בתנועה. עוצרים לפני מחיקת קודים."
                : null;
          if (refusal) {
            set((state) => ({ live: { ...state.live, busy: false } }));
            return refusal;
          }
          session = attachLabContext(
            performQuickScan({ transport: openVehicleLink(vehicle), ...meta(vehicle.sim.ignition) }),
            vehicle.sim,
          );
        } else {
          const link = liveLink;
          if (!link) {
            set((state) => ({ live: { ...state.live, busy: false, session: "ERROR", errorHe: "החיבור למתאם נסגר. מתחברים שוב." } }));
            return "החיבור למתאם נסגר. מתחברים שוב.";
          }
          const result = await clearLive(link, new Date().toISOString());
          if (!result.ok) {
            set((state) => ({
              live: { ...state.live, busy: false, errorHe: result.refused ? null : result.messageHe },
            }));
            return result.messageHe;
          }
          const facts = await readLiveFacts(link, vehicle, new Date().toISOString());
          session = sessionFromFacts(facts, link.link.kind, meta(observedIgnition(facts) ?? vehicle.sim.ignition));
        }
        session = { ...session, kind: "post-clear", clearedCodes: cleared, clearedFromSessionId: prior.id };
        set((state) => ({
          sessions: appendSession(state.sessions, session),
          live: {
            ...state.live,
            session: "COMPLETE",
            busy: false,
            errorHe: null,
            protocolHe: session.protocolHe ?? state.live.protocolHe,
            latestId: session.id,
            scanThisLink: session.id,
          },
        }));
        return null;
      },

      sampleResources: () => {
        const snap: ResourceSnapshot = {
          at: new Date().toISOString(),
          ...heapMb(),
          scans: get().sessions.length,
          vehicles: get().vehicles.length,
          noteHe:
            "מדידת דפדפן בלבד. זה לא RAM של טלפון Android, ולא הבטחת סוללה.",
        };
        set((state) => ({ resources: [snap, ...state.resources].slice(0, 12) }));
      },
    }),
);

if (typeof window !== "undefined") {
  useGarage.subscribe((state, prev) => {
    if (!state.hydrated) return;
    if (
      state.vehicles === prev.vehicles &&
      state.sessions === prev.sessions &&
      state.selectedId === prev.selectedId &&
      state.resources === prev.resources
    ) {
      return;
    }
    browserRepository().save({
      vehicles: state.vehicles,
      sessions: state.sessions,
      selectedId: state.selectedId,
      resources: state.resources,
    });
  });
}
