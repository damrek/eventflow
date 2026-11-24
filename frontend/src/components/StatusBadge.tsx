import type { ReactNode } from "react";

const tones = {
  positive: "bg-emerald-100 text-emerald-800",
  pending: "bg-amber-100 text-amber-900",
  negative: "bg-rose-100 text-rose-800",
  neutral: "bg-slate-100 text-slate-700",
} as const;

export type BadgeTone = keyof typeof tones;

export function StatusBadge({
  tone = "neutral",
  children,
}: {
  tone?: BadgeTone;
  children: ReactNode;
}) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${tones[tone]}`}
    >
      {children}
    </span>
  );
}
