import apiClient from "./client";
import { getStoredToken } from "../utils/storage";

function normalizeAuthPayload(data) {
  const token = data?.token ?? data?.accessToken ?? data?.jwt ?? null;
  const user = data?.user ?? data?.account ?? null;
  return { token, user, raw: data };
}

export async function loginRequest({ email, password }) {
  const { data } = await apiClient.post("/auth/login", { email, password });
  return normalizeAuthPayload(data);
}

export async function registerRequest(payload) {
  const { data } = await apiClient.post("/auth/register", payload);
  return normalizeAuthPayload(data);
}

export async function fetchCurrentUser(token) {
  const { data } = await apiClient.get("/auth/me", token ? { headers: { Authorization: `Bearer ${token}` } } : undefined);
  return data?.user ?? data;
}

export async function logoutRequest(token = getStoredToken()) {
  try {
    await apiClient.post(
      "/auth/logout",
      null,
      token ? { headers: { Authorization: `Bearer ${token}` } } : undefined,
    );
  } catch (error) {
    // An expired token is already invalid. A network/server failure does not
    // prove that all sessions were revoked; the provider clears the device
    // session and reports that server revocation could not be confirmed.
    if (error.response?.status !== 401) throw error;
  }
}
