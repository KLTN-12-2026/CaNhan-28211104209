import ReturnPolicySummary from '../../components/booking/ReturnPolicySummary';
import LicensePhotos from "../../components/user/LicensePhotos";
import BookingDeposit from "../../components/user/BookingDeposit";
import AdminBookingTable from "../../components/booking/AdminBookingTable";
import { bookingStatusLabels, bookingActionLabels, licenseStatusLabels, licenseSchemeLabels, incidentKindLabels, incidentStatusLabels } from "../../utils/uiLabels";
import { visibleBookings, workStatusLabels, vietnamDay, bookingSortLabels, adminDateFormat, adminBookingDate } from "../../utils/bookingQueue";
import BookingIdentity from "../../components/booking/BookingIdentity";
import BookingProgress from "../../components/booking/BookingProgress";
import FavoritesLink, { FavoriteToggle } from "../../components/vehicle/FavoriteControls";
import VehicleGallery from "../../components/vehicle/VehicleGallery";
import BrandBar from "../../components/vehicle/BrandBar";
import TripSearchForm from "../../components/vehicle/TripSearchForm";
import CompareTray, { CompareToggle, CompareLink } from "../../components/vehicle/CompareControls";
import { searchParams, searchSuffix, tripSearch } from "../../utils/vehicleSearch";
import PasswordField from "../../components/common/PasswordField";
import { passwordError, passwordHint } from "../../utils/passwordPolicy";
import {
  useEffect,
  useLayoutEffect,
  useMemo,
  useState,
  useRef,
} from "react";
import {
  Link,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router-dom";
import {
  Field,
  Missing,
  money,
  Notice,
  Page,
  Status,
  dateTime,
  dateOnly,
  bookingDate,
  toUTC,
} from "../../components/common/PageKit";
import EmptyState from "../../components/common/EmptyState";
import LoadingSkeleton from "../../components/common/LoadingSkeleton";
import VehicleCard, {
  VehicleVisual,
} from "../../components/vehicle/VehicleCard";
import {
  quoteRequestIsCurrent,
  sameBookingInput,
} from "../../api/bookingInput";
import { getBookingActions } from "../../api/bookingActions";
import apiClient from "../../api/client";
import { eventsForSelectedIncident } from "../../utils/incidentEvents";
import {
  applyBookingAction,
  createBooking,
  quoteBooking,
  saveLicense,
  updatePassword,
  updateProfile,
} from "../../api/rental";
import { getErrorMessage } from "../../utils/roles";
import { createRequestGeneration } from "../../utils/requestGeneration";
import { useAuth } from "../../context/AuthContext";
import { getStoredToken } from "../../utils/storage";
import { useApiData } from "../../hooks/useApiData";
import useNow from "../../hooks/useNow";

function RequestState({ loading, error, onRetry, children }) {
  if (loading) return <LoadingSkeleton rows={3} />;
  if (error)
    return (
      <div>
        <Notice error>{error}</Notice>
        <button className="btn-secondary" onClick={onRetry}>
          Thử tải lại
        </button>
      </div>
    );
  return children;
}

export function normalizeVehicle(vehicle, catalog) {
  const brandNames = new Map(
    (catalog?.brands || []).map((item) => [item.id, item.name]),
  );
  const categoryNames = new Map(
    (catalog?.categories || []).map((item) => [item.id, item.name]),
  );
  return {
    ...vehicle,
    noLocalAsset: true,
    images: Array.isArray(vehicle.images) ? [...vehicle.images].sort((a, b) => a.sortOrder - b.sortOrder || String(a.id).localeCompare(String(b.id))) : [],
    price: vehicle.dailyPrice,
    brand:
      brandNames.get(vehicle.brandId) || vehicle.brandId || "Chưa phân loại",
    category:
      categoryNames.get(vehicle.categoryId) ||
      vehicle.categoryId ||
      "Chưa phân loại",
    transmission: vehicle.transmission === "AUTO" ? "Tự động" : "Số sàn",
    fuel: "Chưa cập nhật",
    amenities: Array.isArray(vehicle.amenities) ? vehicle.amenities : [],
  };
}

function VehicleOperationalStatus({ status }) {
  const operating = status === "ACTIVE";
  const label =
    status === "ACTIVE"
      ? "Đang khai thác"
      : status === "MAINTENANCE"
        ? "Đang bảo trì"
        : "Tạm ngưng";
  return (
    <span
      className={`inline-flex rounded-full px-3 py-1 text-sm font-semibold ${
        operating
          ? "bg-emerald-50 text-emerald-800"
          : "bg-amber-50 text-amber-800"
      }`}
      role="status"
    >
      {label}
    </span>
  );
}

function localDateTime(value) {
  if (!value) return "";
  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp)
    ? ""
    : new Date(timestamp + 7 * 3600000).toISOString().slice(0, 16);
}

function IncidentSection({ booking, records = [], area }) {
  const request = useApiData(
    `/incidents?bookingId=${encodeURIComponent(booking.id)}`,
  );
  const [selectedId, setSelectedId] = useState("");
  const [eventHistory, setEventHistory] = useState({
    incidentId: null,
    events: [],
  });
  const [eventsLoading, setEventsLoading] = useState(false);
  const [eventsError, setEventsError] = useState("");
  const [formError, setFormError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const reportPending = useRef(false);
  const actionPending = useRef(false);
  const loadEventsRequestId = useRef(0);
  const cases = Array.isArray(request.data) ? request.data : [];
  const selectedEvents = eventsForSelectedIncident(selectedId, eventHistory);
  const handoverRecord = records.find((record) => record.kind === "HANDOVER");
  const returnRecord = records.find((record) => record.kind === "RETURN");
  const custodyStart = localDateTime(handoverRecord?.occurred_at);
  const custodyEnd =
    localDateTime(returnRecord?.occurred_at) || toVietnamInput(0);
  const canReport = ["IN_PROGRESS", "RETURNED", "COMPLETED"].includes(
    booking.status,
  );

  async function report(event) {
    event.preventDefault();
    if (reportPending.current) return;
    reportPending.current = true;
    setFormError("");
    setNotice("");
    setBusy(true);
    const target = event.currentTarget;
    const form = new FormData(target);
    try {
      await apiClient.post("/incidents", {
        bookingId: booking.id,
        kind: String(form.get("kind") || ""),
        occurredAt: toUTC(String(form.get("occurredAt") || "")),
        description: String(form.get("description") || ""),
        evidenceReference: String(form.get("evidenceReference") || "") || null,
        blockVehicle: area !== "user" && form.get("blockVehicle") === "on",
      });
      setNotice(
        "Đã lập hồ sơ sự cố. Chưa có kết luận trách nhiệm hoặc khấu trừ.",
      );
      request.reload();
      target.reset();
    } catch (error) {
      setFormError(getErrorMessage(error, "Không thể lập hồ sơ sự cố."));
    } finally {
      reportPending.current = false;
      setBusy(false);
    }
  }

  async function updateIncident(event, incident, next) {
    event.preventDefault();
    if (actionPending.current) return;
    actionPending.current = true;
    setFormError("");
    setNotice("");
    setBusy(true);
    const form = new FormData(event.currentTarget);
    try {
      await apiClient.post(
        `/incidents/${encodeURIComponent(incident.id)}/actions`,
        {
          next,
          version: incident.version,
          assessedDamage: Number(form.get("assessedDamage") || 0),
          thirdPartyCoverage: Number(form.get("thirdPartyCoverage") || 0),
          requestedAmount: Number(form.get("requestedAmount") || 0),
          responsibilityBasis: String(form.get("responsibilityBasis") || ""),
          note: String(form.get("note") || ""),
        },
      );
      setNotice(`Đã cập nhật hồ sơ sự cố: ${incidentStatusLabels[next] || "Đã cập nhật"}.`);
      request.reload();
      setSelectedId("");
    } catch (error) {
      setFormError(getErrorMessage(error, "Không thể cập nhật hồ sơ sự cố."));
    } finally {
      actionPending.current = false;
      setBusy(false);
    }
  }

  async function loadEvents(id) {
    setSelectedId(id);
    setEventsError("");
    const thisRequestId = ++loadEventsRequestId.current;
    setEventsLoading(true);
    try {
      const { data } = await apiClient.get(
        `/incidents/${encodeURIComponent(id)}/events`,
      );
      if (loadEventsRequestId.current !== thisRequestId) {
        return;
      }
      setEventHistory({
        incidentId: id,
        events: Array.isArray(data) ? data : [],
      });
    } catch (error) {
      if (loadEventsRequestId.current !== thisRequestId) {
        return;
      }
      setEventsError(getErrorMessage(error, "Không thể tải lịch sử hồ sơ."));
    } finally {
      if (loadEventsRequestId.current === thisRequestId) {
        setEventsLoading(false);
      }
    }
  }

  return (
    <section className="panel space-y-4">
      <div>
        <h2 className="text-lg font-semibold">Hồ sơ sự cố</h2>
        <p className="mt-1 text-sm text-slate-600">
          Mọi khoản yêu cầu cần có căn cứ; không tự kết luận trách nhiệm hoặc
          khấu trừ tiền cọc.
        </p>
      </div>
      {notice ? <Notice>{notice}</Notice> : null}
      {formError ? <Notice error>{formError}</Notice> : null}
      <RequestState
        loading={request.loading}
        error={request.error}
        onRetry={request.reload}
      >
        {cases.length ? (
          <div className="space-y-3">
            {cases.map((incident) => (
              <article
                className="rounded-xl border border-slate-200 p-4"
                key={incident.id}
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <h3 className="font-semibold">
                      {incidentKindLabels[incident.kind] || "Sự cố"} · {incidentStatusLabels[incident.status] || "Chưa xác định"}
                    </h3>
                    <p className="mt-1 text-sm text-slate-600">
                      {incident.description}
                    </p>
                    <p className="mt-1 text-xs text-slate-500">
                      Xảy ra {area === "admin" ? adminDateFormat(incident.occurredAt, { withTime: true }) : dateTime(incident.occurredAt)} · Báo cáo{" "}
                      {area === "admin" ? adminDateFormat(incident.reportedAt, { withTime: true }) : dateTime(incident.reportedAt)}
                    </p>
                    {incident.evidenceReference ? (
                      <p className="mt-1 text-xs text-slate-500">
                        Tài liệu: {incident.evidenceReference}
                      </p>
                    ) : null}
                    {incident.status !== "OPEN" ? (
                      <p className="mt-2 text-sm text-slate-600">
                        Thiệt hại {money(incident.assessedDamage)} · Bên khác
                        chi trả {money(incident.thirdPartyCoverage)} · Yêu cầu{" "}
                        {money(incident.requestedAmount)}
                      </p>
                    ) : null}
                  </div>
                  <button
                    className="btn-secondary"
                    onClick={() => loadEvents(incident.id)}
                  >
                    Lịch sử
                  </button>
                </div>
                {selectedId === incident.id ? (
                  <ol className="mt-3 space-y-2 border-t pt-3 text-sm">
                    {eventsLoading ? (
                      <li role="status">Đang tải lịch sử hồ sơ...</li>
                    ) : eventsError ? (
                      <li role="alert">
                        <p>{eventsError}</p>
                        <button
                          className="btn-secondary mt-2"
                          onClick={() => loadEvents(incident.id)}
                        >
                          Thử tải lại lịch sử
                        </button>
                      </li>
                    ) : selectedEvents.length ? (
                      selectedEvents.map((item, index) => (
                        <li key={`${item.occurred_at}-${index}`}>
                          <p>
                            {incidentStatusLabels[item.action] || "Cập nhật hồ sơ"} · {area === "admin" ? adminDateFormat(item.occurred_at, { withTime: true }) : dateTime(item.occurred_at)}
                            {item.previous_status || item.next_status
                              ? ` · ${incidentStatusLabels[item.previous_status] || "Mới"} → ${incidentStatusLabels[item.next_status] || "Cập nhật hồ sơ"}`
                              : ""}
                          </p>
                          <p>{item.note}</p>
                          {item.assessed_damage != null ||
                          item.third_party_coverage != null ||
                          item.requested_amount != null ? (
                            <p className="text-xs text-slate-600">
                              Thiệt hại {money(item.assessed_damage ?? 0)} ·
                              Bên khác chi trả{" "}
                              {money(item.third_party_coverage ?? 0)} · Yêu
                              cầu {money(item.requested_amount ?? 0)}
                            </p>
                          ) : null}
                          {item.responsibility_basis ? (
                            <p className="text-xs">
                              Căn cứ trách nhiệm: {item.responsibility_basis}
                            </p>
                          ) : null}
                        </li>
                      ))
                    ) : (
                      <li>Chưa có lịch sử hồ sơ.</li>
                    )}
                  </ol>
                ) : null}
                {(area !== "user" &&
                  ["OPEN", "DISPUTED"].includes(incident.status)) ||
                (area === "admin" && incident.status === "ASSESSED") ? (
                  <form
                    className="mt-4 grid gap-3 border-t pt-4 sm:grid-cols-2"
                    onSubmit={(event) =>
                      updateIncident(
                        event,
                        incident,
                        incident.status === "ASSESSED" ? "CLAIMED" : "ASSESSED",
                      )
                    }
                  >
                    {incident.status !== "ASSESSED" ? (
                      <>
                        <Field label="Thiệt hại xác minh (VND)">
                          <input
                            name="assessedDamage"
                            type="number"
                            min="0"
                            required
                          />
                        </Field>
                        <Field label="Bảo hiểm/bên khác chi trả (VND)">
                          <input
                            name="thirdPartyCoverage"
                            type="number"
                            min="0"
                            required
                          />
                        </Field>
                        <Field label="Khoản yêu cầu (VND)">
                          <input
                            name="requestedAmount"
                            type="number"
                            min="0"
                            required
                          />
                        </Field>
                        <Field label="Căn cứ trách nhiệm">
                          <input
                            name="responsibilityBasis"
                            required
                            maxLength={1500}
                          />
                        </Field>
                      </>
                    ) : null}
                    <Field label="Ghi chú hồ sơ">
                      <input name="note" required maxLength={1500} />
                    </Field>
                    <button className="btn" disabled={busy}>
                      {incident.status === "ASSESSED"
                        ? "Gửi yêu cầu (quản trị viên)"
                        : "Lưu đánh giá"}
                    </button>
                  </form>
                ) : null}
                {area === "user" && incident.status === "CLAIMED" ? (
                  <form
                    className="mt-4 flex flex-wrap gap-3 border-t pt-4"
                    onSubmit={(event) =>
                      updateIncident(event, incident, "DISPUTED")
                    }
                  >
                    <Field label="Lý do phản hồi">
                      <input name="note" required maxLength={1500} />
                    </Field>
                    <button className="btn-secondary" disabled={busy}>
                      Gửi phản hồi
                    </button>
                  </form>
                ) : null}
                {area === "admin" && incident.status !== "CLOSED" ? (
                  <form
                    className="mt-4 flex flex-wrap gap-3 border-t pt-4"
                    onSubmit={(event) =>
                      updateIncident(event, incident, "CLOSED")
                    }
                  >
                    <Field label="Ghi chú đóng hồ sơ">
                      <input name="note" required maxLength={1500} />
                    </Field>
                    <button className="btn-secondary" disabled={busy}>
                      Đóng hồ sơ
                    </button>
                  </form>
                ) : null}
              </article>
            ))}
          </div>
        ) : (
          <EmptyState title="Chưa có hồ sơ sự cố" />
        )}
      </RequestState>
      {canReport ? (
        <form
          className="grid gap-3 border-t pt-4 sm:grid-cols-2"
          onSubmit={report}
        >
          <Field label="Loại sự cố">
            <select name="kind">
              <option value="DAMAGE">Hư hỏng</option>
              <option value="ACCIDENT">Tai nạn</option>
              <option value="VIOLATION">Vi phạm</option>
            </select>
          </Field>
          <Field label="Thời điểm xảy ra">
            <input
              name="occurredAt"
              type="datetime-local"
              required
              min={custodyStart || undefined}
              max={custodyEnd}
              defaultValue={custodyEnd}
            />
          </Field>
          <p className="text-xs text-slate-500 sm:col-span-2">
            {custodyStart
              ? `Thời điểm phải thuộc thời gian giữ xe thực tế: từ ${area === "admin" ? adminDateFormat(handoverRecord.occurred_at, { withTime: true }) : dateTime(handoverRecord.occurred_at)} đến ${returnRecord ? (area === "admin" ? adminDateFormat(returnRecord.occurred_at, { withTime: true }) : dateTime(returnRecord.occurred_at)) : "hiện tại"}. Hệ thống vẫn kiểm tra lại theo biên bản.`
              : "Cần có biên bản giao xe thực tế để xác định thời gian sự cố hợp lệ."}
          </p>
          <Field label="Mô tả có căn cứ">
            <textarea name="description" required maxLength={1500} />
          </Field>
          <Field label="Mã/tài liệu tham chiếu (không tải tệp)">
            <input name="evidenceReference" maxLength={2000} />
          </Field>
          {area !== "user" ? (
            <label className="flex gap-3 text-sm">
              <input name="blockVehicle" type="checkbox" /> Đưa xe vào bảo trì
            </label>
          ) : null}
          <button className="btn" disabled={busy}>
            Lập hồ sơ sự cố
          </button>
        </form>
      ) : null}
    </section>
  );
}

export function ApiHomePage() {
  const { user: currentUser } = useAuth();
  const vehiclesRequest = useApiData("/vehicles");
  const catalogRequest = useApiData("/vehicles/catalog");
  const [query, setQuery] = useState("");

  const vehicles = useMemo(() => {
    const rawList = Array.isArray(vehiclesRequest.data)
      ? vehiclesRequest.data
      : [];
    const normalized = rawList.map((vehicle) =>
      normalizeVehicle(vehicle, catalogRequest.data),
    );
    const filtered = query.trim()
      ? normalized.filter((vehicle) =>
          `${vehicle.name} ${vehicle.brand} ${vehicle.plate}`
            .toLocaleLowerCase("vi")
            .includes(query.trim().toLocaleLowerCase("vi")),
        )
      : normalized;

    // Sắp xếp xác định: theo tên tăng dần, sau đó theo ID
    const sorted = [...filtered].sort((a, b) => {
      const nameComp = a.name.localeCompare(b.name, "vi");
      if (nameComp !== 0) return nameComp;
      return String(a.id).localeCompare(String(b.id));
    });

    return sorted.slice(0, 6);
  }, [vehiclesRequest.data, catalogRequest.data, query]);

  const loading = vehiclesRequest.loading || catalogRequest.loading;
  const error = vehiclesRequest.error || catalogRequest.error;
  const retry = () => {
    vehiclesRequest.reload();
    catalogRequest.reload();
  };

  const isUser = currentUser?.role === "USER";

  return (
    <><BrandBar vehicles={vehiclesRequest.data} catalog={catalogRequest.data} loading={loading} error={error} />
    <div className="page-shell urban-home">
      <section className="urban-hero">
        <div className="urban-hero-copy">
          <p className="urban-kicker">KDRIVE · THUÊ XE TỰ LÁI</p>
          <h1>Mỗi chuyến đi,<br /><em>một câu chuyện.</em></h1>
          <p className="urban-intro">Tự do chọn hành trình. Tìm chiếc xe phù hợp, chọn điểm nhận và trả, khám phá theo cách của bạn.</p>
          <div className="mt-7 flex flex-wrap gap-3">
            <Link className="btn" to="/vehicles">Xem toàn bộ xe ↗</Link>
            {isUser ? <Link className="btn-secondary" to="/user/bookings">Đơn thuê của tôi</Link> : !currentUser ? <Link className="btn-secondary" to="/register">Đăng ký tài khoản</Link> : null}
            {isUser ? <Link className="btn-secondary" to="/user/profile">Hồ sơ &amp; GPLX</Link> : null}
          </div>
          <p className="urban-smallprint">Giá theo ngày · Giữ xe đến hết ngày trả · Nhận / trả theo tuyến chi nhánh</p>
        </div>
        <div className="urban-hero-photo">
          {!loading && !error && vehicles[0] ? <VehicleGallery vehicle={vehicles[0]} /> : <div className="urban-photo-empty">Hành trình của bạn bắt đầu tại đây.</div>}
          <div className="urban-photo-caption"><span>ĐỒNG HÀNH CÙNG BẠN</span><strong>{!loading && !error ? vehicles[0]?.name || "Khám phá danh mục xe" : "Khám phá danh mục xe"}</strong>{vehicles[0]?.images?.length ? <small>Ảnh minh họa</small> : null}</div>
        </div>
      </section>
      <section className="urban-promises" aria-label="Thông tin thuê xe">
        <div><span>01 / MINH BẠCH</span><h2>Giá rõ ràng</h2><p>Giá niêm yết từ hệ thống; báo giá kiểm tra lịch, tuyến và giá trước khi gửi yêu cầu.</p></div>
        <div><span>02 / LINH HOẠT</span><h2>Chọn tuyến phù hợp</h2><p>Nhận và trả tại các chi nhánh theo tuyến đang được hỗ trợ.</p></div>
        <div><span>03 / CHỦ ĐỘNG</span><h2>Theo dõi đơn thuê</h2><p>Xem trạng thái, thời gian thuê và chứng từ của bạn tại một nơi.</p></div>
      </section>
      <div className="urban-section-heading"><div><p className="urban-kicker">DÀNH CHO HÀNH TRÌNH TIẾP THEO</p><h2>Chiếc xe của bạn.</h2></div><Link to="/vehicles" className="btn-secondary">Khám phá danh mục →</Link></div>

      {/* Danh sách xe nổi bật và Search bar */}
      <section className="space-y-6">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div className="max-w-md flex-1">
            <Field label="Tìm kiếm theo tên xe hoặc hãng">
              <input
                type="search"
                placeholder="Nhập tên xe, hãng xe (ví dụ: Vios, Accent)..."
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
            </Field>
          </div>
          <div className="text-sm font-medium text-slate-500">
            {query.trim() ? (
              <span>Kết quả tìm kiếm ({vehicles.length})</span>
            ) : (
              <span>Hiển thị tối đa 6 xe</span>
            )}
          </div>
        </div>

        <RequestState loading={loading} error={error} onRetry={retry}>
          {vehicles.length > 0 ? (
            <div>
              <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
                {vehicles.map((vehicle) => (
                  <VehicleCard key={vehicle.id} vehicle={vehicle} />
                ))}
              </div>
              <div className="mt-8 flex justify-center border-t border-slate-200 pt-6">
                <Link
                  className="btn-secondary inline-flex items-center gap-2 px-6 py-2.5 font-medium"
                  to="/vehicles"
                >
                  Xem thêm <span aria-hidden="true">→</span>
                </Link>
              </div>
            </div>
          ) : (
            <EmptyState
              title={query.trim() ? "Không tìm thấy xe phù hợp" : "Chưa có xe nào trong hệ thống"}
              description={
                query.trim()
                  ? "Thử tìm kiếm với từ khóa khác hoặc duyệt danh mục xe đầy đủ."
                  : "Hệ thống hiện chưa có danh sách xe khai thác."
              }
              action={
                query.trim() ? (
                  <div className="flex flex-wrap gap-3">
                    <button
                      type="button"
                      className="btn-secondary"
                      onClick={() => setQuery("")}
                    >
                      Xóa từ khóa tìm kiếm
                    </button>
                    <Link className="btn" to="/vehicles">
                      Duyệt danh sách đầy đủ
                    </Link>
                  </div>
                ) : (
                  <button type="button" className="btn-secondary" onClick={retry}>
                    Tải lại danh sách
                  </button>
                )
              }
            />
          )}
        </RequestState>
      </section>
    </div></>
  );
}

export function ApiVehicleListPage() {
  const compareEntry = useRef(null);
  const [params, setParams] = useSearchParams();
  const brand = params.get("brand") || "";
  const vehiclesRequest = useApiData("/vehicles");
  const catalogRequest = useApiData("/vehicles/catalog");
  const branchesRequest = useApiData("/branches");
  const trip = tripSearch(params);
  const tripRequest = useApiData(trip.path, trip.active && !trip.error);
  const update = changes => setParams(searchParams(params, changes));
  const query = params.get("q") || "";

  const category = params.get("category") || "";
  const seats = params.get("seats") || "";
  const transmission = params.get("transmission") || "";
  const sort = params.get("sort") || "", setSort = sort => update({ sort });
  const source = trip.active ? (tripRequest.data?.items || []).map(item => ({ ...item.vehicle, tripTotal: item.total, tripDays: item.rentalDays })) : vehiclesRequest.data;
  const vehicles = useMemo(() => {
    const rows = (
      Array.isArray(source) ? source : []
    )
      .map((vehicle) => ({ ...normalizeVehicle(vehicle, catalogRequest.data), tripTotal: vehicle.tripTotal, tripDays: vehicle.tripDays }))
      .filter(
        (vehicle) =>
          (!query ||
            `${vehicle.name} ${vehicle.brand} ${vehicle.plate}`
              .toLocaleLowerCase("vi")
              .includes(query.toLocaleLowerCase("vi"))) &&
          (!brand || String(vehicle.brandId) === brand) &&
          (!category || vehicle.categoryId === category) &&
          (!seats || vehicle.seats >= Number(seats)) &&
          (!transmission || vehicle.transmission === transmission),
      );
    return rows.sort((a, b) =>
      sort === "price-asc"
        ? (a.tripTotal ?? a.price) - (b.tripTotal ?? b.price)
        : sort === "price-desc"
          ? (b.tripTotal ?? b.price) - (a.tripTotal ?? a.price)
          : a.name.localeCompare(b.name, "vi"),
    );
  }, [
    source,
    catalogRequest.data,
    brand,
    query,
    category,
    seats,
    transmission,
    sort,
  ]);
  const loading = (trip.active && !trip.error ? tripRequest.loading : vehiclesRequest.loading) || catalogRequest.loading;
  const error = trip.error || (trip.active ? tripRequest.error : vehiclesRequest.error) || catalogRequest.error;
  const retry = () => {
    vehiclesRequest.reload();
    catalogRequest.reload();
    tripRequest.reload();
  };
  return (
    <><BrandBar vehicles={vehiclesRequest.data} catalog={catalogRequest.data} loading={vehiclesRequest.loading || catalogRequest.loading} error={vehiclesRequest.error || catalogRequest.error} />
    <Page
      title="Danh sách xe"
      description="Chọn xe theo nhu cầu hoặc tìm lịch trống cho thời gian và tuyến nhận/trả của bạn."
    >
      <TripSearchForm key={["start","end","pickupBranchId","returnBranchId","budget"].map(key => params.get(key) || "").join("|")}
        params={params} catalog={catalogRequest.data}
        branches={(Array.isArray(branchesRequest.data) ? branchesRequest.data : []).filter(branch => branch.status === "ACTIVE")}
        loading={branchesRequest.loading} error={branchesRequest.error} onRetry={branchesRequest.reload}
        onSubmit={update} onUpdate={update} onClear={() => setParams({...brand ? {brand} : {},...params.get("compare") ? {compare:params.get("compare")} : {}})} />
      <div className="search-results-heading vehicle-list-toolbar">
        <p aria-live="polite">{loading ? "Đang tìm xe…" : error ? "Chưa thể hiển thị kết quả" : `${vehicles.length} xe phù hợp`}</p>
        <div className="vehicle-list-tools"><FavoritesLink compact /><CompareLink linkRef={compareEntry} />
        <div className="search-result-sort"><label htmlFor="vehicle-result-sort">Sắp xếp</label><select id="vehicle-result-sort" value={sort} onChange={event => setSort(event.target.value)}>
          <option value="">Tên xe</option><option value="price-asc">Giá thấp trước</option><option value="price-desc">Giá cao trước</option>
        </select></div></div>
      </div>
      <details className="favorites-privacy"><summary>Về lưu xe yêu thích</summary><p>Chỉ lưu trên trình duyệt này, tách riêng khách và từng tài khoản. Không đồng bộ thiết bị.</p></details>
      <CompareTray compact vehicles={vehicles} onRemove={() => compareEntry.current?.focus()} />
      <RequestState loading={loading} error={error} onRetry={retry}>
        {vehicles.length ? (
          <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
            {vehicles.map((vehicle) => (
              <VehicleCard key={vehicle.id} vehicle={vehicle} search={searchSuffix(params)} compareControl={<><FavoriteToggle vehicleId={vehicle.id} name={vehicle.name} /><CompareToggle vehicleId={vehicle.id} name={vehicle.name} /></>} />
            ))}
          </div>
        ) : (
          <EmptyState
            title="Không tìm thấy xe"
            description="Thử đổi điều kiện tìm kiếm hoặc xóa bộ lọc."

          />
        )}
      </RequestState>
    </Page></>
  );
}

export function ApiVehicleDetailPage() {
  const { vehicleId } = useParams();
  const [params] = useSearchParams();
  const branchesRequest = useApiData("/branches");
  const selectedTrip = tripSearch(params);
  const branchName = id => (Array.isArray(branchesRequest.data) ? branchesRequest.data : []).find(branch => branch.id === id)?.name || "Chưa xác định";
  const vehicleRequest = useApiData(
    `/vehicles/${encodeURIComponent(vehicleId)}`,
  );
  const catalogRequest = useApiData("/vehicles/catalog");
  const vehicleReviewsRequest = useApiData(
    `/vehicles/${encodeURIComponent(vehicleId)}/reviews`,
  );
  const vehicle = vehicleRequest.data
    ? normalizeVehicle(vehicleRequest.data, catalogRequest.data)
    : null;
  const loading = vehicleRequest.loading || catalogRequest.loading;
  const error = vehicleRequest.error || catalogRequest.error;
  return (
    <Page title={vehicle?.name || "Chi tiết xe"}>
      <div className="vehicle-detail-tools"><Link className="btn-secondary" to={`/vehicles${searchSuffix(params)}`}>Về danh sách xe</Link>
      <FavoritesLink compact /><CompareLink /></div>
      <RequestState
        loading={loading}
        error={error}
        onRetry={() => {
          vehicleRequest.reload();
          catalogRequest.reload();
        }}
      >
        {!vehicle ? (
          <Missing title="Không tìm thấy xe" back="/vehicles" />
        ) : (
          <div className="grid gap-6 lg:grid-cols-[1fr_360px]">
            <section className="panel">
              <VehicleGallery vehicle={vehicle} />
              <h2 className="mt-5 text-xl font-semibold">{vehicle.name}</h2>
              <p className="mt-2 text-sm text-slate-600">
                {vehicle.brand} · {vehicle.category} · {vehicle.seats} chỗ ·{" "}
                {vehicle.transmission}
              </p>
              <p className="mt-4 leading-7 text-slate-700">
                {vehicle.description || "Chưa có mô tả cho xe này."}
              </p>
              <p className="mt-4 text-sm text-slate-600">
                Chi nhánh hiện tại: {branchesRequest.loading ? "Đang tải tên chi nhánh…" : branchesRequest.error ? "Chưa tải được tên chi nhánh" : branchName(vehicle.currentBranchId)}
              </p>
              {branchesRequest.error ? <button type="button" className="btn-secondary mt-3" onClick={branchesRequest.reload}>Tải lại tên chi nhánh</button> : null}
              {vehicle.amenities && vehicle.amenities.length > 0 ? (
                <div className="mt-5 border-t border-slate-100 pt-5">
                  <h3 className="mb-3 text-sm font-semibold text-slate-700">
                    Tiện nghi ({vehicle.amenities.length})
                  </h3>
                  <ul className="flex flex-wrap gap-2">
                    {vehicle.amenities.map((a) => (
                      <li
                        key={a.id}
                        className="inline-flex items-center gap-1 rounded-md bg-teal-50 px-2.5 py-1 text-xs font-medium text-teal-800"
                      >
                        {a.name}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : (
                <p className="mt-5 border-t border-slate-100 pt-5 text-sm text-slate-500">
                  Chưa có thông tin tiện nghi được cung cấp; vui lòng xác nhận với chi nhánh.
                </p>
              )}
            </section>
            <aside className="panel h-fit">
              <VehicleOperationalStatus status={vehicle.status} />
              <p className="mt-4 text-2xl font-bold text-teal-800">
                {money(vehicle.price)}{" "}
                <span className="text-sm font-normal">/ ngày</span>
              </p>
              <p className="mt-3 text-sm text-slate-600">
                Báo giá sẽ kiểm tra lịch xe, tuyến chi nhánh và giá hiện tại.
              </p>
              {selectedTrip.error ? <Notice error>{selectedTrip.error}</Notice> : selectedTrip.active ? (
                <dl className="vehicle-trip-summary">
                  <div><dt>Nhận xe · Giờ Việt Nam</dt><dd>{(params.get("start").length === 10 ? dateOnly : dateTime)(toUTC(params.get("start")))} · {branchName(params.get("pickupBranchId"))}</dd></div>
                  <div><dt>Trả xe · Giờ Việt Nam</dt><dd>{(params.get("end").length === 10 ? dateOnly : dateTime)(toUTC(params.get("end")))} · {branchName(params.get("returnBranchId") || params.get("pickupBranchId"))}</dd></div>
                </dl>
              ) : <p className="mt-4 text-sm text-muted">Chọn thời gian và điểm nhận/trả ở bước kiểm tra báo giá.</p>}
              {vehicle.status === "ACTIVE" ? (
                <Link
                  className="btn mt-5 w-full"
                  to={`/user/bookings/new?vehicleId=${encodeURIComponent(vehicle.id)}${searchSuffix(params).replace(/^\?/, "&")}`}
                >
                  Kiểm tra giá & lịch
                </Link>
              ) : null}
              <div className="vehicle-card-actions mt-4"><FavoriteToggle vehicleId={vehicle.id} name={vehicle.name} /><CompareToggle vehicleId={vehicle.id} name={vehicle.name} /></div>
            </aside>
            <section className="panel lg:col-span-2">
              <h2 className="mb-4 text-lg font-semibold">
                Đánh giá của khách hàng
              </h2>
              <RequestState
                loading={vehicleReviewsRequest.loading}
                error={vehicleReviewsRequest.error}
                onRetry={vehicleReviewsRequest.reload}
              >
                {vehicleReviewsRequest.data?.length ? (
                  <div className="space-y-4">
                    {vehicleReviewsRequest.data.map((review) => (
                      <article
                        className="border-t border-slate-100 pt-4"
                        key={review.id}
                      >
                        <p className="font-semibold">{review.rating}/5 sao</p>
                        <p className="mt-2 text-sm leading-6 text-slate-700">
                          {review.content}
                        </p>
                        <p className="mt-2 text-xs text-slate-500">
                          {dateTime(review.created_at)}
                        </p>
                      </article>
                    ))}
                  </div>
                ) : (
                  <p className="text-sm text-slate-500">
                    Xe chưa có đánh giá công khai.
                  </p>
                )}
              </RequestState>
            </section>
          </div>
        )}
      </RequestState>
    </Page>
  );
}

function toVietnamInput(days) {
  return new Date(Date.now() + days * 86400000 + 7 * 3600000)
    .toISOString()
    .slice(0, 16);
}

export function ApiNewBookingPage() {
  const [params] = useSearchParams();
  const vehicleId = params.get("vehicleId") || "";
  const vehicleRequest = useApiData(
    `/vehicles/${encodeURIComponent(vehicleId)}`,
    Boolean(vehicleId),
  );
  const branchesRequest = useApiData("/branches");
  const routesRequest = useApiData("/branch-routes");
  const [start, setStart] = useState(
    () => (params.get("start") || toVietnamInput(3)).slice(0,10),
  );
  const [end, setEnd] = useState(() => (params.get("end") || toVietnamInput(4)).slice(0,10));
  const [pickupChoice, setPickup] = useState(() => params.get("pickupBranchId"));
  // null follows pickup; an explicit empty value requires the customer to choose.
  const [dropoffChoice, setDropoff] = useState(() => params.get("returnBranchId"));
  const [quoteValue, setQuote] = useState(null);
  const [quoteInput, setQuoteInput] = useState(null);
  const [error, setError] = useState("");
  const [accepted, setAccepted] = useState(false);
  const [returnPolicyAccepted, setReturnPolicyAccepted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();
  const [quoteRequestGeneration] = useState(createRequestGeneration);
  const branches = (branchesRequest.data || []).filter(
    (branch) => branch.status === "ACTIVE",
  );
  const normalized = vehicleRequest.data
    ? normalizeVehicle(vehicleRequest.data, null)
    : null;
  const defaultPickup = vehicleRequest.data?.currentBranchId;
  const requestedPickup = pickupChoice ?? defaultPickup;
  const pickup = branches.some((branch) => branch.id === requestedPickup) ? requestedPickup : "";
  const routes = (routesRequest.data || []).filter(
    (route) => route.enabled && route.pickupBranchId === pickup &&
      branches.some((branch) => branch.id === route.returnBranchId),
  );
  const requestedDropoff = dropoffChoice ?? pickup;
  const dropoff = routes.some((route) => route.returnBranchId === requestedDropoff) ? requestedDropoff : "";
  useLayoutEffect(() => {
    quoteRequestGeneration.invalidate();
  }, [quoteRequestGeneration, vehicleId, pickup, dropoff, start, end]);

  function getCurrentInput() {
    return {
      vehicleId,
      pickupBranchId: pickup,
      returnBranchId: dropoff,
      pickupDate: start,
      returnDate: end,
    };
  }

  const quote =
    !vehicleRequest.loading && !branchesRequest.loading && !routesRequest.loading &&
    !vehicleRequest.error && !branchesRequest.error && !routesRequest.error &&
    quoteInput && sameBookingInput(getCurrentInput(), quoteInput)
      ? quoteValue
      : null;

  function inputChanged(input) {
    return !sameBookingInput(input, quoteInput);
  }

  function updateQuoteField(field, setter, value) {
    quoteRequestGeneration.invalidate();
    setQuote(null);
    setQuoteInput(null);
    setAccepted(false);
    setReturnPolicyAccepted(false);
    setError("");
    setBusy(false);
    setter(value);
  }

  async function checkQuote(event) {
    event.preventDefault();
    if (!pickup || !dropoff) {
      setError("Hãy chọn chi nhánh nhận và tuyến trả xe đang mở.");
      return;
    }
    setError("");
    setAccepted(false);
    setReturnPolicyAccepted(false);
    setBusy(true);

    const input = getCurrentInput();
    const thisRequestId = quoteRequestGeneration.begin();
    setQuote(null);
    setQuoteInput(input);

    try {
      const result = await quoteBooking(input);
      if (
        !quoteRequestIsCurrent(
          quoteRequestGeneration,
          thisRequestId,
          input,
          getCurrentInput(),
        )
      ) return;
      setQuote(result);
    } catch (requestError) {
      if (
        quoteRequestIsCurrent(
          quoteRequestGeneration,
          thisRequestId,
          input,
          getCurrentInput(),
        )
      ) {
        setError(
          getErrorMessage(requestError, "Không thể kiểm tra giá và lịch xe."),
        );
      }
    } finally {
      if (quoteRequestGeneration.isCurrent(thisRequestId)) {
        setBusy(false);
      }
    }
  }

  async function submitBooking() {
    if (!quote || !accepted || busy || (quote.returnPolicy && !returnPolicyAccepted)) return;
    if (inputChanged(getCurrentInput())) {
      setError("Thông tin đã thay đổi. Vui lòng kiểm tra lại báo giá.");
      setQuote(null);
      setQuoteInput(null);
      setAccepted(false);
      setReturnPolicyAccepted(false);
      return;
    }
    setError("");
    setBusy(true);
    setSubmitting(true);
    try {
      const currentInput = getCurrentInput();
      const booking = await createBooking({
        ...currentInput,
        expectedTotal: quote.total,
        ...(quote.returnPolicy ? { returnPolicyVersion: quote.returnPolicy.version, returnPolicyAccepted } : {}),
      });
      navigate(`/user/bookings/${booking.id}`, {
        replace: true,
        state: { created: true },
      });
    } catch (requestError) {
      setQuote(null);
      setQuoteInput(null);
      setAccepted(false);
      setReturnPolicyAccepted(false);
      setError(getErrorMessage(requestError, "Không thể tạo yêu cầu thuê."));
    } finally {
      setBusy(false);
      setSubmitting(false);
    }
  }

  const loading =
    vehicleRequest.loading || branchesRequest.loading || routesRequest.loading;
  const loadError =
    vehicleRequest.error || branchesRequest.error || routesRequest.error;
  return (
    <Page
      title="Chuyến đi của bạn"
      description="Chọn thời gian và nơi nhận/trả, kiểm tra báo giá rồi gửi yêu cầu thuê."
      actions={
        <Link className="btn-secondary" to={`/vehicles${searchSuffix(params)}`}>
          Quay lại danh sách xe
        </Link>
      }
    >
      <RequestState
        loading={loading}
        error={loadError}
        onRetry={() => {
          vehicleRequest.reload();
          branchesRequest.reload();
          routesRequest.reload();
        }}
      >
        {!normalized ? (
          <Missing title="Không tìm thấy xe để đặt" />
        ) : (
          <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
            <div className="space-y-5">
              <form className="panel space-y-5" aria-label="Chuyến đi thuê xe" onSubmit={checkQuote}>
                <h2 className="text-lg font-semibold"><span className="rental-step">1</span>Chuyến đi</h2>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label="Ngày nhận (giờ Việt Nam)">
                    <input
                      type="date"
                      required
                      disabled={submitting}
                      min={toVietnamInput(0).slice(0,10)}
                      value={start}
                      onChange={(event) => {
                        updateQuoteField("start", setStart, event.target.value);
                      }}
                    />
                  </Field>
                  <Field label="Ngày trả (giờ Việt Nam)">
                    <input
                      type="date"
                      required
                      disabled={submitting}
                      min={start}
                      value={end}
                      onChange={(event) => {
                        updateQuoteField("end", setEnd, event.target.value);
                      }}
                    />
                  </Field>
                  <Field label="Chi nhánh nhận xe">
                    <select
                      required
                      disabled={submitting}
                      value={pickup}
                      onChange={(event) => {
                        const nextPickup = event.target.value;
                        const followsPickup = dropoffChoice === null || dropoffChoice === pickup;
                        const stillValid = (routesRequest.data || []).some((route) =>
                          route.enabled && route.pickupBranchId === nextPickup && route.returnBranchId === dropoff &&
                          branches.some((branch) => branch.id === dropoff));
                        setDropoff(followsPickup ? null : stillValid ? dropoffChoice : "");
                        updateQuoteField(
                          "pickup",
                          setPickup,
                          event.target.value,
                        );
                      }}
                    >
                      <option value="">Chọn chi nhánh nhận xe</option>
                      {branches.map((branch) => (
                        <option key={branch.id} value={branch.id}>
                          {branch.name} · {branch.address}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="Chi nhánh trả xe">
                    <select
                      required
                      disabled={submitting}
                      value={dropoff}
                      onChange={(event) => {
                        updateQuoteField(
                          "dropoff",
                          setDropoff,
                          event.target.value,
                        );
                      }}
                    >
                      <option value="">Chọn chi nhánh trả xe</option>
                      {routes.map((route) => {
                        const branch = branches.find(
                          (item) => item.id === route.returnBranchId,
                        );
                        return branch ? (
                          <option key={route.id} value={route.returnBranchId}>
                            {branch.name} · phí tuyến {money(route.fee)}
                          </option>
                        ) : null;
                      })}
                    </select>
                  </Field>
                </div>
                {pickup && !dropoff && routes.length ? (
                  <Notice>Chưa có điểm trả hợp lệ được chọn. Vui lòng chủ động chọn chi nhánh trả; hệ thống không tự chọn tuyến có phí.</Notice>
                ) : null}
                <p className="text-sm text-muted">Cần giấy phép lái xe phù hợp và còn hạn đến hết chuyến. <Link className="underline" to="/user/profile">Kiểm tra GPLX</Link></p>
                {!routes.length ? (
                  <Notice error>
                    Không có tuyến trả xe đang mở từ chi nhánh nhận đã chọn.
                    Chọn chi nhánh khác hoặc liên hệ quản trị viên.
                  </Notice>
                ) : null}
                {error ? <Notice error>{error}</Notice> : null}
                {error.toLocaleLowerCase("vi").includes("gplx") ? (
                  <Link className="btn-secondary" to="/user/profile">
                    Cập nhật giấy phép lái xe
                  </Link>
                ) : null}
                <button className="btn" disabled={busy || !pickup || !dropoff}>
                  {busy ? "Đang kiểm tra..." : "Kiểm tra lịch & báo giá"}
                </button>
              </form>
              {quote ? (
                <section className="panel space-y-4" aria-label="Báo giá chuyến đi">
                  <h2 className="text-lg font-semibold"><span className="rental-step">2</span>Báo giá chuyến đi</h2>
                  <dl className="space-y-2 text-sm">
                    <div className="flex justify-between gap-4">
                      <dt>
                        {quote.rentalDays} ngày × {money(quote.dailyPrice)}
                      </dt>
                      <dd>{money(quote.rentalTotal)}</dd>
                    </div>
                    <div className="flex justify-between gap-4">
                      <dt>Phí tuyến một chiều</dt>
                      <dd>{money(quote.oneWayFee)}</dd>
                    </div>
                    <div className="flex justify-between gap-4 border-t pt-3 text-base font-bold">
                      <dt>Tổng báo giá</dt>
                      <dd>{money(quote.total)}</dd>
                    </div>
                  </dl>
                  {quote.deposit?.required ? (
                    <div className="space-y-2 text-sm" aria-label="Cọc giữ chỗ dự kiến">
                      <dl className="space-y-2">
                        <div className="flex justify-between gap-4"><dt>Cọc giữ chỗ dự kiến (đã gồm trong tổng)</dt><dd>{money(quote.deposit.amount)}</dd></div>
                        <div className="flex justify-between gap-4"><dt>Còn lại sau khi đóng cọc</dt><dd>{money(Math.max(0, quote.total - quote.deposit.amount))}</dd></div>
                      </dl>
                      <p>Cọc được tính vào tiền thuê xe, không phải cọc bảo đảm hư hỏng. Chỉ đóng cọc sau khi nhân viên kiểm tra hồ sơ và mở yêu cầu thanh toán.</p>
                      {quote.deposit.method === "DEMO_QR_MANUAL" ? <p className="booking-quote-warning">QR DEMO · Mô phỏng — Không chuyển tiền thật.</p> : <p>Thanh toán cọc qua VNPAY Sandbox · Chỉ thử nghiệm.</p>}
                    </div>
                  ) : <p className="text-sm text-muted">Không yêu cầu cọc giữ chỗ theo báo giá hiện tại.</p>}
                  <p className="text-sm text-slate-600">
                    {quote.pickupName} → {quote.returnName}. Giá và khả dụng
                    được kiểm tra lại khi gửi; yêu cầu chỉ được tạo nếu báo giá
                    còn khớp.
                  </p>
                  <p className="booking-quote-warning">
                    Báo giá không giữ chỗ và không xác nhận đã thanh toán. Giá, lịch xe và chính sách cọc được kiểm tra lại khi gửi yêu cầu.
                  </p>
                  <ReturnPolicySummary policy={quote.returnPolicy} />
                  {quote.returnPolicy && <label className="flex items-start gap-3 text-sm">
                    <input type="checkbox" checked={returnPolicyAccepted} onChange={event => setReturnPolicyAccepted(event.target.checked)} />
                    Tôi đã đọc chính sách trả xe sớm/muộn.
                  </label>}
                  <h2 className="rental-send-heading text-lg font-semibold"><span className="rental-step">3</span>Gửi yêu cầu</h2>
                  <label className="flex items-start gap-3 text-sm">
                    <input
                      type="checkbox"
                      checked={accepted}
                      onChange={(event) => setAccepted(event.target.checked)}
                    />
                    Tôi xác nhận thời gian, chi nhánh và tổng báo giá.
                  </label>
                  <p className="text-sm text-muted">Xác nhận báo giá không phải ký hợp đồng. Hợp đồng được phát hành khi yêu cầu được xác nhận.</p>
                  <button
                    className="btn"
                    disabled={!accepted || busy || Boolean(quote.returnPolicy && !returnPolicyAccepted)}
                    onClick={submitBooking}
                  >
                    {busy ? "Đang gửi..." : "Gửi yêu cầu thuê"}
                  </button>
                </section>
              ) : <p role="status" className="text-sm text-muted">Báo giá sẽ hiển thị sau khi kiểm tra lịch và tuyến nhận/trả.</p>}
            </div>
            <aside className="panel h-fit">
              <VehicleVisual vehicle={normalized} />
              <h2 className="mt-4 text-xl font-bold">{normalized.name}</h2>
              <p className="mt-2 text-sm text-slate-500">
                {normalized.seats} chỗ · {normalized.transmission}
              </p>
            </aside>
          </div>
        )}
      </RequestState>
    </Page>
  );
}

export function ApiBookingListPage({ area = "user", mode = "all" }) {
  const request = useApiData("/bookings");
  const [params] = useSearchParams();
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState(
    mode === "handover" ? (params.get("group") === "delivered" ? "IN_PROGRESS" : "CONFIRMED") : mode === "return" ? "IN_PROGRESS" : "",
  );
  const [queue, setQueue] = useState("all");
  const [depositPhase,setDepositPhase]=useState("");
  const [queueDate, setQueueDate] = useState(() => vietnamDay(Date.now()));
  const [sort, setSort] = useState("createdAt_desc");
  const [page, setPage] = useState(1);
  const [copyFeedback, setCopyFeedback] = useState({});
  const [manualCopyId, setManualCopyId] = useState(null);
  const now = useNow();

  const isFilterActive = area === "admin";
  const rows = visibleBookings(request.data, {
    query,
    status,
    queue,
    date: queueDate,
    mode,
    now,
    sort: isFilterActive ? sort : "createdAt_desc",
    depositPhase,
  });

  const pageSize = 20;
  const totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
  const activePage = Math.min(Math.max(1, page), totalPages);

  const filterKey = `${query}|${status}|${queue}|${queueDate}|${sort}|${depositPhase}`;
  const [prevFilterKey, setPrevFilterKey] = useState(filterKey);

  if (filterKey !== prevFilterKey) {
    setPrevFilterKey(filterKey);
    setPage(1);
  }

  const pagedRows = isFilterActive
    ? rows.slice((activePage - 1) * pageSize, activePage * pageSize)
    : rows;

  const statusLabels = workStatusLabels[mode] || bookingStatusLabels;
  const title =
    area === "user"
      ? "Đơn thuê của tôi"
      : area === "admin"
        ? "Tra cứu đơn thuê"
        : mode === "handover"
          ? "Bàn giao xe"
          : mode === "return"
            ? "Nhận trả xe"
            : "Hàng đợi xử lý";

  async function handleCopyId(id) {
    try {
      if (!navigator?.clipboard?.writeText) {
        throw new Error("Clipboard API not available");
      }
      await navigator.clipboard.writeText(id);
      setCopyFeedback((prev) => ({ ...prev, [id]: "Đã sao chép mã đầy đủ" }));
      setManualCopyId(null);
    } catch {
      setCopyFeedback((prev) => ({
        ...prev,
        [id]: "Không thể sao chép tự động, vui lòng chọn mã bên dưới",
      }));
      setManualCopyId(id);
    }
  }

  return (
    <Page
      title={title}
      actions={<button type="button" className="btn-secondary" disabled={request.loading} onClick={request.reload}>Tải lại danh sách</button>}
    >
      <div className={`urban-filter mb-6 grid gap-4 sm:grid-cols-2 ${isFilterActive ? "lg:grid-cols-4" : "lg:grid-cols-3"}`}>
        <Field label="Tìm mã đơn, xe hoặc khách hàng">
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </Field>

        <Field label="Trạng thái">
          <select
            value={status}
            onChange={(event) => setStatus(event.target.value)}
          >
            <option value="">{mode === "handover" ? "Tất cả đơn bàn giao" : mode === "return" ? "Tất cả đơn nhận trả" : "Tất cả"}</option>
            {Object.keys(statusLabels).map((value) => (
              <option key={value} value={value}>
                {statusLabels[value]}
              </option>
            ))}
          </select>
        </Field>
        <div className="space-y-3">
        <Field label="Hàng đợi">
          <select value={queue} onChange={event => setQueue(event.target.value)}>
            <option value="all">Tất cả</option><option value="today">Hôm nay</option><option value="date">Chọn ngày</option>
          </select>
        </Field>
        {queue === "date" ? (
          <Field label="Ngày trong hàng đợi">
            <input type="date" value={queueDate} onChange={event => setQueueDate(event.target.value)} />
          </Field>
        ) : null}
        </div>
        {area === 'staff' && mode === 'all' ? <Field label="Giai đoạn cọc"><select value={depositPhase} onChange={e=>setDepositPhase(e.target.value)}>
          <option value="">Tất cả giai đoạn</option><option value="profile">Chờ kiểm tra hồ sơ</option><option value="payment">Chờ đóng cọc</option><option value="review">Khách đã báo · Chờ đối soát</option>
        </select></Field> : null}
        {isFilterActive ? (
          <Field label="Sắp xếp">
            <select value={sort} onChange={(event) => setSort(event.target.value)}>
              {Object.keys(bookingSortLabels).map((val) => (
                <option key={val} value={val}>
                  {bookingSortLabels[val]}
                </option>
              ))}
            </select>
          </Field>
        ) : null}
      </div>
      {queue === "date" && !queueDate ? <p className="mb-4 text-sm text-muted" role="status">Vui lòng chọn ngày để xem đơn trong hàng đợi.</p> : null}
      <p className="mb-4 text-sm text-muted" aria-live="polite">
        {request.loading
          ? "Đang tải đơn…"
          : request.error
            ? "Chưa tải được danh sách"
            : isFilterActive
              ? `Hiển thị ${pagedRows.length}/${rows.length} đơn · Trang ${activePage}/${totalPages} · ${bookingSortLabels[sort] || "Mới tạo trước"}`
              : `${rows.length} đơn phù hợp · ${depositPhase === "review" ? "Hạn đối soát gần trước" : "Mới tạo trước"}`}
      </p>
      <RequestState
        loading={request.loading}
        error={request.error}
        onRetry={request.reload}
      >
        {rows.length ? (
          <div className="space-y-4">
            {area === "admin" ? <AdminBookingTable bookings={pagedRows} onCopy={handleCopyId} copyFeedback={copyFeedback} manualCopyId={manualCopyId} /> : <>{pagedRows.map((booking) => (
              <article
                className={`panel flex flex-wrap items-center justify-between gap-4 ${area === "user" ? "urban-booking-card" : ""}`}
                key={booking.id}
              >
                <div>
                  <div className="mb-2 flex flex-wrap items-center gap-3">
                    {area === "admin" ? (
                      <span className="flex flex-wrap items-center gap-2">
                        <span className="font-mono text-xs font-semibold text-slate-700" title={booking.id}>
                          #{booking.id.slice(0, 8)}…
                        </span>
                        <button
                          type="button"
                          className="text-xs text-brand hover:underline"
                          onClick={() => handleCopyId(booking.id)}
                          aria-label={`Sao chép mã đơn ${booking.id}`}
                        >
                          Sao chép mã đầy đủ
                        </button>
                        <details className="inline-block text-xs" open={manualCopyId === booking.id}>
                          <summary className="cursor-pointer text-slate-500 hover:text-slate-800">
                            Xem mã đầy đủ
                          </summary>
                          <div className="mt-1">
                            <input
                              type="text"
                              readOnly
                              value={booking.id}
                              aria-label={`Mã đầy đủ đơn ${booking.id}`}
                              className="w-72 select-all rounded border border-slate-300 bg-slate-50 px-1.5 py-0.5 font-mono text-xs text-slate-800"
                              onFocus={(e) => e.target.select()}
                            />
                          </div>
                        </details>
                        {copyFeedback[booking.id] ? (
                          <span className="text-xs text-slate-500" role="status">
                            {copyFeedback[booking.id]}
                          </span>
                        ) : null}
                      </span>
                    ) : (
                      <span className="text-xs font-semibold text-slate-500">
                        {booking.id}
                      </span>
                    )}
                    <Status status={booking.status} />
                    {booking.status === 'PENDING' && booking.deposit?.required ? <span className="text-sm">{booking.deposit.reportedAt ? 'Khách đã báo · Chờ đối soát' : booking.deposit.approvedAt ? 'Chờ đóng cọc' : 'Chờ kiểm tra hồ sơ'}</span> : null}
                  </div>
                  {area === "admin" ? (
                    <div className="mb-1">
                      <p className="text-base font-bold text-slate-900">
                        Khách: {booking.customerName || "Chưa có tên"}
                      </p>
                      <h2 className="text-sm font-medium text-slate-700">Xe: {booking.vehicleName}</h2>
                    </div>
                  ) : (
                    <>
                      <h2 className="font-semibold">{booking.vehicleName}</h2>
                      {area !== "user" ? (
                        <p className="mt-1 text-sm text-slate-500">
                          {booking.customerName}
                        </p>
                      ) : null}
                    </>
                  )}
                  <p className="mt-1 text-sm text-slate-600">
                    {area === "admin"
                      ? `${adminBookingDate(booking, booking.startAt)} → ${adminBookingDate(booking, booking.endAt)}`
                      : `${bookingDate(booking, booking.startAt)} → ${bookingDate(booking, booking.endAt)}`
                    }
                  </p>
                  <p className="mt-1 text-sm text-slate-500">
                    {booking.pickupName} → {booking.returnName}
                  </p>
                  {booking.createdAt ? (
                    <p className="mt-1 text-sm text-muted">
                      Tạo lúc {area === "admin" ? adminDateFormat(booking.createdAt, { withTime: true }) : dateTime(booking.createdAt)}
                    </p>
                  ) : null}
                </div>
                <div className="flex items-center gap-4">
                  <strong>{money(booking.total)}</strong>
                  <Link
                    className="btn-secondary"
                    to={
                      mode === "handover" && booking.status === "CONFIRMED"
                        ? `/staff/handover/${booking.id}`
                        : mode === "return" && booking.status === "IN_PROGRESS"
                          ? `/staff/return/${booking.id}`
                          : `/${area}/bookings/${booking.id}`
                    }
                  >
                    Chi tiết
                  </Link>
                </div>
              </article>
            ))}</>}

            {isFilterActive && totalPages > 1 ? (
              <nav aria-label="Phân trang danh sách đơn" className="mt-6 flex items-center justify-between border-t border-slate-200 pt-4">
                <span className="text-sm text-slate-600">
                  Trang {activePage} / {totalPages} ({rows.length} đơn)
                </span>
                <div className="flex gap-2">
                  <button
                    type="button"
                    className="btn-secondary"
                    disabled={activePage <= 1}
                    onClick={() => setPage((p) => Math.max(1, p - 1))}
                  >
                    Trang trước
                  </button>
                  <button
                    type="button"
                    className="btn-secondary"
                    disabled={activePage >= totalPages}
                    onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
                  >
                    Trang sau
                  </button>
                </div>
              </nav>
            ) : null}
          </div>
        ) : (
          <EmptyState
            title="Không có đơn phù hợp"
            description="Thử thay đổi từ khóa, trạng thái hoặc chọn hàng đợi Tất cả."
          />
        )}
      </RequestState>
    </Page>
  );
}

export function BookingReviewSection({
  booking,
  area = "user",
  bookingReviewRequest,
  submitReview,
  rating,
  setRating,
  review,
  setReview,
  reviewBusy,
}) {
  if (area !== "user" || booking?.status !== "COMPLETED") return null;

  if (bookingReviewRequest.loading) {
    return (
      <section className="panel space-y-2">
        <p role="status">Đang tải đánh giá...</p>
        <LoadingSkeleton rows={2} />
      </section>
    );
  }

  if (bookingReviewRequest.error) {
    return (
      <section className="panel space-y-2">
        <Notice error>{bookingReviewRequest.error}</Notice>
        <button
          className="btn-secondary"
          onClick={bookingReviewRequest.reload}
          type="button"
        >
          Thử tải lại đánh giá
        </button>
      </section>
    );
  }

  if (bookingReviewRequest.data) {
    return (
      <section className="panel space-y-2">
        <h2 className="text-lg font-semibold">Đánh giá của bạn</h2>
        <p className="font-semibold text-amber-600">
          {bookingReviewRequest.data.rating}/5 sao
        </p>
        <p className="whitespace-pre-line text-sm leading-6 text-slate-700">
          {bookingReviewRequest.data.content}
        </p>
        <p className="text-xs text-slate-500">
          {dateTime(bookingReviewRequest.data.created_at)}
          {bookingReviewRequest.data.hidden ? " • (Đang ẩn bởi kiểm duyệt)" : ""}
        </p>
      </section>
    );
  }

  return (
    <form className="panel space-y-4" onSubmit={submitReview}>
      <h2 className="text-lg font-semibold">Đánh giá chuyến thuê</h2>
      <Field label="Mức hài lòng">
        <select
          value={rating}
          onChange={(event) => setRating(event.target.value)}
        >
          {[5, 4, 3, 2, 1].map((value) => (
            <option key={value} value={value}>
              {value} sao
            </option>
          ))}
        </select>
      </Field>
      <Field label="Nội dung">
        <textarea
          required
          maxLength={1000}
          rows={4}
          value={review}
          onChange={(event) => setReview(event.target.value)}
        />
      </Field>
      <button className="btn" disabled={reviewBusy}>
        {reviewBusy ? "Đang gửi..." : "Gửi đánh giá"}
      </button>
    </form>
  );
}

export function ApiBookingDetailPage({ area = "user" }) {
  const { bookingId } = useParams();
  const request = useApiData(`/bookings/${encodeURIComponent(bookingId)}`);
  const bookingReviewRequest = useApiData(
    `/bookings/${encodeURIComponent(bookingId)}/review`,
    area === "user",
  );
  const { user: currentUser } = useAuth();
  const isStaff = currentUser?.role === "STAFF";
  const branchAssignmentsRequest = useApiData("/me/branches", isStaff);
  const [next, setNext] = useState("");
  const [reason, setReason] = useState("");
  const [cancellationKind, setCancellationKind] = useState('');
  const [condition, setCondition] = useState("");
  const [licenseChecked, setLicenseChecked] = useState(false);
  const [maintenance, setMaintenance] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [rating, setRating] = useState("5");
  const [review, setReview] = useState("");
  const [reviewBusy, setReviewBusy] = useState(false);
  const detail = request.data || {};
  const booking = detail.booking;
  const license = detail.license;
  const assignedBranchIds = new Set(
    Array.isArray(branchAssignmentsRequest.data)
      ? branchAssignmentsRequest.data
      : [],
  );
  const now = useNow();
  const prevBookingId = useRef(booking?.id);

  useEffect(() => {
    if (!booking?.id) return;
    if (prevBookingId.current === booking?.id) return;
    prevBookingId.current = booking?.id;
    setNext("");
    setReason("");
    setCancellationKind('');
    setCondition("");
    setLicenseChecked(false);
    setMaintenance(false);
    setError("");
    setNotice("");
  }, [booking?.id]);

  const availableActions = getBookingActions(booking, {
    role: currentUser?.role,
    assignedBranchIds,
    assignmentsReady:
      !isStaff ||
      (!branchAssignmentsRequest.loading && !branchAssignmentsRequest.error),
    now,
  });

  async function submitAction(event) {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      await applyBookingAction(booking.id, {
        next,
        version: booking.version,
        reason: reason || null,
        condition: condition || null,
        licenseChecked,
        maintenance,
        cancellationKind: booking.deposit?.required && next === 'CANCELLED' ? cancellationKind : null,
      });
      setNotice(next === "IN_PROGRESS" ? "Đã bàn giao xe thành công — đơn chuyển sang Đang thuê." : "Đã cập nhật trạng thái đơn.");
      setNext("");
      setReason("");
      setCancellationKind('');
      setCondition("");
      request.reload();
    } catch (actionError) {
      setError(getErrorMessage(actionError, "Không thể cập nhật đơn."));
      if (actionError.response?.status === 409) request.reload();
    } finally {
      setBusy(false);
    }
  }

  async function submitReview(event) {
    event.preventDefault();
    if (reviewBusy) return;
    setError("");
    setNotice("");
    setReviewBusy(true);
    try {
      await apiClient.post("/reviews", {
        bookingId: booking.id,
        rating: Number(rating),
        content: review,
      });
      setNotice("Đã gửi đánh giá cho đơn hoàn tất.");
      setReview("");
      bookingReviewRequest.reload();
    } catch (reviewError) {
      setError(
        getErrorMessage(
          reviewError,
          "Chỉ gửi được một đánh giá từ 1 đến 5 sao cho đơn hoàn tất.",
        ),
      );
    } finally {
      setReviewBusy(false);
    }
  }

  return (
    <Page
      title={booking ? `Đơn thuê · ${booking.id.slice(0, 8)}` : "Chi tiết đơn thuê"}
      description={booking?.vehicleName}
      actions={
        <div className="flex flex-wrap gap-3"><button type="button" className="btn-secondary" onClick={request.reload} disabled={request.loading}>Tải lại đơn</button>
        <Link className="btn-secondary" to={`/${area}/bookings`}>
          Quay lại danh sách
        </Link></div>
      }
    >
      <RequestState
        loading={request.loading}
        error={request.error}
        onRetry={request.reload}
      >
        {!booking ? (
          <Missing title="Không tìm thấy đơn" back={`/${area}/bookings`} />
        ) : (
          <>
            <BookingIdentity key={booking.id} id={booking.id} />
            {notice ? <Notice>{notice}</Notice> : null}
            {isStaff && booking.status === "IN_PROGRESS" ? (
              <p className="mb-4"><Link className="btn-secondary" to="/staff/handover?group=delivered">Xem danh sách Đã bàn giao</Link></p>
            ) : null}
            <div className="mb-5 flex items-center gap-3">
              <Status status={booking.status} />
              {booking.status === 'PENDING' && booking.deposit?.required ? <span>{booking.deposit.reportedAt ? 'Khách đã báo · Chờ đối soát' : booking.deposit.approvedAt ? 'Chờ đóng cọc' : 'Chờ kiểm tra hồ sơ'}</span> : null}
              {area === "staff" ? <span className="text-sm text-slate-500">Phiên bản {booking.version}</span> : null}
            </div>
            {booking.status === "PENDING" && booking.expiresAt && !booking.deposit?.required ? (
              <p className="mb-5 text-sm text-amber-800">
                Yêu cầu giữ lịch đến {area === "admin" ? adminDateFormat(booking.expiresAt, { withTime: true }) : dateTime(booking.expiresAt)}.
              </p>
            ) : null}
            {error ? <Notice error>{error}</Notice> : null}
            <ReturnPolicySummary policy={booking.returnPolicy} />
            {booking.deposit?.required ? <BookingDeposit key={booking.id} booking={booking} role={currentUser?.role} assignedPickup={assignedBranchIds.has(booking.pickupBranchId)} reload={request.reload} now={now} /> : null}
            <div className="grid gap-6 lg:grid-cols-[1fr_330px]">
              <div className="space-y-5">
                <section className="panel">
                  <h2 className="mb-4 text-lg font-semibold">Thông tin thuê</h2>
                  <dl className="grid gap-4 text-sm sm:grid-cols-2">
                    {[
                      ["Khách hàng", booking.customerName],
                      ["Xe", booking.vehicleName],
                      [booking.policyVersion === 'calendar-2026-10-17' ? "Ngày nhận" : "Thời gian nhận", area === "admin" ? adminBookingDate(booking, booking.startAt) : bookingDate(booking, booking.startAt)],
                      [booking.policyVersion === 'calendar-2026-10-17' ? "Ngày trả" : "Thời gian trả", area === "admin" ? adminBookingDate(booking, booking.endAt) : bookingDate(booking, booking.endAt)],
                      ["Chi nhánh nhận", booking.pickupName],
                      [
                        "Chi nhánh trả",
                        booking.actualReturnBranchId
                          ? `${booking.returnName} · đã nhận`
                          : booking.returnName,
                      ],
                      [
                        "Giá thuê",
                        `${booking.rentalDays} ngày × ${money(booking.dailyPrice)}`,
                      ],
                      ["Phí tuyến", money(booking.oneWayFee)],
                      ["Tổng tiền lúc đặt", money(booking.total)],
                    ].map(([label, value]) => (
                      <div key={label}>
                        <dt className="text-slate-500">{label}</dt>
                        <dd className="mt-1 font-semibold">{value || "—"}</dd>
                      </div>
                    ))}
                  </dl>
                  <p className="mt-4 text-xs text-slate-500">
                    Tổng tiền không xác nhận đã thanh toán.
                  </p>
                </section>
                <BookingProgress booking={booking} history={detail.history} role={currentUser?.role} actions={availableActions} area={area} />
                {detail.records?.length ? (
                  <section className="panel">
                    <h2 className="mb-4 text-lg font-semibold">
                      Biên bản giao nhận
                    </h2>
                    <div className="space-y-4">
                      {detail.records.map((record, index) => (
                        <article
                          className="border-t border-slate-100 pt-4"
                          key={`${record.kind}-${index}`}
                        >
                          <h3 className="font-semibold">
                            {record.kind === "HANDOVER"
                              ? "Giao xe"
                              : "Nhận trả xe"}
                          </h3>
                          <p className="mt-1 text-sm text-slate-500">
                            {area === "admin" ? adminDateFormat(record.occurred_at, { withTime: true }) : dateTime(record.occurred_at)}
                          </p>
                          <p className="mt-2 text-sm leading-6">
                            {record.condition_note}
                          </p>
                          {record.maintenance ? (
                            <p className="mt-2 text-sm text-amber-800">
                              Đã chuyển tình trạng xe sang bảo trì.
                            </p>
                          ) : null}
                        </article>
                      ))}
                    </div>
                  </section>
                ) : null}
                <BookingReviewSection
                  booking={booking}
                  area={area}
                  bookingReviewRequest={bookingReviewRequest}
                  submitReview={submitReview}
                  rating={rating}
                  setRating={setRating}
                  review={review}
                  setReview={setReview}
                  reviewBusy={reviewBusy}
                />
                <IncidentSection
                  booking={booking}
                  records={detail.records || []}
                  area={area}
                />
              </div>
              <div className="space-y-4">
                {isStaff && branchAssignmentsRequest.loading ? (
                  <p role="status" className="panel text-sm text-slate-600">
                    Đang tải phân công chi nhánh...
                  </p>
                ) : null}
                {isStaff && branchAssignmentsRequest.error ? (
                  <section className="panel space-y-3">
                    <Notice error>{branchAssignmentsRequest.error}</Notice>
                    <button
                      className="btn-secondary"
                      onClick={branchAssignmentsRequest.reload}
                    >
                      Tải lại phân công
                    </button>
                  </section>
                ) : null}
                {isStaff &&
                !branchAssignmentsRequest.loading &&
                !branchAssignmentsRequest.error &&
                !assignedBranchIds.size ? (
                  <Notice error>
                    Tài khoản nhân viên chưa được phân công chi nhánh; không có
                    thao tác vận hành khả dụng.
                  </Notice>
                ) : null}
                {isStaff && license ? (
                  <section className="panel">
                    <h2 className="mb-4 text-lg font-semibold">
                      GPLX khách hàng
                    </h2>
                    <dl className="grid gap-3 text-sm">
                      <div>
                        <dt className="text-slate-500">Số GPLX</dt>
                        <dd className="mt-1 font-semibold font-mono">
                          {license.licenseNumber}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-slate-500">Hệ / Hạng</dt>
                        <dd className="mt-1 font-semibold">
                          {licenseSchemeLabels[license.scheme] || "Chưa xác định"} / {license.licenseClass}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-slate-500">Hạn dùng</dt>
                        <dd className="mt-1 font-semibold">
                          {dateOnly(license.expiresOn)}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-slate-500">Chỉ số tự động</dt>
                        <dd className="mt-1 font-semibold">
                          {license.automaticOnly ? "Có" : "Không"}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-slate-500">Trạng thái xác minh</dt>
                        <dd className="mt-1 font-semibold">
                          {licenseStatusLabels[license.status] || "Chưa xác định"}
                        </dd>
                      </div>
                    </dl>
                    <p className="mt-3 text-xs text-slate-600">
                      Nhân viên đối chiếu bản gốc trước khi giao xe. Checkbox
                      xác nhận bên dưới không thay thế việc kiểm tra thực tế.
                    </p>
                  </section>
                ) : null}
                {isStaff && license && assignedBranchIds.has(booking.pickupBranchId) && ["PENDING", "CONFIRMED"].includes(booking.status) ? (
                  <div className="panel"><LicensePhotos key={`${booking.id}-${license.version}`} license={license} bookingId={booking.id} onSaved={request.reload} /></div>
                ) : null}
                {availableActions.length ? (
                  <form
                    className="panel h-fit space-y-4"
                    onSubmit={submitAction}
                  >
                  <h2 className="text-lg font-semibold">{area === "user" ? "Hủy đơn thuê" : "Thao tác được phép"}</h2>
                  {area === "user" ? (
                    <p className="text-sm text-slate-600">Chọn hủy đơn và nhập lý do. Đơn chỉ bị hủy sau khi bạn bấm xác nhận và hệ thống xử lý thành công.</p>
                  ) : null}
                  <Field label={area === "user" ? "Yêu cầu xử lý" : "Chuyển trạng thái"}>
                    <select
                      required
                      value={next}
                      onChange={(event) => setNext(event.target.value)}
                    >
                      <option value="">Chọn thao tác</option>
                      {availableActions.map((action) => (
                        <option key={action} value={action}>
                          {bookingActionLabels[action] || bookingStatusLabels[action] || "Thao tác chưa xác định"}
                        </option>
                      ))}
                    </select>
                  </Field>
                  {["REJECTED", "CANCELLED"].includes(next) ? (
                    <Field label="Lý do">
                      <textarea
                        required
                        maxLength={2000}
                        value={reason}
                        onChange={(event) => setReason(event.target.value)}
                      />
                    </Field>
                  ) : null}
                  {booking.deposit?.required && next === 'CANCELLED' && area !== 'user' ? <Field label="Nguyên nhân hủy và chính sách hoàn cọc">
                    <select required value={cancellationKind} onChange={event => setCancellationKind(event.target.value)}>
                      <option value="">Chọn nguyên nhân</option>
                      <option value="BUSINESS">Doanh nghiệp không cung cấp xe — hoàn toàn bộ</option>
                      <option value="LATE_CUSTOMER">Khách hủy — Admin quyết định hoàn cọc</option>
                    </select>
                  </Field> : null}
                  {["IN_PROGRESS", "RETURNED"].includes(next) ? (
                    <Field label="Tình trạng xe / ghi chú">
                      <textarea
                        required
                        maxLength={2000}
                        value={condition}
                        onChange={(event) => setCondition(event.target.value)}
                      />
                    </Field>
                  ) : null}
                  {next === "IN_PROGRESS" ? (
                    <label className="flex gap-3 text-sm">
                      <input
                        type="checkbox"
                        required
                        checked={licenseChecked}
                        onChange={(event) =>
                          setLicenseChecked(event.target.checked)
                        }
                      />{" "}
                      Đã kiểm tra GPLX và xe phù hợp đến hết chuyến.
                    </label>
                  ) : null}
                  {next === "RETURNED" ? (
                    <label className="flex gap-3 text-sm">
                      <input
                        type="checkbox"
                        checked={maintenance}
                        onChange={(event) =>
                          setMaintenance(event.target.checked)
                        }
                      />{" "}
                      Xe cần bảo trì sau khi nhận.
                    </label>
                  ) : null}
                  {error ? <Notice error>{error}</Notice> : null}
                  <button className="btn" disabled={busy || !next}>
                    {busy ? "Đang xử lý..." : next === "CANCELLED" ? "Xác nhận hủy đơn" : bookingActionLabels[next] || "Chọn thao tác"}
                  </button>
                  </form>
                ) : null}
                {currentUser?.role === "USER" &&
                ["PENDING", "CONFIRMED"].includes(booking.status) &&
                new Date(booking.startAt).getTime() - now < 86400000 ? (
                  <Notice>
                    Đã sát giờ nhận xe (dưới 24 giờ). Vui lòng liên hệ nhân viên để được hỗ trợ hủy.
                  </Notice>
                ) : null}
              </div>
              </div>
            </>
        )}
      </RequestState>
    </Page>
  );
}

export function ApiOperationPage({ kind }) {
  const { bookingId } = useParams();
  const area = "staff";
  return <ApiBookingDetailPage area={area} key={`${kind}-${bookingId}`} />;
}

export function ApiDocumentPage({ kind, area }) {
  const { bookingId } = useParams();
  const request = useApiData(
    `/bookings/${encodeURIComponent(bookingId)}/${kind}`,
  );
  const rawDocument = Array.isArray(request.data)
    ? request.data[0]
    : request.data;
  let snapshot = {};
  let documentError = "";
  if (typeof rawDocument?.snapshot === "string") {
    try {
      snapshot = JSON.parse(rawDocument.snapshot);
    } catch {
      documentError =
        "Hồ sơ được trả về nhưng không đọc được bản chụp dữ liệu.";
    }
  } else if (
    rawDocument?.snapshot &&
    typeof rawDocument.snapshot === "object"
  ) {
    snapshot = rawDocument.snapshot;
  }
  const doc = rawDocument
    ? {
        ...snapshot,
        ...rawDocument,
        items: (rawDocument.items || []).map((item) => ({
          ...item,
          unitPrice: item.unitPrice ?? item.unit_price,
        })),
      }
    : null;

  const contractLabels = {
    customer: "Khách hàng",
    vehicle: "Xe",
    pickup: "Chi nhánh nhận",
    return: "Chi nhánh trả",
    startAt: doc?.policyVersion === "calendar-2026-10-17" ? "Ngày nhận" : "Thời gian nhận",
    endAt: doc?.policyVersion === "calendar-2026-10-17" ? "Ngày trả" : "Thời gian trả",
    rentalTotal: "Tiền thuê xe",
    oneWayFee: "Phí tuyến một chiều",
    total: "Tổng tiền",
  };

  const invoiceLabels = {
    customer: "Khách hàng",
    vehicle: "Xe",
    pickup: "Chi nhánh nhận",
    return: "Chi nhánh trả",
    startAt: doc?.policyVersion === "calendar-2026-10-17" ? "Ngày nhận" : "Thời gian nhận",
    endAt: doc?.policyVersion === "calendar-2026-10-17" ? "Ngày trả" : "Thời gian trả",
    note: "Ghi chú",
  };

  const formatValue = (key, value) => {
    if (value == null) return "—";
    if (typeof value === "object") return JSON.stringify(value);
    if (["startAt", "endAt"].includes(key)) {
      return area === "admin" ? adminBookingDate(doc, value) : bookingDate(doc, value);
    }
    if (["rentalTotal", "oneWayFee", "total"].includes(key))
      return money(value);
    return String(value);
  };

  const labels = kind === "contract" ? contractLabels : invoiceLabels;

  return (
    <Page
      title={kind === "contract" ? "Hợp đồng thuê xe" : "Hóa đơn thuê xe"}
      actions={
        <Link className="btn-secondary" to={`/${area}/bookings/${bookingId}`}>
          Chi tiết đơn
        </Link>
      }
    >
      <RequestState
        loading={request.loading}
        error={request.error || documentError}
        onRetry={request.reload}
      >
        {!doc ? (
          <EmptyState
            title="Hồ sơ chưa được phát hành"
            description={
              kind === "contract"
                ? "Hợp đồng chỉ được phát hành khi đơn đã xác nhận."
                : "Hóa đơn chỉ được phát hành khi đơn đã hoàn tất."
            }
          />
        ) : (
          <section className="panel mx-auto max-w-3xl">
            <div className="mb-5 flex flex-wrap justify-between gap-3 border-b pb-4">
              <strong className="text-2xl text-teal-800">KDrive</strong>
              <span className="font-semibold">{doc.id}</span>
            </div>
            <dl className="grid gap-4 text-sm sm:grid-cols-2">
              {Object.entries(labels).map(([key, label]) => {
                const value = doc[key];
                if (value == null) return null;
                return (
                  <div key={key}>
                    <dt className="text-slate-500">{label}</dt>
                    <dd className="mt-1 font-medium">{formatValue(key, value)}</dd>
                  </div>
                );
              })}
            </dl>
            <ReturnPolicySummary policy={doc.returnPolicy} />
            {kind === "contract" && doc.terms ? (
              <div className="mt-6 border-t pt-4">
                <h3 className="font-semibold mb-2">Điều khoản</h3>
                <p className="text-sm text-slate-700 whitespace-pre-line">
                  {doc.terms}
                </p>
              </div>
            ) : null}
            {kind === "invoice" && Array.isArray(doc.items) ? (
              <div className="mt-6 space-y-3 border-t pt-4">
                {doc.items.map((item, index) => (
                  <div
                    className="flex justify-between gap-3 text-sm"
                    key={`${item.name}-${index}`}
                  >
                    <span>
                      {item.name} · {item.quantity} × {money(item.unitPrice)}
                    </span>
                    <strong>{money(item.amount)}</strong>
                  </div>
                ))}
              </div>
            ) : null}
            <p className="mt-5 text-sm text-slate-500">
              {kind === "invoice"
                ? "Hóa đơn không phải bằng chứng thanh toán."
                : "Hợp đồng là bản ghi nhận thỏa thuận; chữ ký/duyệt thực hiện theo quy trình bên ngoài."}
            </p>
          </section>
        )}
      </RequestState>
    </Page>
  );
}

export function ApiProfilePage() {
  const writeInFlight = useRef(false);
  const profileRequest = useApiData("/auth/me");
  const licenseRequest = useApiData("/me/license");
  const navigate = useNavigate();
  const { logout, refreshUser, token } = useAuth();
  const [licenseScheme, setLicenseScheme] = useState("VN_2025");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const profile = profileRequest.data || {};
  const license = licenseRequest.data;
  const schemeInitialized = useRef(false);

  useEffect(() => {
    if (schemeInitialized.current || !license?.scheme) return;
    schemeInitialized.current = true;
    setLicenseScheme(license.scheme);
  }, [license?.scheme]);
  const licenseClasses =
    licenseScheme === "VN_PRE_2025"
      ? ["B1_AUTO", "B1", "B2", "C", "D", "E", "FB2", "FC", "FD", "FE"]
      : [
          "B",
          "C1",
          "C",
          "D1",
          "D2",
          "D",
          "BE",
          "C1E",
          "CE",
          "D1E",
          "D2E",
          "DE",
        ];
  async function update(event) {
    event.preventDefault();
    if (writeInFlight.current) return;
    writeInFlight.current = true;
    setError("");
    setMessage("");
    setBusy(true);
    const form = new FormData(event.currentTarget);
    try {
      const phone = String(form.get("phone") || "").trim().replace(/[\s-]/g, "");
      if (phone && !/^\+?[0-9]{7,15}$/.test(phone)) {
        setError("Số điện thoại cần 7–15 chữ số, có thể bắt đầu bằng +.");
        return;
      }
      const updatedProfile = await updateProfile({
        fullName: String(form.get("fullName") || ""),
        phone,
        version: profile.version,
      });
      if (getStoredToken() !== token) return;
      refreshUser(updatedProfile);
      setMessage("Đã cập nhật hồ sơ.");
      profileRequest.reload();
    } catch (requestError) {
      if (getStoredToken() !== token) return;
      setError(getErrorMessage(requestError, "Không thể cập nhật hồ sơ."));
    } finally {
      writeInFlight.current = false;
      setBusy(false);
    }
  }
  async function changePassword(event) {
    event.preventDefault();
    if (writeInFlight.current) return;
    setError("");
    setMessage("");
    const form = new FormData(event.currentTarget);
    const passwordForm = event.currentTarget;
    const next = String(form.get("next") || "");
    const invalid = passwordError(next);
    const validationErrors = [invalid, next !== String(form.get("confirmation") || "") ? "Mật khẩu nhập lại chưa khớp." : ""].filter(Boolean);
    if (validationErrors.length) {
      setError(validationErrors.join(" "));
      return;
    }
    writeInFlight.current = true;
    setBusy(true);
    try {
      await updatePassword({
        current: String(form.get("current") || ""),
        next,
      });
      if (getStoredToken() !== token) return;
      passwordForm.reset();
      await logout({ callApi: false });
      window.alert("Đổi mật khẩu thành công. Các phiên đăng nhập cũ đã được thu hồi. Vui lòng đăng nhập lại.");
      navigate("/login", { replace: true });
    } catch (requestError) {
      if (getStoredToken() !== token) return;
      setError(getErrorMessage(requestError, "Không thể đổi mật khẩu."));
    } finally {
      writeInFlight.current = false;
      setBusy(false);
    }
  }
  async function updateLicense(event) {
    event.preventDefault();
    if (writeInFlight.current) return;
    writeInFlight.current = true;
    setError("");
    setMessage("");
    const form = new FormData(event.currentTarget);
    setBusy(true);
    try {
      await saveLicense({
        id: license?.id || null,
        licenseNumber: String(form.get("licenseNumber") || ""),
        scheme: String(form.get("scheme") || ""),
        licenseClass: String(form.get("licenseClass") || ""),
        expiresOn: String(form.get("expiresOn") || ""),
        automaticOnly: form.get("automaticOnly") === "on",
        status: license?.status || "PENDING",
        version: license?.version || 0,
      });
      if (getStoredToken() !== token) return;
      setMessage(
        "Đã lưu thông tin GPLX. Hãy tải đủ hai mặt ảnh để nhân viên kiểm tra trước; khi sửa thông tin, kết quả kiểm tra được đặt lại.",
      );
      licenseRequest.reload();
    } catch (requestError) {
      if (getStoredToken() !== token) return;
      setError(getErrorMessage(requestError, "Không thể lưu GPLX."));
    } finally {
      writeInFlight.current = false;
      setBusy(false);
    }
  }
  const loading = profileRequest.loading || licenseRequest.loading;
  const loadError = profileRequest.error || licenseRequest.error;
  return (
    <Page
      title="Thông tin cá nhân"
      description="Quản lý hồ sơ và bằng lái phục vụ xác minh trước khi giao xe."
    >
      <RequestState
        loading={loading}
        error={loadError}
        onRetry={() => {
          profileRequest.reload();
          licenseRequest.reload();
        }}
      >
        {message ? <Notice>{message}</Notice> : null}
        {error ? <Notice error>{error}</Notice> : null}
        <div className="grid gap-5 lg:grid-cols-2">
          <form className="panel space-y-4" onSubmit={update}>
            <h2 className="text-lg font-semibold">Hồ sơ</h2>
            <Field label="Họ và tên">
              <input
                name="fullName"
                required
                maxLength={100}
                defaultValue={profile.fullName || ""}
              />
            </Field>
            <Field label="Email">
              <input readOnly value={profile.email || ""} />
            </Field>
            <Field label="Số điện thoại" hint="7–15 chữ số, có thể bắt đầu bằng +; chỉ kiểm tra định dạng, chưa xác minh sở hữu.">
              <input
                name="phone"
                type="tel"
                autoComplete="tel"
                pattern="[+0-9 -]*"
                maxLength={20}
                defaultValue={profile.phone || ""}
              />
            </Field>
            <button className="btn" disabled={busy}>
              Lưu hồ sơ
            </button>
          </form>
          <form className="panel space-y-4" onSubmit={changePassword}>
            <h2 className="text-lg font-semibold">Đổi mật khẩu</h2>
            <PasswordField label="Mật khẩu hiện tại" name="current" required maxLength={72} autoComplete="current-password" />
            <PasswordField label="Mật khẩu mới" name="next" required minLength={8} maxLength={72} autoComplete="new-password" />
            <p className="text-sm text-slate-600">{passwordHint}</p>
            <PasswordField label="Nhập lại mật khẩu mới" name="confirmation" required autoComplete="new-password" />
            <p className="text-sm text-slate-600">
              Đổi thành công sẽ đăng xuất thiết bị này và thu hồi các phiên trên thiết bị khác. Bạn cần đăng nhập lại.
            </p>
            <button className="btn-secondary" disabled={busy}>
              Đổi mật khẩu
            </button>
          </form>
          <form
            className="panel space-y-4 lg:col-span-2"
            onSubmit={updateLicense}
          >
            <h2 className="text-lg font-semibold">
              Thông tin giấy phép lái xe
            </h2>
            <p className="text-sm text-slate-600">
              Khai báo thông tin và tải hai mặt GPLX bên dưới trước khi gửi yêu cầu thuê. Nhân viên vẫn đối chiếu bản gốc khi giao xe.
            </p>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Số giấy phép">
                <input
                  name="licenseNumber"
                  required
                  maxLength={40}
                  defaultValue={license?.licenseNumber || ""}
                />
              </Field>
              <Field label="Hạng bằng">
                <select
                  key={licenseScheme}
                  name="licenseClass"
                  required
                  defaultValue={
                    licenseClasses.includes(license?.licenseClass)
                      ? license.licenseClass
                      : licenseClasses[0]
                  }
                >
                  {licenseClasses.map((licenseClass) => (
                    <option key={licenseClass} value={licenseClass}>
                      {licenseClass}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Hệ giấy phép">
                <select
                  name="scheme"
                  required
                  value={licenseScheme}
                  onChange={(event) => setLicenseScheme(event.target.value)}
                >
                  <option value="VN_PRE_2025">Việt Nam trước 2025</option>
                  <option value="VN_2025">Việt Nam 2025</option>
                </select>
              </Field>
              <Field label="Ngày hết hạn">
                <input
                  name="expiresOn"
                  type="date"
                  required
                  defaultValue={license?.expiresOn || ""}
                />
              </Field>
            </div>
            <label className="flex gap-3 text-sm">
              <input
                name="automaticOnly"
                type="checkbox"
                defaultChecked={license?.automaticOnly || false}
              />{" "}
              Chỉ được điều khiển xe số tự động
            </label>
            {license ? (
              <p className="text-sm text-slate-600">
                Trạng thái xác minh: {licenseStatusLabels[license.status] || "Chưa xác định"}
              </p>
            ) : null}
            <button className="btn" disabled={busy}>
              Lưu thông tin GPLX
            </button>
          </form>
          <div className="panel lg:col-span-2"><LicensePhotos key={license?.version ?? "new"} license={license} onSaved={licenseRequest.reload} /></div>
        </div>
      </RequestState>
    </Page>
  );
}
