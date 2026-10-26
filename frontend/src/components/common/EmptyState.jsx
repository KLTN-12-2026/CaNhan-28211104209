import { Inbox } from "lucide-react";

export default function EmptyState({
  icon: Icon = Inbox,
  title = "Chưa có dữ liệu",
  description = "Nội dung sẽ xuất hiện khi hệ thống có dữ liệu tương ứng.",
  action,
}) {
  return (
    <div className="flex flex-col items-center justify-center rounded-2xl border border-dashed border-slate-300 bg-white px-6 py-16 text-center">
      <div className="mb-4 rounded-full bg-teal-50 p-3 text-teal-700">
        <Icon className="h-7 w-7" aria-hidden="true" />
      </div>
      <h2 className="text-lg font-semibold text-slate-900">{title}</h2>
      <p className="mt-2 max-w-md text-sm text-slate-500">{description}</p>
      {action ? <div className="mt-5">{action}</div> : null}
    </div>
  );
}
