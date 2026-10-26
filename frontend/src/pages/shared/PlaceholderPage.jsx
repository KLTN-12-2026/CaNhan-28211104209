import EmptyState from "../../components/common/EmptyState";

export default function PlaceholderPage({ title, description, icon }) {
  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="mb-6 text-2xl font-bold text-slate-900">{title}</h1>
      <EmptyState
        title="Giao diện đang chờ dữ liệu API"
        description={description}
        icon={icon}
      />
    </div>
  );
}
