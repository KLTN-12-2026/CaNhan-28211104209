import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getSavedItinerary } from "../../api/itineraries";
import { Page } from "../../components/common/PageKit";
import EmptyState from "../../components/common/EmptyState";

function formatDateVn(isoDate) {
  if (!isoDate || typeof isoDate !== "string") return isoDate;
  const parts = isoDate.split("-");
  if (parts.length === 3) {
    return `${parts[2]}/${parts[1]}/${parts[0]}`;
  }
  return isoDate;
}

export default function ApiItineraryDetailPage() {
  const { itineraryId } = useParams();
  const [plan, setPlan] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    const ctrl = new globalThis.AbortController();
    getSavedItinerary(itineraryId, ctrl.signal)
      .then((data) => {
        if (!active) return;
        setPlan(data);
        setLoading(false);
      })
      .catch((err) => {
        if (!active) return;
        setError(err?.response?.data?.message || err.message || "Không thể tải chi tiết hành trình.");
        setLoading(false);
      });
    return () => {
      active = false;
      ctrl.abort();
    };
  }, [itineraryId]);

  return (
    <Page
      title={plan ? plan.title : "Chi tiết hành trình"}
      description={plan ? `Hành trình 1 ngày vào ngày ${formatDateVn(plan.tripDate)}` : ""}
      actions={
        <Link className="btn-secondary" to="/user/itineraries">
          ← Danh sách hành trình
        </Link>
      }
    >
      {loading ? (
        <div className="panel text-center py-8 text-slate-500">Đang tải chi tiết hành trình...</div>
      ) : error ? (
        <div className="panel rounded bg-rose-50 p-4 text-rose-600">{error}</div>
      ) : plan ? (
        <div className="space-y-6">
          <div className="panel flex flex-col gap-3">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-slate-400">Thời gian dự kiến</p>
                <p className="text-lg font-bold text-slate-900">
                  {plan.criteria.startTime} – {plan.criteria.endTime}
                </p>
              </div>
              <div className="flex gap-6 text-sm">
                <div>
                  <p className="text-slate-400">Điểm tham quan</p>
                  <p className="font-bold text-slate-800">{plan.totals.locationCount} điểm</p>
                </div>
                <div>
                  <p className="text-slate-400">Tổng tham quan</p>
                  <p className="font-bold text-slate-800">{plan.totals.totalDwellMinutes} phút</p>
                </div>
                <div>
                  <p className="text-slate-400">Tổng lái xe (OSRM)</p>
                  <p className="font-bold text-slate-800">{plan.totals.totalTravelMinutes} phút</p>
                </div>
                <div>
                  <p className="text-slate-400">Tổng quãng đường</p>
                  <p className="font-bold text-slate-800">{(plan.totals.totalDistanceMeters / 1000).toFixed(1)} km</p>
                </div>
              </div>
            </div>
            <p className="border-t border-slate-100 pt-2 text-xs italic text-slate-400">
              * Lưu ý: Thời gian và quãng đường chỉ tính giữa các điểm tham quan liên tiếp trong lộ trình; không bao gồm chặng di chuyển từ điểm xuất phát ban đầu đến điểm thứ nhất và chặng quay về sau điểm cuối cùng.
            </p>
          </div>

          <div className="space-y-4">
            {plan.items.map((item) => (
              <div key={item.locationId} className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                <div className="flex items-start justify-between gap-3">
                  <div className="flex items-center gap-3">
                    <span className="flex h-8 w-8 items-center justify-center rounded-full bg-slate-900 text-sm font-bold text-white">
                      {item.sortOrder}
                    </span>
                    <div>
                      <h3 className="text-base font-bold text-slate-900">{item.locationName}</h3>
                      <p className="text-xs text-slate-500">{item.categoryName}</p>
                    </div>
                  </div>
                  <div className="text-right text-sm">
                    <span className="font-bold text-slate-900">{item.arrival}</span>
                    <span className="text-slate-400"> → </span>
                    <span className="font-bold text-slate-900">{item.departure}</span>
                    <p className="text-xs text-slate-500">Tham quan: {item.dwellMinutes} phút</p>
                  </div>
                </div>

                {item.waitMinutes > 0 && (
                  <div className="mt-3 rounded bg-amber-50 px-3 py-1.5 text-xs text-amber-800">
                    Thời gian chờ mở cửa: {item.waitMinutes} phút
                  </div>
                )}

                {item.hoursEvidenceUrl && (
                  <div className="mt-2 text-xs text-slate-400">
                    Nguồn kiểm chứng giờ mở cửa:{" "}
                    <a
                      href={item.hoursEvidenceUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="text-sky-600 underline"
                    >
                      {item.hoursEvidenceUrl}
                    </a>
                  </div>
                )}

                {item.travelToNextMinutes !== null && (
                  <div className="mt-4 flex items-center gap-2 rounded bg-slate-50 p-2.5 text-xs text-slate-700">
                    <span>🚗 Chặng tiếp theo:</span>
                    <span className="font-semibold text-slate-900">
                      {(item.distanceToNextMeters / 1000).toFixed(1)} km
                    </span>
                    <span>·</span>
                    <span className="font-semibold text-slate-900">
                      ~{item.travelToNextMinutes} phút lái xe thực tế
                    </span>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      ) : (
        <EmptyState
          title="Không tìm thấy hành trình"
          description="Hành trình này có thể đã bị xóa hoặc bạn không có quyền truy cập."
        />
      )}
    </Page>
  );
}
