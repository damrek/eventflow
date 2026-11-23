import type { ReactNode } from "react";

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed border-slate-300 bg-white p-6 text-center text-sm text-slate-600">
      <p className="font-medium text-slate-800">{title}</p>
      {children ? <div className="mt-3">{children}</div> : null}
    </div>
  );
}
