import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { listActiveLocations, previewItinerary, saveItinerary } from "../../api/itineraries";
import { Page, Field } from "../../components/common/PageKit";
import EmptyState from "../../components/common/EmptyState";
import { useAuth } from "../../context/AuthContext";

function formatDateVn(isoDate) {
  if (!isoDate || typeof isoDate !== "string") return "";
  const parts = isoDate.split("-");
  if (parts.length === 3) {
    return `${parts[2]}/${parts[1]}/${parts[0]}`;
  }
  return isoDate;
}

function getVietnamDateStrings() {
  const formatter = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Ho_Chi_Minh",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
  const todayStr = formatter.format(new Date());
  const now = new Date();
  const maxDate = new Date(now.getTime() + 90 * 24 * 60 * 60 * 1000);
  const maxDateStr = formatter.format(maxDate);
  return { todayStr, maxDateStr };
}

export default function ApiItineraryPlannerPage() {
  const { user } = useAuth();
  const navigate = useNavigate();

  const [locations, setLocations] = useState([]);
  const [categories, setCategories] = useState([]);
  const [loadingLocations, setLoadingLocations] = useState(true);

  // Form criteria state tính theo múi giờ Việt Nam Asia/Ho_Chi_Minh (F05)
  const { todayStr, maxDateStr } = getVietnamDateStrings();

  const [tripDate, setTripDate] = useState(todayStr);
  const [startTime, setStartTime] = useState("09:00");
  const [endTime, setEndTime] = useState("17:00");
  const [startLocationId, setStartLocationId] = useState("");
  const [preferredCategoryId, setPreferredCategoryId] = useState("");

  // Result & UI state
  const [previewResult, setPreviewResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveTitle, setSaveTitle] = useState("");
  const [saveSuccessId, setSaveSuccessId] = useState("");
  const [error, setError] = useState("");

  const generationRef = useRef(0);
  const previewAbortRef = useRef(null);
  const saveAbortRef = useRef(null);

  // Khi thay đổi bất kỳ trường criteria nào, tăng generation và abort preview request đang chạy (F05)
  function handleCriteriaChange(setter, value) {
    if (saveAbortRef.current) return;
    setter(value);
    generationRef.current += 1;
    if (previewAbortRef.current) {
      previewAbortRef.current.abort();
      previewAbortRef.current = null;
    }
    setPreviewResult(null);
    setSaveSuccessId("");
    setError("");
    setBusy(false);
  }

  const [prevUserKey, setPrevUserKey] = useState(() => `${user?.id || ""}:${user?.role || ""}`);
  const currentUserKey = `${user?.id || ""}:${user?.role || ""}`;

  // Bảo vệ preview/save khi switch account / logout (F05) - state adjustment an toàn trong render (không đụng ref)
  if (currentUserKey !== prevUserKey) {
    setPrevUserKey(currentUserKey);
    setPreviewResult(null);
    setSaveSuccessId("");
    setError("");
    setBusy(false);
    setSaving(false);
  }

  // Hủy các request đang chạy khi user thay đổi (F05) - thực hiện trong effect an toàn cho refs
  useEffect(() => {
    generationRef.current += 1;
    if (previewAbortRef.current) {
      previewAbortRef.current.abort();
      previewAbortRef.current = null;
    }
    if (saveAbortRef.current) {
      saveAbortRef.current.abort();
      saveAbortRef.current = null;
    }
  }, [user?.id, user?.role]);

  // Bảo vệ cleanup khi unmount (F05)
  useEffect(() => {
    return () => {
      generationRef.current += 1;
      if (previewAbortRef.current) {
        previewAbortRef.current.abort();
        previewAbortRef.current = null;
      }
      if (saveAbortRef.current) {
        saveAbortRef.current.abort();
        saveAbortRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    let active = true;
    const ctrl = new globalThis.AbortController();
    listActiveLocations(ctrl.signal)
      .then((data) => {
        if (!active) return;
        const valid = (data || []).filter((l) => l.metadata && l.metadata.durationMinutes > 0);
        setLocations(valid);
        if (valid.length > 0) {
          setStartLocationId(valid[0].id);
        }
        const cats = Array.from(
          new Map(valid.map((l) => [l.categoryId, { id: l.categoryId, name: l.categoryName }])).values()
        );
        setCategories(cats);
        setLoadingLocations(false);
      })
      .catch((err) => {
        if (!active) return;
        setError(err?.response?.data?.message || err.message || "Không thể tải danh mục địa điểm.");
        setLoadingLocations(false);
      });
    return () => {
      active = false;
      ctrl.abort();
    };
  }, []);

  async function handlePreview(e) {
    e.preventDefault();
    if (saveAbortRef.current) return;
    setError("");
    setPreviewResult(null);
    setSaveSuccessId("");
    setBusy(true);

    const gen = ++generationRef.current;
    if (previewAbortRef.current) {
      previewAbortRef.current.abort();
    }
    const ctrl = new globalThis.AbortController();
    previewAbortRef.current = ctrl;

    try {
      const payload = {
        tripDate,
        startTime,
        endTime,
        startLocationId,
        preferredCategoryId: preferredCategoryId || null,
      };
      const res = await previewItinerary(payload, ctrl.signal);
      if (gen !== generationRef.current) return;

      setPreviewResult(res);
      setSaveTitle(`Hành trình Đà Nẵng · Ngày ${formatDateVn(tripDate)}`);
    } catch (err) {
      if (gen !== generationRef.current) return;
      setError(err?.response?.data?.message || err.message || "Lập lịch trình thất bại.");
    } finally {
      if (gen === generationRef.current) {
        setBusy(false);
      }
    }
  }

  async function handleSave(e) {
    e.preventDefault();
    if (saveAbortRef.current) return;
    if (!user || user.role !== "USER") {
      navigate("/login");
      return;
    }
    if (!previewResult || !previewResult.fingerprint || previewResult.status !== "VALIDATED") {
      return;
    }

    setSaving(true);
    setError("");

    if (saveAbortRef.current) {
      saveAbortRef.current.abort();
    }
    const ctrl = new globalThis.AbortController();
    saveAbortRef.current = ctrl;
    const saveGen = ++generationRef.current;

    try {
      // Gửi đúng tiêu chí đã được preview và fingerprint đã xác thực (F02, F05)
      const payload = {
        title: saveTitle.trim() || `Hành trình Đà Nẵng · Ngày ${formatDateVn(previewResult.criteria.tripDate)}`,
        fingerprint: previewResult.fingerprint,
        tripDate: previewResult.criteria.tripDate,
        startTime: previewResult.criteria.startTime,
        endTime: previewResult.criteria.endTime,
        startLocationId: previewResult.criteria.startLocationId,
        preferredCategoryId: previewResult.criteria.preferredCategoryId || null,
        orderedLocationIds: previewResult.orderedLocationIds,
      };
      const saved = await saveItinerary(payload, ctrl.signal);
      if (saveGen !== generationRef.current) return;
      setSaveSuccessId(saved.id);
    } catch (err) {
      if (saveGen !== generationRef.current) return;
      if (err?.name === "AbortError" || err?.code === "ERR_CANCELED") {
        return;
      }
      if (err.code === "ECONNABORTED" || err.message?.includes("timeout") || !err.response) {
        setError(
          "Yêu cầu lưu hành trình bị gián đoạn do mạng hoặc hết thời gian chờ. Trạng thái lưu có thể chưa rõ, vui lòng kiểm tra danh sách 'Hành trình đã lưu' trước khi thử lại."
        );
      } else if (err.response?.status === 409) {
        setError(
          "Dữ liệu lộ trình hoặc giờ hoạt động của địa điểm đã thay đổi so với thời điểm xem trước. Vui lòng bấm 'Xem trước kế hoạch' để cập nhật lại trước khi lưu."
        );
      } else {
        setError(err?.response?.data?.message || err.message || "Lưu hành trình thất bại.");
      }
    } finally {
      if (saveAbortRef.current === ctrl) saveAbortRef.current = null;
      if (saveGen === generationRef.current) {
        setSaving(false);
      }
    }
  }

  const isCustomer = user && user.role === "USER";

  return (
    <Page
      title="Lập kế hoạch hành trình 1 ngày"
      description="Gợi ý chuỗi thứ tự điểm đến bằng AI và tính toán từng chặng thời gian thực tế bằng OSRM."
    >
      <div className="rounded-lg border border-sky-200 bg-sky-50 p-4 text-sm text-sky-800">
        <p className="font-semibold">Nguyên tắc tính toán thực tế:</p>
        <ul className="mt-1 list-disc pl-5 space-y-1">
          <li>Chỉ kết hợp 2–4 điểm tham quan có dữ liệu giờ mở cửa và thời lượng được kiểm chứng.</li>
          <li>Thời gian di chuyển từng chặng được đo chính xác bằng mạng đường bộ OSRM (làm tròn lên phút).</li>
          <li>Không bịa đặt giờ mở cửa, giá vé, hay tuyến đường giả định.</li>
          <li className="font-medium text-amber-900">
            Lưu ý: Lộ trình và thời gian được tính từ điểm xuất phát tới lần lượt các điểm tham quan. Không bao gồm chặng đón từ xa hoặc chặng quay về điểm ban đầu.
          </li>
        </ul>
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-[340px_1fr]">
        <form className="panel h-fit space-y-4" onSubmit={handlePreview}>
          <fieldset disabled={saving} className="space-y-4">
          <Field label="Khu vực hoạt động">
            <input readOnly value="Đà Nẵng – Hội An (Vùng kiểm chứng OSRM)" className="bg-slate-100" />
          </Field>

          <Field label="Ngày hành trình" hint={`Định dạng hiển thị: ${formatDateVn(tripDate)}`}>
            <input
              type="date"
              required
              min={todayStr}
              max={maxDateStr}
              value={tripDate}
              onChange={(e) => handleCriteriaChange(setTripDate, e.target.value)}
            />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Giờ bắt đầu">
              <input
                type="time"
                required
                value={startTime}
                onChange={(e) => handleCriteriaChange(setStartTime, e.target.value)}
              />
            </Field>
            <Field label="Giờ kết thúc">
              <input
                type="time"
                required
                value={endTime}
                onChange={(e) => handleCriteriaChange(setEndTime, e.target.value)}
              />
            </Field>
          </div>

          <Field label="Điểm xuất phát">
            {loadingLocations ? (
              <select disabled value="">
                <option value="">Đang tải danh mục địa điểm...</option>
              </select>
            ) : locations.length === 0 ? (
              <select disabled value="">
                <option value="">Chưa có địa điểm nào có dữ liệu kiểm chứng.</option>
              </select>
            ) : (
              <select
                required
                value={startLocationId}
                onChange={(e) => handleCriteriaChange(setStartLocationId, e.target.value)}
              >
                {locations.map((loc) => (
                  <option key={loc.id} value={loc.id}>
                    {loc.name} ({loc.categoryName} · {loc.metadata.durationMinutes}p)
                  </option>
                ))}
              </select>
            )}
          </Field>

          <Field label="Sở thích ưu tiên (không bắt buộc)">
            <select
              value={preferredCategoryId}
              onChange={(e) => handleCriteriaChange(setPreferredCategoryId, e.target.value)}
            >
              <option value="">Tất cả danh mục</option>
              {categories.map((cat) => (
                <option key={cat.id} value={cat.id}>
                  {cat.name}
                </option>
              ))}
            </select>
          </Field>

          {error && <div className="rounded bg-rose-50 p-3 text-sm text-rose-600">{error}</div>}

          <button
            type="submit"
            disabled={saving || busy || loadingLocations || locations.length === 0}
            className="btn w-full justify-center"
          >
            {busy ? "Đang tính toán tuyến đường..." : "Xem trước kế hoạch"}
          </button>
          </fieldset>
        </form>

        <div className="space-y-6">
          {previewResult ? (
            <div className="space-y-6">
              {previewResult.status === "VALIDATED" ? (
                <>
                  <div className="panel space-y-4">
                    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 pb-4">
                      <div>
                        <span className="inline-block rounded bg-emerald-100 px-2.5 py-1 text-xs font-semibold text-emerald-800">
                          HÀNH TRÌNH KHẢ THI
                        </span>
                        <h2 className="mt-2 text-xl font-bold">
                          Kế hoạch chi tiết ngày {formatDateVn(previewResult.criteria.tripDate)}
                        </h2>
                        <p className="text-sm text-slate-500">
                          Khung giờ dự kiến: {previewResult.criteria.startTime} – {previewResult.criteria.endTime}
                        </p>
                      </div>
                      <div className="flex gap-4 text-right text-sm">
                        <div>
                          <p className="text-slate-500">Tổng tham quan</p>
                          <p className="font-bold text-slate-800">{previewResult.totals.totalDwellMinutes} phút</p>
                        </div>
                        <div>
                          <p className="text-slate-500">Tổng di chuyển</p>
                          <p className="font-bold text-slate-800">{previewResult.totals.totalTravelMinutes} phút</p>
                        </div>
                        <div>
                          <p className="text-slate-500">Quãng đường</p>
                          <p className="font-bold text-slate-800">{(previewResult.totals.totalDistanceMeters / 1000).toFixed(1)} km</p>
                        </div>
                      </div>
                    </div>

                    <div className="space-y-4">
                      {previewResult.items.map((item) => (
                        <div key={item.locationId} className="relative rounded-lg border border-slate-200 bg-white p-4">
                          <div className="flex items-start justify-between gap-3">
                            <div className="flex items-center gap-3">
                              <span className="flex h-7 w-7 items-center justify-center rounded-full bg-slate-900 text-xs font-bold text-white">
                                {item.sortOrder}
                              </span>
                              <div>
                                <h3 className="font-semibold text-slate-900">{item.locationName}</h3>
                                <p className="text-xs text-slate-500">{item.categoryName}</p>
                              </div>
                            </div>
                            <div className="text-right text-sm">
                              <span className="font-semibold text-slate-900">{item.arrival}</span>
                              <span className="text-slate-400"> → </span>
                              <span className="font-semibold text-slate-900">{item.departure}</span>
                              <p className="text-xs text-slate-500">Tham quan: {item.dwellMinutes} phút</p>
                            </div>
                          </div>

                          {item.waitMinutes > 0 && (
                            <div className="mt-2 text-xs text-amber-700 bg-amber-50 rounded px-2.5 py-1">
                              Chờ mở cửa: {item.waitMinutes} phút
                            </div>
                          )}

                          {item.fullHoursDescription && (
                            <div className="mt-2 text-xs text-slate-600">
                              Lịch mở cửa: <span className="font-medium">{item.fullHoursDescription}</span>
                            </div>
                          )}

                          {item.hoursEvidenceUrl && (
                            <div className="mt-1 text-xs text-slate-400">
                              Nguồn kiểm chứng:{" "}
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
                            <div className="mt-3 flex items-center gap-2 rounded bg-slate-50 p-2 text-xs text-slate-600">
                              <span>🚗 Chặng tiếp theo:</span>
                              <span className="font-medium text-slate-900">
                                {(item.distanceToNextMeters / 1000).toFixed(1)} km
                              </span>
                              <span>·</span>
                              <span className="font-medium text-slate-900">
                                ~{item.travelToNextMinutes} phút lái xe
                              </span>
                              <span className="text-slate-400">({item.osmAttribution || "OSRM"})</span>
                            </div>
                          )}
                        </div>
                      ))}
                    </div>

                    <div className="border-t border-slate-100 pt-4">
                      {saveSuccessId ? (
                        <div className="rounded-lg bg-emerald-50 p-4 text-emerald-800">
                          <p className="font-semibold">Hành trình đã được lưu thành công!</p>
                          <div className="mt-2 flex gap-3">
                            <Link to={`/user/itineraries/${saveSuccessId}`} className="btn text-sm">
                              Xem hành trình đã lưu →
                            </Link>
                            <Link to="/user/itineraries" className="btn-secondary text-sm">
                              Danh sách hành trình
                            </Link>
                          </div>
                        </div>
                      ) : (
                        <form onSubmit={handleSave} className="flex flex-wrap items-center gap-3">
                          <input
                            type="text"
                            required
                            maxLength={200}
                            value={saveTitle}
              onChange={(e) => setSaveTitle(e.target.value)}
              disabled={saving}
                            placeholder="Đặt tên cho hành trình..."
                            className="flex-1 min-w-[200px]"
                          />
                          {isCustomer ? (
                            <button
                              type="submit"
                              disabled={saving}
                              className="btn"
                            >
                              {saving ? "Đang lưu..." : "Lưu vào tài khoản"}
                            </button>
                          ) : (
                            <button
                              type="button"
                              onClick={() => navigate("/login")}
                              className="btn-secondary"
                            >
                              Đăng nhập tài khoản để lưu
                            </button>
                          )}
                        </form>
                      )}
                    </div>
                  </div>
                </>
              ) : (
                <div className="panel space-y-3">
                  <span className="inline-block rounded bg-amber-100 px-2.5 py-1 text-xs font-semibold text-amber-800">
                    {previewResult.status === "NO_FEASIBLE_PLAN"
                      ? "KHÔNG TÌM ĐƯỢC LỊCH TRÌNH PHÙ HỢP"
                      : previewResult.status === "NEEDS_DATA"
                      ? "THIẾU DỮ LIỆU KIỂM CHỨNG"
                      : "DỊCH VỤ TẠM THỜI GIÁN ĐOẠN"}
                  </span>
                  <p className="text-sm text-slate-700">
                    Hệ thống không thể tạo kế hoạch theo yêu cầu của bạn vì các lý do sau:
                  </p>
                  <ul className="list-disc pl-5 text-sm text-slate-600 space-y-1">
                    {previewResult.notices && previewResult.notices.length > 0 ? (
                      previewResult.notices.map((n, i) => <li key={i}>{n}</li>)
                    ) : (
                      <li>Khung giờ hoặc tuyến đường giữa các điểm không khả thi.</li>
                    )}
                  </ul>
                  <p className="text-xs text-slate-500">
                    Gợi ý: Hãy nới rộng khung giờ (ví dụ 08:00 đến 17:30) hoặc chọn điểm xuất phát khác.
                  </p>
                </div>
              )}
            </div>
          ) : (
            <EmptyState
              title="Chưa có bản xem trước hành trình"
              description="Điền thông tin và bấm 'Xem trước kế hoạch' để nhận lịch trình được tính toán bằng OSRM thực tế."
            />
          )}
        </div>
      </div>
    </Page>
  );
}
