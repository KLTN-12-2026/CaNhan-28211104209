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
let ApiVehicleCatalogPage;
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
    statusText: status === 409 ? "Conflict" : "Error",
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

function changeValue(element, value) {
  const prototype = element instanceof dom.window.HTMLTextAreaElement
    ? dom.window.HTMLTextAreaElement.prototype
    : element instanceof dom.window.HTMLSelectElement
      ? dom.window.HTMLSelectElement.prototype
      : dom.window.HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
  if (setter) {
    setter.call(element, value);
  } else {
    element.value = value;
  }
  element.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
  element.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
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
  ({ default: ApiVehicleCatalogPage } = await viteServer.ssrLoadModule("/src/pages/api/ApiVehicleCatalogPage.jsx"));
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
// Tiêu chí 1: Mount actual React component ApiVehicleCatalogPage vào DOM
// Kiểm tra loading (skeleton), error (notice + nút retry), empty state
// =============================================================================

async function renderCatalog(brands=[{id:"brand",name:"Existing Brand",revision:"a".repeat(64)}], categories=[]) {
  const mounted=await mount(createElement(AuthProvider,null,createElement(ApiVehicleCatalogPage)));
  await act(async()=>{
    fulfill(takeRequest("/admin/vehicle-brands"),brands);
    fulfill(takeRequest("/admin/vehicle-categories"),categories);fulfill(takeRequest("/admin/amenities"),[]);
  });
  return mounted;
}
test("catalog shows loading/error/empty and actual retry request",async()=>{
  const {container,unmount}=await mount(createElement(AuthProvider,null,createElement(ApiVehicleCatalogPage)));
  assert.match(container.textContent,/Đang tải/);
  await act(async()=>{rejectWithStatus(takeRequest("/admin/vehicle-brands"),500,{message:"Catalog failure"});fulfill(takeRequest("/admin/vehicle-categories"),[]);fulfill(takeRequest("/admin/amenities"),[]);});
  assert.match(container.textContent,/Catalog failure/);assert.match(container.textContent,/Chưa có danh mục/);
  const section=container.querySelector('section[aria-label="Hãng xe"]');
  await act(async()=>section.querySelector("button").click());
  await act(async()=>fulfill(takeRequest("/admin/vehicle-brands"),[]));
  assert.doesNotMatch(section.textContent,/Catalog failure/);assert.match(section.textContent,/Chưa có danh mục/);await unmount();
});
test("catalog mounted double-submit suppression, stale conflict and recovery",async()=>{
  const {container,unmount}=await renderCatalog();const section=container.querySelector('section[aria-label="Hãng xe"]');
  let form=section.querySelector("form");let input=form.querySelector("input");
  await act(async()=>changeValue(input,"Created Brand"));
  await act(async()=>{form.dispatchEvent(new dom.window.Event("submit",{bubbles:true,cancelable:true}));form.dispatchEvent(new dom.window.Event("submit",{bubbles:true,cancelable:true}));});
  const post=takeRequest("/admin/vehicle-brands");assert.equal(post.config.method,"post");assert.equal(requests.filter(r=>r.config.method==="post").length,1);assert.equal(form.querySelector("button").disabled,true);
  await act(async()=>fulfill(post,{id:"new",name:"Created Brand",revision:"b".repeat(64)},201));
  await act(async()=>fulfill(takeRequest("/admin/vehicle-brands"),[{id:"brand",name:"Existing Brand",revision:"a".repeat(64)}]));
  form=section.querySelector("form");input=form.querySelector("input");
  await act(async()=>section.querySelector("li button").click());await act(async()=>changeValue(input,"Renamed Brand"));
  await act(async()=>{form.dispatchEvent(new dom.window.Event("submit",{bubbles:true,cancelable:true}));form.dispatchEvent(new dom.window.Event("submit",{bubbles:true,cancelable:true}));});
  const put=takeRequest("/admin/vehicle-brands/brand");assert.equal(put.config.method,"put");assert.equal(JSON.parse(put.config.data).expectedRevision,"a".repeat(64));assert.equal(requests.filter(r=>r.config.method==="put").length,1);
  await act(async()=>rejectWithStatus(put,409,{message:"Stale catalog"}));assert.match(section.textContent,/Stale catalog/);assert.equal(input.value,"Renamed Brand");assert.equal(form.querySelector("button").disabled,false);
  await act(async()=>Array.from(form.querySelectorAll("button")).find(button=>button.textContent==="Tải lại bản mới").click());
  await act(async()=>fulfill(takeRequest("/admin/vehicle-brands"),[{id:"brand",name:"Concurrent Brand",revision:"b".repeat(64)}]));
  form=section.querySelector("form");input=form.querySelector("input");await act(async()=>section.querySelector("li button").click());await act(async()=>changeValue(input,"Renamed Brand"));
  await act(async()=>form.dispatchEvent(new dom.window.Event("submit",{bubbles:true,cancelable:true})));const recovery=takeRequest("/admin/vehicle-brands/brand");assert.equal(JSON.parse(recovery.config.data).expectedRevision,"b".repeat(64));assert.equal(requests.filter(r=>r.config.method==="put").length,2);
  await act(async()=>fulfill(recovery,{id:"brand",name:"Renamed Brand",revision:"c".repeat(64)}));await act(async()=>fulfill(takeRequest("/admin/vehicle-brands"),[{id:"brand",name:"Renamed Brand",revision:"c".repeat(64)}]));assert.match(section.textContent,/Renamed Brand/);await unmount();
});

test("amenity validation error keeps input, releases guard and does not claim success",async()=>{
 const {container,unmount}=await renderCatalog();const section=container.querySelector('section[aria-label="Tiện nghi"]');const form=section.querySelector("form");
 await act(async()=>changeValue(form.querySelector("input"),"   "));await act(async()=>form.dispatchEvent(new dom.window.Event("submit",{bubbles:true,cancelable:true})));
 const request=takeRequest("/admin/amenities");assert.equal(request.config.method,"post");await act(async()=>rejectWithStatus(request,400,{message:"Invalid amenity"}));
 assert.match(section.textContent,/Invalid amenity/);assert.equal(form.querySelector("input").value,"   ");assert.equal(form.querySelector("button").disabled,false);assert.doesNotMatch(section.textContent,/Đã lưu danh mục/);await unmount();
});

test("catalog delete confirmation, revision, pending guard and successful reload",async()=>{
 const {container,unmount}=await renderCatalog([{id:"brand",name:"Unused",revision:"a".repeat(64)}]);const section=container.querySelector('section[aria-label="Hãng xe"]');
 const remove=()=>section.querySelector('button[aria-label="Xóa Unused"]');await act(async()=>remove().click());assert.equal(requests.filter(r=>r.config.method==="delete").length,0);await act(async()=>[...section.querySelectorAll("button")].find(b=>b.textContent==="Hủy").click());
 await act(async()=>{remove().click();remove().click();});await act(async()=>[...section.querySelectorAll("button")].find(b=>b.textContent==="Xác nhận").click());const request=takeRequest("/admin/vehicle-brands/brand");assert.deepEqual(request.config.params,{expectedRevision:"a".repeat(64)});assert.equal(requests.filter(r=>r.config.method==="delete").length,1);assert.equal(remove().disabled,true);assert.doesNotMatch(section.textContent,/Đã xóa/);
 await act(async()=>fulfill(request,null,204));await act(async()=>fulfill(takeRequest("/admin/vehicle-brands"),[]));assert.match(section.textContent,/Đã xóa hãng xe/);assert.equal(section.querySelectorAll("li").length,0);await unmount();
});

test("catalog reference409 keeps item and never claims delete success",async()=>{
 const {container,unmount}=await renderCatalog(undefined,[{id:"category",name:"Used",revision:"b".repeat(64)}]);const section=container.querySelector('section[aria-label="Loại xe"]');
 await act(async()=>section.querySelector('button[aria-label="Xóa Used"]').click());await act(async()=>[...section.querySelectorAll("button")].find(b=>b.textContent==="Xác nhận").click());await act(async()=>rejectWithStatus(takeRequest("/admin/vehicle-categories/category"),409,{message:"Danh mục đang được 2 xe sử dụng."}));
 assert.match(section.textContent,/2 xe sử dụng/);assert.equal(section.querySelectorAll("li").length,1);assert.doesNotMatch(section.textContent,/Đã xóa/);await unmount();
});
