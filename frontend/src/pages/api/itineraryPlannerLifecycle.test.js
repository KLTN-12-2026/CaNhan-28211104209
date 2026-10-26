import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { fileURLToPath, URL } from "node:url";
import process from "node:process";
import { act, createElement } from "react";
import { AxiosError } from "axios";
import { JSDOM } from "jsdom";
import { createServer } from "vite";

const frontendRoot = fileURLToPath(new URL("../../..", import.meta.url));
const globals = new Map();
const roots = new Set();
const requests = [];
let dom;
let viteServer;
let apiClient;
let ApiItineraryPlannerPage;
let createRoot;
let MemoryRouter;
let AuthContext;
let AppRoutes;

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

function take(url, method = "get") {
  const request = requests.find(
    (item) => !item.claimed && item.config.url === url && (item.config.method || "get").toLowerCase() === method
  );
  assert.ok(
    request,
    `expected ${method.toUpperCase()} ${url}; seen: ${requests
      .map((item) => `${item.config.method} ${item.config.url}`)
      .join(", ")}`
  );
  request.claimed = true;
  return request;
}

function resolve(request, data, status = 200) {
  request.resolve({ config: request.config, data, headers: {}, status, statusText: "OK" });
}

function resetRequests() {
  requests.length = 0;
  apiClient.defaults.adapter = (config) => {
    const request = { ...deferred(), config, claimed: false };
    requests.push(request);
    return request.promise;
  };
}

async function unmountAll() {
  for (const root of roots) await act(async () => root.unmount());
  roots.clear();
  document.body.replaceChildren();
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
    IS_REACT_ACT_ENVIRONMENT: true,
  })) {
    globals.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
    Object.defineProperty(globalThis, name, { configurable: true, value, writable: true });
  }

  ({ createRoot } = await import("react-dom/client"));
  const rrd = await import("react-router-dom");
  MemoryRouter = rrd.MemoryRouter;

  viteServer = await createServer({
    configFile: `${frontendRoot}/vite.config.js`,
    root: frontendRoot,
    appType: "custom",
    logLevel: "error",
    optimizeDeps: { noDiscovery: true },
    server: { middlewareMode: true },
  });

  const clientModule = await viteServer.ssrLoadModule("/src/api/client.js");
  apiClient = clientModule.default;

  const pageModule = await viteServer.ssrLoadModule("/src/pages/api/ApiItineraryPlannerPage.jsx");
  ApiItineraryPlannerPage = pageModule.default;

  const authModule = await viteServer.ssrLoadModule("/src/context/AuthContext.jsx");
  AuthContext = authModule.default || authModule.AuthContext;
  ({ default: AppRoutes } = await viteServer.ssrLoadModule("/src/routes/AppRoutes.jsx"));
});

for (const role of [null, "STAFF", "ADMIN", "USER"]) {
  test(`actual itinerary route admits only USER (${role || "guest"})`, async () => {
    resetRequests();
    const container = document.createElement("div");document.body.append(container);
    const root = createRoot(container);roots.add(root);
    const user = role ? { id: "route-user", role, fullName: "Fixture" } : null;
    await act(async () => root.render(createElement(AuthContext.Provider,
      { value: { user, isAuthenticated: Boolean(user), loading: false } },
      createElement(MemoryRouter, { initialEntries: ["/ai/itinerary"] }, createElement(AppRoutes)))));
    if (!role) assert.ok(container.querySelector('input[type="email"]'), "guest redirected to login");
    else if (role !== "USER") assert.ok(container.textContent.includes("Không có quyền truy cập"));
    else assert.ok(container.textContent.includes("Lập kế hoạch hành trình 1 ngày"));
    assert.equal(requests.filter(r => r.config.url === "/ai/locations").length, role === "USER" ? 1 : 0);
    await unmountAll();
  });
}

after(async () => {
  await unmountAll();
  if (viteServer) await viteServer.close();
  for (const [name, descriptor] of globals.entries()) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor);
    else delete globalThis[name];
  }
});

for (const stage of ["preview", "save"]) {
  for (const action of ["switch", "logout", "unmount"]) {
    test(`planner ${stage}: ${action} aborts request and ignores its late result`, async () => {
      resetRequests();
      const container = document.createElement("div");
      document.body.append(container);
      const root = createRoot(container);
      roots.add(root);
      const render = (user) => root.render(createElement(AuthContext.Provider, { value: { user } },
        createElement(MemoryRouter, null, createElement(ApiItineraryPlannerPage))));
      await act(async () => render({ id: "u1", role: "USER" }));
      await act(async () => resolve(take("/ai/locations"), [
        { id: "loc-1", name: "Test A", categoryId: "c1", categoryName: "Test", metadata: { durationMinutes: 30 } },
        { id: "loc-2", name: "Test B", categoryId: "c1", categoryName: "Test", metadata: { durationMinutes: 30 } },
      ]));
      await act(async () => container.querySelector("form").dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true })));
      let pending = take("/ai/itineraries/preview", "post");
      const preview = {
        status: "VALIDATED", fingerprint: "fp", orderedLocationIds: ["loc-1", "loc-2"],
        criteria: { tripDate: "2026-10-25", startTime: "09:00", endTime: "17:00", startLocationId: "loc-1" },
        items: [], totals: { totalDwellMinutes: 60, totalTravelMinutes: 5, totalDistanceMeters: 1000 },
      };
      if (stage === "save") {
        await act(async () => resolve(pending, preview));
        await act(async () => container.querySelectorAll("form")[1].dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true })));
        pending = take("/itineraries", "post");
      }
      await act(async () => {
        if (action === "unmount") { root.unmount(); roots.delete(root); }
        else render(action === "logout" ? null : { id: "u2", role: "USER" });
      });
      assert.equal(pending.config.signal.aborted, true, `${stage} canceled on ${action}`);
      await act(async () => resolve(pending, stage === "preview" ? preview : { id: "previous-user-plan" }));
      assert.ok(!container.textContent.includes("HÀNH TRÌNH KHẢ THI"));
      assert.equal(container.querySelector('a[href*="previous-user-plan"]'), null);
      assert.ok(container.querySelectorAll("form").length <= 1, "no stale save form");
      await unmountAll();
    });
  }
}

test("ApiItineraryPlannerPage: criteria change aborts pending preview and rejects late responses", async () => {
  resetRequests();
  const container = document.createElement("div");
  document.body.append(container);
  const root = createRoot(container);
  roots.add(root);

  // Mount
  await act(async () => {
    root.render(
      createElement(
        AuthContext.Provider,
        { value: { user: { id: "u1", role: "USER", fullName: "Khách test" }, token: "mock-token" } },
        createElement(
          MemoryRouter,
          null,
          createElement(ApiItineraryPlannerPage)
        )
      )
    );
  });

  // 1. Initial listActiveLocations
  const locReq = take("/ai/locations");
  await act(async () => {
    resolve(locReq, [
      {
        id: "loc-1",
        name: "Bảo tàng Chăm",
        categoryId: "cat-1",
        categoryName: "Văn hóa",
        metadata: { durationMinutes: 60 },
      },
      {
        id: "loc-2",
        name: "Ngũ Hành Sơn",
        categoryId: "cat-2",
        categoryName: "Thắng cảnh",
        metadata: { durationMinutes: 90 },
      },
    ]);
  });

  // Verify initial date is rendered
  const dateInput = container.querySelector("input[type='date']");
  assert.ok(dateInput, "Phải có trường ngày");
  assert.match(dateInput.value, /^\d{4}-\d{2}-\d{2}$/, "Ngày khởi tạo phải theo format YYYY-MM-DD");

  // 2. Bấm submit Xem trước kế hoạch (Preview)
  const submitBtn = container.querySelector("button[type='submit']");
  assert.ok(submitBtn, "Phải có nút Xem trước");

  await act(async () => {
    submitBtn.click();
  });

  const previewReq1 = take("/ai/itineraries/preview", "post");
  assert.ok(previewReq1, "Phải gửi request preview");

  // 3. Đổi input giờ bắt đầu trong khi request preview đang bay (Pending)
  const timeInput = container.querySelectorAll("select")[1];
  assert.ok(timeInput, "Phải có trường giờ");

  await act(async () => {
    timeInput.value = "cat-2";
    timeInput.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
  });
  assert.equal(timeInput.value, "cat-2", "criteria actually changed");
  assert.equal(previewReq1.config.signal.aborted, true, "old preview aborted");

  // 4. Phản hồi trễ từ previewReq1 trả về sau khi criteria đã đổi
  await act(async () => {
    resolve(previewReq1, {
      status: "VALIDATED",
      fingerprint: "stale-fingerprint-old",
      criteria: { tripDate: dateInput.value, startTime: "09:00", endTime: "17:00", startLocationId: "loc-1" },
      items: [
        { sortOrder: 1, locationId: "loc-1", locationName: "Bảo tàng Chăm", arrival: "09:00", departure: "10:00", dwellMinutes: 60 },
      ],
      totals: { totalDwellMinutes: 60, totalWaitMinutes: 0, totalTravelMinutes: 0, totalDistanceMeters: 0, locationCount: 1 },
      orderedLocationIds: ["loc-1"],
    });
  });

  // 5. Kết quả preview cũ KHÔNG ĐƯỢC PHÉP hiển thị hoặc cho phép Lưu
  assert.equal(container.querySelectorAll("form").length, 1, "old preview cannot restore save form");
  assert.ok(!container.textContent.includes("HÀNH TRÌNH KHẢ THI"));

  await unmountAll();
});

test("ApiItineraryPlannerPage: unmount cleans up preview abort controller without error", async () => {
  resetRequests();
  const container = document.createElement("div");
  document.body.append(container);
  const root = createRoot(container);
  roots.add(root);

  await act(async () => {
    root.render(
      createElement(
        AuthContext.Provider,
        { value: { user: { id: "u1", role: "USER", fullName: "Khách test" }, token: "mock-token" } },
        createElement(
          MemoryRouter,
          null,
          createElement(ApiItineraryPlannerPage)
        )
      )
    );
  });

  const locReq = take("/ai/locations");
  let aborted = false;
  locReq.config.signal?.addEventListener?.("abort", () => {
    aborted = true;
  });

  // Unmount ngay khi đang tải
  await act(async () => {
    root.unmount();
  });
  roots.delete(root);
  assert.strictEqual(aborted, true, "pending locations request must be aborted on unmount");

  // Không ném exception và controller đã được cleanup
  assert.ok(true, "Unmount an toàn không rò rỉ");
  document.body.replaceChildren();
});

test("ApiItineraryPlannerPage: network interruption or timeout on save displays verification advice", async () => {
  resetRequests();
  const container = document.createElement("div");
  document.body.append(container);
  const root = createRoot(container);
  roots.add(root);

  await act(async () => {
    root.render(
      createElement(
        AuthContext.Provider,
        { value: { user: { id: "u1", role: "USER", fullName: "Khách test" }, token: "mock-token" } },
        createElement(
          MemoryRouter,
          null,
          createElement(ApiItineraryPlannerPage)
        )
      )
    );
  });

  const locReq = take("/ai/locations");
  await act(async () => {
    resolve(locReq, [
      { id: "loc-1", name: "Bảo tàng Chăm", categoryId: "cat-1", categoryName: "Văn hóa", metadata: { durationMinutes: 60 } },
      { id: "loc-2", name: "Ngũ Hành Sơn", categoryId: "cat-2", categoryName: "Thắng cảnh", metadata: { durationMinutes: 90 } },
    ]);
  });

  const submitBtn = container.querySelector("button[type='submit']");
  await act(async () => {
    submitBtn.click();
  });

  const previewReq = take("/ai/itineraries/preview", "post");
  await act(async () => {
    resolve(previewReq, {
      status: "VALIDATED",
      fingerprint: "valid-fp-save-test",
      criteria: { tripDate: "2026-10-25", startTime: "08:00", endTime: "17:00", startLocationId: "loc-1" },
      items: [
        { sortOrder: 1, locationId: "loc-1", locationName: "Bảo tàng Chăm", arrival: "08:00", departure: "09:00", dwellMinutes: 60 },
        { sortOrder: 2, locationId: "loc-2", locationName: "Ngũ Hành Sơn", arrival: "09:30", departure: "11:00", dwellMinutes: 90 },
      ],
      totals: { totalDwellMinutes: 150, totalWaitMinutes: 0, totalTravelMinutes: 30, totalDistanceMeters: 15000, locationCount: 2 },
      orderedLocationIds: ["loc-1", "loc-2"],
    });
  });

  // Nút Lưu phải xuất hiện
  const forms = container.querySelectorAll("form");
  assert.equal(forms.length, 2, "Form lưu phải xuất hiện sau khi preview thành công");
  const saveBtn = forms[1].querySelector("button[type='submit']");
  assert.ok(saveBtn, "Nút Lưu phải hiển thị");

  await act(async () => {
    saveBtn.click();
  });

  const saveReq = take("/itineraries", "post");
  assert.ok(saveReq, "Phải gửi request POST /itineraries");
  assert.equal(container.querySelector("fieldset").disabled, true, "criteria locked while saving");
  const previewCount = requests.filter(r => r.config.url === "/ai/itineraries/preview").length;
  await act(async () => submitBtn.click());
  assert.equal(requests.filter(r => r.config.url === "/ai/itineraries/preview").length, previewCount);
  await act(async () => saveBtn.click());
  assert.equal(requests.filter(r => r.config.url === "/itineraries").length, 1, "no duplicate POST");

  // Giả lập lỗi timeout mạng (ECONNABORTED)
  await act(async () => {
    saveReq.reject(new AxiosError("timeout of 10000ms exceeded", "ECONNABORTED", saveReq.config, undefined, undefined));
  });

  // Kiểm tra UI hiển thị thông điệp an toàn
  assert.ok(
    container.textContent.includes("kiểm tra danh sách 'Hành trình đã lưu'"),
    "Phải hướng dẫn người dùng kiểm tra danh sách đã lưu khi gặp timeout"
  );
  assert.equal(container.querySelector("fieldset").disabled, false, "save failure releases criteria");
  assert.equal(saveBtn.disabled, false, "no stranded saving flag");

  await unmountAll();
});
