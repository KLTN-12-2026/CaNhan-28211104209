import { useState } from "react";
import { VehicleVisual } from "./VehicleCard";

// Keying by vehicle prevents a previous vehicle's selection/error leaking into this gallery.
export default function VehicleGallery({ vehicle }) {
  return <Gallery key={vehicle.id} vehicle={vehicle} />;
}

function formatAttribution(text) {
  if (!text) return "Chưa cung cấp thông tin nguồn ảnh";
  const urlRegex = /(https?:\/\/[^\s]+)/g;
  const parts = text.split(urlRegex);
  return parts.map((part, index) => {
    if (urlRegex.test(part)) {
      return (
        <a
          key={index}
          href={part}
          target="_blank"
          rel="noopener noreferrer"
          className="text-teal-700 underline hover:text-teal-900 break-all"
        >
          {part}
        </a>
      );
    }
    return part;
  });
}

function Gallery({ vehicle }) {
  const images = Array.isArray(vehicle.images) ? vehicle.images : [];
  const [selectedId, setSelectedId] = useState(null);
  const selected = images.find((image) => image.id === selectedId) || images[0];
  return (
    <section className="vehicle-gallery" aria-label={`Thư viện ảnh ${vehicle.name}`}>
      <VehicleVisual vehicle={vehicle} large customSrc={selected?.url} />
      {selected ? (
        <div className="image-credit">
          <p className="font-medium text-slate-700">Ảnh minh họa</p>
          <details className="mt-1 text-xs text-slate-600">
            <summary className="cursor-pointer text-teal-700 hover:text-teal-800 focus:outline-none focus:underline">
              Nguồn ảnh và giấy phép
            </summary>
            <div className="mt-1 break-words">
              {formatAttribution(selected.attribution)}
            </div>
          </details>
        </div>
      ) : null}
      {images.length > 1 ? (
        <div className="gallery-thumbnails" aria-label="Chọn ảnh xe">
          {images.map((image, index) => (
            <button
              key={image.id}
              type="button"
              aria-label={`Xem ảnh ${index + 1}`}
              aria-pressed={selected?.id === image.id}
              onClick={() => setSelectedId(image.id)}
            >
              <img src={image.url} alt={`Ảnh ${index + 1} của ${vehicle.name}`} loading="lazy" />
            </button>
          ))}
        </div>
      ) : null}
    </section>
  );
}
