import type { AdapterKind, ConnectResult, ScanFacts } from "../domain/types.ts";

/**
 * Read-only link contract.
 * A scan passes no vehicle and no fault list: the adapter already owns the link.
 * Simulated, BLE, classic, and Wi-Fi adapters implement this same surface.
 * There is intentionally no write, clear, coding, security-access, or flash method.
 */
export interface ObdTransport {
  readonly kind: AdapterKind;
  readonly readOnly: true;
  probe(): ConnectResult;
  readFacts(readAt: string): ScanFacts;
}
