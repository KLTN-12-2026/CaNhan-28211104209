export default function LoadingSkeleton({ rows = 4, className = "" }) {
  return (
    <div
      className={`animate-pulse space-y-3 ${className}`}
      aria-busy="true"
      aria-live="polite"
    >
      {Array.from({ length: rows }).map((_, index) => (
        <div
          key={index}
          className="overflow-hidden rounded-xl border border-slate-200 bg-white p-4"
        >
          <div className="h-4 w-1/3 rounded bg-slate-200" />
          <div className="mt-3 h-3 w-full rounded bg-slate-100" />
          <div className="mt-2 h-3 w-2/3 rounded bg-slate-100" />
        </div>
      ))}
    </div>
  );
}
