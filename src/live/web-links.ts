import type { ByteLink } from "./elm327.ts";

/*
 * Browser byte links to an ELM327 adapter.
 * - Web Bluetooth: BLE adapters (Chrome / Edge on Android, Windows, macOS; not iPhone).
 * - Web Serial: USB adapters, or Bluetooth Classic adapters paired as a COM port
 *   (Chrome / Edge on a computer).
 * A browser cannot open raw TCP sockets, so Wi-Fi adapters need the Android app.
 */

// Minimal typings: these APIs are not in TypeScript's DOM lib yet.
interface BleCharacteristic {
  properties: { notify: boolean; indicate: boolean; write: boolean; writeWithoutResponse: boolean };
  startNotifications(): Promise<BleCharacteristic>;
  addEventListener(type: "characteristicvaluechanged", listener: (event: Event) => void): void;
  writeValueWithResponse?(value: BufferSource): Promise<void>;
  writeValueWithoutResponse?(value: BufferSource): Promise<void>;
  writeValue(value: BufferSource): Promise<void>;
  value?: DataView;
}
interface BleService {
  getCharacteristic(uuid: string | number): Promise<BleCharacteristic>;
  getCharacteristics(): Promise<BleCharacteristic[]>;
}
interface BleServer {
  connected: boolean;
  getPrimaryService(uuid: string | number): Promise<BleService>;
  getPrimaryServices(): Promise<BleService[]>;
  disconnect(): void;
}
interface BleDevice extends EventTarget {
  name?: string;
  gatt?: { connect(): Promise<BleServer> };
}
interface BluetoothApi {
  requestDevice(options: unknown): Promise<BleDevice>;
}
interface SerialPortLike {
  open(options: { baudRate: number }): Promise<void>;
  close(): Promise<void>;
  readable: ReadableStream<Uint8Array> | null;
  writable: WritableStream<Uint8Array> | null;
  getInfo?(): { usbVendorId?: number; usbProductId?: number };
}
interface SerialApi {
  requestPort(options?: unknown): Promise<SerialPortLike>;
}

function bluetoothApi(): BluetoothApi | null {
  if (typeof navigator === "undefined") return null;
  return (navigator as unknown as { bluetooth?: BluetoothApi }).bluetooth ?? null;
}

function serialApi(): SerialApi | null {
  if (typeof navigator === "undefined") return null;
  return (navigator as unknown as { serial?: SerialApi }).serial ?? null;
}

export function webBluetoothSupported(): boolean {
  return bluetoothApi() !== null;
}

export function webSerialSupported(): boolean {
  return serialApi() !== null;
}

const uuid16 = (n: number) => `0000${n.toString(16).padStart(4, "0")}-0000-1000-8000-00805f9b34fb`;

/** Known (service, notify, write) layouts of BLE OBD adapters, most common first. */
const LAYOUTS: Array<[string, string, string]> = [
  [uuid16(0xfff0), uuid16(0xfff1), uuid16(0xfff2)],
  [uuid16(0xffe0), uuid16(0xffe1), uuid16(0xffe1)],
  [uuid16(0x18f0), uuid16(0x2af0), uuid16(0x2af1)],
  [
    "e7810a71-73ae-499d-8c15-faa9aef0c3f2",
    "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f",
    "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f",
  ],
];
const NAME_PREFIXES = ["OBD", "ELM", "V-LINK", "VLINK", "Vgate", "IOS-Vlink", "Veepeak", "KONNWEI", "vLinker", "LELink"];

/**
 * Opens the browser's Bluetooth picker. Must be called directly from a click handler,
 * before any other await, or the browser blocks it.
 */
export async function openBleLink(): Promise<ByteLink> {
  const api = bluetoothApi();
  if (!api) throw new Error("web-bluetooth-unsupported");
  const services = LAYOUTS.map(([service]) => service);
  const device = await api.requestDevice({
    filters: [
      ...services.map((service) => ({ services: [service] })),
      ...NAME_PREFIXES.map((namePrefix) => ({ namePrefix })),
    ],
    optionalServices: services,
  });
  const server = await device.gatt?.connect();
  if (!server) throw new Error("gatt-unavailable");

  let notify: BleCharacteristic | null = null;
  let write: BleCharacteristic | null = null;
  for (const [service, n, w] of LAYOUTS) {
    try {
      const s = await server.getPrimaryService(service);
      notify = await s.getCharacteristic(n);
      write = await s.getCharacteristic(w);
      break;
    } catch {
      notify = null;
      write = null;
    }
  }
  if (!notify || !write) {
    for (const s of await server.getPrimaryServices().catch(() => [] as BleService[])) {
      const chars = await s.getCharacteristics().catch(() => [] as BleCharacteristic[]);
      const n = chars.find((c) => c.properties.notify || c.properties.indicate);
      const w = chars.find((c) => c.properties.write || c.properties.writeWithoutResponse);
      if (n && w) {
        notify = n;
        write = w;
        break;
      }
    }
  }
  if (!notify || !write) {
    server.disconnect();
    throw new Error("no-obd-service");
  }

  let receiver: (bytes: Uint8Array) => void = () => undefined;
  let lost: () => void = () => undefined;
  let closing = false;
  notify.addEventListener("characteristicvaluechanged", (event) => {
    const view = (event.target as unknown as BleCharacteristic).value;
    if (view) receiver(new Uint8Array(view.buffer.slice(view.byteOffset, view.byteOffset + view.byteLength)));
  });
  await notify.startNotifications();
  device.addEventListener("gattserverdisconnected", () => {
    if (!closing) lost();
  });

  const writer = write;
  return {
    kind: "ble",
    name: device.name ?? "BLE OBD",
    async write(bytes) {
      for (let i = 0; i < bytes.length; i += 20) {
        const chunk = bytes.slice(i, i + 20);
        if (writer.properties.writeWithoutResponse && writer.writeValueWithoutResponse) {
          await writer.writeValueWithoutResponse(chunk);
        } else if (writer.writeValueWithResponse) {
          await writer.writeValueWithResponse(chunk);
        } else {
          await writer.writeValue(chunk);
        }
      }
    },
    onData(r) {
      receiver = r;
    },
    onLost(l) {
      lost = l;
    },
    async close() {
      closing = true;
      if (server.connected) server.disconnect();
    },
  };
}

/** Opens the browser's serial-port picker. Same click-handler rule as Bluetooth. */
export async function openSerialLink(baudRate = 38_400): Promise<ByteLink> {
  const api = serialApi();
  if (!api) throw new Error("web-serial-unsupported");
  const port = await api.requestPort();
  await port.open({ baudRate });
  const readable = port.readable;
  const writable = port.writable;
  if (!readable || !writable) {
    await port.close().catch(() => undefined);
    throw new Error("serial-unavailable");
  }
  const reader = readable.getReader();
  const writer = writable.getWriter();
  let receiver: (bytes: Uint8Array) => void = () => undefined;
  let lost: () => void = () => undefined;
  let closing = false;

  void (async () => {
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        if (value) receiver(value);
      }
    } catch {
      // fall through to lost
    }
    if (!closing) lost();
  })();

  return {
    kind: "serial",
    name: "USB / Serial",
    async write(bytes) {
      await writer.write(bytes);
    },
    onData(r) {
      receiver = r;
    },
    onLost(l) {
      lost = l;
    },
    async close() {
      closing = true;
      await reader.cancel().catch(() => undefined);
      reader.releaseLock();
      writer.releaseLock();
      await port.close().catch(() => undefined);
    },
  };
}
