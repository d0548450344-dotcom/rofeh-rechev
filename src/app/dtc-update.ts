import { DTC_SOURCE_URL, installDtcOverlay, parseDtcSource } from "../domain/catalog.ts";

/*
 * Keeps the fault-code texts current in the browser. Installs the last good download
 * from this browser's storage, then fetches a fresh copy at most once a day.
 * Every failure is silent: the built-in table is always there.
 */
const CACHE_KEY = "rofeh-rechev.dtc";
const CHECKED_KEY = "rofeh-rechev.dtc.checkedAt";
const DAY_MS = 24 * 60 * 60 * 1000;

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // storage full or blocked: the in-memory update still applies
  }
}

export async function refreshDtcDatabase(now = Date.now()): Promise<void> {
  if (typeof window === "undefined") return;
  const cached = read(CACHE_KEY);
  const fromCache = cached ? parseDtcSource(cached) : null;
  if (fromCache) installDtcOverlay(fromCache);

  if (now - Number(read(CHECKED_KEY) ?? 0) < DAY_MS) return;
  try {
    const response = await fetch(DTC_SOURCE_URL, { cache: "no-cache" });
    if (!response.ok) return;
    const text = await response.text();
    const parsed = parseDtcSource(text);
    if (!parsed) return;
    installDtcOverlay(parsed);
    write(CACHE_KEY, text);
    write(CHECKED_KEY, String(now));
  } catch {
    // offline: keep what we have
  }
}
