import apiClient from "./client";

export async function listLocationCategories() {
  return (await apiClient.get("/admin/location-categories")).data;
}

export async function createLocationCategory(input) {
  return (await apiClient.post("/admin/location-categories", input)).data;
}

export async function updateLocationCategory(id, input) {
  return (
    await apiClient.put(`/admin/location-categories/${encodeURIComponent(id)}`, input)
  ).data;
}

export async function listLocations() {
  return (await apiClient.get("/admin/locations")).data;
}

export async function createLocation(input) {
  return (await apiClient.post("/admin/locations", input)).data;
}

export async function updateLocation(id, input) {
  return (await apiClient.put(`/admin/locations/${encodeURIComponent(id)}`, input)).data;
}