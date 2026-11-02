import { useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { CarFront, Plus, Trash2 } from "lucide-react";
import { ECU_META, FAULT_CATALOG, FUEL_LABEL, IGNITION_LABEL } from "@/domain/catalog";
import type { Fuel } from "@/domain/types";
import { Empty, Panel, Pill } from "@/components/ui";
import { useGarage } from "@/store/garage";

const FUELS: Fuel[] = ["gasoline", "diesel", "hybrid", "electric"];

export function GarageHome() {
  const vehicles = useGarage((state) => state.vehicles);
  const sessions = useGarage((state) => state.sessions);
  const selectedId = useGarage((state) => state.selectedId);
  const addVehicle = useGarage((state) => state.addVehicle);
  const selectVehicle = useGarage((state) => state.selectVehicle);
  const removeVehicle = useGarage((state) => state.removeVehicle);
  const navigate = useNavigate();

  const [nickname, setNickname] = useState("הרכב שלי");
  const [make, setMake] = useState("טויוטה");
  const [model, setModel] = useState("קורולה");
  const [year, setYear] = useState(2018);
  const [fuel, setFuel] = useState<Fuel>("gasoline");
  const [vin, setVin] = useState("");

  function create() {
    if (!nickname.trim() || !make.trim() || !model.trim()) return;
    const yearValue = Number.isFinite(year) ? Math.min(2035, Math.max(1985, year)) : 2018;
    addVehicle({
      nickname: nickname.trim(),
      make: make.trim(),
      model: model.trim(),
      year: yearValue,
      fuel,
      vin: vin.trim().toUpperCase(),
    });
    void navigate({ to: "/bench" });
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[1.15fr_0.85fr]">
      <Panel
        title="כלי הרכב"
        hint="הפרופיל הוא מה שהקלדת. הוא לא נקרא מהרכב. אחרי רענון הנתונים נשארים במכשיר הזה."
      >
        {vehicles.length === 0 ? (
          <Empty
            title="עדיין אין רכב"
            body="אפשר לפתוח את המסך בלי רכב. יוצרים פרופיל ואז עוברים לספסל הסימולציה."
          />
        ) : (
          <ul className="space-y-2">
            {vehicles.map((vehicle) => {
              const scans = sessions.filter((session) => session.vehicleId === vehicle.id);
              const active = vehicle.id === selectedId;
              return (
                <li key={vehicle.id}>
                  <button
                    type="button"
                    onClick={() => {
                      selectVehicle(vehicle.id);
                      void navigate({ to: "/bench" });
                    }}
                    className={`flex w-full items-start gap-3 rounded-2xl border px-3 py-3 text-start ${
                      active ? "border-primary bg-bg-raised" : "border-line bg-bg-raised/60"
                    }`}
                  >
                    <span className="mt-0.5 grid size-10 shrink-0 place-items-center rounded-xl bg-surface-2 text-primary">
                      <CarFront className="size-5" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block font-medium">{vehicle.nickname}</span>
                      <span className="mt-0.5 block text-sm text-muted">
                        {vehicle.make} {vehicle.model} · {vehicle.year} · {FUEL_LABEL[vehicle.fuel]}
                      </span>
                      <span className="mt-2 flex flex-wrap gap-1.5">
                        <Pill>{IGNITION_LABEL[vehicle.sim.ignition]}</Pill>
                        <Pill tone={scans.length ? "info" : "neutral"}>
                          {scans.length} סריקות
                        </Pill>
                        {vehicle.sim.faults.length > 0 ? (
                          <Pill tone="warn">{vehicle.sim.faults.length} הזרקות מעבדה</Pill>
                        ) : null}
                      </span>
                    </span>
                    <span
                      role="button"
                      tabIndex={0}
                      aria-label="מחיקת רכב"
                      className="grid size-11 place-items-center rounded-xl text-muted hover:bg-surface-2 hover:text-bad"
                      onClick={(event) => {
                        event.stopPropagation();
                        removeVehicle(vehicle.id);
                      }}
                      onKeyDown={(event) => {
                        if (event.key === "Enter" || event.key === " ") {
                          event.preventDefault();
                          event.stopPropagation();
                          removeVehicle(vehicle.id);
                        }
                      }}
                    >
                      <Trash2 className="size-4" />
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </Panel>

      <Panel title="רכב חדש" hint="VIN אופציונלי. אם ממלאים אותו, הוא נשאר הערה ידנית ולא אימות.">
        <form
          className="grid gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            create();
          }}
        >
          <Field label="כינוי">
            <input value={nickname} onChange={(event) => setNickname(event.target.value)} className={inputClass} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="יצרן">
              <input value={make} onChange={(event) => setMake(event.target.value)} className={inputClass} />
            </Field>
            <Field label="דגם">
              <input value={model} onChange={(event) => setModel(event.target.value)} className={inputClass} />
            </Field>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label="שנה">
              <input
                inputMode="numeric"
                value={year}
                onChange={(event) => setYear(Number(event.target.value))}
                className={`${inputClass} num`}
              />
            </Field>
            <Field label="הנעה">
              <select
                value={fuel}
                onChange={(event) => setFuel(event.target.value as Fuel)}
                className={inputClass}
              >
                {FUELS.map((item) => (
                  <option key={item} value={item}>
                    {FUEL_LABEL[item]}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <Field label="VIN ידני">
            <input
              value={vin}
              onChange={(event) => setVin(event.target.value)}
              placeholder="לא נקרא מהרכב"
              className={`${inputClass} num`}
            />
          </Field>
          <button
            type="submit"
            className="mt-1 inline-flex min-h-12 items-center justify-center gap-2 rounded-2xl bg-primary px-4 font-medium text-primary-ink"
          >
            <Plus className="size-4" />
            יצירת פרופיל ומעבר לספסל
          </button>
        </form>
      </Panel>

      <div className="lg:col-span-2">
        <Panel
          title="מה הסימולציה כן ומה היא לא"
          hint="ארבע יחידות מדומות. שאר המערכות מוצגות כלא נגישות, גם כשהסריקה נראית מלאה."
        >
          <div className="grid gap-2 sm:grid-cols-2">
            {(Object.keys(ECU_META) as Array<keyof typeof ECU_META>).map((id) => (
              <div key={id} className="rounded-2xl bg-bg-raised px-3 py-3">
                <p className="font-medium">{ECU_META[id].nameHe}</p>
                <p className="num mt-1 text-xs text-faint">{ECU_META[id].address}</p>
                <p className="mt-1 text-sm text-muted">{ECU_META[id].protocolHe}</p>
              </div>
            ))}
          </div>
          <p className="mt-4 text-sm leading-6 text-muted">
            {FAULT_CATALOG.length} הזרקות מעבדה זמינות בספסל. הן משנות את הרכב המדומה בלבד, ומנוע הכללים רואה רק את מה שהמתאם החזיר.
          </p>
        </Panel>
      </div>
    </div>
  );
}

const inputClass =
  "min-h-11 w-full rounded-xl border border-line bg-bg px-3 text-sm text-fg outline-none focus:border-primary";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block text-sm">
      <span className="mb-1.5 block text-muted">{label}</span>
      {children}
    </label>
  );
}
