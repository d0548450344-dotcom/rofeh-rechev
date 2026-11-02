import type { Vehicle } from "../domain/types.ts";
import { createSimulatedAdapter } from "../simulation/adapter.ts";
import type { ObdTransport } from "../transport/obd-transport.ts";

/**
 * Composition root for the link.
 * Quick scan and the rules never import a concrete adapter.
 * A later BLE or Wi-Fi build replaces this function only.
 */
export function openVehicleLink(vehicle: Vehicle): ObdTransport {
  return createSimulatedAdapter(vehicle);
}
