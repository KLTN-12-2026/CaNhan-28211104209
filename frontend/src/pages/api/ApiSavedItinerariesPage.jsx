import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { listSavedItineraries } from "../../api/itineraries";
import { Page } from "../../components/common/PageKit";
import EmptyState from "../../components/common/EmptyState";

export default function ApiSavedItinerariesPage() {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    const ctrl = new globalThis.AbortController();
    listSavedItineraries(ctrl.signal)
      .then((data) => {
        if (!active) return;
        setItems(data);
        setLoading(false);
      })
      .catch((err) => {
        if (!active) return;
        setError(err?.response?.data?.message || err.message || "Không thể tải danh sách hành trình.");
        setLoading(false);
      });
    return () => {
      active = false;
      ctrl.abort();
    };
  }, []);

  return (
    <Page
      title="Hành trình của tôi"
      description="Các kế hoạch chuyến đi đã được lưu vào tài khoản của bạn."
      actions={
        <Link className="btn" to="/ai/itinerary">
          Lập hành trình mới
        </Link>
      }
    >
      {loading ? (
        <div className="panel text-center py-8 text-slate-500">Đang tải danh sách hành trình...</div>
      ) : error ? (
        <div className="panel rounded bg-rose-50 p-4 text-rose-600">{error}</div>
      ) : items.length > 0 ? (
        <div className="grid gap-5 sm:grid-cols-2">
          {items.map((item) => (
            <article className="panel space-y-3" key={item.id}>
              <div className="flex items-center justify-between">
                <span className="inline-block rounded bg-sky-100 px-2.5 py-0.5 text-xs font-semibold text-sky-800">
                  {item.tripDate?.split("-").reverse().join("/")}
                </span>
                <span className="text-xs text-slate-400">
                  {item.locationCount} điểm tham quan
                </span>
              </div>
              <h2 className="text-lg font-bold text-slate-900">{item.title}</h2>
              <div className="flex gap-4 text-xs text-slate-500">
                <span>Tham quan: ~{item.totalDwellMinutes}p</span>
                <span>·</span>
                <span>Di chuyển: ~{item.totalTravelMinutes}p</span>
                <span>·</span>
                <span>Quãng đường: {(item.totalDistanceMeters / 1000).toFixed(1)} km</span>
              </div>
              <div className="pt-2">
                <Link className="btn-secondary text-sm" to={`/user/itineraries/${item.id}`}>
                  Xem chi tiết hành trình →
                </Link>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <EmptyState
          title="Chưa có hành trình nào được lưu"
          description="Hãy tạo một lịch trình 1 ngày bằng công cụ AI và OSRM để lưu vào tài khoản."
          action={
            <Link className="btn" to="/ai/itinerary">
              Lập kế hoạch ngay
            </Link>
          }
        />
      )}
    </Page>
  );
}
