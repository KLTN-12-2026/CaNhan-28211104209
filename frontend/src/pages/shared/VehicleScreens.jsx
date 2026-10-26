import { roleLabels } from "../../utils/uiLabels";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { useState, useMemo } from "react";
import {
  Calendar,
  Car,
  Check,
  ChevronRight,
  Clock,
  ExternalLink,
  Filter,
  Fuel,
  Gauge,
  Info,
  MapPin,
  RotateCcw,
  Search,
  ShieldCheck,
  Users,
  X,
} from "lucide-react";
import { useDemo } from "../../context/DemoContext";
import { useAuth } from "../../context/AuthContext";
import { available } from "../../demo/model";
import {
  Page,
  Field,
  Notice,
  Missing,
  money,
  toUTC,
} from "../../components/common/PageKit";
import EmptyState from "../../components/common/EmptyState";
import Badge from "../../components/common/Badge";
import VehicleCard, {
  VehicleVisual,
} from "../../components/vehicle/VehicleCard";
import { getVehicleMedia } from "../../assets/images/vehicles";

export function VehicleListScreen() {
  const { data } = useDemo();
  const [params, setParams] = useSearchParams();
  const [filtersOpen, setFiltersOpen] = useState(false);

  const q = params.get("q") || "";
  const seats = params.get("seats") || "";
  const category = params.get("category") || "";
  const fuel = params.get("fuel") || "";
  const transmission = params.get("transmission") || "";
  const start = params.get("start") || "";
  const end = params.get("end") || "";
  const sort = params.get("sort") || "default";

  const validDate = (v) =>
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(v) &&
    Number.isFinite(Date.parse(`${v}:00+07:00`));

  const datesOK =
    validDate(start) && validDate(end) && toUTC(start) < toUTC(end);
  const dateError = Boolean(start || end) && !datesOK;

  const rentalDays = useMemo(() => {
    if (!datesOK) return 0;
    const s = new Date(`${start}:00+07:00`).getTime();
    const e = new Date(`${end}:00+07:00`).getTime();
    return Math.max(1, Math.ceil((e - s) / 86400000));
  }, [datesOK, start, end]);

  function change(key, value) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  }

  function clearAllFilters() {
    setParams({}, { replace: true });
  }

  const activeFilters = useMemo(() => {
    const list = [];
    if (q) list.push({ key: "q", label: `Từ khóa: "${q}"` });
    if (category) list.push({ key: "category", label: `Loại xe: ${category}` });
    if (seats) list.push({ key: "seats", label: `>= ${seats} chỗ` });
    if (fuel) list.push({ key: "fuel", label: `Nhiên liệu: ${fuel}` });
    if (transmission)
      list.push({ key: "transmission", label: `Hộp số: ${transmission}` });
    if (datesOK)
      list.push({
        key: "dates",
        label: `Thời gian: ${rentalDays} ngày`,
        isDate: true,
      });
    return list;
  }, [q, category, seats, fuel, transmission, datesOK, rentalDays]);

  let vehicles = data.vehicles.filter((v) => {
    const matchQuery =
      !q ||
      v.name.toLocaleLowerCase("vi").includes(q.toLocaleLowerCase("vi")) ||
      v.brand.toLocaleLowerCase("vi").includes(q.toLocaleLowerCase("vi"));
    const matchSeats = !seats || v.seats >= Number(seats);
    const matchCategory = !category || v.category === category;
    const matchFuel = !fuel || v.fuel === fuel;
    const matchTransmission = !transmission || v.transmission === transmission;
    const matchAvailable =
      !datesOK ||
      available(data.vehicles, data.bookings, v.id, toUTC(start), toUTC(end));

    return (
      matchQuery &&
      matchSeats &&
      matchCategory &&
      matchFuel &&
      matchTransmission &&
      matchAvailable
    );
  });

  if (sort === "price-asc") {
    vehicles = [...vehicles].sort((a, b) => a.price - b.price);
  } else if (sort === "price-desc") {
    vehicles = [...vehicles].sort((a, b) => b.price - a.price);
  } else if (sort === "seats-desc") {
    vehicles = [...vehicles].sort((a, b) => b.seats - a.seats);
  }

  const availableCount = vehicles.filter((v) => v.status === "ACTIVE").length;

  return (
    <Page
      title="Danh sách xe tự lái"
      description="Lựa chọn phương tiện chất lượng cao cho chuyến đi của bạn tại Đà Nẵng và miền Trung."
    >
      <div className="grid gap-6 lg:grid-cols-[280px_1fr]">
        {/* SIDEBAR BỘ LỌC */}
        <aside className="panel self-start lg:sticky lg:top-24 space-y-5">
          <div className="flex items-center justify-between border-b border-slate-100 pb-3.5">
            <div className="flex items-center gap-2 font-bold text-slate-900">
              <Filter size={18} className="text-teal-700" />
              <span>Bộ lọc tìm kiếm</span>
            </div>
            {activeFilters.length > 0 ? (
              <button
                type="button"
                onClick={clearAllFilters}
                className="flex items-center gap-1 text-xs font-semibold text-teal-700 hover:text-teal-900 hover:underline"
              >
                <RotateCcw size={12} />
                <span>Đặt lại</span>
              </button>
            ) : null}
          </div>

          <button
            className="flex w-full items-center justify-between rounded-xl bg-slate-50 p-2.5 text-sm font-semibold text-slate-800 lg:hidden"
            aria-expanded={filtersOpen}
            aria-controls="vehicle-filters"
            onClick={() => setFiltersOpen((value) => !value)}
          >
            <span>Tùy chỉnh bộ lọc</span>
            <span aria-hidden="true">{filtersOpen ? "−" : "+"}</span>
          </button>

          <div
            id="vehicle-filters"
            className={`${filtersOpen ? "block" : "hidden"} space-y-4 lg:block`}
          >
            <label className="field">
              <span>Tìm kiếm xe</span>
              <div className="relative">
                <input
                  placeholder="Toyota, Vios, Xpander..."
                  value={q}
                  onChange={(e) => change("q", e.target.value)}
                  className="pr-8"
                />
                <Search
                  size={16}
                  className="pointer-events-none absolute right-3 top-3.5 text-slate-400"
                />
              </div>
            </label>

            <Field label="Loại xe">
              <select
                value={category}
                onChange={(e) => change("category", e.target.value)}
              >
                <option value="">Tất cả phân khúc</option>
                {[...new Set(data.vehicles.map((v) => v.category))].map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </Field>

            <Field label="Số chỗ ngồi tối thiểu">
              <select
                value={seats}
                onChange={(e) => change("seats", e.target.value)}
              >
                <option value="">Tất cả (5 & 7 chỗ)</option>
                <option value="5">5 chỗ trở lên</option>
                <option value="7">7 chỗ trở lên</option>
              </select>
            </Field>

            <Field label="Hộp số">
              <select
                value={transmission}
                onChange={(e) => change("transmission", e.target.value)}
              >
                <option value="">Tất cả hộp số</option>
                <option value="Tự động">Tự động</option>
                <option value="Số sàn">Số sàn</option>
              </select>
            </Field>

            <Field label="Nhiên liệu">
              <select
                value={fuel}
                onChange={(e) => change("fuel", e.target.value)}
              >
                <option value="">Tất cả nhiên liệu</option>
                <option value="Xăng">Xăng</option>
                <option value="Dầu">Dầu (Diesel)</option>
                <option value="Điện">Điện</option>
                <option value="Hybrid">Hybrid</option>
              </select>
            </Field>

            <div className="border-t border-slate-100 pt-4 space-y-3">
              <p className="text-xs font-bold uppercase tracking-wider text-slate-500">
                Lịch thuê dự kiến
              </p>
              <Field label="Nhận xe (Giờ VN)">
                <input
                  type="datetime-local"
                  value={start}
                  onChange={(e) => change("start", e.target.value)}
                />
              </Field>

              <Field label="Trả xe (Giờ VN)">
                <input
                  type="datetime-local"
                  value={end}
                  onChange={(e) => change("end", e.target.value)}
                />
              </Field>
            </div>
          </div>
        </aside>

        {/* NỘI DUNG DANH SÁCH */}
        <div className="space-y-4">
          {dateError ? (
            <Notice error>
              Vui lòng nhập đủ thời gian nhận và trả xe hợp lệ. Thời gian trả xe
              phải sau thời gian nhận xe.
            </Notice>
          ) : datesOK ? (
            <div className="rounded-xl border border-teal-200 bg-teal-50/80 p-3.5 text-sm text-teal-900 flex items-center justify-between flex-wrap gap-2">
              <div className="flex items-center gap-2">
                <Calendar size={18} className="text-teal-700" />
                <span>
                  Đang lọc xe khả dụng trong <strong>{rentalDays} ngày</strong>.
                  Giá dự kiến đã được tính tự động.
                </span>
              </div>
              <span className="text-xs font-medium text-teal-700">
                Chi nhánh KDrive Đà Nẵng
              </span>
            </div>
          ) : (
            <Notice>
              Chọn thời gian nhận - trả xe ở bộ lọc bên trái để kiểm tra chính
              xác xe trống và chi phí dự kiến.
            </Notice>
          )}

          {/* ACTIVE FILTER CHIPS & TOOLBAR */}
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200/80 pb-3">
            <div className="flex flex-wrap items-center gap-2">
              <p className="text-sm font-semibold text-slate-800">
                Tìm thấy{" "}
                <span className="text-teal-700 font-bold">
                  {vehicles.length}
                </span>{" "}
                xe ({availableCount} sẵn sàng)
              </p>
              {activeFilters.map((f) => (
                <span
                  key={f.key}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-700"
                >
                  {f.label}
                  {f.isDate ? (
                    <button
                      type="button"
                      onClick={() => {
                        change("start", "");
                        change("end", "");
                      }}
                      className="text-slate-400 hover:text-slate-700"
                      title="Bỏ lọc thời gian"
                    >
                      <X size={12} />
                    </button>
                  ) : (
                    <button
                      type="button"
                      onClick={() => change(f.key, "")}
                      className="text-slate-400 hover:text-slate-700"
                      title="Bỏ lọc"
                    >
                      <X size={12} />
                    </button>
                  )}
                </span>
              ))}
            </div>

            <div className="flex items-center gap-2">
              <span className="text-xs font-medium text-slate-500 whitespace-nowrap">
                Sắp xếp theo:
              </span>
              <select
                className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-800 shadow-sm"
                value={sort}
                onChange={(e) => change("sort", e.target.value)}
              >
                <option value="default">Đề xuất mặc định</option>
                <option value="price-asc">Giá thuê: Thấp → Cao</option>
                <option value="price-desc">Giá thuê: Cao → Thấp</option>
                <option value="seats-desc">Số chỗ: Nhiều nhất</option>
              </select>
            </div>
          </div>

          {/* GRID XE */}
          {vehicles.length > 0 ? (
            <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3 pt-2">
              {vehicles.map((v) => (
                <VehicleCard
                  key={v.id}
                  vehicle={v}
                  search={`?${params}`}
                  rentalDays={rentalDays}
                />
              ))}
            </div>
          ) : (
            <EmptyState
              title="Chưa tìm thấy xe phù hợp"
              description="Hãy thử nới lỏng bộ lọc, chọn khoảng thời gian khác hoặc đặt lại bộ lọc tìm kiếm."
              action={
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={clearAllFilters}
                >
                  Xóa tất cả bộ lọc
                </button>
              }
            />
          )}
        </div>
      </div>
    </Page>
  );
}

export function VehicleDetailScreen() {
  const { vehicleId } = useParams();
  const [params] = useSearchParams();
  const { data } = useDemo();
  const { user } = useAuth();

  const vehicle = data.vehicles.find((v) => v.id === vehicleId);

  if (!vehicle) {
    return (
      <Page title="Chi tiết xe">
        <Missing back="/vehicles" title="Không tìm thấy xe yêu cầu" />
      </Page>
    );
  }

  return (
    <VehicleDetailContent
      key={vehicleId}
      vehicle={vehicle}
      vehicleId={vehicleId}
      params={params}
      data={data}
      user={user}
    />
  );
}

function VehicleDetailContent({ vehicle, vehicleId, params, data, user }) {
  const [activeImageIndex, setActiveImageIndex] = useState(0);
  const media = getVehicleMedia(vehicle);
  const galleryItems =
    media?.gallery ||
    (vehicle.gallery
      ? vehicle.gallery.map((g) =>
          typeof g === "string" ? { url: g, label: "Ảnh xe" } : g,
        )
      : [{ url: vehicle.image, label: "Ảnh ngoại thất" }]);

  const activeImage =
    galleryItems[activeImageIndex]?.url ||
    galleryItems[0]?.url ||
    vehicle.image;
  const activeLabel =
    galleryItems[activeImageIndex]?.label || galleryItems[0]?.label || "Ảnh xe";
  const attribution = media?.attribution?.images
    ? media.attribution.images[activeImageIndex === 0 ? "main" : "interior"]
    : media?.attribution || vehicle.attribution;

  const startParam = params.get("start") || "";
  const endParam = params.get("end") || "";

  const [localStart, setLocalStart] = useState(startParam);
  const [localEnd, setLocalEnd] = useState(endParam);

  const validDate = (v) =>
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(v) &&
    Number.isFinite(Date.parse(`${v}:00+07:00`));

  const hasDates =
    validDate(localStart) &&
    validDate(localEnd) &&
    toUTC(localStart) < toUTC(localEnd);

  const rentalDays = useMemo(() => {
    if (!hasDates) return 1;
    const s = new Date(`${localStart}:00+07:00`).getTime();
    const e = new Date(`${localEnd}:00+07:00`).getTime();
    return Math.max(1, Math.ceil((e - s) / 86400000));
  }, [hasDates, localStart, localEnd]);

  const estimatedTotal = vehicle.price * rentalDays;

  const bookingQuery = new URLSearchParams({ vehicleId });
  if (localStart) bookingQuery.set("start", localStart);
  if (localEnd) bookingQuery.set("end", localEnd);

  const reviews = data.reviews.filter(
    (r) => r.vehicleId === vehicleId && !r.hidden,
  );

  const isAvailable = vehicle.status === "ACTIVE";

  return (
    <Page
      title={vehicle.name}
      description={`${vehicle.brand} · ${vehicle.category} ${vehicle.seats} chỗ · Số ${vehicle.transmission.toLowerCase()}`}
      actions={
        <Link className="btn-secondary" to={`/vehicles?${params}`}>
          ← Quay lại danh sách xe
        </Link>
      }
    >
      {/* BREADCRUMB */}
      <nav
        aria-label="Breadcrumb"
        className="mb-4 flex items-center gap-2 text-xs text-slate-500"
      >
        <Link to="/" className="hover:text-teal-700">
          Trang chủ
        </Link>
        <ChevronRight size={13} />
        <Link to={`/vehicles?${params}`} className="hover:text-teal-700">
          Danh sách xe
        </Link>
        <ChevronRight size={13} />
        <span className="font-semibold text-slate-800">{vehicle.name}</span>
      </nav>

      <div className="grid gap-8 lg:grid-cols-[1fr_380px]">
        {/* CỘT TRÁI: ẢNH GALLERY & THÔNG TIN CHI TIẾT */}
        <div className="space-y-6">
          {/* GALLERY KHUNG ẢNH */}
          <div className="space-y-3">
            <VehicleVisual
              vehicle={vehicle}
              large
              customSrc={activeImage}
              alt={`${vehicle.name} - ${activeLabel}`}
            />

            {galleryItems.length > 1 ? (
              <div className="flex flex-wrap items-center gap-3 pt-1">
                {galleryItems.map((item, index) => {
                  const isSelected = index === activeImageIndex;
                  return (
                    <button
                      key={item.url || index}
                      type="button"
                      aria-pressed={isSelected}
                      onClick={() => setActiveImageIndex(index)}
                      className={`group flex items-center gap-2.5 rounded-xl border p-1.5 text-xs font-semibold transition ${
                        isSelected
                          ? "border-teal-600 bg-teal-50 text-teal-900 shadow-sm ring-2 ring-teal-500/20"
                          : "border-slate-200 bg-white text-slate-600 hover:border-slate-300 hover:bg-slate-50"
                      }`}
                    >
                      <div className="h-12 w-16 overflow-hidden rounded-lg bg-slate-100">
                        <img
                          src={item.url}
                          alt={item.label || `Góc nhìn ${index + 1}`}
                          className="h-full w-full object-cover object-center"
                        />
                      </div>
                      <div className="text-left pr-2">
                        <p className="font-bold">
                          {item.label || `Ảnh ${index + 1}`}
                        </p>
                        <p className="text-[10px] text-slate-400 font-normal">
                          {index === 0 ? "Ngoại thất" : "Khoang lái"}
                        </p>
                      </div>
                    </button>
                  );
                })}
              </div>
            ) : null}
          </div>

          {/* ĐẶC ĐIỂM NỔI BẬT & MÔ TẢ */}
          <section className="panel space-y-4">
            <div className="flex items-center justify-between border-b border-slate-100 pb-3">
              <h2 className="text-lg font-bold text-slate-900">
                Đồng hành & trải nghiệm chuyến đi
              </h2>
              {isAvailable ? (
                <Badge tone="success">Xe sẵn sàng giao nhận</Badge>
              ) : (
                <Badge tone="warning">
                  {vehicle.status === "MAINTENANCE"
                    ? "Đang bảo dưỡng kỹ thuật"
                    : "Ngừng khai thác"}
                </Badge>
              )}
            </div>

            <p className="leading-7 text-slate-700">{vehicle.description}</p>

            {/* TRANG BỊ & TIỆN NGHI */}
            <div className="pt-2">
              <h3 className="mb-2.5 text-xs font-bold uppercase tracking-wider text-slate-500">
                Trang bị & Tiện nghi xe
              </h3>
              <div className="flex flex-wrap gap-2">
                {vehicle.amenities.map((a) => (
                  <span
                    key={a}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-teal-200/80 bg-teal-50/70 px-3 py-1.5 text-xs font-semibold text-teal-900"
                  >
                    <Check size={14} className="text-teal-700" />
                    {a}
                  </span>
                ))}
              </div>
            </div>

            {/* BẢNG THÔNG SỐ KỸ THUẬT */}
            <div className="pt-4 border-t border-slate-100">
              <h3 className="mb-3 text-xs font-bold uppercase tracking-wider text-slate-500">
                Thông số kỹ thuật chính
              </h3>
              <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
                <div className="rounded-xl bg-slate-50 p-3">
                  <dt className="text-xs text-slate-500 flex items-center gap-1">
                    <Car size={13} />
                    <span>Hãng xe</span>
                  </dt>
                  <dd className="mt-1 font-bold text-slate-800">
                    {vehicle.brand}
                  </dd>
                </div>

                <div className="rounded-xl bg-slate-50 p-3">
                  <dt className="text-xs text-slate-500 flex items-center gap-1">
                    <Users size={13} />
                    <span>Số chỗ ngồi</span>
                  </dt>
                  <dd className="mt-1 font-bold text-slate-800">
                    {vehicle.seats} chỗ
                  </dd>
                </div>

                <div className="rounded-xl bg-slate-50 p-3">
                  <dt className="text-xs text-slate-500 flex items-center gap-1">
                    <Gauge size={13} />
                    <span>Hộp số</span>
                  </dt>
                  <dd className="mt-1 font-bold text-slate-800">
                    {vehicle.transmission}
                  </dd>
                </div>

                <div className="rounded-xl bg-slate-50 p-3">
                  <dt className="text-xs text-slate-500 flex items-center gap-1">
                    <Fuel size={13} />
                    <span>Nhiên liệu</span>
                  </dt>
                  <dd className="mt-1 font-bold text-slate-800">
                    {vehicle.fuel}
                  </dd>
                </div>
              </dl>
            </div>

            {/* ATTRIBUTION BẢN QUYỀN HÌNH ẢNH */}
            {attribution ? (
              <div className="mt-6 rounded-xl border border-slate-200/70 bg-slate-50/70 p-3.5 text-xs text-slate-600">
                <div className="flex items-center gap-1.5 font-semibold text-slate-800">
                  <ShieldCheck size={16} className="text-teal-700" />
                  <span>Nguồn & Giấy phép sử dụng hình ảnh</span>
                </div>
                <p className="mt-1.5 leading-relaxed">
                  Ảnh {activeLabel.toLowerCase()} minh họa bởi tác giả{" "}
                  <strong>{attribution.author}</strong> trên{" "}
                  <strong>{attribution.source || "Wikimedia Commons"}</strong>{" "}
                  (Giấy phép{" "}
                  <a
                    href={
                      attribution.licenseUrl ||
                      "https://creativecommons.org/licenses/by-sa/4.0/"
                    }
                    target="_blank"
                    rel="noreferrer"
                    className="font-medium text-teal-700 underline hover:text-teal-800"
                  >
                    {attribution.license || "CC BY-SA 4.0"}
                  </a>
                  ).{" "}
                  {attribution.sourceUrl ? (
                    <a
                      href={attribution.sourceUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-0.5 text-teal-700 underline hover:text-teal-800"
                    >
                      Trang nguồn gốc <ExternalLink size={11} />
                    </a>
                  ) : null}
                </p>
                <p className="mt-1">
                  Ảnh có thể khác đời hoặc phiên bản xe thuê. Bản hiển thị đã
                  thu nhỏ/nén và có thể cắt khung.
                </p>
              </div>
            ) : null}
          </section>

          {/* ĐÁNH GIÁ TỪ CHUYẾN ĐI */}
          <section className="panel space-y-3">
            <h2 className="text-lg font-bold text-slate-900">
              Đánh giá từ khách hàng ({reviews.length})
            </h2>
            {reviews.length ? (
              reviews.map((r) => (
                <div className="border-t border-slate-100 py-3" key={r.id}>
                  <p className="font-semibold text-slate-800">
                    {r.rating}/5 ★ · Khách hàng KDrive
                  </p>
                  <p className="mt-1.5 text-sm text-slate-600">{r.content}</p>
                </div>
              ))
            ) : (
              <p className="text-sm text-slate-500 py-2">
                Chưa có đánh giá công khai nào cho mẫu xe này.
              </p>
            )}
          </section>
        </div>

        {/* CỘT PHẢI: STICKY PANEL BÁO GIÁ VÀ ĐẶT XE */}
        <aside className="panel h-fit space-y-5 lg:sticky lg:top-24">
          <div className="border-b border-slate-100 pb-4">
            <p className="text-xs font-medium uppercase tracking-wider text-slate-500">
              Giá thuê tự lái niêm yết
            </p>
            <div className="mt-1 flex items-baseline gap-1.5">
              <span className="text-3xl font-bold text-teal-800">
                {money(vehicle.price)}
              </span>
              <span className="text-sm font-medium text-slate-500">
                / 24 giờ
              </span>
            </div>
            <p className="mt-1 text-xs text-slate-400">
              Biển số mẫu: <span className="font-mono">{vehicle.plate}</span> ·
              Giao nhận tại trạm
            </p>
          </div>

          {/* BỘ CHỌN THỜI GIAN NHANH */}
          <div className="space-y-3 rounded-xl bg-slate-50 p-3.5 border border-slate-100">
            <p className="text-xs font-bold uppercase tracking-wider text-slate-700 flex items-center gap-1.5">
              <Clock size={14} className="text-teal-700" />
              <span>Dự tính chi phí theo lịch</span>
            </p>

            <Field label="Nhận xe">
              <input
                type="datetime-local"
                value={localStart}
                onChange={(e) => setLocalStart(e.target.value)}
              />
            </Field>

            <Field label="Trả xe">
              <input
                type="datetime-local"
                value={localEnd}
                onChange={(e) => setLocalEnd(e.target.value)}
              />
            </Field>

            {hasDates ? (
              <div className="mt-3 border-t border-slate-200/80 pt-3 space-y-1.5 text-xs">
                <div className="flex justify-between text-slate-600">
                  <span>Thời lượng thuê:</span>
                  <span className="font-semibold text-slate-900">
                    {rentalDays} ngày ({rentalDays * 24} giờ)
                  </span>
                </div>
                <div className="flex justify-between text-slate-600">
                  <span>Đơn giá ngày:</span>
                  <span>{money(vehicle.price)}</span>
                </div>
                <div className="flex justify-between text-sm font-bold text-teal-800 border-t border-slate-200/60 pt-2">
                  <span>Tổng tiền thuê dự kiến:</span>
                  <span>{money(estimatedTotal)}</span>
                </div>
              </div>
            ) : null}
          </div>

          {/* ĐỊA ĐIỂM NHẬN TRẢ */}
          <div className="flex items-start gap-2.5 text-xs text-slate-600">
            <MapPin size={16} className="text-teal-700 shrink-0 mt-0.5" />
            <div>
              <p className="font-semibold text-slate-800">
                KDrive Đà Nẵng · Chi nhánh demo
              </p>
              <p className="text-slate-500">
                Nhận và hoàn trả xe cùng chi nhánh trong trung tâm thành phố.
              </p>
            </div>
          </div>

          {/* CTA & ROLE CHECK */}
          {vehicle.status !== "ACTIVE" ? (
            <Notice error>
              Xe đang trong lịch{" "}
              {vehicle.status === "MAINTENANCE"
                ? "bảo dưỡng"
                : "ngừng khai thác"}
              , tạm thời không nhận đặt mới.
            </Notice>
          ) : user && user.role !== "USER" ? (
            <Notice>
              Tài khoản hiện tại có vai trò <strong>{roleLabels[user.role] || "Chưa xác định"}</strong>. Hãy
              đổi sang vai trò <strong>Khách hàng</strong> trong thanh công cụ dữ liệu mẫu để
              tạo đơn thuê xe.
            </Notice>
          ) : (
            <Link
              className="btn w-full shadow-md hover:shadow-lg font-bold text-base"
              to={`/user/bookings/new?${bookingQuery}`}
            >
              Tiến hành đặt xe ngay ➔
            </Link>
          )}

          {/* ĐIỀU KHOẢN & DEMO NOTICE */}
          <div className="rounded-xl bg-slate-50 p-3 text-[11px] text-slate-500 leading-relaxed border border-slate-100">
            <p className="font-semibold text-slate-700 flex items-center gap-1 mb-1">
              <Info size={12} className="text-teal-700" />
              Lưu ý quy trình thuê xe:
            </p>
            <ul className="list-disc list-inside space-y-0.5">
              <li>Thời lượng tính theo block 24 giờ, làm tròn lên.</li>
              <li>Nhân viên kiểm tra GPLX & CCCD khi bàn giao xe.</li>
              <li>Bản DEMO mô phỏng luồng duyệt đơn và quản lý đội xe.</li>
            </ul>
          </div>
        </aside>
      </div>
    </Page>
  );
}
