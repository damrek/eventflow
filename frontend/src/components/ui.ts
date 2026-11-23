const focusRing =
  "focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-700";

export type ButtonVariant = "primary" | "secondary" | "danger";

const variantClasses: Record<ButtonVariant, string> = {
  primary: "bg-sky-700 text-white hover:bg-sky-800 disabled:bg-slate-300 disabled:text-slate-600",
  secondary:
    "border border-slate-300 bg-white text-slate-800 hover:bg-slate-100 disabled:text-slate-400",
  danger: "border border-rose-300 bg-white text-rose-700 hover:bg-rose-50 disabled:text-rose-300",
};

export function buttonClass(variant: ButtonVariant = "primary", extra = ""): string {
  return [
    "inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-semibold transition",
    "disabled:cursor-not-allowed",
    focusRing,
    variantClasses[variant],
    extra,
  ]
    .join(" ")
    .trim();
}

export const cardClass = "rounded-lg border border-slate-200 bg-white p-5 shadow-sm";

export const inputClass = `w-24 rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 ${focusRing}`;

export const linkClass = `text-sky-700 underline underline-offset-2 hover:text-sky-900 ${focusRing}`;
