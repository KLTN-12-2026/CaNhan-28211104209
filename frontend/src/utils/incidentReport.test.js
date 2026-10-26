import assert from "node:assert/strict";
import { after, afterEach, before, beforeEach, test } from "node:test";
import { fileURLToPath, URL } from "node:url";
import process from "node:process";
import { act, createElement } from "react";
import { AxiosError } from "axios";
import { JSDOM } from "jsdom";
import { createServer } from "vite";

const frontendRoot = fileURLToPath(new URL("../..", import.meta.url));
const originalGlobals = new Map();
const roots = new Set();
const requests = [];
let dom;
let viteServer;
let apiClient;
let AuthProvider;
let ApiBookingDetailPage;
let MemoryRouter;
let Routes;
let Route;
let createRoot;
let originalAdapter;

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

function response(request, data, status = 200) {
  request.resolve({ config: request.config, data, headers: {}, status, statusText: "OK" });
}

function take(url, method) {
  const request = requests.find(
    (candidate) => !candidate.claimed && candidate.config.url === url &&
      (candidate.config.method || "get").toLowerCase() === method,
  );
  assert.ok(request, `expected ${method.toUpperCase()} ${url}`);
  request.claimed = true;
  return request;
}

function setValue(element, value) {
  const prototype = element instanceof dom.window.HTMLTextAreaElement
    ? dom.window.HTMLTextAreaElement.prototype
    : element instanceof dom.window.HTMLSelectElement
      ? dom.window.HTMLSelectElement.prototype
      : dom.window.HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(prototype, "value").set.call(element, value);
  element.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
  element.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
}

async function mount(area = "user") {
  const container = document.createElement("div");
  document.body.append(container);
  const root = createRoot(container);
  roots.add(root);
  await act(async () => root.render(createElement(
    AuthProvider,
    null,
    createElement(MemoryRouter, { initialEntries: [`/${area}/bookings/c7-report-ui`] },
      createElement(Routes, null, createElement(Route, {
        path: "/:area/bookings/:bookingId",
        element: createElement(ApiBookingDetailPage, { area }),
      })),
    ),
  )));
  return { container, root };
}

before(async () => {
  process.env.VITE_DATA_MODE = "api";
  dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/" });
  for (const [name, value] of Object.entries({
    window: dom.window,
    document: dom.window.document,
    navigator: dom.window.navigator,
    localStorage: dom.window.localStorage,
    sessionStorage: dom.window.sessionStorage,
    HTMLElement: dom.window.HTMLElement,
    MutationObserver: dom.window.MutationObserver,
    Node: dom.window.Node,
    Event: dom.window.Event,
    FormData: dom.window.FormData,
    IS_REACT_ACT_ENVIRONMENT: true,
  })) {
    originalGlobals.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
    Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
  }
  ({ createRoot } = await import("react-dom/client"));
  viteServer = await createServer({
    configFile: `${frontendRoot}/vite.config.js`,
    root: frontendRoot,
    appType: "custom",
    logLevel: "error",
    optimizeDeps: { noDiscovery: true },
    server: { middlewareMode: true },
  });
  ({ AuthProvider } = await viteServer.ssrLoadModule("/src/context/AuthContext.jsx"));
  ({ ApiBookingDetailPage } = await viteServer.ssrLoadModule("/src/pages/api/ApiScreens.jsx"));
  ({ default: apiClient } = await viteServer.ssrLoadModule("/src/api/client.js"));
  ({ MemoryRouter, Routes, Route } = await viteServer.ssrLoadModule("react-router-dom"));
  originalAdapter = apiClient.defaults.adapter;
});

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  requests.length = 0;
  apiClient.defaults.adapter = (config) => {
    if (config.url === "/bookings/c7-report-ui/review") {
      return Promise.resolve({ config, data: null, headers: {}, status: 200, statusText: "OK" });
    }
    const request = { ...deferred(), config, claimed: false };
    requests.push(request);
    return request.promise;
  };
});

afterEach(async () => {
  for (const root of roots) {
    await act(async () => root.unmount());
  }
  roots.clear();
  document.body.replaceChildren();
  apiClient.defaults.adapter = originalAdapter;
});

after(async () => {
  await viteServer?.close();
  for (const [name, descriptor] of originalGlobals) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor);
    else delete globalThis[name];
  }
});

test("mounted incident report locks duplicate submit, maps Vietnam time, and reads the created case back", async () => {
  const { container } = await mount();
  const bookingRequest = take("/bookings/c7-report-ui", "get");
  await act(async () => response(bookingRequest, {
    booking: { id: "c7-report-ui", status: "COMPLETED", version: 0 },
    records: [
      { kind: "HANDOVER", occurred_at: "2026-10-10T02:00:00Z" },
      { kind: "RETURN", occurred_at: "2026-10-10T04:00:00Z" },
    ],
  }));
  const listRequest = take("/incidents?bookingId=c7-report-ui", "get");
  assert.ok(container.querySelector(".animate-pulse"), "incident list remains in loading state while GET is held");
  listRequest.reject(new AxiosError("Request failed with status code 500", "ERR_BAD_RESPONSE", listRequest.config, undefined, {
    config: listRequest.config, data: { message: "Lỗi tải danh sách sự cố." }, headers: {}, status: 500, statusText: "Server Error",
  }));
  await act(async () => {});
  assert.match(container.textContent, /Lỗi tải danh sách sự cố/);
  assert.doesNotMatch(container.textContent, /Chưa có hồ sơ sự cố/);
  const retryButton = [...container.querySelectorAll("button")].find((button) => button.textContent.includes("Thử tải lại"));
  assert.ok(retryButton, "failed list request exposes retry");
  await act(async () => retryButton.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true })));
  const retryListRequest = take("/incidents?bookingId=c7-report-ui", "get");
  assert.ok(container.querySelector(".animate-pulse"), "retry returns list to loading state");
  await act(async () => response(retryListRequest, []));

  const form = [...container.querySelectorAll("form")].find((item) =>
    item.querySelector('[name="occurredAt"]'),
  );
  assert.ok(form, "completed booking should expose report form");
  const occurredAt = form.querySelector('[name="occurredAt"]');
  assert.equal(occurredAt.min, "2026-10-10T09:00");
  assert.equal(occurredAt.max, "2026-10-10T11:00");
  assert.equal(occurredAt.value, "2026-10-10T11:00");
  assert.equal(occurredAt.required, true);
  assert.equal(form.querySelector('[name="description"]').required, true);
  assert.equal(form.checkValidity(), false, "missing report description is invalid in the mounted form");
  assert.equal(form.querySelector('[name="blockVehicle"]'), null, "USER has no vehicle-control checkbox");
  setValue(form.querySelector('[name="description"]'), "Vết xước phát hiện khi trả xe");
  assert.equal(form.checkValidity(), true);

  await act(async () => {
    form.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
  });
  const post = take("/incidents", "post");
  const payload = typeof post.config.data === "string" ? JSON.parse(post.config.data) : post.config.data;
  assert.equal(payload.occurredAt, "2026-10-10T04:00:00.000Z");
  assert.equal(payload.bookingId, "c7-report-ui");
  assert.equal(payload.blockVehicle, false);

  await act(async () => {
    form.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
  });
  assert.equal(requests.filter((item) => item.config.url === "/incidents" && item.config.method === "post").length, 1);
  assert.equal(form.querySelector("button").disabled, true);

  await act(async () => response(post, {
    id: "case-c7-ui-1",
    bookingId: "c7-report-ui",
    kind: "DAMAGE",
    occurredAt: payload.occurredAt,
    reportedAt: "2026-10-11T04:00:00Z",
    description: payload.description,
    status: "OPEN",
    version: 0,
  }, 201));
  const reloadedList = take("/incidents?bookingId=c7-report-ui", "get");
  await act(async () => response(reloadedList, [{
    id: "case-c7-ui-1", kind: "DAMAGE", status: "OPEN",
    occurredAt: "2026-10-10T04:00:00Z", reportedAt: "2026-10-11T04:00:00Z",
    description: "Vết xước phát hiện khi trả xe", version: 0,
  }]));
  assert.match(container.textContent, /Hư hỏng · Đã tiếp nhận/);
  assert.match(container.textContent, /Chưa có kết luận trách nhiệm hoặc khấu trừ/);
  assert.doesNotMatch(container.textContent, /Đã thanh toán|Đã khấu trừ/);

  const historyButton = [...container.querySelectorAll("button")].find((button) => button.textContent.includes("Lịch sử"));
  await act(async () => historyButton.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true })));
  const eventsRequest = take("/incidents/case-c7-ui-1/events", "get");
  assert.match(container.textContent, /Đang tải lịch sử hồ sơ/);
  eventsRequest.reject(new AxiosError("Request failed with status code 500", "ERR_BAD_RESPONSE", eventsRequest.config, undefined, {
    config: eventsRequest.config, data: { message: "Lỗi tải lịch sử." }, headers: {}, status: 500, statusText: "Server Error",
  }));
  await act(async () => {});
  assert.match(container.textContent, /Lỗi tải lịch sử/);
  assert.doesNotMatch(container.textContent, /HANDOVER → OPEN/);
  const historyRetry = [...container.querySelectorAll("button")].find((button) => button.textContent.includes("Thử tải lại lịch sử"));
  assert.ok(historyRetry);
  await act(async () => historyRetry.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true })));
  const retriedEvents = take("/incidents/case-c7-ui-1/events", "get");
  await act(async () => response(retriedEvents, [{
    action: "OPEN", occurred_at: "2026-10-11T04:00:00Z", next_status: "OPEN",
    note: "Tiếp nhận hồ sơ; chưa có kết luận trách nhiệm.",
  }]));
  assert.match(container.textContent, /Tiếp nhận hồ sơ; chưa có kết luận trách nhiệm/);

  await act(async () => historyButton.dispatchEvent(new dom.window.MouseEvent("click", { bubbles: true })));
  const refreshEvents = take("/incidents/case-c7-ui-1/events", "get");
  assert.match(container.textContent, /Đang tải lịch sử hồ sơ/);
  assert.doesNotMatch(container.textContent, /Tiếp nhận hồ sơ; chưa có kết luận trách nhiệm/);
  await act(async () => response(refreshEvents, [{
    action: "OPEN", occurred_at: "2026-10-11T04:00:00Z", next_status: "OPEN",
    note: "Lịch sử mới đọc lại từ API.",
  }]));
  assert.match(container.textContent, /Lịch sử mới đọc lại từ API/);
});

test("mounted incident report displays API authorization errors without a success claim or list reload", async () => {
  const { container } = await mount();
  await act(async () => response(take("/bookings/c7-report-ui", "get"), {
    booking: { id: "c7-report-ui", status: "IN_PROGRESS", version: 0 },
    records: [{ kind: "HANDOVER", occurred_at: "2026-10-10T02:00:00Z" }],
  }));
  await act(async () => response(take("/incidents?bookingId=c7-report-ui", "get"), []));
  const form = [...container.querySelectorAll("form")].find((item) => item.querySelector('[name="occurredAt"]'));
  assert.ok(form);
  setValue(form.querySelector('[name="description"]'), "Báo sự cố thử");
  await act(async () => form.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true })));
  const post = take("/incidents", "post");
  const responseBody = { message: "Không có quyền truy cập hồ sơ này." };
  post.reject(new AxiosError("Request failed with status code 403", "ERR_BAD_REQUEST", post.config, undefined, {
    config: post.config, data: responseBody, headers: {}, status: 403, statusText: "Forbidden",
  }));
  await act(async () => {});
  assert.match(container.textContent, /Không có quyền truy cập hồ sơ này/);
  assert.doesNotMatch(container.textContent, /Đã lập hồ sơ sự cố/);
  assert.equal(requests.filter((item) => item.config.url === "/incidents?bookingId=c7-report-ui" && item.config.method === "get").length, 1);
});

test("mounted incident assessment permits one pending POST and releases its guard after a conflict", async () => {
  const { container } = await mount("staff");
  await act(async () => response(take("/bookings/c7-report-ui", "get"), {
    booking: { id: "c7-report-ui", status: "COMPLETED", version: 0 },
    records: [{ kind: "HANDOVER", occurred_at: "2026-10-10T02:00:00Z" }],
  }));
  await act(async () => response(take("/incidents?bookingId=c7-report-ui", "get"), [{
    id: "assessment-case", kind: "DAMAGE", status: "OPEN", description: "Assessment fixture", version: 0,
  }]));
  const form = [...container.querySelectorAll("form")].find((item) => item.querySelector('[name="assessedDamage"]'));
  assert.ok(form);
  for (const [name, value] of Object.entries({ assessedDamage: "1000000", thirdPartyCoverage: "250000", requestedAmount: "750000", responsibilityBasis: "Verified evidence", note: "Assessment" })) {
    setValue(form.querySelector(`[name="${name}"]`), value);
  }
  await act(async () => form.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true })));
  const pending = take("/incidents/assessment-case/actions", "post");
  await act(async () => form.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true })));
  assert.equal(requests.filter((r) => r.config.url === "/incidents/assessment-case/actions").length, 1);
  assert.equal(form.querySelector("button").disabled, true);
  pending.reject(new AxiosError("Conflict", "ERR_BAD_REQUEST", pending.config, undefined, {
    config: pending.config, data: { message: "Hồ sơ đã thay đổi." }, headers: {}, status: 409, statusText: "Conflict",
  }));
  await act(async () => {});
  assert.match(container.textContent, /Hồ sơ đã thay đổi/);
  assert.doesNotMatch(container.textContent, /Đã cập nhật hồ sơ/);
  assert.equal(form.querySelector("button").disabled, false);
  await act(async () => form.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true })));
  const retry = take("/incidents/assessment-case/actions", "post");
  assert.equal(requests.filter((r) => r.config.url === "/incidents/assessment-case/actions").length, 2);
  await act(async () => response(retry, { status: "ASSESSED", version: 1 }));
  await act(async () => response(take("/incidents?bookingId=c7-report-ui", "get"), [{
    id: "assessment-case", kind: "DAMAGE", status: "ASSESSED", description: "Assessment fixture", version: 1,
  }]));
  assert.match(container.textContent, /Hư hỏng · Đã đánh giá/);
});
