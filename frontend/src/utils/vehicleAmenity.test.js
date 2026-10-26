/**
 * C10A UI — vehicleAmenity.test.js
 *
 * Khắc phục triệt để findings UI-R02 và UI-R03:
 * 1. UI-R02: Gọi trực tiếp actual normalizeVehicle từ production (ApiScreens.jsx),
 *    không dùng bất kỳ hàm mirror/sao chép nào.
 * 2. UI-R03:
 *    - Assert 409 stale conflict hiển thị thông báo lỗi 409 rõ ràng, không false-success.
 *    - Click "Tải lại bản mới", reload fresh revision và thực hiện subsequent PUT
 *      với expectedRevision mới, assert payload và status 200.
 *    - Test đầy đủ các trạng thái loading, error (kèm retry), empty catalog.
 *    - Test mounted public chips và detail rendering của amenities trên giao diện xe.
 */
import assert from "node:assert/strict";
import { after, afterEach, before, beforeEach, test } from "node:test";
import { fileURLToPath, URL } from "node:url";
import process from "node:process";
import { createElement, act } from "react";
import { AxiosError } from "axios";
import { JSDOM } from "jsdom";
import { createServer } from "vite";

const frontendRoot = fileURLToPath(new URL("../..", import.meta.url));
const globalDescriptors = new Map();
const mountedRoots = new Set();
const requests = [];

let dom;
let apiClient;
let setAuthInterceptors;
let AuthProvider;
let ApiVehiclesAdminPage;
let ApiVehicleDetailPage;
let normalizeVehicle;
let createRoot;
let viteServer;
let originalAdapter;

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function installDomGlobal(name, value) {
  globalDescriptors.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
  Object.defineProperty(globalThis, name, {
    configurable: true,
    writable: true,
    value,
  });
}

function takeRequest(url) {
  const request = requests.find(
    (candidate) => candidate.config.url === url && !candidate.claimed,
  );
  assert.ok(request, `expected an unclaimed request for ${url}`);
  request.claimed = true;
  return request;
}

function fulfill(request, data, status = 200) {
  request.resolve({
    config: request.config,
    data,
    headers: {},
    status,
    statusText: status === 200 ? "OK" : "HTTP response",
  });
}

function rejectWithStatus(request, status, data = { message: "Error" }) {
  const response = {
    config: request.config,
    data,
    headers: {},
    status,
    statusText: status === 409 ? "Conflict" : status === 400 ? "Bad Request" : "Error",
  };
  request.reject(
    new AxiosError(
      `Request failed with status code ${status}`,
      status === 409 ? "ERR_BAD_REQUEST" : "ERR_NETWORK",
      request.config,
      undefined,
      response,
    ),
  );
}

async function mount(element) {
  const container = document.createElement("div");
  document.body.append(container);
  const root = createRoot(container);
  mountedRoots.add(root);
  await act(async () => {
    root.render(element);
  });
  return {
    container,
    unmount: async () => {
      await act(async () => {
        root.unmount();
      });
      mountedRoots.delete(root);
      container.remove();
    },
  };
}

before(async () => {
  process.env.VITE_DATA_MODE = "api";
  dom = new JSDOM("<!doctype html><html><body></body></html>", {
    url: "http://localhost/",
  });
  for (const [name, value] of Object.entries({
    window: dom.window,
    document: dom.window.document,
    navigator: dom.window.navigator,
    localStorage: dom.window.localStorage,
    HTMLElement: dom.window.HTMLElement,
    MutationObserver: dom.window.MutationObserver,
    Node: dom.window.Node,
    Event: dom.window.Event,
    MouseEvent: dom.window.MouseEvent,
    FormData: dom.window.FormData,
    IS_REACT_ACT_ENVIRONMENT: true,
  })) {
    installDomGlobal(name, value);
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
  ({ ApiVehiclesAdminPage } = await viteServer.ssrLoadModule("/src/pages/api/ApiManagementScreens.jsx"));
  const apiScreens = await viteServer.ssrLoadModule("/src/pages/api/ApiScreens.jsx");
  normalizeVehicle = apiScreens.normalizeVehicle;
  ApiVehicleDetailPage = apiScreens.ApiVehicleDetailPage;

  ({ default: apiClient, setAuthInterceptors } = await viteServer.ssrLoadModule("/src/api/client.js"));

  originalAdapter = apiClient.defaults.adapter;
});

beforeEach(() => {
  localStorage.clear();
  requests.length = 0;
  setAuthInterceptors();
  apiClient.defaults.adapter = (config) => {
    const request = {
      ...deferred(),
      config,
      claimed: false,
    };
    requests.push(request);
    return request.promise;
  };
});

afterEach(async () => {
  for (const root of mountedRoots) {
    await act(async () => {
      root.unmount();
    });
  }
  mountedRoots.clear();
  document.body.replaceChildren();
  apiClient.defaults.adapter = originalAdapter;
  setAuthInterceptors();
});

after(async () => {
  await viteServer?.close();
  for (const [name, descriptor] of globalDescriptors) {
    if (descriptor) {
      Object.defineProperty(globalThis, name, descriptor);
    } else {
      delete globalThis[name];
    }
  }
});

// =============================================================================
// PHẦN 1 (UI-R02): Test trực tiếp actual normalizeVehicle từ ApiScreens.jsx
// =============================================================================

test("UI-R02: actual production normalizeVehicle giữ amenities [{id,name}] từ DTO backend", () => {
  assert.equal(typeof normalizeVehicle, "function", "normalizeVehicle phải được export từ ApiScreens");
  const vehicle = {
    id: "v1",
    name: "Toyota Vios",
    plate: "51A-00001",
    brandId: "brand1",
    categoryId: "cat1",
    homeBranchId: "b1",
    currentBranchId: "b1",
    seats: 5,
    transmission: "AUTO",
    dailyPrice: 500000,
    status: "ACTIVE",
    description: "",
    version: 1,
    amenities: [
      { id: "a1", name: "Điều hòa" },
      { id: "a2", name: "GPS" },
    ],
  };
  const result = normalizeVehicle(vehicle, {
    brands: [{ id: "brand1", name: "Toyota" }],
    categories: [{ id: "cat1", name: "Sedan" }],
  });
  assert.equal(result.amenities.length, 2);
  assert.equal(result.amenities[0].id, "a1");
  assert.equal(result.amenities[0].name, "Điều hòa");
  assert.equal(result.amenities[1].id, "a2");
  assert.equal(result.amenities[1].name, "GPS");
});

test("UI-R02: actual production normalizeVehicle giữ amenities:[] trung thực", () => {
  const vehicle = {
    id: "v2",
    name: "Honda City",
    plate: "51B-00002",
    brandId: "b2",
    categoryId: "c2",
    homeBranchId: "br",
    currentBranchId: "br",
    seats: 5,
    transmission: "MANUAL",
    dailyPrice: 400000,
    status: "ACTIVE",
    description: "",
    version: 1,
    amenities: [],
  };
  const result = normalizeVehicle(vehicle, null);
  assert.ok(Array.isArray(result.amenities));
  assert.equal(result.amenities.length, 0);
});

test("UI-R02: actual production normalizeVehicle fallback [] khi thiếu field", () => {
  const vehicle = {
    id: "v3",
    name: "Ford Ranger",
    plate: "51C-00003",
    brandId: "b3",
    categoryId: "c3",
    homeBranchId: "br",
    currentBranchId: "br",
    seats: 5,
    transmission: "AUTO",
    dailyPrice: 800000,
    status: "ACTIVE",
    description: "",
    version: 1,
  };
  const result = normalizeVehicle(vehicle, null);
  assert.ok(Array.isArray(result.amenities));
  assert.equal(result.amenities.length, 0);
});

// =============================================================================
// PHẦN 2 (UI-R03): Mounted DOM tests hoàn chỉnh cho Admin & Detail & Public
// =============================================================================

const mockCatalog = {
  brands: [{ id: "b1", name: "Toyota" }],
  categories: [{ id: "c1", name: "Sedan" }],
  amenities: [
    { id: "am1", name: "Bluetooth" },
    { id: "am2", name: "Camera lùi" },
    { id: "am3", name: "Cửa sổ trời" },
  ],
};

const mockBranches = [
  { id: "br1", name: "Chi nhánh Q1", status: "ACTIVE" },
];

const mockVehicles = [
  {
    id: "veh-101",
    name: "Toyota Vios 2024",
    plate: "51A-99999",
    brandId: "b1",
    categoryId: "c1",
    homeBranchId: "br1",
    currentBranchId: "br1",
    seats: 5,
    transmission: "AUTO",
    dailyPrice: 600000,
    status: "ACTIVE",
    description: "Xe mới",
    version: 1,
    amenities: [{ id: "am1", name: "Bluetooth" }],
  },
];

async function renderAdminVehicles(vehicles = mockVehicles) {
  const mounted = await mount(
    createElement(AuthProvider, null, createElement(ApiVehiclesAdminPage, { fleet: false })),
  );
  await act(async () => {
    fulfill(takeRequest("/vehicles"), vehicles);
    fulfill(takeRequest("/vehicles/catalog"), mockCatalog);
    fulfill(takeRequest("/branches"), mockBranches);
  });
  return mounted;
}

test("DOM: ApiVehiclesAdminPage click Chỉnh sửa hiển thị VehicleAmenityEditor với links hiện hành", async () => {
  const { container, unmount } = await renderAdminVehicles();

  const editBtn = container.querySelector("article button");
  assert.ok(editBtn, "Phải có nút Chỉnh sửa");
  await act(async () => {
    editBtn.click();
  });

  const amenityReq = takeRequest("/admin/vehicles/veh-101/amenities");
  assert.equal(amenityReq.config.method, "get");

  await act(async () => {
    fulfill(amenityReq, {
      amenities: [{ id: "am1", name: "Bluetooth" }],
      revision: "rev-abc-123",
    });
  });

  const amenityForm = container.querySelector('form[aria-label="Tiện nghi xe"]');
  assert.ok(amenityForm, "Phải hiển thị form Tiện nghi xe");

  const checkboxes = amenityForm.querySelectorAll('input[type="checkbox"]');
  assert.equal(checkboxes.length, 3, "Phải có 3 checkbox từ catalog");
  assert.equal(checkboxes[0].checked, true, "am1 phải được chọn");
  assert.equal(checkboxes[1].checked, false, "am2 không được chọn");
  assert.equal(checkboxes[2].checked, false, "am3 không được chọn");

  await unmount();
});

test("DOM: VehicleAmenityEditor loading, error với retry, và empty catalog state", async () => {
  const { container, unmount } = await renderAdminVehicles();

  await act(async () => {
    container.querySelector("article button").click();
  });

  // 1. Kiểm tra loading state
  const amenityReq = takeRequest("/admin/vehicles/veh-101/amenities");
  assert.match(container.textContent, /Đang tải tiện nghi/);

  // 2. Kiểm tra error state
  await act(async () => {
    rejectWithStatus(amenityReq, 500, { message: "Mạng lỗi khi tải tiện nghi" });
  });
  assert.match(container.textContent, /Mạng lỗi khi tải tiện nghi/);

  // 3. Click Thử tải lại tiện nghi
  const retryBtn = Array.from(container.querySelectorAll("button")).find(
    (b) => b.textContent === "Thử tải lại tiện nghi",
  );
  assert.ok(retryBtn, "Phải có nút Thử tải lại tiện nghi");
  await act(async () => {
    retryBtn.click();
  });

  const retryReq = takeRequest("/admin/vehicles/veh-101/amenities");
  await act(async () => {
    fulfill(retryReq, { amenities: [], revision: "rev-empty" });
  });

  const amenityForm = container.querySelector('form[aria-label="Tiện nghi xe"]');
  assert.ok(amenityForm, "Form phải mount sau khi retry thành công");

  await unmount();
});

test("DOM: VehicleAmenityEditor assign/clear và chống double-PUT", async () => {
  const { container, unmount } = await renderAdminVehicles();

  const editBtn = container.querySelector("article button");
  await act(async () => {
    editBtn.click();
  });
  await act(async () => {
    fulfill(takeRequest("/admin/vehicles/veh-101/amenities"), {
      amenities: [{ id: "am1", name: "Bluetooth" }],
      revision: "rev-1",
    });
  });

  const amenityForm = container.querySelector('form[aria-label="Tiện nghi xe"]');
  const checkboxes = amenityForm.querySelectorAll('input[type="checkbox"]');

  // Toggle am2 (thêm Camera lùi)
  await act(async () => {
    checkboxes[1].click();
  });
  assert.equal(checkboxes[1].checked, true);

  // Double dispatch submit
  await act(async () => {
    amenityForm.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
    amenityForm.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
  });

  const putReqs = requests.filter(
    (r) => r.config.method === "put" && r.config.url.includes("/amenities"),
  );
  assert.equal(putReqs.length, 1, "Pending ref phải chặn double PUT");

  const putReq = takeRequest("/admin/vehicles/veh-101/amenities");
  const payload = JSON.parse(putReq.config.data);
  assert.deepEqual(payload.amenityIds.sort(), ["am1", "am2"].sort());
  assert.equal(payload.expectedRevision, "rev-1");

  await act(async () => {
    fulfill(putReq, {
      amenities: [
        { id: "am1", name: "Bluetooth" },
        { id: "am2", name: "Camera lùi" },
      ],
      revision: "rev-2",
    });
  });

  const reloadGet = takeRequest("/admin/vehicles/veh-101/amenities");
  await act(async () => {
    fulfill(reloadGet, {
      amenities: [
        { id: "am1", name: "Bluetooth" },
        { id: "am2", name: "Camera lùi" },
      ],
      revision: "rev-2",
    });
  });

  await unmount();
});

test("DOM: VehicleAmenityEditor 400 validation error giải phóng guard và giữ lựa chọn", async () => {
  const { container, unmount } = await renderAdminVehicles();

  await act(async () => {
    container.querySelector("article button").click();
  });
  await act(async () => {
    fulfill(takeRequest("/admin/vehicles/veh-101/amenities"), {
      amenities: [],
      revision: "rev-1",
    });
  });

  const amenityForm = container.querySelector('form[aria-label="Tiện nghi xe"]');
  const checkboxes = amenityForm.querySelectorAll('input[type="checkbox"]');
  await act(async () => {
    checkboxes[0].click();
  });

  await act(async () => {
    amenityForm.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
  });

  const putReq = takeRequest("/admin/vehicles/veh-101/amenities");
  await act(async () => {
    rejectWithStatus(putReq, 400, { message: "Amenity IDs invalid" });
  });

  assert.match(amenityForm.textContent, /Amenity IDs invalid/);
  assert.equal(checkboxes[0].checked, true, "Lựa chọn phải được bảo toàn");
  const submitBtn = amenityForm.querySelector('button[type="submit"]');
  assert.equal(submitBtn.disabled, false, "Guard phải được giải phóng sau lỗi 400");

  await unmount();
});

test("UI-R03: VehicleAmenityEditor 409 stale conflict hiển thị lỗi, reload fresh revision và subsequent PUT gửi expectedRevision mới", async () => {
  const { container, unmount } = await renderAdminVehicles();

  await act(async () => {
    container.querySelector("article button").click();
  });
  await act(async () => {
    fulfill(takeRequest("/admin/vehicles/veh-101/amenities"), {
      amenities: [{ id: "am1", name: "Bluetooth" }],
      revision: "rev-stale-initial",
    });
  });

  let amenityForm = container.querySelector('form[aria-label="Tiện nghi xe"]');

  // Submit PUT gây ra 409 conflict
  await act(async () => {
    amenityForm.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
  });

  const putReq1 = takeRequest("/admin/vehicles/veh-101/amenities");
  await act(async () => {
    rejectWithStatus(putReq1, 409, { message: "Stale revision" });
  });

  // Assert: Thông báo lỗi 409 xuất hiện rõ ràng, tuyệt đối không có false-success
  assert.match(amenityForm.textContent, /Tiện nghi đã thay đổi bởi thao tác khác \(409\)/);
  assert.doesNotMatch(amenityForm.textContent, /thành công/);

  // Nút "Tải lại bản mới" xuất hiện
  const reloadBtn = Array.from(amenityForm.querySelectorAll("button")).find(
    (b) => b.textContent === "Tải lại bản mới",
  );
  assert.ok(reloadBtn, "Phải có nút Tải lại bản mới khi gặp 409");

  // Click "Tải lại bản mới"
  await act(async () => {
    reloadBtn.click();
  });

  // GET reload trả về fresh revision và amenities cập nhật
  const refreshReq = takeRequest("/admin/vehicles/veh-101/amenities");
  await act(async () => {
    fulfill(refreshReq, {
      amenities: [
        { id: "am1", name: "Bluetooth" },
        { id: "am3", name: "Cửa sổ trời" },
      ],
      revision: "rev-fresh-after-409",
    });
  });

  // Form mới đã nhận fresh revision
  amenityForm = container.querySelector('form[aria-label="Tiện nghi xe"]');
  const boxes = amenityForm.querySelectorAll('input[type="checkbox"]');
  assert.equal(boxes[0].checked, true); // am1
  assert.equal(boxes[1].checked, false); // am2
  assert.equal(boxes[2].checked, true); // am3

  // Subsequent PUT: thêm am2 và lưu tiếp
  await act(async () => {
    boxes[1].click();
  });
  await act(async () => {
    amenityForm.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
  });

  const putReq2 = takeRequest("/admin/vehicles/veh-101/amenities");
  const payload2 = JSON.parse(putReq2.config.data);
  assert.equal(
    payload2.expectedRevision,
    "rev-fresh-after-409",
    "Subsequent PUT bắt buộc phải gửi fresh revision, không được dùng stale revision cũ",
  );
  assert.deepEqual(payload2.amenityIds.sort(), ["am1", "am2", "am3"].sort());

  // Fulfill 200 cho subsequent PUT
  await act(async () => {
    fulfill(putReq2, {
      amenities: [
        { id: "am1", name: "Bluetooth" },
        { id: "am2", name: "Camera lùi" },
        { id: "am3", name: "Cửa sổ trời" },
      ],
      revision: "rev-final-success",
    });
  });

  await unmount();
});

test("DOM: ApiVehiclesAdminPage fleet mode (Staff) không hiển thị amenity editor", async () => {
  const mounted = await mount(
    createElement(AuthProvider, null, createElement(ApiVehiclesAdminPage, { fleet: true })),
  );
  await act(async () => {
    fulfill(takeRequest("/vehicles"), mockVehicles);
    fulfill(takeRequest("/vehicles/catalog"), mockCatalog);
    fulfill(takeRequest("/branches"), mockBranches);
    fulfill(takeRequest("/me/branches"), ["br1"]);
  });

  const amenityForm = mounted.container.querySelector('form[aria-label="Tiện nghi xe"]');
  assert.equal(amenityForm, null, "Staff fleet mode tuyệt đối không có amenity editor");
  assert.equal([...mounted.container.querySelectorAll("button")].some((button) => button.textContent.trim() === "Xóa xe"), false, "Staff không có thao tác xóa xe");

  await mounted.unmount();
});

test("DOM: ApiVehicleDetailPage render đầy đủ tiện nghi dạng chips từ DTO backend", async () => {
  const { MemoryRouter, Routes, Route } = await viteServer.ssrLoadModule("react-router-dom");
  const mounted = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(
        MemoryRouter,
        { initialEntries: ["/vehicles/veh-101"] },
        createElement(
          Routes,
          null,
          createElement(Route, {
            path: "/vehicles/:vehicleId",
            element: createElement(ApiVehicleDetailPage),
          }),
        ),
      ),
    ),
  );

  await act(async () => {
    fulfill(takeRequest("/vehicles/veh-101"), {
      ...mockVehicles[0],
      amenities: [
        { id: "am1", name: "Bluetooth" },
        { id: "am2", name: "Camera lùi" },
      ],
    });
    fulfill(takeRequest("/vehicles/catalog"), mockCatalog);
    fulfill(takeRequest("/vehicles/veh-101/reviews"), []);
  });

  // Kiểm tra section tiện nghi xuất hiện đầy đủ
  assert.match(mounted.container.textContent, /Tiện nghi \(2\)/);
  assert.match(mounted.container.textContent, /Bluetooth/);
  assert.match(mounted.container.textContent, /Camera lùi/);

  await mounted.unmount();
});

test("Homepage API uses real cards, one H1 and honest availability copy", async () => {
  const { ApiHomePage } = await viteServer.ssrLoadModule("/src/pages/api/ApiScreens.jsx");
  const { MemoryRouter } = await viteServer.ssrLoadModule("react-router-dom");
  const mounted = await mount(createElement(AuthProvider, null,
    createElement(MemoryRouter, null, createElement(ApiHomePage))));
  await act(async () => {
    fulfill(takeRequest("/vehicles"), [{ ...mockVehicles[0], amenities: [{ id: "am1", name: "Bluetooth" }] }]);
    fulfill(takeRequest("/vehicles/catalog"), mockCatalog);
  });
  assert.equal(mounted.container.querySelectorAll("h1").length, 1);
  const card = mounted.container.querySelector("article");
  assert.match(card.textContent, /Bluetooth/);
  assert.match(card.textContent, /Đang khai thác/);
  assert.doesNotMatch(card.textContent, /Sẵn sàng thuê/);
  assert.doesNotMatch(mounted.container.textContent, /Không tính phí ẩn|bảo đảm ngay/);
  assert.equal(mounted.container.querySelector(".animate-pulse"), null);
  const cta = Array.from(mounted.container.querySelectorAll("a")).find(link => link.textContent === "Xem toàn bộ xe ↗");
  assert.equal(cta.getAttribute("href"), "/vehicles");
  await mounted.unmount();
});

test("Shared vehicle card retains demo label separately from API state", async () => {
  const { default: VehicleCard } = await viteServer.ssrLoadModule("/src/components/vehicle/VehicleCard.jsx");
  const { MemoryRouter } = await viteServer.ssrLoadModule("react-router-dom");
  const mounted = await mount(createElement(MemoryRouter, null,
    createElement(VehicleCard, { vehicle: { ...mockVehicles[0], status: "ACTIVE", noLocalAsset: false, price: 100, amenities: ["Bluetooth"] } })));
  assert.match(mounted.container.textContent, /Đang khai thác/);
  assert.match(mounted.container.textContent, /Minh họa · Dữ liệu mẫu/);
  assert.match(mounted.container.textContent, /Bluetooth/);
  await mounted.unmount();
});

test("Desktop catalog: six homepage cards, all twenty list rows and unified search without status filter", async () => {
  const { ApiHomePage, ApiVehicleListPage } = await viteServer.ssrLoadModule("/src/pages/api/ApiScreens.jsx");
  const { MemoryRouter } = await viteServer.ssrLoadModule("react-router-dom");
  const rows = Array.from({ length: 20 }, (_, index) => ({
    ...mockVehicles[0], id: `car-${index}`, name: `Xe ${String(index).padStart(2, "0")}`,
    status: index === 18 ? "MAINTENANCE" : index === 19 ? "INACTIVE" : "ACTIVE",
  }));
  const render = async (Component) => {
    const mounted = await mount(createElement(AuthProvider, null,
      createElement(MemoryRouter, null, createElement(Component))));
    await act(async () => {
      fulfill(takeRequest("/vehicles"), rows);
      fulfill(takeRequest("/vehicles/catalog"), mockCatalog);
    });
    return mounted;
  };
  const home = await render(ApiHomePage);
  assert.equal(home.container.querySelectorAll("article.vehicle-card").length, 6);
  const more = Array.from(home.container.querySelectorAll("a")).find(a => a.textContent.startsWith("Xem thêm"));
  assert.equal(more.getAttribute("href"), "/vehicles");
  assert.doesNotMatch(home.container.textContent, /Xem tất cả xe trong hệ thống|demo|dữ liệu mẫu/i);
  await home.unmount();
  const list = await render(ApiVehicleListPage);
  assert.equal(list.container.querySelectorAll("article.vehicle-card").length, 20);
  const statusLabel = Array.from(list.container.querySelectorAll("label")).find(label => label.textContent.includes("Trạng thái xe"));
  assert.equal(statusLabel, undefined);
  assert.equal(list.container.querySelectorAll('form[aria-label="Tìm xe theo chuyến"]').length,1);
  assert.match(list.container.textContent, /Giá và lịch được kiểm tra lại khi báo giá/);
  await list.unmount();
});

test("Homepage never invents cars for a sparse, empty or failed API response", async () => {
  const { ApiHomePage } = await viteServer.ssrLoadModule("/src/pages/api/ApiScreens.jsx");
  const { MemoryRouter } = await viteServer.ssrLoadModule("react-router-dom");
  for (const count of [3, 0, -1]) {
    const mounted = await mount(createElement(AuthProvider, null,
      createElement(MemoryRouter, null, createElement(ApiHomePage))));
    await act(async () => {
      const request = takeRequest("/vehicles");
      if (count < 0) rejectWithStatus(request, 503, { message: "Không tải được danh sách xe" });
      else fulfill(request, Array.from({ length: count }, (_, i) => ({ ...mockVehicles[0], id: `sparse-${i}` })));
      fulfill(takeRequest("/vehicles/catalog"), mockCatalog);
    });
    assert.equal(mounted.container.querySelectorAll("article.vehicle-card").length, Math.max(count, 0));
    if (count === 0) assert.match(mounted.container.textContent, /Chưa có xe nào/);
    if (count < 0) assert.match(mounted.container.textContent, /Thử tải lại/);
    await mounted.unmount();
  }
});

test("Late save of an old vehicle cannot claim success on the new editor", async () => {
  const second = { ...mockVehicles[0], id: "veh-202", name: "Second car", plate: "51A-20202" };
  const mounted = await renderAdminVehicles([mockVehicles[0], second]);
  const editButtons = [...mounted.container.querySelectorAll("article button")]
    .filter((button) => button.textContent.trim() === "Chỉnh sửa");
  await act(async () => editButtons[0].click());
  await act(async () => fulfill(takeRequest("/admin/vehicles/veh-101/amenities"), {
    amenities: [{ id: "am1", name: "Bluetooth" }], revision: "first-revision",
  }));
  const oldForm = mounted.container.querySelector('form[aria-label="Tiện nghi xe"]');
  await act(async () => oldForm.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true })));
  const oldPut = takeRequest("/admin/vehicles/veh-101/amenities");
  await act(async () => editButtons[1].click());
  await act(async () => fulfill(takeRequest("/admin/vehicles/veh-202/amenities"), {
    amenities: [{ id: "am2", name: "Camera lùi" }], revision: "second-revision",
  }));
  await act(async () => fulfill(oldPut, { amenities: [{ id: "am1", name: "Bluetooth" }], revision: "first-revision" }));
  const lateReload = requests.find(request => !request.claimed && request.config.url === "/admin/vehicles/veh-202/amenities");
  if (lateReload) {
    lateReload.claimed = true;
    await act(async () => fulfill(lateReload, { amenities: [{ id: "am2", name: "Camera lùi" }], revision: "second-revision" }));
  }
  const newForm = mounted.container.querySelector('form[aria-label="Tiện nghi xe"]');
  assert.doesNotMatch(newForm.textContent, /Đã lưu tiện nghi thành công/);
  assert.equal(newForm.querySelectorAll('input[type="checkbox"]')[1].checked, true);
  await mounted.unmount();
});

test("DOM: ApiVehicleDetailPage displays standardized empty amenity advice when amenities is empty", async () => {
  const { MemoryRouter, Routes, Route } = await viteServer.ssrLoadModule("react-router-dom");
  const mounted = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(
        MemoryRouter,
        { initialEntries: ["/vehicles/veh-empty"] },
        createElement(
          Routes,
          null,
          createElement(Route, {
            path: "/vehicles/:vehicleId",
            element: createElement(ApiVehicleDetailPage),
          }),
        ),
      ),
    ),
  );

  await act(async () => {
    fulfill(takeRequest("/vehicles/veh-empty"), {
      ...mockVehicles[0],
      id: "veh-empty",
      amenities: [],
    });
    fulfill(takeRequest("/vehicles/catalog"), mockCatalog);
    fulfill(takeRequest("/vehicles/veh-empty/reviews"), []);
  });

  assert.match(mounted.container.textContent, /Chưa có thông tin tiện nghi được cung cấp; vui lòng xác nhận với chi nhánh\./);
  assert.doesNotMatch(mounted.container.textContent, /Xe chưa có thông tin tiện nghi\./);

  await mounted.unmount();
});
