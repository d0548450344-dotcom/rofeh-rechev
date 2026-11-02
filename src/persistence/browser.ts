import type { GarageRepository } from "./repository.ts";
import { webStorageRepository } from "./repository.ts";
import { STORAGE_KEY } from "./record.ts";

export function browserRepository(): GarageRepository {
  if (typeof localStorage === "undefined") {
    throw new Error("browserRepository requires localStorage");
  }
  return webStorageRepository(localStorage, STORAGE_KEY);
}
