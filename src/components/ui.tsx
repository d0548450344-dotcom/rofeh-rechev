import type { ReactNode } from "react";
import type { Confidence, Severity } from "@/domain/types";

export function Panel({
  title,
  hint,
  children,
  action,
}: {
  title: string;
  hint?: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <section className="rounded-[var(--radius-card)] border border-line bg-surface p-4 sm:p-5">
      <div className="mb-4 flex items-start justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold">{title}</h2>
          {hint ? <p className="mt-1 text-sm leading-6 text-muted">{hint}</p> : null}
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}

export function Pill({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: "neutral" | "ok" | "warn" | "bad" | "info";
}) {
  const tones = {
    neutral: "bg-surface-2 text-fg",
    ok: "bg-ok/15 text-ok",
    warn: "bg-warn/15 text-warn",
    bad: "bg-bad/15 text-bad",
    info: "bg-info/15 text-info",
  };
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-1 text-xs ${tones[tone]}`}>
      {children}
    </span>
  );
}

export function severityTone(severity: Severity): "neutral" | "ok" | "warn" | "bad" | "info" {
  if (severity === "critical" || severity === "high") return "bad";
  if (severity === "medium") return "warn";
  if (severity === "low") return "info";
  return "neutral";
}

export function confidenceTone(confidence: Confidence): "neutral" | "ok" | "warn" | "bad" | "info" {
  if (confidence === "high") return "ok";
  if (confidence === "medium") return "warn";
  if (confidence === "low" || confidence === "insufficient") return "info";
  return "neutral";
}

export function Empty({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-2xl border border-dashed border-line px-4 py-8 text-center">
      <p className="font-medium">{title}</p>
      <p className="mx-auto mt-1 max-w-sm text-sm leading-6 text-muted">{body}</p>
    </div>
  );
}
