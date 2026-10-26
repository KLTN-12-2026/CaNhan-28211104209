import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useState, useMemo } from "react";
import {
  Car,
  ChevronRight,
  Search,
  Sparkles,
  Shield,
  MapPin,
  Users,
  Gauge,
  Fuel,
} from "lucide-react";
import { useDemo } from "../../context/DemoContext";
import { DEMO_MODE } from "../../demo/config";
import {
  Field,
  Notice,
  localInput,
  toUTC,
} from "../../components/common/PageKit";
import VehicleCard from "../../components/vehicle/VehicleCard";

const vehicleCategories = ["Tất cả", "Sedan", "SUV", "MPV"];
const seatOptions = ["Tất cả", "5 chỗ", "7 chỗ"];
const transmissionOptions = ["Tất cả", "Tự động", "Số sàn"];
const fuelOptions = ["Tất cả", "Xăng", "Dầu", "Điện", "Hybrid"];

function buildSearchParams(values) {
  const params = new URLSearchParams();
  if (values.query) params.set("q", values.query);
  if (values.category && values.category !== "Tất cả")
    params.set("category", values.category);
  if (values.seats && values.seats !== "Tất cả")
    params.set("seats", values.seats.replace(" chỗ", ""));
  if (values.transmission && values.transmission !== "Tất cả")
    params.set("transmission", values.transmission);
  if (values.fuel && values.fuel !== "Tất cả") params.set("fuel", values.fuel);
  if (values.start) params.set("start", values.start);
  if (values.end) params.set("end", values.end);
  return params.toString();
}

export default function HomePage() {
  const { data } = useDemo();
  const navigate = useNavigate();
  const [searchError, setSearchError] = useState("");
  const [searchParams] = useSearchParams();
  const [formValues, setFormValues] = useState(() => ({
    query: searchParams.get("q") || "",
    category: searchParams.get("category") || "Tất cả",
    seats: searchParams.get("seats")
      ? `${searchParams.get("seats")} chỗ`
      : "Tất cả",
    transmission: searchParams.get("transmission") || "Tất cả",
    fuel: searchParams.get("fuel") || "Tất cả",
    start: searchParams.get("start") || localInput(1),
    end: searchParams.get("end") || localInput(2),
  }));

  const handleChange = (key, value) => {
    setSearchError("");
    setFormValues((prev) => ({ ...prev, [key]: value }));
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    const { start, end } = formValues;
    if ((start || end) && (!start || !end || toUTC(start) >= toUTC(end))) {
      setSearchError(
        "Vui lòng chọn đủ thời gian nhận và trả xe; thời gian trả phải sau thời gian nhận.",
      );
      return;
    }
    if (start && Date.parse(toUTC(start)) < Date.now()) {
      setSearchError("Thời gian nhận xe phải ở tương lai.");
      return;
    }
    const queryString = buildSearchParams(formValues);
    navigate(`/vehicles${queryString ? `?${queryString}` : ""}`);
  };

  const featuredVehicles = useMemo(() => {
    return data.vehicles.filter((v) => v.status === "ACTIVE").slice(0, 4);
  }, [data.vehicles]);

  const steps = [
    {
      number: "01",
      title: "Tìm xe phù hợp",
      description:
        "Chọn loại xe, số chỗ, hộp số, nhiên liệu và khoảng thời gian thuê.",
      icon: Search,
    },
    {
      number: "02",
      title: "Gửi yêu cầu thuê",
      description:
        "Kiểm tra thông tin, xem giá mẫu và gửi yêu cầu để nhân viên xác nhận.",
      icon: Shield,
    },
    {
      number: "03",
      title: "Nhận xe & tận hưởng",
      description:
        "Nhận xe tại chi nhánh, kiểm tra GPLX & CCCD. Trả xe cùng chi nhánh.",
      icon: Car,
    },
  ];

  return (
    <div>
      <section className="bg-gradient-to-br from-teal-900 via-teal-800 to-slate-900 text-white relative overflow-hidden">
        <div
          className="absolute inset-0 bg-[url('/images/vehicles/toyota-vios.jpg')] bg-cover bg-center opacity-5"
          aria-hidden="true"
        />
        <div className="absolute inset-0 bg-gradient-to-r from-teal-900/90 via-teal-800/80 to-slate-900/90" />
        <div className="relative mx-auto max-w-6xl px-4 py-16 lg:py-24">
          <div className="max-w-2xl">
            <p className="text-sm font-semibold uppercase tracking-wide text-teal-200">
              Hệ thống thuê xe tự lái KDrive
            </p>
            <h1 className="mt-3 text-3xl font-bold leading-tight sm:text-5xl lg:text-6xl">
              Chọn xe phù hợp, đi chuyến bất ngờ
            </h1>
            <p className="mt-4 max-w-xl text-teal-50/90 text-lg">
              Catalog xe đa dạng, giá niêm yết minh bạch, quy trình đặt – nhận –
              trả đơn giản tại Đà Nẵng.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <Link
                to="/vehicles"
                className="rounded-xl bg-white px-6 py-3.5 text-sm font-semibold text-teal-900 hover:bg-teal-50 transition shadow-sm"
              >
                Xem toàn bộ xe
              </Link>
              <Link
                to="/ai/recommend"
                className="rounded-xl border border-teal-200/60 px-6 py-3.5 text-sm font-semibold text-white hover:bg-white/10 transition"
              >
                Thử gợi ý AI
              </Link>
            </div>
            {DEMO_MODE && (
              <p className="mt-5 text-xs font-medium text-teal-200/80 flex items-center gap-1.5">
                <span className="px-2 py-0.5 rounded bg-white/10 backdrop-blur">
                  DEMO UI
                </span>
                Dữ liệu minh họa · Không đặt xe thật
              </p>
            )}
          </div>
        </div>
      </section>

      <section className="page-shell -mt-10 relative z-10">
        <form
          onSubmit={handleSubmit}
          className="panel grid grid-cols-1 gap-5 lg:grid-cols-12 lg:items-end"
          noValidate
        >
          <div className="min-w-0 lg:col-span-12">
            <h2 className="text-xl font-bold text-slate-900">
              Tìm xe cho chuyến đi của bạn
            </h2>
            <p className="mt-1 text-sm text-slate-500">
              Chọn xe và thời gian thuê phù hợp. Thời gian hiển thị theo giờ
              Việt Nam.
            </p>
          </div>
          <label className="field min-w-0 lg:col-span-4">
            <span>Tìm kiếm xe</span>
            <div className="relative min-w-0">
              <input
                type="search"
                placeholder="Toyota, Vios, Xpander..."
                value={formValues.query}
                onChange={(e) => handleChange("query", e.target.value)}
                className="pr-10"
                aria-label="Từ khóa tìm kiếm xe"
              />
              <Search
                size={18}
                className="pointer-events-none absolute right-3 top-3.5 text-slate-400"
                aria-hidden="true"
              />
            </div>
          </label>

          <div className="grid min-w-0 grid-cols-2 gap-4 lg:col-span-8 lg:grid-cols-4">
            <Field label="Loại xe">
              <select
                value={formValues.category}
                onChange={(e) => handleChange("category", e.target.value)}
                aria-label="Chọn loại xe"
              >
                {vehicleCategories.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </Field>

            <Field label="Số chỗ">
              <select
                value={formValues.seats}
                onChange={(e) => handleChange("seats", e.target.value)}
                aria-label="Chọn số chỗ ngồi"
              >
                {seatOptions.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </Field>

            <Field label="Hộp số">
              <select
                value={formValues.transmission}
                onChange={(e) => handleChange("transmission", e.target.value)}
                aria-label="Chọn hộp số"
              >
                {transmissionOptions.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </Field>

            <Field label="Nhiên liệu">
              <select
                value={formValues.fuel}
                onChange={(e) => handleChange("fuel", e.target.value)}
                aria-label="Chọn nhiên liệu"
              >
                {fuelOptions.map((f) => (
                  <option key={f} value={f}>
                    {f}
                  </option>
                ))}
              </select>
            </Field>
          </div>

          <div className="grid min-w-0 grid-cols-1 gap-4 sm:grid-cols-2 lg:col-span-8">
            <Field label="Nhận xe (Giờ VN)">
              <input
                type="datetime-local"
                value={formValues.start}
                onChange={(e) => handleChange("start", e.target.value)}
                min={localInput(0)}
                aria-label="Thời gian nhận xe"
              />
            </Field>

            <Field label="Trả xe (Giờ VN)">
              <input
                type="datetime-local"
                value={formValues.end}
                onChange={(e) => handleChange("end", e.target.value)}
                min={formValues.start}
                aria-label="Thời gian trả xe"
              />
            </Field>
          </div>

          <button
            type="submit"
            className="btn min-h-12 w-full whitespace-nowrap px-6 text-base font-semibold shadow-md hover:shadow-lg lg:col-span-4"
          >
            <Search size={18} className="mr-2" aria-hidden="true" />
            Tìm xe sẵn sàng
          </button>
          {searchError && (
            <div className="lg:col-span-12">
              <Notice error>{searchError}</Notice>
            </div>
          )}
        </form>

        <div className="mt-4 flex flex-wrap items-center justify-center gap-2 text-xs text-slate-500">
          <span className="flex items-center gap-1 px-2 py-1 rounded bg-teal-50 text-teal-800 font-medium">
            <MapPin size={12} /> KDrive Đà Nẵng · Chi nhánh demo
          </span>
          <span className="flex items-center gap-1 px-2 py-1 rounded bg-slate-100 text-slate-700">
            <Users size={12} /> 5–7 chỗ
          </span>
          <span className="flex items-center gap-1 px-2 py-1 rounded bg-slate-100 text-slate-700">
            <Gauge size={12} /> Tự động / Số sàn
          </span>
          <span className="flex items-center gap-1 px-2 py-1 rounded bg-slate-100 text-slate-700">
            <Fuel size={12} /> Xăng / Dầu / Điện / Hybrid
          </span>
        </div>
      </section>

      {featuredVehicles.length > 0 && (
        <section className="page-shell">
          <div className="mb-7 flex flex-wrap items-center justify-between gap-4">
            <div>
              <p className="eyebrow">XE NỔI BẬT</p>
              <h2 className="mt-2 text-2xl font-bold tracking-tight">
                Chọn lọc cho chuyến đi sắp tới
              </h2>
              <p className="mt-1 text-sm text-slate-600">
                {featuredVehicles.length} xe đang khai thác. Chọn thời gian thuê
                để kiểm tra lịch trống.
              </p>
            </div>
            <Link
              to="/vehicles"
              className="btn-secondary text-sm font-semibold hover:border-teal-600 hover:text-teal-800 whitespace-nowrap"
            >
              Xem toàn bộ xe <ChevronRight size={16} className="ml-1" />
            </Link>
          </div>

          <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
            {featuredVehicles.map((vehicle) => (
              <VehicleCard
                key={vehicle.id}
                vehicle={vehicle}
                search=""
                rentalDays={0}
              />
            ))}
          </div>
        </section>
      )}

      <section className="page-shell bg-slate-50 rounded-3xl border border-slate-200">
        <div className="grid gap-8 lg:grid-cols-3">
          {steps.map((step) => (
            <article key={step.number} className="relative flex flex-col">
              <div className="flex items-start gap-4">
                <span className="flex-shrink-0 flex h-12 w-12 items-center justify-center rounded-xl bg-teal-100 text-2xl font-bold text-teal-800">
                  {step.number}
                </span>
                <div>
                  <div className="flex items-center gap-2">
                    <step.icon
                      size={20}
                      className="text-teal-700"
                      aria-hidden="true"
                    />
                    <h3 className="font-semibold text-slate-900">
                      {step.title}
                    </h3>
                  </div>
                  <p className="mt-2 text-sm leading-6 text-slate-600">
                    {step.description}
                  </p>
                </div>
              </div>
              {step.number !== "03" && (
                <div
                  className="absolute right-0 top-6 hidden lg:block w-10 h-0.5 bg-gradient-to-r from-teal-300 to-transparent"
                  aria-hidden="true"
                />
              )}
            </article>
          ))}
        </div>

        <div className="mt-10 rounded-2xl border border-teal-200 bg-teal-50/80 p-5 text-sm text-teal-900">
          <div className="flex items-center gap-2">
            <Sparkles size={18} className="text-teal-700" />
            <span className="font-semibold">Mẹo:</span>
          </div>
          <p className="mt-2 leading-6">
            Chọn thời gian nhận – trả ở bộ lọc tìm kiếm để hệ thống tự lọc xe
            trống và tính giá dự kiến chính xác theo block 24 giờ.
          </p>
        </div>
      </section>

      <section className="page-shell">
        <div className="panel text-center py-10">
          <p className="eyebrow">SẴN SÀNG BẮT ĐẦU?</p>
          <h2 className="mt-3 text-2xl font-bold tracking-tight">
            Đặt xe đầu tiên trong vài phút
          </h2>
          <p className="mt-2 max-w-xl mx-auto text-sm text-slate-600">
            Không cần cài ứng dụng. Chỉ cần chọn xe, điền thời gian và gửi yêu
            cầu. Nhân viên KDrive sẽ liên hệ xác nhận.
          </p>
          <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
            <Link
              to="/vehicles"
              className="btn text-base font-semibold px-8 shadow-md hover:shadow-lg"
            >
              Bắt đầu tìm xe ngay
            </Link>
            <Link
              to="/login"
              className="btn-secondary text-base font-semibold px-8"
            >
              Đăng nhập để quản lý đơn
            </Link>
          </div>
        </div>
      </section>
    </div>
  );
}
