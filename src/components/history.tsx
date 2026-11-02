import { useState } from "react";
import { FUEL_LABEL, IGNITION_LABEL, POSTURE_LABEL } from "@/domain/catalog";
import { Empty, Panel, Pill } from "@/components/ui";
import { ScanReport } from "@/components/report";
import { labFor, useGarage } from "@/store/garage";

export function HistoryView() {
  const vehicles = useGarage((state) => state.vehicles);
  const sessions = useGarage((state) => state.sessions);
  const [openId, setOpenId] = useState<string | null>(null);
  const open = sessions.find((session) => session.id === openId) ?? sessions[0] ?? null;

  if (sessions.length === 0) {
    return (
      <Empty
        title="אין היסטוריה"
        body="סריקה שנשמרת נשארת אחרי סגירת הדף. עדיין לא בוצעה סריקה במכשיר הזה."
      />
    );
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[0.8fr_1.2fr]">
      <Panel title="סריקות שמורות" hint="החדשות למעלה. נשמרות מקומית, עד 40 אחרונות.">
        <ul className="space-y-2">
          {sessions.map((session) => {
            const vehicle = vehicles.find((item) => item.id === session.vehicleId);
            const active = session.id === open?.id;
            return (
              <li key={session.id}>
                <button
                  type="button"
                  onClick={() => setOpenId(session.id)}
                  className={`w-full rounded-2xl border px-3 py-3 text-start ${
                    active ? "border-primary bg-bg-raised" : "border-line bg-bg-raised/50"
                  }`}
                >
                  <span className="block font-medium">
                    {vehicle?.nickname ?? "רכב שנמחק מהרשימה"}
                  </span>
                  <span className="mt-1 block text-sm text-muted">
                    {new Date(session.finishedAt).toLocaleString("he-IL", {
                      dateStyle: "short",
                      timeStyle: "short",
                    })}
                    {" · "}
                    {IGNITION_LABEL[session.ignition]} · {POSTURE_LABEL[session.posture]}
                  </span>
                  <span className="mt-2 flex flex-wrap gap-1.5">
                    <Pill>{session.dtcs.length} קודים</Pill>
                    <Pill tone={session.findings.length ? "warn" : "neutral"}>
                      {session.findings.length} ממצאים
                    </Pill>
                    {vehicle ? <Pill tone="neutral">{FUEL_LABEL[vehicle.fuel]}</Pill> : null}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </Panel>
      <div>{open ? <ScanReport session={open} checks={labFor(open)} /> : null}</div>
    </div>
  );
}
