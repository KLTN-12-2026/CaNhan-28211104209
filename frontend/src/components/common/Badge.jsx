export default function Badge({ children, tone = "neutral", className = "" }) {
  const tones = {
    neutral: "bg-slate-100 text-slate-700",
    success: "bg-emerald-100 text-emerald-800",
    warning: "bg-amber-100 text-amber-800",
    danger: "bg-rose-100 text-rose-800",
    info: "bg-blue-100 text-blue-800",
    rental: "bg-violet-100 text-violet-800",
    returned: "bg-slate-200 text-slate-700",
    brand: "bg-teal-100 text-teal-800",
  };

  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${tones[tone] ?? tones.neutral} ${className}`}
    >
      {children}
    </span>
  );
}
