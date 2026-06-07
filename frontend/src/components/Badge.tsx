import { labelize } from "../lib/format";

interface BadgeProps {
  value: string;
  tone?: "neutral" | "success" | "warning" | "danger";
}

export function Badge({ value, tone = "neutral" }: BadgeProps) {
  const tones = {
    neutral: "bg-slate-100 text-slate-700",
    success: "bg-emerald-50 text-emerald-700",
    warning: "bg-amber-50 text-amber-700",
    danger: "bg-red-50 text-red-700"
  };

  return (
    <span className={`inline-flex rounded-full px-2 py-1 text-xs font-semibold ${tones[tone]}`}>
      {labelize(value)}
    </span>
  );
}
