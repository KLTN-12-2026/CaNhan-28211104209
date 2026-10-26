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
let ApiVehiclesAdminPage;
let createRoot;

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
  const request = requests.find((item) => !item.claimed && item.config.url === url &&
    (item.config.method || "get").toLowerCase() === method);
  assert.ok(request, `expected ${method.toUpperCase()} ${url}; seen: ${requests.map((item) => `${item.config.method} ${item.config.url}`).join(", ")}; dom: ${document.body.textContent}`);
  request.claimed = true;
  return request;
}

function resolve(request, data, status = 200) {
  request.resolve({ config: request.config, data, headers: {}, status, statusText: "OK" });
}

function reject(request, status, data = { message: "Máy chủ từ chối yêu cầu." }) {
  request.reject(new AxiosError(
    `Request failed with status code ${status}`,
    "ERR_BAD_REQUEST",
    request.config,
    undefined,
    { config: request.config, data, headers: {}, status, statusText: "Conflict" },
  ));
}

async function mount(props = {}) {
  const container = document.createElement("div");
  document.body.append(container);
  const root = createRoot(container);
  roots.add(root);
  await act(async () => root.render(createElement(ApiVehiclesAdminPage, props)));
  return container;
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
  ({ ApiVehiclesAdminPage } = await viteServer.ssrLoadModule("/src/pages/api/ApiManagementScreens.jsx"));
  ({ default: apiClient } = await viteServer.ssrLoadModule("/src/api/client.js"));
});

const vehicle = { id: "new", name: "Xe mới", plate: "TEST-01", seats: 5, transmission: "AUTO", currentBranchId: "long-branch-id", homeBranchId: "long-branch-id", dailyPrice: 500000, status: "ACTIVE", version: 4 };
async function loaded(customVehicles = [vehicle], customBranches = [{ id: "long-branch-id", name: "Chi nhánh thử nghiệm", status: "ACTIVE" }], props = {}) {
  resetRequests();
  const container = await mount(props);
  await act(async () => {
    resolve(take("/vehicles"), customVehicles);
    resolve(take("/vehicles/catalog"), { brands: [{ id: "brand", name: "Hãng thử" }], categories: [{ id: "category", name: "Loại thử" }], amenities: [{ id: "am1", name: "Bluetooth" }] });
    resolve(take("/branches"), customBranches);
    if (props.fleet) {
      resolve(take("/me/branches"), ["long-branch-id"]);
    }
  });
  return container;
}
function button(container, name) { return [...container.querySelectorAll("button")].find((item) => item.textContent.trim() === name); }
function setInputValue(input, value) {
  const prototype = Object.getPrototypeOf(input);
  const descriptor = Object.getOwnPropertyDescriptor(prototype, "value");
  if (descriptor?.set) {
    descriptor.set.call(input, value);
  } else {
    input.value = value;
  }
  input.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
  input.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
}
function setSelectValue(select, value) {
  select.value = value;
  select.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
}

test("vehicle delete cancel sends no request; actions share a separate bottom row", async () => {
  const container = await loaded();
  const article = container.querySelector("article");
  const actions = article.lastElementChild;
  assert.equal(button(container, "Xóa xe").parentElement, actions);
  assert.equal(button(container, "Chỉnh sửa").parentElement, actions);
  assert.match(article.textContent, /Chi nhánh thử nghiệm/);
  await act(async () => button(container, "Xóa xe").click());
  assert.match(container.querySelector('[role="dialog"]').textContent, /Xe mới \(TEST-01\)/);
  await act(async () => button(container, "Hủy").click());
  assert.equal(requests.filter((item) => item.config.method === "delete").length, 0);
  await unmountAll();
});

test("confirmed delete uses current version, blocks controls while pending and reloads after success", async () => {
  const container = await loaded();
  await act(async () => { button(container, "Xóa xe").click(); button(container, "Xóa xe").click(); });
  assert.equal(requests.filter(item => item.config.method === "delete").length, 0);
  await act(async () => { button(container, "Xác nhận").click(); });
  assert.equal(requests.filter((item) => item.config.method === "delete").length, 1);
  const request = take("/admin/vehicles/new", "delete");
  assert.deepEqual(request.config.params, { version: 4 });
  assert.equal(button(container, "Xóa xe").disabled, true);
  assert.equal(button(container, "Chỉnh sửa").disabled, true);
  assert.doesNotMatch(container.textContent, /Đã xóa xe/);
  await act(async () => resolve(request, null, 204));
  const reload = take("/vehicles");
  await act(async () => resolve(reload, []));
  assert.match(container.textContent, /Đã xóa xe Xe mới/);
  assert.equal(container.querySelectorAll("article").length, 0);
  await unmountAll();
});

test("history or stale conflict keeps vehicle and displays server message without success", async () => {
  const container = await loaded();
  await act(async () => button(container, "Xóa xe").click());
  await act(async () => button(container, "Xác nhận").click());
  await act(async () => reject(take("/admin/vehicles/new", "delete"), 409, { message: "Xe đã có đơn thuê. Hãy dùng Ngừng khai thác." }));
  assert.match(container.querySelector('[role="alert"]').textContent, /Ngừng khai thác/);
  assert.equal(container.querySelectorAll("article").length, 1);
  assert.doesNotMatch(container.textContent, /Đã xóa xe/);
  assert.equal(button(container, "Xóa xe").disabled, false);
  await unmountAll();
});

test("P0-2: create201 transitions to edit mode, requests image and amenity without list search, tabs keep draft", async () => {
  const container = await loaded([]);
  assert.match(container.textContent, /Thêm xe/);
  assert.equal(container.querySelector('[role="tablist"]'), null);

  const inputs = container.querySelectorAll("form input");
  const nameInput = inputs[0];
  const plateInput = inputs[1];
  const seatsInput = inputs[2];
  const priceInput = inputs[3];

  await act(async () => {
    setInputValue(nameInput, "Xe Vừa Tạo");
    setInputValue(plateInput, "51F-99999");
    setInputValue(seatsInput, "7");
    setInputValue(priceInput, "750000");
  });

  await act(async () => { const selects = container.querySelectorAll("form select"); setSelectValue(selects[0], "brand"); setSelectValue(selects[1], "category"); });
  const createBtn = button(container, "Tạo xe");
  assert.ok(createBtn);
  await act(async () => createBtn.click());

  const postReq = take("/admin/vehicles", "post");
  const sentData = typeof postReq.config.data === "string" ? JSON.parse(postReq.config.data) : postReq.config.data;
  assert.equal(sentData.name, "Xe Vừa Tạo");
  assert.equal(sentData.plate, "51F-99999");
  assert.equal(sentData.seats, 7);
  assert.equal(sentData.dailyPrice, 750000);

  const createdVehicle = {
    id: "veh-created-99",
    name: "Xe Vừa Tạo",
    plate: "51F-99999",
    seats: 7,
    transmission: "AUTO",
    currentBranchId: "long-branch-id",
    homeBranchId: "long-branch-id",
    dailyPrice: 750000,
    status: "ACTIVE",
    version: 1,
  };

  await act(async () => {
    resolve(postReq, createdVehicle, 201);
  });
  const reloadReq = take("/vehicles");
  await act(async () => {
    resolve(reloadReq, [createdVehicle]);
  });

  assert.match(container.textContent, /Đã tạo xe Xe Vừa Tạo/);
  const tablist = container.querySelector('[role="tablist"]');
  assert.ok(tablist, "Phải hiển thị tablist sau khi tạo xe thành công");

  const imgReq = take("/admin/vehicles/veh-created-99/images");
  assert.ok(imgReq);
  await act(async () => resolve(imgReq, { images: [], revision: "rev-img-1" }));

  const amReq = take("/admin/vehicles/veh-created-99/amenities");
  assert.ok(amReq);
  await act(async () => resolve(amReq, { amenities: [], revision: "rev-am-1" }));

  const tabButtons = [...tablist.querySelectorAll("button")];
  const infoTab = tabButtons.find(b => b.textContent.includes("Thông tin xe"));
  const imagesTab = tabButtons.find(b => b.textContent.includes("Hình ảnh"));
  const amenitiesTab = tabButtons.find(b => b.textContent.includes("Tiện nghi"));
  assert.ok(infoTab && imagesTab && amenitiesTab);

  await act(async () => infoTab.click());
  assert.match(container.textContent, /Cập nhật xe: Xe Vừa Tạo \(51F-99999\)/);
  const descTextarea = container.querySelector("form textarea");
  await act(async () => {
    setInputValue(descTextarea, "Draft mô tả xe mới");
  });

  await act(async () => amenitiesTab.click());
  await act(async () => infoTab.click());
  assert.equal(descTextarea.value, "Draft mô tả xe mới");

  const cancelBtn = button(container, "Hủy");
  assert.ok(cancelBtn);
  await act(async () => cancelBtn.click());
  assert.match(container.textContent, /Thêm xe/);
  assert.equal(container.querySelector('[role="tablist"]'), null);

  await unmountAll();
});

test("P0-2: create400/409/500 does not fake success and does not open image/amenity editor", async () => {
  const container = await loaded([]);
  const inputs = container.querySelectorAll("form input");
  await act(async () => {
    setInputValue(inputs[0], "Xe Lỗi");
    setInputValue(inputs[1], "51F-00000");
    setInputValue(inputs[2], "5");
    setInputValue(inputs[3], "500000");
  });

  await act(async () => { const selects = container.querySelectorAll("form select"); setSelectValue(selects[0], "brand"); setSelectValue(selects[1], "category"); });
  await act(async () => button(container, "Tạo xe").click());
  const postReq = take("/admin/vehicles", "post");
  await act(async () => reject(postReq, 400, { message: "Biển số xe đã được đăng ký" }));

  assert.match(container.querySelector('[role="alert"]').textContent, /Biển số xe đã được đăng ký/);
  assert.doesNotMatch(container.textContent, /Đã tạo xe/);
  assert.equal(container.querySelector('[role="tablist"]'), null);
  assert.equal(requests.filter(r => r.config.url.includes("images") || r.config.url.includes("amenities")).length, 0);

  await unmountAll();
});

test("P0-3: Admin filter and pagination: query, inactive branch, status, and reset/clamp", async () => {
  const manyVehicles = Array.from({ length: 45 }, (_, i) => ({
    id: `veh-${String(i + 1).padStart(2, "0")}`,
    name: `Car ${i + 1}`,
    plate: `51A-${String(10000 + i)}`,
    seats: 5,
    transmission: "AUTO",
    currentBranchId: i < 25 ? "branch-active" : "branch-inactive",
    homeBranchId: "branch-active",
    dailyPrice: (i + 1) * 100000,
    status: i < 25 ? "ACTIVE" : i < 40 ? "INACTIVE" : "MAINTENANCE",
    version: 1,
  }));

  const customBranches = [
    { id: "branch-active", name: "Chi nhánh Đang Mở", status: "ACTIVE" },
    { id: "branch-inactive", name: "Chi nhánh Đã Đóng", status: "INACTIVE" },
  ];

  const container = await loaded(manyVehicles, customBranches);

  assert.match(container.textContent, /Hiển thị 20\/45 xe · Trang 1\/3/);
  assert.equal(container.querySelectorAll("article").length, 20);

  const nextBtn = button(container, "Trang sau");
  const prevBtn = button(container, "Trang trước");
  assert.equal(prevBtn.disabled, true);
  assert.equal(nextBtn.disabled, false);

  await act(async () => nextBtn.click());
  assert.match(container.textContent, /Hiển thị 20\/45 xe · Trang 2\/3/);
  assert.match(container.querySelectorAll("article")[0].textContent, /Car 21/);

  await act(async () => nextBtn.click());
  assert.match(container.textContent, /Hiển thị 5\/45 xe · Trang 3\/3/);
  assert.equal(nextBtn.disabled, true);

  const filterPanel = container.querySelector('[aria-label="Bộ lọc quản lý xe"]');
  assert.ok(filterPanel);
  const branchSelect = filterPanel.querySelectorAll("select")[0];
  assert.ok(branchSelect);
  assert.match(branchSelect.textContent, /Chi nhánh Đã Đóng \(Ngừng nhận đơn\)/);

  await act(async () => setSelectValue(branchSelect, "branch-inactive"));
  assert.match(container.textContent, /Hiển thị 20\/20 xe · Trang 1\/1/);
  assert.equal(container.querySelectorAll("article").length, 20);

  const statusSelect = filterPanel.querySelectorAll("select")[1];
  await act(async () => setSelectValue(statusSelect, "MAINTENANCE"));
  assert.match(container.textContent, /Hiển thị 5\/5 xe · Trang 1\/1/);
  assert.equal(container.querySelectorAll("article").length, 5);

  const searchInput = filterPanel.querySelector("input");
  await act(async () => setInputValue(searchInput, "51a-10042"));
  assert.match(container.textContent, /Hiển thị 1\/1 xe · Trang 1\/1/);
  assert.match(container.querySelectorAll("article")[0].textContent, /Car 43/);

  await act(async () => setInputValue(searchInput, "khong-khop"));
  assert.match(container.textContent, /Không có xe phù hợp/);

  await unmountAll();
});

test("P0-3: Staff fleet view omits admin filters, create/edit forms, and image/amenity editors", async () => {
  const container = await loaded([vehicle], [{ id: "long-branch-id", name: "Chi nhánh", status: "ACTIVE" }], { fleet: true });
  assert.match(container.textContent, /Tình trạng đội xe/);
  assert.equal(container.querySelector('[aria-label="Bộ lọc quản lý xe"]'), null);
  assert.equal(button(container, "Chỉnh sửa"), undefined);
  assert.equal(button(container, "Xóa xe"), undefined);
  assert.equal(button(container, "Tạo xe"), undefined);
  assert.equal(container.querySelector('[role="tablist"]'), null);
  assert.ok(button(container, "Bảo trì"));

  await unmountAll();
});

after(async () => {
  await viteServer?.close();
  for (const [name, descriptor] of globals) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor);
    else delete globalThis[name];
  }
  dom?.window.close();
});
