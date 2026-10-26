import apiClient from "./client";

const path = (vehicleId) => `/admin/vehicles/${encodeURIComponent(vehicleId)}/images`;
export async function saveVehicleImage(vehicleId, imageId, input) {
  const base = path(vehicleId);
  const { data } = imageId
    ? await apiClient.put(`${base}/${encodeURIComponent(imageId)}`, input)
    : await apiClient.post(base, input);
  return data;
}
export async function reorderVehicleImages(vehicleId, imageIds, expectedRevision) {
  const { data } = await apiClient.put(`${path(vehicleId)}/order`, { imageIds, expectedRevision });
  return data;
}
