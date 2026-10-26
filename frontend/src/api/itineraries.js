import apiClient from "./client";

export async function listActiveLocations(signal) {
  const { data } = await apiClient.get("/ai/locations", { signal });
  return data;
}

export async function previewItinerary(payload, signal) {
  const { data } = await apiClient.post("/ai/itineraries/preview", payload, { signal, timeout: 65000 });
  return data;
}

export async function saveItinerary(payload, signal) {
  const { data } = await apiClient.post("/itineraries", payload, { signal });
  return data;
}

export async function listSavedItineraries(signal) {
  const { data } = await apiClient.get("/itineraries", { signal });
  return data;
}

export async function getSavedItinerary(id, signal) {
  const { data } = await apiClient.get(`/itineraries/${encodeURIComponent(id)}`, { signal });
  return data;
}
