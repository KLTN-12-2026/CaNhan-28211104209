import axios from "axios";
import { clearSession, getStoredToken, readStoredSession } from "../utils/storage";
import { shouldClearUnauthorizedSession } from "../utils/requestGeneration";

const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";

const AUTH_SKIP_PATHS = ["/auth/login", "/auth/register"];

let onUnauthorized = null;
let onForbidden = null;
let onServerError = null;
let sessionGuard = null;
let sessionEpoch = 0;

export function setSessionGuard(guard) { sessionGuard = guard; }
export function invalidateSessionRequests() { sessionEpoch += 1; }

function isSessionRequest(config) {
  return ![...AUTH_SKIP_PATHS, "/auth/logout"].includes(config?.url);
}

function isStaleSession(config) {
  if (!isSessionRequest(config)) return false;
  const { token, issue } = readStoredSession();
  if (config?._sessionEpoch !== sessionEpoch || config?._sessionToken !== token || issue) {
    sessionGuard?.(token, issue);
    return true;
  }
  return false;
}

export function setAuthInterceptors({
  unauthorized,
  forbidden,
  serverError,
} = {}) {
  onUnauthorized = unauthorized ?? null;
  onForbidden = forbidden ?? null;
  onServerError = serverError ?? null;
}

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 20000,
  headers: {
    "Content-Type": "application/json",
    Accept: "application/json",
  },
});

apiClient.interceptors.request.use((config) => {
  const isAuthCall = AUTH_SKIP_PATHS.some((path) =>
    config.url?.includes(path),
  );
  if (!isAuthCall) {
    const { token, issue } = readStoredSession();
    if (issue && !config.headers.Authorization) throw new Error(issue);
    if (!config.headers.Authorization && sessionGuard && !sessionGuard(token, issue)) {
      throw new axios.CanceledError("Phiên truy cập đã thay đổi. Vui lòng thử lại.");
    }
    if (token && !config.headers.Authorization) {
      config.headers.Authorization = `Bearer ${token}`;
    }
  }
  config._sessionEpoch = sessionEpoch;
  config._sessionToken = config.headers.Authorization?.replace(/^Bearer\s+/i, "") ?? null;
  return config;
});

apiClient.interceptors.response.use(
  (response) => {
    if (isStaleSession(response.config)) throw new axios.CanceledError("Phản hồi thuộc phiên truy cập cũ.");
    return response;
  },
  (error) => {
    if (error.config && isStaleSession(error.config)) return Promise.reject(error);
    const status = error.response?.status;
    const requestUrl = error.config?.url ?? "";
    const isAuthCall = AUTH_SKIP_PATHS.some((path) =>
      requestUrl.includes(path),
    );
    const authorization =
      error.config?.headers?.Authorization ??
      error.config?.headers?.authorization;
    const requestToken =
      typeof authorization === "string"
        ? authorization.replace(/^Bearer\s+/i, "")
        : null;

    if (requestToken && requestToken !== getStoredToken()) {
      return Promise.reject(error);
    }

    if (
      status === 401 &&
      !isAuthCall &&
      shouldClearUnauthorizedSession(requestToken, getStoredToken())
    ) {
      clearSession();
      onUnauthorized?.();
    } else if (status === 403 && requestUrl !== "/auth/logout") {
      onForbidden?.(error);
    } else if (status >= 500) {
      onServerError?.(error);
    }

    return Promise.reject(error);
  },
);

export default apiClient;
