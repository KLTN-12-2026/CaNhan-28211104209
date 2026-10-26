import assert from "node:assert/strict";
import { before, after, afterEach, test } from "node:test";
import { createElement, act } from "react";
import { createRoot } from "react-dom/client";
import { JSDOM } from "jsdom";
import { createServer } from "vite";
import { fileURLToPath, URL } from "node:url";
import { MemoryRouter } from "react-router-dom";

let server, Gallery, Editor, Visual, client, BookingList;
const roots = [];
const descriptors = new Map();
before(async () => {
  const dom = new JSDOM("<!doctype html><body></body>", { url: "http://localhost" });
  for (const [name, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator, localStorage: dom.window.localStorage, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true })) {
    descriptors.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
    Object.defineProperty(globalThis, name, { configurable: true, value });
  }
  server = await createServer({ root: fileURLToPath(new URL("../..", import.meta.url)), appType: "custom", logLevel: "error", server: { middlewareMode: true, hmr: false } });
  ({ default: Gallery } = await server.ssrLoadModule("/src/components/vehicle/VehicleGallery.jsx"));
  ({ default: Editor } = await server.ssrLoadModule("/src/components/vehicle/VehicleImageEditor.jsx"));
  ({ VehicleVisual: Visual } = await server.ssrLoadModule("/src/components/vehicle/VehicleCard.jsx"));
  ({ default: client } = await server.ssrLoadModule("/src/api/client.js"));
  ({ ApiBookingListPage: BookingList } = await server.ssrLoadModule("/src/pages/api/ApiScreens.jsx"));
});
afterEach(async () => { await act(async () => { roots.splice(0).forEach((root) => root.unmount()); }); document.body.replaceChildren(); });
after(async () => { await server.close(); for (const [name, descriptor] of descriptors) { if (descriptor) Object.defineProperty(globalThis, name, descriptor); else delete globalThis[name]; } });
async function mount(Component, props) {
  const element = document.createElement("div"); document.body.append(element);
  const root = createRoot(element); roots.push(root);
  const render = async (next) => { await act(async () => { root.render(createElement(Component, next)); }); };
  await render(props); return { element, render };
}
async function click(button) { await act(async () => { button.click(); }); }
const images = [{ id: "a", url: "/media/vehicles/a.jpg", sortOrder: 0, attribution: "Author A <b>plain</b>" }, { id: "b", url: "/media/vehicles/b.jpg", sortOrder: 1, attribution: "Author B" }];
const response = (config, data) => ({ data, status: 200, headers: {}, config });
const button = (element, label) => [...element.querySelectorAll("button")].find((node) => node.textContent === label);
const fail = (status) => Object.assign(new Error("Invalid input"), { response: { status, data: { message: "Invalid input" } } });
test("Gallery selects actual API image/credit; credit is text; changing vehicle resets selection", async () => {
  const { element, render } = await mount(Gallery, { vehicle: { id: "v1", name: "Vios", noLocalAsset: true, images } });
  assert.equal(element.querySelector("img").getAttribute("src"), images[0].url);
  assert.equal(element.querySelector("img").getAttribute("loading"), "eager");
  assert.ok(element.textContent.includes("<b>plain</b>")); assert.equal(element.querySelector("b"), null);
  await click(element.querySelector('[aria-label="Xem ảnh 2"]'));
  assert.equal(element.querySelector("img").getAttribute("src"), images[1].url); assert.ok(element.textContent.includes("Author B"));
  await render({ vehicle: { id: "v2", name: "Accent", noLocalAsset: true, images } });
  assert.equal(element.querySelector("img").getAttribute("src"), images[0].url);
});
test("API empty/broken image never falls back to demo or stale vehicle.image", async () => {
  const { element, render } = await mount(Visual, { vehicle: { id: "v1", name: "Vios", noLocalAsset: true, images: [], image: "demo.jpg" } });
  assert.equal(element.querySelector("img"), null); assert.ok(element.textContent.includes("Ảnh chưa cập nhật"));
  await render({ vehicle: { id: "v1", noLocalAsset: true, images } });
  await act(async () => { element.querySelector("img").dispatchEvent(new window.Event("error")); });
  assert.equal(element.querySelector("img"), null);
});
test("Editor exact-permutation reorder sends current revision; double submit is suppressed", async () => {
  const calls = []; let resolveWrite;
  client.defaults.adapter = (config) => { calls.push(config); return config.method === "get" ? Promise.resolve(response(config, { images, revision: "r1" })) : new Promise((resolve) => { resolveWrite = () => resolve(response(config, { images: [...images].reverse(), revision: "r2" })); }); };
  const { element } = await mount(Editor, { vehicleId: "v1" });
  await act(async () => { element.querySelector('[aria-label="Đưa ảnh 2 lên"]').click(); element.querySelector('[aria-label="Đưa ảnh 2 lên"]').click(); });
  assert.equal(calls.filter((call) => call.method === "put").length, 1);
  assert.deepEqual(JSON.parse(calls[1].data), { imageIds: ["b", "a"], expectedRevision: "r1" });
  await act(async () => resolveWrite()); assert.ok(element.textContent.includes("Đã lưu ảnh xe."));
});
test("Editor 400 keeps input; 409 blocks writes until explicit fresh reload", async () => {
  let status = 400; let revision = "r1"; const calls = [];
  client.defaults.adapter = async (config) => { calls.push(config); if (config.method === "get") return response(config, { images, revision }); throw fail(status); };
  const { element } = await mount(Editor, { vehicleId: "v1" });
  await click(button(element, "Sửa ảnh 1"));
  await click(button(element, "Lưu thông tin ảnh"));
  assert.equal(element.querySelector("input").value, images[0].url); assert.ok(!element.textContent.includes("Đã lưu ảnh xe."));
  status = 409; await click(button(element, "Lưu thông tin ảnh")); assert.ok(element.textContent.includes("409:")); assert.ok(button(element, "Lưu thông tin ảnh").disabled);
  revision = "r2"; await click(button(element, "Tải lại bản mới"));
  assert.equal(element.querySelector("input").value, "");
  await click(button(element, "Sửa ảnh 1")); await click(button(element, "Lưu thông tin ảnh"));
  assert.equal(JSON.parse(calls.at(-1).data).expectedRevision, "r2");
});
test("Late previous-vehicle write cannot claim success in new editor", async () => {
  let resolveWrite;
  client.defaults.adapter = (config) => config.method === "get" ? Promise.resolve(response(config, { images, revision: "r1" })) : new Promise((resolve) => { resolveWrite = () => resolve(response(config, { images, revision: "r2" })); });
  const { element, render } = await mount(Editor, { vehicleId: "v1" });
  await click(button(element, "Sửa ảnh 1")); await click(button(element, "Lưu thông tin ảnh"));
  await render({ vehicleId: "v2" }); await act(async () => resolveWrite());
  assert.ok(!element.textContent.includes("Đã lưu ảnh xe.")); assert.equal(element.querySelector("input").value, "");
});
test("Urban own booking card retains API status, VND/date/branch and actual detail link", async () => {
  client.defaults.adapter = async (config) => response(config, [{ id: "bk1", vehicleName: "Toyota Vios", status: "CONFIRMED", startAt: "2026-11-01T00:00:00Z", endAt: "2026-11-02T00:00:00Z", pickupName: "Đà Nẵng", returnName: "Hà Nội", total: 650000 }]);
  const { element } = await mount(() => createElement(MemoryRouter, null, createElement(BookingList, { area: "user" })), {});
  assert.ok(element.querySelector(".urban-booking-card"));
  assert.ok(element.textContent.includes("Toyota Vios")); assert.ok(element.textContent.includes("650.000")); assert.ok(element.textContent.includes("Đà Nẵng → Hà Nội"));
  assert.equal(element.querySelector('a').getAttribute("href"), "/user/bookings/bk1");
});
test("Staff handover list keeps operational action and does not use customer card selector", async () => {
  client.defaults.adapter = async (config) => response(config, [{ id: "bk1", vehicleName: "Toyota Vios", status: "CONFIRMED", total: 650000 }]);
  const { element } = await mount(() => createElement(MemoryRouter, null, createElement(BookingList, { area: "staff", mode: "handover" })), {});
  assert.equal(element.querySelector(".urban-booking-card"), null);
  assert.equal(element.querySelector('a').getAttribute("href"), "/staff/handover/bk1");
});

test("UX02b Gallery attribution is placed in expandable details with accessible URL links", async () => {
  const customImages = [
    {
      id: "img-1",
      url: "/media/test-1.jpg",
      sortOrder: 0,
      attribution: "PhotoAuthor · CC BY 4.0 · https://example.com/photo-1 · <script>alert(1)</script>"
    },
    {
      id: "img-2",
      url: "/media/test-2.jpg",
      sortOrder: 1,
      attribution: null
    }
  ];
  const { element } = await mount(Gallery, { vehicle: { id: "v10", name: "TestCar", noLocalAsset: true, images: customImages } });

  // Verify label "Ảnh minh họa"
  assert.ok(element.textContent.includes("Ảnh minh họa"));

  // Verify details and summary
  const details = element.querySelector("details");
  assert.ok(details, "Expected <details> element for attribution");
  const summary = details.querySelector("summary");
  assert.ok(summary, "Expected <summary> element");
  assert.equal(summary.textContent.trim(), "Nguồn ảnh và giấy phép");

  // Verify markup is rendered safely as text without script tags
  assert.equal(element.querySelector("script"), null);
  assert.ok(element.textContent.includes("<script>alert(1)</script>"));

  // Verify URL link rendered with target and rel
  const link = details.querySelector('a[href="https://example.com/photo-1"]');
  assert.ok(link, "Expected clickable link for attribution URL");
  assert.equal(link.getAttribute("target"), "_blank");
  assert.equal(link.getAttribute("rel"), "noopener noreferrer");

  // Verify toggling open attribute
  assert.equal(details.open, false);
  await act(async () => {
    details.open = true;
    details.dispatchEvent(new window.Event("toggle"));
  });
  assert.equal(details.open, true);

  // Switch to image 2 without attribution
  await click(element.querySelector('[aria-label="Xem ảnh 2"]'));
  assert.ok(element.textContent.includes("Chưa cung cấp thông tin nguồn ảnh"));
});
