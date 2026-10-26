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
let ApiLocationsPage;
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

async function mount() {
  const container = document.createElement("div");
  document.body.append(container);
  const root = createRoot(container);
  roots.add(root);
  await act(async () => root.render(createElement(ApiLocationsPage)));
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

async function loadInitial(categories, locations) {
  const categoryRequest = take("/admin/location-categories");
  const locationRequest = take("/admin/locations");
  await act(async () => {
    resolve(categoryRequest, categories);
    resolve(locationRequest, locations);
  });
}

async function loadAfterMutation(categories, locations) {
  const categoryRequest = take("/admin/location-categories");
  const locationRequest = take("/admin/locations");
  await act(async () => {
    resolve(categoryRequest, categories);
    resolve(locationRequest, locations);
  });
}

async function fill(input, value) {
  await act(async () => {
    const prototype = input instanceof dom.window.HTMLSelectElement
      ? dom.window.HTMLSelectElement.prototype
      : dom.window.HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, "value").set.call(input, value);
    input.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
    input.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
  });
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
  ({ default: ApiLocationsPage } = await viteServer.ssrLoadModule("/src/pages/api/ApiLocationsPage.jsx"));
  ({ default: apiClient } = await viteServer.ssrLoadModule("/src/api/client.js"));
});

after(async () => {
  await viteServer?.close();
  for (const [name, descriptor] of globals) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor);
    else delete globalThis[name];
  }
  dom?.window.close();
});

async function loadFailureAndRetry() {
  resetRequests();
  const container = await mount();
  const categories = take("/admin/location-categories");
  const locations = take("/admin/locations");
  await act(async () => {
    reject(categories, 503, { message: "Catalog unavailable" });
    resolve(locations, []);
  });
  assert.match(container.textContent, /Catalog unavailable/);
  assert.doesNotMatch(container.textContent, /Chưa có địa điểm/);

  const retry = [...container.querySelectorAll("button")].find((button) => button.textContent.includes("Thử tải lại"));
  assert.ok(retry);
  await act(async () => {
    retry.click();
    await Promise.resolve();
  });
  const categoryRetry = take("/admin/location-categories");
  const locationRetry = take("/admin/locations");
  await act(async () => {
    resolve(categoryRetry, []);
    resolve(locationRetry, []);
  });
  assert.match(container.textContent, /Chưa có địa điểm/);
  await act(async () => container.querySelector('[role="tab"]:nth-child(2)').click());
  assert.match(container.textContent, /Chưa có loại địa điểm/);
  await unmountAll();
}

async function pendingCategoryCreate() {
  resetRequests();
  const container = await mount();
  await loadInitial([], []);
  await act(async () => container.querySelector('[role="tab"]:nth-child(2)').click());
  await fill(container.querySelector('input[name="categoryName"]'), "Bãi biển");

  const form = container.querySelector("form");
  await act(async () => {
    form.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
    form.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
  });

  const post = take("/admin/location-categories", "post");
  assert.equal(requests.filter((item) => item.config.url === "/admin/location-categories" && item.config.method === "post").length, 1);
  assert.equal(container.querySelector('button[type="submit"]').disabled, true);
  await act(async () => resolve(post, { id: "category-1", name: "Bãi biển", revision: "a".repeat(64) }, 201));
  await loadAfterMutation([{ id: "category-1", name: "Bãi biển", revision: "a".repeat(64) }], []);
  assert.match(container.textContent, /Đã tạo loại địa điểm/);
  assert.match(container.textContent, /Bãi biển/);
  await unmountAll();
}

async function staleLocationUpdate() {
  resetRequests();
  const container = await mount();
  const category = { id: "category-1", name: "Biển", revision: "b".repeat(64) };
  const location = {
    id: "location-1",
    categoryId: category.id,
    categoryName: category.name,
    name: "Bãi biển",
    latitude: "0.0000000",
    longitude: "0.0000000",
    status: "INACTIVE",
    revision: "c".repeat(64),
  };
  await loadInitial([category], [location]);
  await act(async () => container.querySelector('[data-testid="edit-location-location-1"]').click());

  assert.equal(container.querySelector('input[name="latitude"]').value, "0.0000000");
  assert.equal(container.querySelector('input[name="longitude"]').value, "0.0000000");
  const form = container.querySelector("form");
  await act(async () => form.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true })));
  const put = take("/admin/locations/location-1", "put");
  const sent = JSON.parse(put.config.data);
  assert.equal(sent.expectedRevision, location.revision);
  assert.equal(sent.latitude, "0.0000000");
  assert.equal(sent.longitude, "0.0000000");
  await act(async () => reject(put, 409, { message: "Địa điểm đã thay đổi." }));
  const refreshedLocations = take("/admin/locations");
  await act(async () => resolve(refreshedLocations, [
    { ...location, name: "Tên mới từ máy chủ", revision: "d".repeat(64) },
  ]));

  assert.match(container.textContent, /Địa điểm đã thay đổi/);
  assert.match(container.textContent, /Tên mới từ máy chủ/);
  assert.doesNotMatch(container.textContent, /Đã cập nhật địa điểm/);
  await unmountAll();
}

test("location API screen covers retry, pending writes, zero coordinates and stale conflicts", async () => {
  await loadFailureAndRetry();
  await pendingCategoryCreate();
  await staleLocationUpdate();
});test("category edit sends revision, preserves rejected input and recovers after stale reload", async () => {
  resetRequests();
  const container = await mount();
  const category = { id: "revision-test", name: "Biển", revision: "a".repeat(64) };
  await loadInitial([category], []);
  await act(async () => container.querySelector('[role="tab"]:nth-child(2)').click());
  const edit = () => container.querySelector('[data-testid="edit-category-revision-test"]');
  assert.ok(edit());
  await act(async () => edit().click());
  await fill(container.querySelector('input[name="categoryName"]'), "Tên sửa");
  const submit = async () => {
    await act(async () => container.querySelector("form").dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true })));
    return take("/admin/location-categories/revision-test", "put");
  };
  let put = await submit();
  assert.deepEqual(JSON.parse(put.config.data), { name: "Tên sửa", expectedRevision: category.revision });
  assert.equal(container.querySelector('button[type="submit"]').disabled, true);
  await act(async () => reject(put, 400, { message: "Tên không hợp lệ" }));
  assert.equal(container.querySelector('input[name="categoryName"]').value, "Tên sửa");
  assert.match(container.textContent, /Tên không hợp lệ/);
  assert.doesNotMatch(container.textContent, /Đã cập nhật loại địa điểm/);
  put = await submit();
  assert.equal(JSON.parse(put.config.data).expectedRevision, category.revision);
  await act(async () => reject(put, 409, { message: "Loại đã thay đổi" }));
  const fresh = { ...category, name: "Tên máy chủ", revision: "b".repeat(64) };
  await act(async () => resolve(take("/admin/location-categories"), [fresh]));
  assert.match(container.textContent, /Loại đã thay đổi/);
  assert.doesNotMatch(container.textContent, /Đã cập nhật loại địa điểm/);
  await act(async () => edit().click());
  await fill(container.querySelector('input[name="categoryName"]'), "Tên phục hồi");
  put = await submit();
  assert.deepEqual(JSON.parse(put.config.data), { name: "Tên phục hồi", expectedRevision: fresh.revision });
  const saved = { ...fresh, name: "Tên phục hồi", revision: "c".repeat(64) };
  await act(async () => resolve(put, saved));
  await loadAfterMutation([saved], []);
  assert.match(container.textContent, /Đã cập nhật loại địa điểm/);
  assert.match(container.textContent, /Tên phục hồi/);
  assert.equal(container.querySelector('input[name="categoryName"]').value, "");
  await unmountAll();
});
