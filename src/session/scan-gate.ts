import type { SessionState } from "../domain/types.ts";

/** A scan is saved only if this attempt is still the active one and still scanning. */
export function shouldCommitScan(args: {
  epochAtStart: number;
  epochNow: number;
  session: SessionState;
}): boolean {
  return args.epochNow === args.epochAtStart && args.session === "SCANNING";
}
