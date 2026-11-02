import {
  CLEAR_SCAN_HE,
  CONFIDENCE_LABEL,
  DTC_STATUS_LABEL,
  ECU_ACCESS_LABEL,
  ECU_META,
  GAP_BADGE_HE,
  MONITOR_STATUS_LABEL,
  SEVERITY_LABEL,
} from "@/domain/catalog";
import type { EcuAccess, ScanSession } from "@/domain/types";
import type { LabCheck } from "@/engine/lab";
import { Panel, Pill, confidenceTone, severityTone } from "@/components/ui";
import { clearNote } from "@/engine/quick-scan";

export function ScanReport({
  session,
  checks,
}: {
  session: ScanSession;
  checks: LabCheck[];
}) {
  const when = new Date(session.finishedAt).toLocaleString("he-IL", {
    dateStyle: "short",
    timeStyle: "short",
  });
  const accessOrder: EcuAccess[] = ["checked", "comm_error", "inaccessible", "not_scanned"];
  const accessTone: Record<EcuAccess, "ok" | "bad" | "warn" | "neutral"> = {
    checked: "ok",
    comm_error: "bad",
    inaccessible: "warn",
    not_scanned: "neutral",
  };

  return (
    <div className="space-y-4">
      <Panel
        title={session.kind === "post-clear" ? "סריקת אימות אחרי מחיקה" : "סריקה מהירה"}
        hint={`${when} · ${session.durationMs} ms · ${session.adapterLabelHe}`}
      >
        <p className="text-sm leading-6 text-muted">{session.vinNoteHe}</p>
        <p className="mt-2 text-sm leading-6">
          {session.protocolHe ?? "לא זוהה פרוטוקול מנוע בסריקה הזו."}
        </p>
        {session.errorHe ? <p className="mt-2 text-sm text-bad">{session.errorHe}</p> : null}
        {clearNote(session) ? (
          <p className="mt-3 rounded-xl border border-warn bg-warn/10 px-3 py-2 text-sm leading-6">{clearNote(session)}</p>
        ) : null}
      </Panel>

      <Panel
        title="יחידות"
        hint="נבדק, לא נבדק, לא נגיש ושגיאת תקשורת הם מצבים שונים. מערכת שלא נסרקה אינה תקינה ואינה תקוּלה."
      >
        <div className="space-y-2">
          {accessOrder.flatMap((access) =>
            session.ecus
              .filter((ecu) => ecu.access === access)
              .map((ecu) => (
                <Row
                  key={ecu.id}
                  title={ECU_META[ecu.id].nameHe}
                  tone={accessTone[access]}
                  badge={ECU_ACCESS_LABEL[access]}
                  body={ecu.reasonHe}
                />
              )),
          )}
          {session.gaps.map((gap) => (
            <Row key={gap.id} title={gap.nameHe} tone="neutral" badge={GAP_BADGE_HE} body={gap.reasonHe} />
          ))}
        </div>
      </Panel>

      <Panel title="ממצאים" hint="כל ממצא הוא כלל על עובדות שנקראו. אין כאן ניחוש של חלק להחלפה.">
        {session.findings.length === 0 ? (
          <p className="text-sm leading-6 text-muted">{CLEAR_SCAN_HE}</p>
        ) : (
          <ul className="space-y-3">
            {session.findings.map((finding) => (
              <li key={finding.id} className="rounded-2xl bg-bg-raised p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <p className="font-medium">{finding.titleHe}</p>
                  <Pill tone={severityTone(finding.severity)}>{SEVERITY_LABEL[finding.severity]}</Pill>
                  <Pill tone={confidenceTone(finding.confidence)}>
                    {CONFIDENCE_LABEL[finding.confidence]}
                  </Pill>
                </div>
                <p className="mt-2 text-sm leading-6">{finding.summaryHe}</p>
                <p className="mt-2 text-xs text-faint">
                  כלל {finding.ruleId}
                  {finding.code ? ` · קוד ${finding.code}` : ""} · טענה: {finding.claimHe}
                </p>
                <ul className="mt-2 space-y-1 text-sm text-muted">
                  {finding.evidence.map((line) => (
                    <li key={line}>· {line}</li>
                  ))}
                </ul>
                <p className="mt-2 text-sm leading-6 text-fg">{finding.nextStepHe}</p>
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <Panel title="קודי תקלה" hint="שמור, ממתין וקבוע נשמרים כמו שהיחידה החזירה אותם.">
        {session.dtcs.length === 0 ? (
          <p className="text-sm text-muted">לא הוחזרו קודים מהיחידות שנענו.</p>
        ) : (
          <ul className="space-y-2">
            {session.dtcs.map((dtc) => (
              <li key={`${dtc.ecuId}-${dtc.code}-${dtc.status}`} className="rounded-2xl bg-bg-raised px-3 py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="num font-medium">{dtc.code}</span>
                  <Pill>{DTC_STATUS_LABEL[dtc.status]}</Pill>
                  <Pill tone={severityTone(dtc.severity)}>{SEVERITY_LABEL[dtc.severity]}</Pill>
                  <span className="text-xs text-faint">{ECU_META[dtc.ecuId].nameHe}</span>
                </div>
                <p className="mt-1 text-sm leading-6 text-muted">{dtc.descriptionHe}</p>
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <Panel title="נתונים חיים · דגימה אחת" hint="קצב הדגימה אינו קצב המסך. כאן יש דגימה בודדת, לא לוג נסיעה.">
        {session.pids.length === 0 ? (
          <p className="text-sm text-muted">לא התקבלו PID. אם הייתה פסיקת זמן, הערכים לא הומצאו.</p>
        ) : (
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
            {session.pids.map((pid) => (
              <div key={pid.id} className="rounded-2xl bg-bg-raised px-3 py-3">
                <p className="text-xs text-faint">{pid.nameHe}</p>
                <p className="num mt-1 text-lg">
                  {pid.quality === "valid" && pid.value !== null ? pid.value : "—"}
                  <span className="ms-1 text-xs text-muted">{pid.unit}</span>
                </p>
                <p className="mt-1 text-[11px] text-faint">
                  {pid.quality === "valid" ? "יש ערך" : "אין קריאה"}
                  {pid.expected ? "" : " · לא צפוי ברכב הזה"}
                  {pid.genericObd ? "" : " · ערוץ יצרן"}
                </p>
              </div>
            ))}
          </div>
        )}
      </Panel>

      <Panel title="מוכנות" hint="מוניטור שלא נתמך נשאר לא נתמך. הוא לא נספר כתקין.">
        {session.readiness.length === 0 ? (
          <p className="text-sm text-muted">מוכנות לא נקראה. יחידת המנוע לא השלימה תשובה.</p>
        ) : (
          <ul className="grid gap-2 sm:grid-cols-2">
            {session.readiness.map((item) => (
              <li key={item.id} className="flex items-center justify-between gap-2 rounded-xl bg-bg-raised px-3 py-2 text-sm">
                <span>{item.nameHe}</span>
                <Pill
                  tone={
                    item.status === "complete" ? "ok" : item.status === "incomplete" ? "warn" : "neutral"
                  }
                >
                  {MONITOR_STATUS_LABEL[item.status]}
                </Pill>
              </li>
            ))}
          </ul>
        )}
      </Panel>

      {checks.length > 0 ? (
        <Panel
          title="בדיקת מעבדה"
          hint="ההזרקה ידועה רק כאן, כדי לוודא שהכלל הגיב נכון. זה לא מוצג כממצא מהרכב."
        >
          <ul className="space-y-2">
            {checks.map((check) => (
              <li key={check.faultId} className="rounded-2xl bg-bg-raised px-3 py-3">
                <div className="flex items-center justify-between gap-2">
                  <p className="font-medium">{check.titleHe}</p>
                  <Pill tone={check.met ? "ok" : "bad"}>{check.met ? "הכלל הגיב" : "לא כמצופה"}</Pill>
                </div>
                <p className="mt-1 text-sm leading-6 text-muted">{check.detailHe}</p>
              </li>
            ))}
          </ul>
        </Panel>
      ) : null}
    </div>
  );
}

function Row({
  title,
  body,
  badge,
  tone,
}: {
  title: string;
  body: string;
  badge: string;
  tone: "ok" | "bad" | "warn" | "neutral";
}) {
  return (
    <div className="rounded-2xl bg-bg-raised px-3 py-3">
      <div className="flex items-center justify-between gap-2">
        <p className="font-medium">{title}</p>
        <Pill tone={tone}>{badge}</Pill>
      </div>
      <p className="mt-1 text-sm leading-6 text-muted">{body}</p>
    </div>
  );
}
