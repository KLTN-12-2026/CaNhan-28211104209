import { useState } from "react";
import { CarFront, Users, Gauge, Fuel, Check } from "lucide-react";
import { Link } from "react-router-dom";
import { money } from "../common/PageKit";
import Badge from "../common/Badge";
import { getVehicleMedia } from "../../assets/images/vehicles";

export function VehicleVisual({
  vehicle,
  large = false,
  customSrc = null,
  alt = "",
}) {
  const [failedSrc, setFailedSrc] = useState(null);
  const media = vehicle?.noLocalAsset ? null : getVehicleMedia(vehicle);
  const imageSrc = customSrc || (vehicle?.noLocalAsset ? vehicle?.images?.[0]?.url : media?.main || vehicle?.image);

  if (imageSrc && failedSrc !== imageSrc) {
    return (
      <div
        className={`relative flex items-center justify-center overflow-hidden rounded-xl bg-slate-100 ${
          large ? "h-64 sm:h-96" : "h-52"
        }`}
      >
        <img
          src={imageSrc}
          alt={alt || vehicle?.name || "Ảnh xe"}
          className="h-full w-full object-cover object-center transition-transform duration-500 group-hover:scale-105"
          loading={large ? "eager" : "lazy"}
          onError={() => setFailedSrc(imageSrc)}
        />
        <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-slate-900/50 via-slate-900/10 to-transparent opacity-70" />
        <div className="absolute top-3 left-3 flex gap-1.5">
          <span className="rounded-lg bg-teal-900/80 px-2.5 py-1 text-xs font-semibold text-white backdrop-blur-md">
            {vehicle?.category || "Sedan"}
          </span>
        </div>
        <div className="absolute bottom-2.5 right-3">
          <span className="rounded bg-black/60 px-2 py-0.5 text-[10px] font-mono uppercase tracking-wider text-slate-200 backdrop-blur-sm">
            {vehicle?.noLocalAsset ? "Ảnh minh họa" : vehicle?.plate || "Minh họa"}
          </span>
        </div>
      </div>
    );
  }

  return (
    <div
      className={`relative flex items-center justify-center overflow-hidden rounded-xl bg-gradient-to-br from-teal-50 via-slate-50 to-slate-200 ${
        large ? "h-64 sm:h-96" : "h-52"
      }`}
    >
      <div className="absolute bottom-6 h-3 w-36 rounded-[100%] bg-slate-300/40 blur-md" />
      <CarFront
        aria-hidden="true"
        strokeWidth={1}
        style={{ color: vehicle?.color || "#0f766e" }}
        className={large ? "h-40 w-64" : "h-28 w-44"}
      />
      <span className="absolute bottom-3 right-3 text-[10px] uppercase tracking-widest text-slate-500">
        {vehicle?.noLocalAsset ? "Ảnh chưa cập nhật" : "Minh họa · Dữ liệu mẫu"}
      </span>
    </div>
  );
}

export default function VehicleCard({ vehicle, search = "", rentalDays = 0, compareControl = null }) {
  const isOperating = vehicle.status === "ACTIVE";
  const totalPrice = rentalDays > 0 ? vehicle.price * rentalDays : 0;

  return (
    <article className="vehicle-card group flex flex-col justify-between overflow-hidden rounded-2xl border border-slate-200 bg-white p-3.5 shadow-sm transition-[transform,box-shadow,border-color] duration-200 motion-reduce:transition-none motion-reduce:hover:translate-y-0 hover:-translate-y-0.5 hover:border-teal-300 hover:shadow-md">
      <div>
        <Link to={`/vehicles/${vehicle.id}${search}`} className="block">
          <VehicleVisual vehicle={vehicle} />
        </Link>
        <div className="p-2 pt-4">
          <div className="mb-2.5 flex items-center justify-between gap-2">
            <span className="text-xs font-medium text-slate-500">
              {vehicle.brand}
            </span>
            {isOperating ? (
              <Badge tone="success">Đang khai thác</Badge>
            ) : (
              <Badge tone="warning">
                {vehicle.status === "MAINTENANCE"
                  ? "Đang bảo trì"
                  : "Tạm ngưng"}
              </Badge>
            )}
          </div>
          <h2 className="text-lg font-bold text-slate-900 transition-colors group-hover:text-teal-700">
            <Link to={`/vehicles/${vehicle.id}${search}`}>{vehicle.name}</Link>
          </h2>

          <div className={`my-3.5 grid ${vehicle.fuel && vehicle.fuel !== "Chưa cập nhật" ? "grid-cols-3" : "grid-cols-2"} gap-2 rounded-xl bg-slate-50 p-2 text-xs font-medium text-slate-600`}>
            <div className="flex items-center gap-1.5 justify-center">
              <Users size={14} className="text-teal-700" />
              <span>{vehicle.seats} chỗ</span>
            </div>
            <div className="flex items-center gap-1.5 justify-center border-x border-slate-200 px-1">
              <Gauge size={14} className="text-teal-700" />
              <span className="truncate">{vehicle.transmission}</span>
            </div>
            {vehicle.fuel && vehicle.fuel !== "Chưa cập nhật" ? <div className="flex items-center gap-1.5 justify-center">
              <Fuel size={14} className="text-teal-700" />
              <span>{vehicle.fuel}</span>
            </div> : null}
          </div>

          {vehicle.amenities && vehicle.amenities.length > 0 ? (
            <div className="mb-4 flex flex-wrap gap-1.5">
              {vehicle.amenities.slice(0, 3).map((a) => (
                <span
                  key={typeof a === "object" ? a.id : a}
                  className="inline-flex items-center gap-1 rounded-md bg-teal-50/70 px-2 py-0.5 text-[11px] font-medium text-teal-800"
                >
                  <Check size={11} className="text-teal-600" />
                  {typeof a === "object" ? a.name : a}
                </span>
              ))}
            </div>
          ) : null}
        </div>
      </div>

      {compareControl ? <div className="vehicle-card-actions">{compareControl}</div> : null}
      <div className="flex flex-wrap items-end justify-between gap-3 border-t border-slate-100 p-2 pt-3.5">
        <div>
          <div className="flex items-baseline gap-1">
            <strong className="text-lg font-bold text-teal-800">
              {money(vehicle.price)}
            </strong>
            <span className="text-xs text-slate-500">/ ngày</span>
          </div>
          {vehicle.tripTotal != null ? (
            <p className="text-sm font-semibold text-teal-700">Tổng chuyến: {money(vehicle.tripTotal)} ({vehicle.tripDays} ngày)</p>
          ) : rentalDays > 0 ? (
            <p className="text-[11px] font-medium text-teal-700">
              Ước tính: {money(totalPrice)} ({rentalDays} ngày)
            </p>
          ) : (
            <p className="text-[11px] text-slate-400">
              {vehicle.noLocalAsset ? "Giá niêm yết từ hệ thống" : "Giá mẫu niêm yết"}
            </p>
          )}
        </div>
        <Link
          className="btn text-xs font-semibold"
          to={`/vehicles/${vehicle.id}${search}`}
        >
          Xem chi tiết ↗
        </Link>
      </div>
    </article>
  );
}
