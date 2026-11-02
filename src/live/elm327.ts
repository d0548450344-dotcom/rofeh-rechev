import { parseElm, elmLines, codePairs, type ElmErrorKind } from "./elm-parser.ts";

/** A byte pipe to the adapter: Web Bluetooth, Web Serial, or a test fake. */
export interface ByteLink {
  readonly kind: "ble" | "serial";
  readonly name: string;
  write(bytes: Uint8Array): Promise<void>;
  /** Registers the single receiver for incoming bytes. */
  onData(receiver: (bytes: Uint8Array) => void): void;
  /** Called once if the link drops on its own. */
  onLost(listener: () => void): void;
  close(): Promise<void>;
}

export interface ElmIdentity {
  banner: string | null;
  protocolNumber: string | null;
  supportedPids: Set<number>;
}

export type ElmReply =
  | { ok: true; service: number; payload: number[] }
  | { ok: false; reason: "no_data" | "unsupported" | "negative" | "unexpected" | "timeout" | "lost" | "adapter"; detail: string };

export type ElmLinkReason = "no_vehicle" | "timeout" | "lost" | "not_elm" | "adapter";

export class ElmLinkError extends Error {
  readonly reason: ElmLinkReason;
  readonly adapterError: ElmErrorKind | null;

  constructor(reason: ElmLinkReason, message: string, adapterError: ElmErrorKind | null = null) {
    super(message);
    this.reason = reason;
    this.adapterError = adapterError;
  }
}

/** Services request() will put on the bus. 0x04 (clear) only goes through clearCodes(). */
export const READ_ONLY_SERVICES: ReadonlySet<number> = new Set([0x01, 0x02, 0x03, 0x07, 0x09, 0x0a]);

/**
 * Services that write to a controller: flashing / tuning, coding, security access,
 * routines, resets, session changes and UDS code clearing. Never sent by this app.
 */
export const WRITE_AND_FLASH_SERVICES: ReadonlySet<number> = new Set([
  0x10, 0x11, 0x14, 0x27, 0x28, 0x2e, 0x2f, 0x31, 0x34, 0x35, 0x36, 0x37, 0x3b, 0x3d, 0x85,
]);

/** Proof that the user acknowledged both clear-codes warnings. */
export interface ClearCodesConsent {
  confirmedAt: string;
  understoodCodesReturn: true;
  understoodReadinessReset: true;
}

export type ClearReply = { ok: true } | { ok: false; reason: "engine_running" | "refused" | "unconfirmed" | "lost" };

export const INIT_COMMANDS = ["ATZ", "ATE0", "ATL0", "ATS1", "ATH0", "ATAT1", "ATSP0"] as const;
const REQUIRED = new Set(["ATE0", "ATH0", "ATSP0"]);

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Real OBD-II over an ELM327-compatible adapter. Read-only: only the AT setup
 * commands above and the read services in READ_ONLY_SERVICES are ever sent, and an
 * empty line (which makes an ELM327 repeat its previous command) is never sent.
 */
export class Elm327Session {
  private buffer = "";
  private waiter: (() => void) | null = null;
  private lost = false;
  private queue: Promise<unknown> = Promise.resolve();
  identity: ElmIdentity | null = null;
  readonly link: ByteLink;
  private readonly requestTimeoutMs: number;

  constructor(link: ByteLink, requestTimeoutMs = 6_000) {
    this.link = link;
    this.requestTimeoutMs = requestTimeoutMs;
    link.onData((bytes) => {
      for (const byte of bytes) if (byte !== 0) this.buffer += String.fromCharCode(byte);
      this.waiter?.();
    });
    link.onLost(() => {
      this.lost = true;
      this.waiter?.();
    });
  }

  get connected(): boolean {
    return !this.lost && this.identity !== null;
  }

  /** Initialises the adapter and checks that a car answers. Throws ElmLinkError. */
  async init(): Promise<ElmIdentity> {
    await sleep(300);
    this.buffer = "";
    let banner: string | null = null;
    for (const command of INIT_COMMANDS) {
      const text = await this.exchange(command, command === "ATZ" ? 4_000 : 2_000);
      const lines = elmLines(text, command);
      if (command === "ATZ") {
        banner = lines.find((line) => line.includes("ELM") || line.includes("OBD")) ?? null;
        await sleep(300);
        continue;
      }
      if (!lines.includes("OK") && REQUIRED.has(command)) {
        throw new ElmLinkError("not_elm", `init:${command}`);
      }
    }
    const search = parseElm(await this.exchange("0100", 15_000), "0100");
    if (search.kind === "adapter_error") {
      throw new ElmLinkError("no_vehicle", search.word, search.error);
    }
    if (search.kind === "no_data") throw new ElmLinkError("no_vehicle", "no-data", "no_vehicle");
    const supportedPids = new Set<number>();
    const reply = search.messages.find((m) => m.length >= 6 && m[0] === 0x41 && m[1] === 0x00);
    if (reply) {
      for (let byteIndex = 0; byteIndex < 4; byteIndex++) {
        for (let bit = 0; bit < 8; bit++) {
          if ((reply[2 + byteIndex] >> (7 - bit)) & 1) supportedPids.add(byteIndex * 8 + bit + 1);
        }
      }
    }
    const dpn = elmLines(await this.exchange("ATDPN", 2_000), "ATDPN")[0] ?? "";
    const protocolNumber = dpn.replace(/^A/, "").match(/[0-9A-C]/)?.[0] ?? null;
    this.identity = { banner, protocolNumber, supportedPids };
    return this.identity;
  }

  /** One read request. Never throws for car-side answers; throws only if the link is gone. */
  request(service: number, payload: number[] = []): Promise<ElmReply> {
    const run = async (): Promise<ElmReply> => {
      if (!READ_ONLY_SERVICES.has(service)) {
        return { ok: false, reason: "unsupported", detail: `service-${service}` };
      }
      if (this.lost) return { ok: false, reason: "lost", detail: "link" };
      const command = [service, ...payload].map((b) => b.toString(16).toUpperCase().padStart(2, "0")).join("");
      let text: string;
      try {
        text = await this.exchange(command, this.requestTimeoutMs);
      } catch (error) {
        if (error instanceof ElmLinkError && error.reason === "lost") {
          return { ok: false, reason: "lost", detail: command };
        }
        return { ok: false, reason: "timeout", detail: command };
      }
      const parsed = parseElm(text, command);
      if (parsed.kind === "no_data") return { ok: false, reason: "no_data", detail: command };
      if (parsed.kind === "adapter_error") {
        if (parsed.error === "no_vehicle") return { ok: false, reason: "no_data", detail: parsed.word };
        if (parsed.error === "unknown_command") return { ok: false, reason: "unsupported", detail: parsed.word };
        if (parsed.error === "stopped") return { ok: false, reason: "timeout", detail: parsed.word };
        return { ok: false, reason: "adapter", detail: parsed.word };
      }
      const positive = (service + 0x40) & 0xff;
      const mine = parsed.messages.filter((m) => m.length > 0 && m[0] === positive);
      if (mine.length === 0) {
        const negative = parsed.messages.some((m) => m.length >= 2 && m[0] === 0x7f && m[1] === service);
        return { ok: false, reason: negative ? "negative" : "unexpected", detail: command };
      }
      if (service === 0x03 || service === 0x07 || service === 0x0a) {
        return { ok: true, service: positive, payload: codePairs(mine, positive) };
      }
      const wanted = payload[0];
      const match = mine.find((m) => wanted === undefined || (m.length > 1 && m[1] === wanted));
      if (!match) return { ok: false, reason: "unexpected", detail: command };
      return { ok: true, service: positive, payload: match.slice(1) };
    };
    const next = this.queue.then(run, run);
    this.queue = next.catch(() => undefined);
    return next;
  }

  /**
   * Mode 04: clears stored/pending codes, freeze frame and readiness. The only write this
   * driver can send. Most ECUs refuse it while the engine runs (negative answer 0x22).
   */
  clearCodes(consent: ClearCodesConsent): Promise<ClearReply> {
    const run = async (): Promise<ClearReply> => {
      if (!consent.understoodCodesReturn || !consent.understoodReadinessReset) return { ok: false, reason: "refused" };
      if (this.lost) return { ok: false, reason: "lost" };
      let text: string;
      try {
        text = await this.exchange("04", 10_000);
      } catch (error) {
        return { ok: false, reason: error instanceof ElmLinkError && error.reason === "lost" ? "lost" : "unconfirmed" };
      }
      const parsed = parseElm(text, "04");
      if (parsed.kind !== "messages") return { ok: false, reason: "unconfirmed" };
      if (parsed.messages.some((m) => m[0] === 0x44)) return { ok: true };
      const negative = parsed.messages.find((m) => m.length >= 3 && m[0] === 0x7f && m[1] === 0x04);
      if (negative) return { ok: false, reason: negative[2] === 0x22 ? "engine_running" : "refused" };
      return { ok: false, reason: "unconfirmed" };
    };
    const next = this.queue.then(run, run);
    this.queue = next.catch(() => undefined);
    return next;
  }

  async close(): Promise<void> {
    if (!this.lost && this.identity) {
      try {
        await this.exchange("ATPC", 500);
      } catch {
        // closing anyway
      }
    }
    this.identity = null;
    await this.link.close().catch(() => undefined);
  }

  private async exchange(command: string, timeoutMs: number): Promise<string> {
    if (command.length === 0) throw new Error("an empty line makes the ELM327 repeat its last command");
    if (this.lost) throw new ElmLinkError("lost", "link");
    this.buffer = "";
    try {
      await this.link.write(new TextEncoder().encode(`${command}\r`));
    } catch {
      this.lost = true;
      throw new ElmLinkError("lost", "write");
    }
    const deadline = Date.now() + timeoutMs;
    while (!this.buffer.includes(">")) {
      if (this.lost) throw new ElmLinkError("lost", "read");
      const left = deadline - Date.now();
      if (left <= 0) throw new ElmLinkError("timeout", command);
      await new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, Math.min(left, 250));
        this.waiter = () => {
          clearTimeout(timer);
          resolve();
        };
      });
      this.waiter = null;
    }
    const text = this.buffer;
    this.buffer = "";
    return text;
  }
}
