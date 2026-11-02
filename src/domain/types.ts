export type IgnitionState = "OFF" | "ACC" | "ON" | "STARTING" | "RUNNING";

export type SessionState =
  | "DISCONNECTED"
  | "CONNECTING"
  | "CONNECTED"
  | "SCANNING"
  | "COMPLETE"
  | "DISCONNECTING"
  | "ERROR";

export type EcuId = "engine" | "abs" | "bcm" | "srs";

export type EcuAccess = "checked" | "inaccessible" | "not_scanned" | "comm_error";

export type Fuel = "gasoline" | "diesel" | "hybrid" | "electric";

export type Posture = "idle" | "straight" | "turn" | "brake";

export type FaultId =
  | "low_battery"
  | "high_coolant"
  | "misfire"
  | "o2_anomaly"
  | "wheel_speed_mismatch"
  | "steering_angle_anomaly"
  | "lost_ecu"
  | "comm_timeout"
  | "intermittent_sensor"
  | "multiple_dtcs";

export type DtcStatus = "stored" | "pending" | "permanent";

export type Severity = "info" | "low" | "medium" | "high" | "critical";

export type Confidence = "insufficient" | "low" | "medium" | "high";

export type PidQuality = "valid" | "unavailable";

export type AdapterKind = "simulated" | "ble" | "classic" | "wifi" | "serial";

export interface VehicleSim {
  ignition: IgnitionState;
  posture: Posture;
  faults: FaultId[];
  lostEcu: EcuId | null;
}

export interface Vehicle {
  id: string;
  nickname: string;
  make: string;
  model: string;
  year: number;
  fuel: Fuel;
  /** User-entered. Never treated as a value read from the car. */
  vin: string;
  createdAt: string;
  sim: VehicleSim;
}

export interface Dtc {
  code: string;
  status: DtcStatus;
  ecuId: EcuId;
  descriptionHe: string;
  severity: Severity;
  /** When this code was read. Not when the ECU first stored it. */
  readAt: string;
}

export interface PidSample {
  id: string;
  nameHe: string;
  value: number | null;
  unit: string;
  ecuId: EcuId;
  quality: PidQuality;
  /** Standard OBD-II mode 01 signal. False = simulated manufacturer channel. */
  genericObd: boolean;
  /** False when this vehicle is not expected to expose the signal. */
  expected: boolean;
  readAt: string;
}

export interface Monitor {
  id: string;
  nameHe: string;
  status: "complete" | "incomplete" | "unsupported";
}

export interface EcuReport {
  id: EcuId;
  access: EcuAccess;
  reasonHe: string;
  protocolHe: string | null;
}

export interface SystemGap {
  id: string;
  nameHe: string;
  reasonHe: string;
  /** Structured coverage. A gap is never implied healthy or faulty. */
  coverage: "not_scanned";
}

export interface Finding {
  id: string;
  ruleId: string;
  code: string | null;
  titleHe: string;
  summaryHe: string;
  severity: Severity;
  confidence: Confidence;
  /** What the confidence applies to. Never a hidden part replacement. */
  claimHe: string;
  evidence: string[];
  conditions: string[];
  nextStepHe: string;
  source: "rule";
  ecuId: EcuId | null;
}

export interface ScanFacts {
  readAt: string;
  fuel: Fuel;
  protocolHe: string | null;
  vinNoteHe: string;
  ecus: EcuReport[];
  gaps: SystemGap[];
  dtcs: Dtc[];
  pids: PidSample[];
  readiness: Monitor[];
  engineTimeout: boolean;
}

export interface ConnectResult {
  ok: boolean;
  protocolHe: string | null;
  errorHe: string | null;
}

export interface ScanSession {
  id: string;
  vehicleId: string;
  kind: "quick" | "post-clear";
  adapterKind: AdapterKind;
  adapterLabelHe: string;
  startedAt: string;
  finishedAt: string;
  durationMs: number;
  ignition: IgnitionState;
  posture: Posture;
  protocolHe: string | null;
  vinNoteHe: string;
  ecus: EcuReport[];
  gaps: SystemGap[];
  dtcs: Dtc[];
  pids: PidSample[];
  readiness: Monitor[];
  findings: Finding[];
  /** Lab metadata only. Rules never read this. */
  labFaults: FaultId[];
  labLostEcu: EcuId | null;
  errorHe: string | null;
  /** Codes cleared right before this verification scan. Absent on a normal scan. */
  clearedCodes?: string[];
  /** The scan whose codes were cleared. */
  clearedFromSessionId?: string | null;
}
