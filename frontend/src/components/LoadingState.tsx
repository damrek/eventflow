export function LoadingState({ label }: { label: string }) {
  return (
    <p role="status" aria-live="polite" className="flex items-center gap-2 text-sm text-slate-600">
      <span
        aria-hidden="true"
        className="h-3 w-3 animate-spin rounded-full border-2 border-slate-300 border-t-sky-700"
      />
      {label}
    </p>
  );
}
