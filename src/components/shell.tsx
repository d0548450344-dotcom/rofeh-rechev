import { useEffect, useState, type ReactNode } from "react";
import { Link, useRouterState } from "@tanstack/react-router";
import { Activity, History, Radio, ShieldCheck, Wrench } from "lucide-react";
import { useGarage } from "@/store/garage";

const NAV = [
  { to: "/", label: "מוסך", icon: Wrench },
  { to: "/bench", label: "ספסל", icon: Radio },
  { to: "/history", label: "היסטוריה", icon: History },
] as const;

export function Shell({ children }: { children: ReactNode }) {
  const path = useRouterState({ select: (state) => state.location.pathname });
  const [ready, setReady] = useState(false);

  useEffect(() => {
    useGarage.getState().hydrate();
    setReady(true);
  }, []);

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-6xl flex-col px-4 pb-24 pt-5 sm:px-6 lg:pb-10">
      <header className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="mb-1 flex items-center gap-2 text-xs tracking-[0.18em] text-primary uppercase">
            <Activity className="size-3.5" />
            שלב 0 · סימולציה
          </p>
          <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">רופא רכב</h1>
          <p className="mt-1 max-w-xl text-sm leading-6 text-muted">
            קריאה בלבד: רכב מדומה, או מתאם ELM327 אמיתי. אין כתיבה ליחידת בקרה. מה שלא נקרא — לא נקבע.
          </p>
        </div>
        <div className="flex items-center gap-2 rounded-full border border-line bg-surface px-3 py-1.5 text-xs text-muted">
          <ShieldCheck className="size-3.5 text-ok" />
          מתאם מדומה · Read only
        </div>
      </header>

      <nav className="mb-6 hidden gap-2 sm:flex">
        {NAV.map((item) => {
          const active = path === item.to;
          const Icon = item.icon;
          return (
            <Link
              key={item.to}
              to={item.to}
              className={`inline-flex min-h-11 items-center gap-2 rounded-full px-4 text-sm ${
                active
                  ? "bg-primary text-primary-ink"
                  : "border border-line bg-surface text-fg hover:bg-surface-2"
              }`}
            >
              <Icon className="size-4" />
              {item.label}
            </Link>
          );
        })}
      </nav>

      <main className="flex-1">
        {ready ? (
          children
        ) : (
          <p className="text-sm text-muted">טוען את המוסך השמור…</p>
        )}
      </main>

      <nav className="fixed inset-x-0 bottom-0 z-20 border-t border-line bg-bg/95 px-3 py-2 backdrop-blur sm:hidden">
        <div className="mx-auto grid max-w-lg grid-cols-3 gap-2">
          {NAV.map((item) => {
            const active = path === item.to;
            const Icon = item.icon;
            return (
              <Link
                key={item.to}
                to={item.to}
                className={`flex min-h-12 flex-col items-center justify-center gap-0.5 rounded-2xl text-xs ${
                  active ? "bg-primary text-primary-ink" : "text-muted"
                }`}
              >
                <Icon className="size-4" />
                {item.label}
              </Link>
            );
          })}
        </div>
      </nav>
    </div>
  );
}
