import assert from "node:assert/strict";
import { before, after, beforeEach, afterEach, test } from "node:test";
import { fileURLToPath, URL } from "node:url";
import { createElement, act } from "react";
import { JSDOM } from "jsdom";
import { createServer } from "vite";
import { AxiosError } from "axios";

let server, dom, Component, api, createRoot, root, container, requests, adapter;
const originals = new Map();
const fixture = { version: 7, photos: { front: true, back: true, status: "PENDING" } };
before(async () => {
  dom = new JSDOM("<html><body></body></html>", { url: "http://localhost" });
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator, localStorage: dom.window.localStorage, FormData: dom.window.FormData, IS_REACT_ACT_ENVIRONMENT: true })) {
    originals.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  ({ createRoot } = await import("react-dom/client"));
  const frontendRoot = fileURLToPath(new URL("../../..", import.meta.url));
  server = await createServer({ root: frontendRoot, configFile: `${frontendRoot}/vite.config.js`, appType: "custom", logLevel: "error", optimizeDeps: { noDiscovery: true }, server: { middlewareMode: true } });
  ({ default: Component } = await server.ssrLoadModule("/src/components/user/LicensePhotos.jsx"));
  ({ default: api } = await server.ssrLoadModule("/src/api/client.js"));
  adapter = api.defaults.adapter;
});
beforeEach(() => {
  requests = [];
  api.defaults.adapter = config => new Promise((resolve, reject) => requests.push({ config, resolve, reject }));
  localStorage.clear();
  container = document.createElement("div"); document.body.append(container); root = createRoot(container);
});
afterEach(async () => { await act(async () => root.unmount()); container.remove(); api.defaults.adapter = adapter; });
after(async () => {
  await server.close(); dom.window.close();
  for (const [key, descriptor] of originals) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key]; }
});
async function mount(props) { await act(async () => root.render(createElement(Component, props))); }
async function submit() { await act(async () => container.querySelector("form").dispatchEvent(new window.Event("submit", { bubbles: true, cancelable: true }))); }
async function resolve(index, data) { const r = requests[index]; await act(async () => r.resolve({ config: r.config, data, status: 200, headers: {} })); }
test("missing license asks user to save details without creating an upload form", async () => {
  await mount({ license: null, onSaved() {} });
  assert.match(container.textContent, /Lưu thông tin GPLX bên trên/);
  assert.equal(container.querySelector("form"), null); assert.equal(requests.length, 0);
});
test("upload sends version and both file parts, single-flight and saved readback callback", async () => {
  let saved = 0;
  await mount({ license: fixture, onSaved() { saved++; } });
  assert.equal(container.querySelectorAll('input[type="file"][required]').length, 2);
  await submit(); await submit(); assert.equal(requests.length, 1);
  assert.equal(requests[0].config.method, "put"); assert.equal(requests[0].config.url, "/me/license/photos");
  const form = requests[0].config.data;
  assert.equal(form.get("version"), "7"); assert.ok(form.has("front")); assert.ok(form.has("back"));
  assert.notEqual(requests[0].config.headers.get("Content-Type"), "application/json");
  await resolve(0, { version: 8 }); assert.equal(saved, 1); assert.match(container.textContent, /Đã lưu hai mặt ảnh GPLX/);
});
test("staff requests changes with expected version and explicit reason; no physical verification", async () => {
  let saved = 0;
  await mount({ license: fixture, bookingId: "booking-1", onSaved() { saved++; } });
  container.querySelector("select").value = "NEEDS_CHANGES"; container.querySelector("textarea").value = "Ảnh mặt sau bị mờ";
  await submit();
  assert.equal(requests[0].config.url, "/bookings/booking-1/license-photo-review");
  assert.deepEqual(JSON.parse(requests[0].config.data), { version: 7, approve: false, reason: "Ảnh mặt sau bị mờ" });
  await resolve(0, {}); assert.equal(saved, 1); assert.match(container.textContent, /Vẫn cần đối chiếu bản gốc/);
});
test("409 preserves error and does not report success or reload", async () => {
  let saved = 0;
  await mount({ license: fixture, onSaved() { saved++; } }); await submit();
  const request = requests[0];
  await act(async () => request.reject(new AxiosError("Conflict", "ERR_BAD_REQUEST", request.config, null, { status: 409, data: { message: "GPLX đã thay đổi. Vui lòng tải lại." } })));
  assert.match(container.querySelector('[role="alert"]').textContent, /GPLX đã thay đổi/);
  assert.equal(saved, 0); assert.equal(container.querySelector('[role="status"]'), null);
});
test("response from changed session cannot show a successful upload", async () => {
  let saved = 0;
  await mount({ license: fixture, onSaved() { saved++; } }); await submit();
  localStorage.setItem("kdrive.jwt", "different-test-session");
  await resolve(0, {}); assert.equal(saved, 0); assert.equal(container.querySelector('[role="status"]'), null);
});
