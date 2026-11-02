import type { IgnitionState, SessionState } from "./types.ts";

const IGNITION_NEXT: Record<IgnitionState, readonly IgnitionState[]> = {
  // Happy path is OFF → ACC → ON → STARTING → RUNNING → OFF.
  // Cancel, failed start, and stall are real key states, not shortcuts.
  OFF: ["ACC"],
  ACC: ["OFF", "ON"],
  ON: ["OFF", "ACC", "STARTING"],
  STARTING: ["RUNNING", "ON", "OFF"],
  RUNNING: ["OFF", "ON"],
};

const SESSION_NEXT: Record<SessionState, readonly SessionState[]> = {
  DISCONNECTED: ["CONNECTING"],
  CONNECTING: ["CONNECTED", "ERROR"],
  CONNECTED: ["SCANNING", "DISCONNECTING", "ERROR"],
  SCANNING: ["COMPLETE", "ERROR", "DISCONNECTING"],
  COMPLETE: ["SCANNING", "DISCONNECTING"],
  DISCONNECTING: ["DISCONNECTED"],
  ERROR: ["DISCONNECTED", "CONNECTING"],
};

export function canIgnition(from: IgnitionState, to: IgnitionState): boolean {
  return IGNITION_NEXT[from].includes(to);
}

export function ignitionAllowsObd(state: IgnitionState): boolean {
  return state === "ON" || state === "STARTING" || state === "RUNNING";
}

export function canSession(from: SessionState, to: SessionState): boolean {
  return SESSION_NEXT[from].includes(to);
}

/**
 * Legal steps to drop the link. A single jump such as CONNECTED → DISCONNECTED
 * is not an edge; the path is CONNECTED → DISCONNECTING → DISCONNECTED.
 * CONNECTING has no disconnect edge, so the caller must wait or fail the attempt.
 */
export function disconnectPath(from: SessionState): SessionState[] | null {
  if (from === "DISCONNECTED") return [];
  if (from === "DISCONNECTING") return ["DISCONNECTED"];
  if (from === "ERROR") return canSession(from, "DISCONNECTED") ? ["DISCONNECTED"] : null;
  if (canSession(from, "DISCONNECTING")) return ["DISCONNECTING", "DISCONNECTED"];
  return null;
}

export function releasedSession(from: SessionState): SessionState | null {
  const path = disconnectPath(from);
  if (!path) return null;
  return path.length === 0 ? "DISCONNECTED" : path[path.length - 1];
}

export function ignitionMoves(from: IgnitionState): { to: IgnitionState; label: string }[] {
  switch (from) {
    case "OFF":
      return [{ to: "ACC", label: "אביזרים" }];
    case "ACC":
      return [
        { to: "ON", label: "הצתה" },
        { to: "OFF", label: "כיבוי" },
      ];
    case "ON":
      return [
        { to: "STARTING", label: "התנעה" },
        { to: "ACC", label: "חזרה לאביזרים" },
        { to: "OFF", label: "כיבוי" },
      ];
    case "STARTING":
      return [
        { to: "RUNNING", label: "המנוע נתפס" },
        { to: "ON", label: "התנעה נכשלה" },
        { to: "OFF", label: "ביטול" },
      ];
    case "RUNNING":
      return [
        { to: "OFF", label: "כיבוי" },
        { to: "ON", label: "המנוע כבה, ההצתה דולקת" },
      ];
  }
}
