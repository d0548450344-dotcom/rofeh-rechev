import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { Plug, PlugZap, ScanSearch, Unplug } from "lucide-react";
import {
  ECU_META,
  FAULT_CATALOG,
  FUEL_LABEL,
  IGNITION_LABEL,
  POSTURE_LABEL,
  SESSION_LABEL,
} from "@/domain/catalog";
import { ignitionMoves } from "@/domain/machines";
import type { EcuId, Posture } from "@/domain/types";
import { Empty, Panel, Pill } from "@/components/ui";
import { ScanReport } from "@/components/report";
import { canClearCodes, labFor, latestFor, useGarage, type AdapterMode } from "@/store/garage";
import { webBluetoothSupported, webSerialSupported } from "@/live/web-links";

const POSTURES: Posture[] = ["idle", "straight", "turn", "brake"];
const ECUS: EcuId[] = ["engine", "abs", "bcm", "srs"];

export function Bench() {
  const vehicles = useGarage((state) => state.vehicles);
  const selectedId = useGarage((state) => state.selectedId);
  const sessions = useGarage((state) => state.sessions);
  const live = useGarage((state) => state.live);
  const resources = useGarage((state) => state.resources);
  const setIgnition = useGarage((state) => state.setIgnition);
  const setPosture = useGarage((state) => state.setPosture);
  const toggleFault = useGarage((state) => state.toggleFault);
  const setLostEcu = useGarage((state) => state.setLostEcu);
  const connect = useGarage((state) => state.connect);
  const disconnect = useGarage((state) => state.disconnect);
  const quickScan = useGarage((state) => state.quickScan);
  const sampleResources = useGarage((state) => state.sampleResources);
  const adapterMode = useGarage((state) => state.adapterMode);
  const setAdapterMode = useGarage((state) => state.setAdapterMode);
  const [note, setNote] = useState<string | null>(null);
  const realCar = adapterMode !== "simulated";
  const clearCodes = useGarage((state) => state.clearCodes);
  const clearable = useGarage(canClearCodes);

  const vehicle = vehicles.find((item) => item.id === selectedId) ?? null;
  const latest = sessions.find((session) => session.id === live.latestId) ?? latestFor(sessions, selectedId);
  const checks = labFor(latest && latest.vehicleId === vehicle?.id ? latest : null);

  if (!vehicle) {
    return (
      <Empty
        title="אין רכב על הספסל"
        body="יוצרים פרופיל במוסך, ואז חוזרים לכאן למחזור הצתה, חיבור וסריקה מהירה."
      />
    );
  }

  const moves = ignitionMoves(vehicle.sim.ignition);
  const linked = live.session !== "DISCONNECTED" && live.session !== "ERROR";

  return (
    <div className="grid gap-4 lg:grid-cols-[0.92fr_1.08fr]">
      <div className="space-y-4">
        <Panel
          title={vehicle.nickname}
          hint={`${vehicle.make} ${vehicle.model} · ${vehicle.year} · ${FUEL_LABEL[vehicle.fuel]}`}
          action={
            <Link to="/" className="text-sm text-primary">
              החלפת רכב
            </Link>
          }
        >
          <div className="flex flex-wrap gap-2">
            <Pill tone={linked ? "ok" : live.session === "ERROR" ? "bad" : "neutral"}>
              {SESSION_LABEL[live.session]}
            </Pill>
            {realCar ? (
              <Pill tone="info">{ADAPTER_MODE_LABEL[adapterMode]}</Pill>
            ) : (
              <>
                <Pill>{IGNITION_LABEL[vehicle.sim.ignition]}</Pill>
                <Pill tone="info">{POSTURE_LABEL[vehicle.sim.posture]}</Pill>
              </>
            )}
          </div>
          <p className="mt-3 text-sm leading-6 text-muted">
            {live.protocolHe ?? "פרוטוקול יופיע רק אחרי שהמתאם זיהה ערוץ. אין פרוטוקול — לא ממציאים אחד."}
          </p>
          {live.errorHe ? <p className="mt-2 text-sm leading-6 text-bad">{live.errorHe}</p> : null}
          {note ? <p className="mt-2 text-sm leading-6 text-warn">{note}</p> : null}
        </Panel>

        <AdapterPicker
          mode={adapterMode}
          disabled={live.busy || (live.session !== "DISCONNECTED" && live.session !== "ERROR")}
          onChange={(mode) => {
            setNote(null);
            setAdapterMode(mode);
          }}
        />

        {realCar ? (
          <RealCarTips mode={adapterMode} />
        ) : (
          <>
          <Panel title="הצתה" hint="רק מעבר חוקי. אי אפשר לקפוץ מכיבוי ישר למנוע פועל.">
            <ol className="mb-4 grid grid-cols-5 gap-1 text-center text-[11px] text-faint">
              {(["OFF", "ACC", "ON", "STARTING", "RUNNING"] as const).map((step) => (
                <li
                  key={step}
                  className={`rounded-xl px-1 py-2 ${
                    vehicle.sim.ignition === step ? "bg-primary text-primary-ink" : "bg-bg-raised"
                  }`}
                >
                  {IGNITION_LABEL[step]}
                </li>
              ))}
            </ol>
            <div className="flex flex-wrap gap-2">
              {moves.map((move) => (
                <button
                  key={move.to}
                  type="button"
                  disabled={live.busy}
                  onClick={() => setNote(setIgnition(move.to))}
                  className="min-h-11 rounded-xl border border-line bg-bg-raised px-3 text-sm disabled:opacity-40"
                >
                  {move.label}
                </button>
              ))}
            </div>
          </Panel>

          <Panel title="תנאי נסיעה מדומים" hint="התנאי נשמר עם הסריקה. הוא לא נמדד מחיישן אמיתי.">
            <div className="grid grid-cols-2 gap-2">
              {POSTURES.map((posture) => (
                <button
                  key={posture}
                  type="button"
                  disabled={live.busy}
                  onClick={() => setPosture(posture)}
                  className={`min-h-11 rounded-xl px-3 text-sm ${
                    vehicle.sim.posture === posture
                      ? "bg-primary text-primary-ink"
                      : "border border-line bg-bg-raised"
                  }`}
                >
                  {POSTURE_LABEL[posture]}
                </button>
              ))}
            </div>
          </Panel>

          <Panel
            title="הזרקת תקלה"
            hint="משנה את הרכב המדומה לפני הסריקה. הממצא חייב להגיע מהנתונים, לא משם ההזרקה."
          >
            <ul className="space-y-2">
              {FAULT_CATALOG.map((fault) => {
                const on = vehicle.sim.faults.includes(fault.id);
                return (
                  <li key={fault.id}>
                    <button
                      type="button"
                      disabled={live.busy}
                      onClick={() => toggleFault(fault.id)}
                      className={`w-full rounded-2xl border px-3 py-3 text-start disabled:opacity-40 ${
                        on ? "border-warn bg-warn/10" : "border-line bg-bg-raised"
                      }`}
                    >
                      <span className="flex items-center justify-between gap-2">
                        <span className="font-medium">{fault.titleHe}</span>
                        <Pill tone={on ? "warn" : "neutral"}>{on ? "פעיל" : "כבוי"}</Pill>
                      </span>
                      <span className="mt-1 block text-sm leading-6 text-muted">{fault.detailHe}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
            {vehicle.sim.faults.includes("lost_ecu") ? (
              <div className="mt-3">
                <p className="mb-2 text-sm text-muted">איזו יחידה לא תענה</p>
                <div className="grid grid-cols-2 gap-2">
                  {ECUS.map((ecu) => (
                    <button
                      key={ecu}
                      type="button"
                      onClick={() => setLostEcu(ecu)}
                      className={`min-h-11 rounded-xl px-3 text-sm ${
                        vehicle.sim.lostEcu === ecu
                          ? "bg-primary text-primary-ink"
                          : "border border-line bg-bg-raised"
                      }`}
                    >
                      {ECU_META[ecu].nameHe}
                    </button>
                  ))}
                </div>
              </div>
            ) : null}
          </Panel>
          </>
        )}
      </div>

      <div className="space-y-4">
        <Panel
          title={realCar ? ADAPTER_MODE_LABEL[adapterMode] : "מתאם מדומה"}
          hint="אין צריבה ואין שינוי תוכנה ביחידות. מחיקת קודים רק אחרי סריקה ושני אישורים."
        >
          <div className="grid gap-2 sm:grid-cols-3">
            <Action
              label="התחברות"
              icon={<Plug className="size-4" />}
              disabled={live.busy || (live.session !== "DISCONNECTED" && live.session !== "ERROR")}
              onClick={() => {
                setNote(null);
                void connect();
              }}
            />
            <Action
              label={live.session === "SCANNING" ? "סורק…" : "סריקה מהירה"}
              icon={<ScanSearch className="size-4" />}
              primary
              disabled={live.busy || (live.session !== "CONNECTED" && live.session !== "COMPLETE")}
              onClick={() => {
                setNote(null);
                void quickScan();
              }}
            />
            <Action
              label={live.session === "SCANNING" ? "הפסקת סריקה" : "ניתוק"}
              icon={linked ? <PlugZap className="size-4" /> : <Unplug className="size-4" />}
              disabled={
                live.session === "DISCONNECTED" ||
                live.session === "DISCONNECTING" ||
                (live.busy && live.session !== "SCANNING")
              }
              onClick={() => {
                setNote(null);
                void disconnect();
              }}
            />
          </div>
        </Panel>

        {clearable ? (
          <ClearCodesPanel
            onClear={async () => {
              setNote(null);
              const refusal = await clearCodes(true, true);
              if (refusal) setNote(refusal);
            }}
          />
        ) : null}

        {latest && latest.vehicleId === vehicle.id ? (
          <ScanReport session={latest} checks={checks} />
        ) : (
          <Empty
            title="עדיין אין סריקה לרכב הזה"
            body={
              realCar
                ? "מחברים את המתאם לרכב, מעבירים הצתה ל-ON, לוחצים התחברות ובוחרים את המתאם בחלון של הדפדפן, ואז סריקה מהירה."
                : "מחזור טיפוסי: הצתה, מנוע פועל, התחברות, ואז סריקה מהירה. בלי חיבור לא נקרא כלום."
            }
          />
        )}

        <Panel
          title="משאבים"
          hint="דגימה ידנית אחרי סריקה. המספר, אם קיים, הוא זיכרון JS בדפדפן — לא יעד ה-Android."
          action={
            <button type="button" onClick={sampleResources} className="text-sm text-primary">
              דגימה
            </button>
          }
        >
          {resources[0] ? (
            <div className="grid grid-cols-3 gap-2 text-center">
              <Stat label="JS heap" value={resources[0].heapMb === null ? "לא זמין" : `${resources[0].heapMb}`} unit="MB" />
              <Stat label="סריקות שמורות" value={String(resources[0].scans)} />
              <Stat label="רכבים" value={String(resources[0].vehicles)} />
            </div>
          ) : (
            <p className="text-sm text-muted">עוד לא נדגם. סריקה שומרת נקודה אוטומטית.</p>
          )}
          <p className="mt-3 text-xs leading-5 text-faint">
            היסטוריה מוגבלת ל-40 סריקות אחרונות כדי לא לגדול בלי גבול. מיקום לא נאסף.
          </p>
        </Panel>
      </div>
    </div>
  );
}

function Action({
  label,
  icon,
  onClick,
  disabled,
  primary,
}: {
  label: string;
  icon: React.ReactNode;
  onClick: () => void;
  disabled?: boolean;
  primary?: boolean;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`inline-flex min-h-12 items-center justify-center gap-2 rounded-2xl px-3 text-sm font-medium disabled:opacity-40 ${
        primary ? "bg-primary text-primary-ink" : "border border-line bg-bg-raised"
      }`}
    >
      {icon}
      {label}
    </button>
  );
}

function Stat({ label, value, unit }: { label: string; value: string; unit?: string }) {
  return (
    <div className="rounded-2xl bg-bg-raised px-2 py-3">
      <p className="text-xs text-faint">{label}</p>
      <p className="num mt-1 text-lg">
        {value}
        {unit ? <span className="ms-1 text-xs text-muted">{unit}</span> : null}
      </p>
    </div>
  );
}

const ADAPTER_MODE_LABEL: Record<AdapterMode, string> = {
  simulated: "רכב מדומה",
  ble: "מתאם Bluetooth LE",
  serial: "מתאם USB / יציאה טורית",
};

function AdapterPicker({
  mode,
  disabled,
  onChange,
}: {
  mode: AdapterMode;
  disabled: boolean;
  onChange: (mode: AdapterMode) => void;
}) {
  const options: Array<{ mode: AdapterMode; label: string; available: boolean; why: string }> = [
    { mode: "simulated", label: "רכב מדומה", available: true, why: "" },
    {
      mode: "ble",
      label: "Bluetooth LE",
      available: webBluetoothSupported(),
      why: "דורש Chrome או Edge (לא באייפון).",
    },
    {
      mode: "serial",
      label: "USB / טורי",
      available: webSerialSupported(),
      why: "דורש Chrome או Edge במחשב.",
    },
  ];
  return (
    <Panel title="מקור הנתונים" hint="רכב מדומה לתרגול, או מתאם ELM327 אמיתי שמחובר לרכב.">
      <div className="grid grid-cols-3 gap-2">
        {options.map((option) => (
          <button
            key={option.mode}
            type="button"
            disabled={disabled || !option.available}
            onClick={() => onChange(option.mode)}
            className={`min-h-11 rounded-xl px-2 text-sm disabled:opacity-40 ${
              mode === option.mode ? "bg-primary text-primary-ink" : "border border-line bg-bg-raised"
            }`}
          >
            {option.label}
          </button>
        ))}
      </div>
      {options
        .filter((option) => !option.available)
        .map((option) => (
          <p key={option.mode} className="mt-2 text-xs leading-5 text-faint">
            {option.label}: {option.why}
          </p>
        ))}
      <p className="mt-2 text-xs leading-5 text-faint">
        מתאם Wi-Fi עובד רק באפליקציית ה-Android: דפדפן לא יכול לפתוח חיבור כזה.
      </p>
    </Panel>
  );
}

function RealCarTips({ mode }: { mode: AdapterMode }) {
  return (
    <Panel title="לפני סריקה ברכב" hint="מה שהאפליקציה קוראת, ומה לא.">
      <ol className="list-decimal space-y-1 ps-5 text-sm leading-6">
        <li>מחברים את המתאם לשקע ה-OBD (בדרך כלל מתחת להגה).</li>
        <li>מעבירים הצתה ל-ON או מתניעים. במצב כבוי הרכב לא עונה.</li>
        <li>
          {mode === "ble"
            ? "לוחצים התחברות ובוחרים את המתאם ברשימה שהדפדפן פותח."
            : "לוחצים התחברות ובוחרים את יציאת ה-USB או ה-COM של המתאם (מתאם Bluetooth רגיל מצמדים קודם ב-Windows)."}
        </li>
        <li>החיבור הראשון יכול לקחת עד 15 שניות, ואז סריקה מהירה.</li>
      </ol>
      <p className="mt-3 text-sm leading-6 text-muted">
        נקראים קודי מנוע ופליטה, מוכנות ונתונים חיים לפי תקן OBD-II. ABS, כריות אוויר ומרכב דורשים פרוטוקול יצרן
        ולא נסרקים.
      </p>
    </Panel>
  );
}

/** Two explicit steps before any code is cleared. The store then re-checks the car. */
function ClearCodesPanel({ onClear }: { onClear: () => Promise<void> }) {
  const [step, setStep] = useState<0 | 1 | 2>(0);
  return (
    <Panel title="מחיקת קודי תקלה" hint="מחיקה לא מתקנת תקלה. אין צריבה ואין שינוי תוכנה ביחידות.">
      {step === 0 ? (
        <button
          type="button"
          onClick={() => setStep(1)}
          className="min-h-11 w-full rounded-xl border border-line bg-bg-raised px-3 text-sm"
        >
          מחיקת קודי תקלה…
        </button>
      ) : null}
      {step === 1 ? (
        <div className="space-y-2 text-sm leading-6">
          <p>מחיקה מכבה את נורת התקלה ומוחקת את הקודים השמורים והממתינים. היא לא מתקנת דבר.</p>
          <p>אם התקלה אמיתית, הקוד יחזור, לפעמים כבר בנסיעה הבאה.</p>
          <p>הסריקה הנוכחית כבר שמורה בהיסטוריה, כך שהקודים לא ילכו לאיבוד.</p>
          <div className="grid grid-cols-2 gap-2 pt-1">
            <button type="button" onClick={() => setStep(2)} className="min-h-11 rounded-xl bg-primary px-3 text-primary-ink">
              הבנתי, המשך
            </button>
            <button type="button" onClick={() => setStep(0)} className="min-h-11 rounded-xl border border-line bg-bg-raised px-3">
              ביטול
            </button>
          </div>
        </div>
      ) : null}
      {step === 2 ? (
        <div className="space-y-2 text-sm leading-6">
          <p>מוכנות המערכות תתאפס. רכב שנמחקו לו קודים ממש לפני טסט עלול להיכשל בו עד שהמערכות ייבדקו מחדש.</p>
          <p>המנוע צריך להיות כבוי וההצתה במצב ON. הרכב עומד.</p>
          <p>קודים קבועים (Permanent) לא נמחקים ממתאם. רק הרכב מוחק אותם אחרי תיקון.</p>
          <p>אחרי המחיקה תרוץ סריקת אימות אוטומטית.</p>
          <div className="grid grid-cols-2 gap-2 pt-1">
            <button
              type="button"
              onClick={() => {
                setStep(0);
                void onClear();
              }}
              className="min-h-11 rounded-xl bg-bad px-3 text-white"
            >
              מחק קודים
            </button>
            <button type="button" onClick={() => setStep(0)} className="min-h-11 rounded-xl border border-line bg-bg-raised px-3">
              ביטול
            </button>
          </div>
        </div>
      ) : null}
    </Panel>
  );
}
