import apiClient from "./client";

export async function listVehicles() {
  const { data } = await apiClient.get("/vehicles");
  return data;
}

export async function getVehicle(id) {
  const { data } = await apiClient.get(`/vehicles/${encodeURIComponent(id)}`);
  return data;
}

export async function getVehicleCatalog() {
  const { data } = await apiClient.get("/vehicles/catalog");
  return data;
}

export async function listBranches() {
  const { data } = await apiClient.get("/branches");
  return data;
}

export async function listBranchRoutes() {
  const { data } = await apiClient.get("/branch-routes");
  return data;
}

export async function listBookings() {
  const { data } = await apiClient.get("/bookings");
  return data;
}

export async function getBooking(id) {
  const { data } = await apiClient.get(`/bookings/${encodeURIComponent(id)}`);
  return data;
}

export async function quoteBooking(input) {
  const { data } = await apiClient.post("/bookings/quote", input);
  return data;
}

export async function createBooking(input) {
  const { data } = await apiClient.post("/bookings", input);
  return data;
}

export async function applyBookingAction(id, action) {
  const { data } = await apiClient.post(
    `/bookings/${encodeURIComponent(id)}/actions`,
    action,
  );
  return data;
}

export async function getBookingDocument(id, kind) {
  const { data } = await apiClient.get(
    `/bookings/${encodeURIComponent(id)}/${kind}`,
  );
  return data;
}

export async function getProfile() {
  const { data } = await apiClient.get("/auth/me");
  return data;
}

export async function updateProfile(input) {
  const { data } = await apiClient.put("/me/profile", input);
  return data;
}

export async function updatePassword(input) {
  await apiClient.put("/me/password", input);
}

export async function getLicense() {
  const { data } = await apiClient.get("/me/license");
  return data;
}

export async function saveLicense(input) {
  const { data } = await apiClient.put("/me/license", input);
  return data;
}

export async function saveLicensePhotos(form) {
  const { data } = await apiClient.put("/me/license/photos", form, {
    headers: { "Content-Type": undefined },
  });
  return data;
}

export async function reviewLicensePhotos(bookingId, input) {
  const { data } = await apiClient.post(`/bookings/${encodeURIComponent(bookingId)}/license-photo-review`, input);
  return data;
}

export async function getLicensePhoto(bookingId, side, signal) {
  const base = bookingId ? `/bookings/${encodeURIComponent(bookingId)}/license-photos` : "/me/license/photos";
  const { data } = await apiClient.get(`${base}/${side}`, { responseType: "blob", signal });
  return data;
}
