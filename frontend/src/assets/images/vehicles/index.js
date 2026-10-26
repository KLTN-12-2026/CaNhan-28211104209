import toyotaViosMain from "./toyota-vios.jpg";
import toyotaViosInterior from "./toyota-vios-interior.jpg";
import mitsubishiXpanderMain from "./mitsubishi-xpander.jpg";
import mitsubishiXpanderInterior from "./mitsubishi-xpander-interior.jpg";
import hyundaiAccentMain from "./hyundai-accent.jpg";
import hyundaiAccentInterior from "./hyundai-accent-interior.jpg";
import toyotaCorollaCrossMain from "./toyota-corolla-cross.jpg";
import toyotaCorollaCrossInterior from "./toyota-corolla-cross-interior.jpg";
import attributions from "./attributions.json";

export const vehicleAssetMap = {
  v1: {
    main: toyotaViosMain,
    interior: toyotaViosInterior,
    gallery: [
      { url: toyotaViosMain, label: "Ngoại thất góc trước" },
      { url: toyotaViosInterior, label: "Khoang lái & nội thất" },
    ],
    attribution: attributions.find((a) => a.id === "v1"),
  },
  v2: {
    main: mitsubishiXpanderMain,
    interior: mitsubishiXpanderInterior,
    gallery: [
      { url: mitsubishiXpanderMain, label: "Ngoại thất góc trước" },
      { url: mitsubishiXpanderInterior, label: "Khoang lái & nội thất" },
    ],
    attribution: attributions.find((a) => a.id === "v2"),
  },
  v3: {
    main: hyundaiAccentMain,
    interior: hyundaiAccentInterior,
    gallery: [
      { url: hyundaiAccentMain, label: "Ngoại thất góc trước" },
      { url: hyundaiAccentInterior, label: "Khoang lái & nội thất" },
    ],
    attribution: attributions.find((a) => a.id === "v3"),
  },
  v4: {
    main: toyotaCorollaCrossMain,
    interior: toyotaCorollaCrossInterior,
    gallery: [
      { url: toyotaCorollaCrossMain, label: "Ngoại thất góc trước" },
      { url: toyotaCorollaCrossInterior, label: "Khoang lái & nội thất" },
    ],
    attribution: attributions.find((a) => a.id === "v4"),
  },
};

export function getVehicleMedia(vehicle) {
  if (!vehicle) return null;
  const match =
    vehicleAssetMap[vehicle.id] || vehicleAssetMap[vehicle.imageKey];
  if (match) return match;

  if (vehicle.image) {
    return {
      main: vehicle.image,
      interior: vehicle.gallery?.[1]?.url || vehicle.gallery?.[1] || vehicle.image,
      gallery: Array.isArray(vehicle.gallery)
        ? vehicle.gallery.map((g) => (typeof g === "string" ? { url: g, label: "Ảnh xe" } : g))
        : [{ url: vehicle.image, label: "Ảnh đại diện" }],
      attribution: vehicle.attribution || null,
    };
  }
  return null;
}

export {
  toyotaViosMain,
  toyotaViosInterior,
  mitsubishiXpanderMain,
  mitsubishiXpanderInterior,
  hyundaiAccentMain,
  hyundaiAccentInterior,
  toyotaCorollaCrossMain,
  toyotaCorollaCrossInterior,
  attributions,
};
