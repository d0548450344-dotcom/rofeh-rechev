/**
 * Turns the text an ELM327 prints for one OBD request into byte messages.
 * Mirrors android/protocol/.../ElmResponseParser.kt; keep the two in step.
 *
 * Assumes headers off (ATH0) and CAN auto-formatting on, so every message starts
 * at the service byte. Handles single frames, ISO-TP multi-frame output
 * ("014" / "0: .." / "1: .."), several ECUs answering, and status words.
 */

export type ElmErrorKind =
  | "no_vehicle"
  | "bus"
  | "corrupt"
  | "stopped"
  | "adapter_reset"
  | "unknown_command";

export type ElmParse =
  | { kind: "messages"; messages: number[][] }
  | { kind: "no_data" }
  | { kind: "adapter_error"; error: ElmErrorKind; word: string };

const ERRORS: Array<[string, ElmErrorKind]> = [
  ["UNABLE TO CONNECT", "no_vehicle"],
  ["CAN ERROR", "bus"],
  ["BUS ERROR", "bus"],
  ["BUS BUSY", "bus"],
  ["FB ERROR", "bus"],
  ["DATA ERROR", "corrupt"],
  ["RX ERROR", "corrupt"],
  ["BUFFER FULL", "corrupt"],
  ["STOPPED", "stopped"],
  ["LV RESET", "adapter_reset"],
  ["ACT ALERT", "adapter_reset"],
];

const LENGTH_LINE = /^[0-9A-F]{3}$/;
const FRAME_LINE = /^([0-9A-F]):(.*)$/;
const HEX_ONLY = /^[0-9A-F]+$/;

export function elmLines(raw: string, sentCommand?: string): string[] {
  const echo = sentCommand?.replace(/ /g, "").toUpperCase();
  return raw
    .replace(/\u0000/g, "")
    .replace(/>/g, "")
    .split(/[\r\n]/)
    .map((line) => line.trim().toUpperCase())
    .filter((line) => line.length > 0)
    .filter((line) => !(echo !== undefined && line.replace(/ /g, "") === echo))
    .filter((line) => !line.startsWith("SEARCHING"))
    .filter((line) => !(line.startsWith("BUS INIT:") && line.endsWith("OK")));
}

export function classifyElmLine(line: string): ElmErrorKind | null {
  if (line.startsWith("BUS INIT")) return line.includes("ERROR") ? "no_vehicle" : null;
  if (line === "?") return "unknown_command";
  if (/^ERR\d*$/.test(line)) return "bus";
  for (const [word, kind] of ERRORS) {
    if (line.includes(word)) return kind;
  }
  return null;
}

function hexBytes(compact: string): number[] | null {
  if (compact.length === 0 || compact.length % 2 !== 0 || !HEX_ONLY.test(compact)) return null;
  const out: number[] = [];
  for (let i = 0; i < compact.length; i += 2) out.push(parseInt(compact.slice(i, i + 2), 16));
  return out;
}

export function parseElm(raw: string, sentCommand?: string): ElmParse {
  const lines = elmLines(raw, sentCommand);
  if (lines.length === 0) return { kind: "no_data" };
  for (const line of lines) {
    if (line === "NO DATA") continue;
    const error = classifyElmLine(line);
    if (error) return { kind: "adapter_error", error, word: line };
  }

  const messages: number[][] = [];
  let pending: number[] | null = null;
  let expected = 0;
  const flush = () => {
    if (pending && pending.length > 0) {
      messages.push(expected > 0 ? pending.slice(0, expected) : pending);
    }
    pending = null;
    expected = 0;
  };

  for (const line of lines) {
    if (line === "NO DATA") continue;
    const compact = line.replace(/ /g, "");
    if (LENGTH_LINE.test(compact) && !line.includes(" ")) {
      flush();
      expected = parseInt(compact, 16);
      pending = [];
      continue;
    }
    const frame = FRAME_LINE.exec(compact);
    if (frame) {
      const bytes = hexBytes(frame[2]);
      if (!bytes) continue;
      if (!pending) pending = [];
      pending.push(...bytes);
      if (expected > 0 && pending.length >= expected) flush();
      continue;
    }
    if (HEX_ONLY.test(compact)) {
      flush();
      const bytes = hexBytes(compact);
      if (bytes) messages.push(bytes);
    }
  }
  flush();
  return messages.length === 0 ? { kind: "no_data" } : { kind: "messages", messages };
}

/**
 * Joins the trouble-code bytes of every ECU message for a mode 03/07/0A reply.
 * CAN replies carry a count byte after the service byte (odd data length).
 */
export function codePairs(messages: number[][], positiveService: number): number[] {
  const out: number[] = [];
  for (const message of messages) {
    if (message.length === 0 || message[0] !== positiveService) continue;
    let data = message.slice(1);
    if (data.length % 2 === 1) data = data.slice(1);
    out.push(...data);
  }
  return out;
}

export function formatDtc(hi: number, lo: number): string {
  const type = ["P", "C", "B", "U"][(hi >> 6) & 0x3];
  const hex = (n: number) => n.toString(16).toUpperCase();
  return `${type}${(hi >> 4) & 0x3}${hex(hi & 0xf)}${hex((lo >> 4) & 0xf)}${hex(lo & 0xf)}`;
}

export function busLabel(protocolNumber: string | null): string {
  switch (protocolNumber?.toUpperCase()) {
    case "1":
      return "SAE J1850 PWM";
    case "2":
      return "SAE J1850 VPW";
    case "3":
      return "ISO 9141-2";
    case "4":
      return "ISO 14230-4 KWP · אתחול איטי";
    case "5":
      return "ISO 14230-4 KWP · אתחול מהיר";
    case "6":
      return "ISO 15765-4 CAN · 11-bit · 500 kbit/s";
    case "7":
      return "ISO 15765-4 CAN · 29-bit · 500 kbit/s";
    case "8":
      return "ISO 15765-4 CAN · 11-bit · 250 kbit/s";
    case "9":
      return "ISO 15765-4 CAN · 29-bit · 250 kbit/s";
    case "A":
      return "SAE J1939 CAN";
    case "B":
    case "C":
      return "CAN מוגדר משתמש";
    default:
      return "OBD-II · פרוטוקול לא זוהה";
  }
}
