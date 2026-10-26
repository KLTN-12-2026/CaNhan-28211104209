import assert from "node:assert/strict";
import { after, afterEach, before, beforeEach, test } from "node:test";
import { fileURLToPath, URL } from "node:url";
import process from "node:process";
import { createElement, act, useEffect, useState } from "react";
import { AxiosError } from "axios";
import { JSDOM } from "jsdom";
import { createServer } from "vite";

const frontendRoot = fileURLToPath(new URL("../..", import.meta.url));
const originalDataMode = process.env.VITE_DATA_MODE;
const globalDescriptors = new Map();
const mountedRoots = new Set();
const requests = [];
let dom;
let apiClient;
let setAuthInterceptors;
let AuthProvider;
let useAuth;
let useApiData;
let createRoot;
let viteServer;
let originalAdapter;
let storageUtils;
let useVehicleFavorites;

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

function rejectWithStatus(request, status) {
  const response = {
    config: request.config,
    data: { message: "Unauthorized" },
    headers: {},
    status,
    statusText: "Unauthorized",
  };
  request.reject(
    new AxiosError(
      `Request failed with status code ${status}`,
      "ERR_BAD_REQUEST",
      request.config,
      undefined,
      response,
    ),
  );
}

function DataProbe({ path }) {
  const state = useApiData(path);
  return createElement(
    "section",
    null,
    createElement(
      "output",
      { "data-testid": "request-state" },
      state.loading ? "loading" : state.error ? "error" : "ready",
    ),
    createElement(
      "pre",
      { "data-testid": "request-data" },
      JSON.stringify(state.data ?? null),
    ),
    createElement(
      "button",
      { type: "button", "data-testid": "reload", onClick: state.reload },
      "Reload",
    ),
  );
}

function AuthProbe({
  dataPath = null,
  onLogin = () => {},
  onLogout = () => {},
}) {
  const auth = useAuth();
  const data = useApiData(dataPath ?? "", Boolean(dataPath));
  return createElement(
    "section",
    null,
    createElement("output", { "data-testid": "token" }, auth.token ?? ""),
    createElement("output", { "data-testid": "user-id" }, auth.user?.id ?? ""),
    createElement("output", { "data-testid": "user-role" }, auth.user?.role ?? ""),
    createElement(
      "output",
      { "data-testid": "authenticated" },
      String(auth.isAuthenticated),
    ),
    createElement(
      "output",
      { "data-testid": "data-error" },
      data.error,
    ),
    createElement(
      "button",
      {
        type: "button",
        "data-testid": "login",
        onClick: () =>
          onLogin(
            auth.login({
              email: "new@example.test",
              password: "not-a-real-password",
            }),
          ),
      },
      "Login",
    ),
    createElement(
      "button",
      {
        type: "button",
        "data-testid": "logout",
        onClick: () => onLogout(auth.logout({ callApi: false })),
      },
      "Logout",
    ),
  );
}

async function mount(element, wrapInAuth = false) {
  const container = document.createElement("div");
  document.body.append(container);
  const root = createRoot(container);
  mountedRoots.add(root);
  const app = wrapInAuth
    ? createElement(AuthProvider, null, element)
    : element;
  await act(async () => {
    root.render(app);
  });
  return {
    container,
    render: async (nextElement) => {
      const nextApp = wrapInAuth
        ? createElement(AuthProvider, null, nextElement)
        : nextElement;
      await act(async () => {
        root.render(nextApp);
      });
    },
    unmount: async () => {
      await act(async () => {
        root.unmount();
      });
      mountedRoots.delete(root);
      container.remove();
    },
  };
}

function text(container, testId) {
  return container.querySelector(`[data-testid="${testId}"]`).textContent;
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
  ({ AuthProvider, useAuth } = await viteServer.ssrLoadModule(
    "/src/context/AuthContext.jsx",
  ));
  ({ useApiData } = await viteServer.ssrLoadModule(
    "/src/hooks/useApiData.js",
  ));
  ({ default: apiClient, setAuthInterceptors } =
    await viteServer.ssrLoadModule("/src/api/client.js"));
  originalAdapter = apiClient.defaults.adapter;
  storageUtils = await viteServer.ssrLoadModule("/src/utils/storage.js");
  ({ useVehicleFavorites } = await viteServer.ssrLoadModule("/src/hooks/useVehicleFavorites.js"));
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
  Object.defineProperty(globalThis, "localStorage", { configurable: true, writable: true, value: dom.window.localStorage });
  storageUtils.clearSession();
  document.body.replaceChildren();
  apiClient.defaults.adapter = originalAdapter;
  setAuthInterceptors();
});

after(async () => {
  if (viteServer) await viteServer.close();
  if (dom) dom.window.close();
  if (originalDataMode === undefined) {
    delete process.env.VITE_DATA_MODE;
  } else {
    process.env.VITE_DATA_MODE = originalDataMode;
  }
  for (const [name, descriptor] of globalDescriptors) {
    if (descriptor) {
      Object.defineProperty(globalThis, name, descriptor);
    } else {
      delete globalThis[name];
    }
  }
});

test("route A resolving after route B cannot replace B's mounted hook data", async () => {
  const view = await mount(createElement(DataProbe, { path: "/route/a" }));
  const routeA = takeRequest("/route/a");

  await view.render(createElement(DataProbe, { path: "/route/b" }));
  const routeB = takeRequest("/route/b");

  await act(async () => {
    fulfill(routeB, { id: "route-b", name: "B" });
  });
  assert.equal(text(view.container, "request-state"), "ready");
  assert.deepEqual(JSON.parse(text(view.container, "request-data")), {
    id: "route-b",
    name: "B",
  });

  await act(async () => {
    fulfill(routeA, { id: "route-a", name: "A" });
  });
  assert.deepEqual(JSON.parse(text(view.container, "request-data")), {
    id: "route-b",
    name: "B",
  });
  await view.unmount();
});

test("route changes and reload hide old hook data while the replacement is pending", async () => {
  const view = await mount(createElement(DataProbe, { path: "/route/a" }));
  const firstA = takeRequest("/route/a");
  await act(async () => {
    fulfill(firstA, { id: "old-route" });
  });
  assert.equal(
    JSON.parse(text(view.container, "request-data")).id,
    "old-route",
  );

  await view.render(createElement(DataProbe, { path: "/route/b" }));
  assert.equal(text(view.container, "request-state"), "loading");
  assert.equal(text(view.container, "request-data"), "null");
  const routeB = takeRequest("/route/b");
  await act(async () => {
    fulfill(routeB, { id: "new-route" });
  });
  assert.equal(
    JSON.parse(text(view.container, "request-data")).id,
    "new-route",
  );

  await act(async () => {
    view.container.querySelector('[data-testid="reload"]').click();
  });
  assert.equal(text(view.container, "request-state"), "loading");
  assert.equal(text(view.container, "request-data"), "null");
  const reloadedB = takeRequest("/route/b");
  await act(async () => {
    fulfill(reloadedB, { id: "reloaded-route" });
  });
  assert.equal(
    JSON.parse(text(view.container, "request-data")).id,
    "reloaded-route",
  );
  await view.unmount();
});

test("late hydration cannot replace the session established by a completed login", async () => {
  localStorage.setItem("kdrive.jwt", "old-token");
  let loginPromise;
  const view = await mount(
    createElement(AuthProbe, { onLogin: (promise) => (loginPromise = promise) }),
    true,
  );
  const hydration = takeRequest("/auth/me");

  await act(async () => {
    view.container.querySelector('[data-testid="login"]').click();
  });
  const login = takeRequest("/auth/login");
  assert.equal(login.config.headers.get("Authorization"), undefined);
  await act(async () => {
    fulfill(login, {
      token: "new-token",
      user: { id: "new-user", fullName: "New", email: "new@example.test", role: "USER" },
    });
    await loginPromise;
  });

  assert.equal(text(view.container, "token"), "new-token");
  assert.equal(text(view.container, "user-id"), "new-user");
  assert.equal(text(view.container, "user-role"), "USER");
  assert.equal(localStorage.getItem("kdrive.jwt"), "new-token");

  await act(async () => {
    fulfill(hydration, {
      id: "old-user",
      fullName: "Old",
      email: "old@example.test",
      role: "USER",
    });
  });
  assert.equal(text(view.container, "token"), "new-token");
  assert.equal(text(view.container, "user-id"), "new-user");
  assert.equal(text(view.container, "user-role"), "USER");
  assert.equal(localStorage.getItem("kdrive.jwt"), "new-token");
  await view.unmount();
});

test("logout during an in-flight login prevents that response from restoring a session", async () => {
  let loginOutcome;
  const view = await mount(
    createElement(AuthProbe, {
      onLogin: (promise) => {
        loginOutcome = promise.then(
          () => ({ resolved: true }),
          (error) => ({ error }),
        );
      },
    }),
    true,
  );
  await act(async () => {
    view.container.querySelector('[data-testid="login"]').click();
  });
  const login = takeRequest("/auth/login");

  await act(async () => {
    view.container.querySelector('[data-testid="logout"]').click();
  });
  assert.equal(text(view.container, "authenticated"), "false");
  assert.equal(localStorage.getItem("kdrive.jwt"), null);

  await act(async () => {
    fulfill(login, {
      token: "late-token",
      user: { id: "late-user", fullName: "Late", email: "late@example.test", role: "USER" },
    });
    const outcome = await loginOutcome;
    assert.match(outcome.error.message, /đã bị thay thế/);
  });
  assert.equal(text(view.container, "token"), "");
  assert.equal(text(view.container, "user-id"), "");
  assert.equal(text(view.container, "user-role"), "");
  assert.equal(text(view.container, "authenticated"), "false");
  assert.equal(localStorage.getItem("kdrive.jwt"), null);
  await view.unmount();
});

test("an old-token 401 cannot clear a newer mounted session", async () => {
  localStorage.setItem("kdrive.jwt", "old-token");
  let loginPromise;
  const view = await mount(
    createElement(AuthProbe, { onLogin: (promise) => (loginPromise = promise) }),
    true,
  );
  const hydration = takeRequest("/auth/me");
  await act(async () => {
    fulfill(hydration, {
      id: "old-user",
      fullName: "Old",
      email: "old@example.test",
      role: "USER",
    });
  });
  assert.equal(text(view.container, "user-id"), "old-user");

  await view.render(createElement(AuthProbe, { dataPath: "/protected/data" }));
  const protectedRequest = takeRequest("/protected/data");
  assert.equal(
    protectedRequest.config.headers.get("Authorization"),
    "Bearer old-token",
  );

  await act(async () => {
    view.container.querySelector('[data-testid="login"]').click();
  });
  const login = takeRequest("/auth/login");
  await act(async () => {
    fulfill(login, {
      token: "new-token",
      user: { id: "new-user", fullName: "New", email: "new@example.test", role: "USER" },
    });
    await loginPromise;
  });
  assert.equal(text(view.container, "user-id"), "new-user");

  await act(async () => {
    rejectWithStatus(protectedRequest, 401);
  });
  assert.equal(text(view.container, "token"), "new-token");
  assert.equal(text(view.container, "user-id"), "new-user");
  assert.equal(text(view.container, "user-role"), "USER");
  assert.equal(text(view.container, "authenticated"), "true");
  assert.equal(localStorage.getItem("kdrive.jwt"), "new-token");
  assert.equal(
    JSON.parse(localStorage.getItem("kdrive.user")).id,
    "new-user",
  );
  await view.unmount();
});
for (const status of [403, 500]) {
  test(`old-session ${status} cannot trigger global callbacks for a newer session`, async () => {
    localStorage.setItem("kdrive.jwt", "old-token");
    let callbacks = 0;
    setAuthInterceptors({forbidden: () => callbacks++, serverError: () => callbacks++});
    const outcome = apiClient.get("/private/resource").catch(error => error);
    await new Promise(resolve => setTimeout(resolve, 0));
    const request = takeRequest("/private/resource");
    localStorage.setItem("kdrive.jwt", "new-token");
    rejectWithStatus(request, status);
    assert.equal((await outcome).response.status, status);
    assert.equal(callbacks, 0);
    assert.equal(localStorage.getItem("kdrive.jwt"), "new-token");
    const current = apiClient.get("/private/current").catch(error => error);
    await new Promise(resolve => setTimeout(resolve, 0));
    rejectWithStatus(takeRequest("/private/current"), status);
    await current;
    assert.equal(callbacks, 1, "current-session error still reaches global handler");
  });
}

let currentAuth;
function SessionProbe() {
  const auth = useAuth();
  useEffect(() => { currentAuth = auth; }, [auth]);
  return createElement("output", null, JSON.stringify({ user: auth.user, token: auth.token, loading: auth.loading, error: auth.sessionError, warning: auth.logoutWarning }));
}
function failStorage(method, key = null, name = "SecurityError") {
  const actual = dom.window.localStorage;
  globalThis.localStorage = {
    getItem(item) { if (method === "getItem" && (!key || key === item)) throw Object.assign(new Error("blocked"), { name }); return actual.getItem(item); },
    setItem(item, value) { if (method === "setItem" && (!key || key === item)) throw Object.assign(new Error("full"), { name }); actual.setItem(item, value); },
    removeItem(item) { if (method === "removeItem" && (!key || key === item)) throw Object.assign(new Error("blocked"), { name }); actual.removeItem(item); },
  };
}
const fixtureProfile = { id: "storage-user", fullName: "Storage", email: "storage@example.test", role: "USER" };
for (const getter of [false, true]) test(`blocked bootstrap is mounted, anonymous and reports error: getter=${getter}`, async () => {
  if (getter) Object.defineProperty(globalThis, "localStorage", { configurable: true, get() { throw new Error("blocked property"); } });
  else failStorage("getItem");
  const view = await mount(createElement(SessionProbe), true);
  assert.equal(currentAuth.isAuthenticated, false);
  assert.equal(currentAuth.token, null);
  assert.equal(currentAuth.loading, false);
  assert.match(currentAuth.sessionError, /lưu phiên/);
  assert.equal(requests.length, 0, "unknown credential must not hydrate");
  Object.defineProperty(globalThis, "localStorage", { configurable: true, writable: true, value: dom.window.localStorage });
  await view.unmount();
});
for (const action of ["login", "register"]) for (const key of ["kdrive.user", "kdrive.jwt"]) test(`${action} cannot establish session on quota failure at ${key}`, async () => {
  await mount(createElement(SessionProbe), true);
  failStorage("setItem", key, "QuotaExceededError");
  let outcome;
  await act(async () => { outcome = currentAuth[action]({ email: "storage@example.test", password: "Fixture1!" }).catch(error => error); });
  await act(async () => {
    fulfill(takeRequest(`/auth/${action}`), { token: "quota-token", user: fixtureProfile });
    assert.match((await outcome).message, /lưu phiên/);
  });
  assert.equal(currentAuth.isAuthenticated, false);
  assert.equal(currentAuth.user, null);
  assert.equal(currentAuth.token, null);
  assert.equal(currentAuth.loading, false);
  assert.ok(currentAuth.sessionError);
  assert.equal(dom.window.localStorage.getItem("kdrive.jwt"), null);
  assert.equal(dom.window.localStorage.getItem("kdrive.user"), null);
  await assert.rejects(apiClient.get("/protected/storage"), /lưu phiên/);
  assert.equal(requests.some(request => request.config.url === "/protected/storage"), false);
  // Explicit successful login, rather than merely reading leftovers, recovers.
  Object.defineProperty(globalThis, "localStorage", { configurable: true, writable: true, value: dom.window.localStorage });
  await act(async () => { outcome = currentAuth.login({ email: "storage@example.test", password: "Fixture1!" }); });
  await act(async () => { fulfill(takeRequest("/auth/login"), { token: "recovered", user: fixtureProfile }); await outcome; });
  assert.equal(currentAuth.isAuthenticated, true);
  assert.equal(currentAuth.sessionError, "");
});
for (const serverFails of [false, true]) test(`logout clears mounted state despite removal failure; server failure=${serverFails}`, async () => {
  localStorage.setItem("kdrive.jwt", "retained-token");
  await mount(createElement(SessionProbe), true);
  await act(async () => fulfill(takeRequest("/auth/me"), fixtureProfile));
  assert.equal(currentAuth.isAuthenticated, true);
  failStorage("removeItem", "kdrive.jwt");
  let logout;
  await act(async () => { logout = currentAuth.logout(); });
  assert.equal(currentAuth.user, null);
  assert.equal(currentAuth.token, null);
  assert.equal(currentAuth.isAuthenticated, false);
  assert.match(currentAuth.logoutWarning, /không xóa được/);
  assert.equal(storageUtils.getStoredToken(), null);
  assert.equal(dom.window.localStorage.getItem("kdrive.jwt"), "retained-token");
  const request = takeRequest("/auth/logout");
  assert.equal(request.config.headers.get("Authorization"), "Bearer retained-token");
  await act(async () => { if (serverFails) request.reject(new Error("network")); else fulfill(request, null); await logout; });
  assert.match(currentAuth.logoutWarning, /không xóa được/);
  if (serverFails) assert.match(currentAuth.logoutWarning, /Chưa xác nhận/);
  await assert.rejects(apiClient.get("/protected/after-logout"), /lưu phiên/);
});
test("storage read failure while hydration is pending leaves no authenticated session", async () => {
  localStorage.setItem("kdrive.jwt", "pending-token");
  await mount(createElement(SessionProbe), true);
  const request = takeRequest("/auth/me");
  failStorage("getItem");
  await act(async () => fulfill(request, fixtureProfile));
  assert.equal(currentAuth.isAuthenticated, false);
  assert.equal(currentAuth.token, null);
  assert.equal(currentAuth.loading, false);
  assert.ok(currentAuth.sessionError);
});

function TabProbe() {
  const auth = useAuth(), favorites = useVehicleFavorites();
  const [draft, setDraft] = useState("clean");
  useEffect(() => { currentAuth = auth; }, [auth]);
  return createElement("section", null,
    createElement("output", { "data-testid": "identity" }, auth.user?.id ?? "guest"),
    createElement("output", { "data-testid": "favorites" }, favorites.ready ? JSON.stringify(favorites.ids) : "unverified"),
    createElement("output", { "data-testid": "draft" }, draft),
    createElement("button", { "data-testid": "draft-edit", onClick: () => setDraft("private-A-draft") }, "Edit"),
    auth.user ? createElement(DataProbe, { path: "/private/tab" }) : null,
  );
}
let tabFixtureId = 0;
async function mountTab() {
  const id = `tab-a-${++tabFixtureId}`;
  localStorage.setItem("kdrive.jwt", "tab-token-a");
  localStorage.setItem(`kdrive.favorites.v1.user.${id}`, '["v1"]');
  const view = await mount(createElement(TabProbe), true);
  await act(async () => fulfill(takeRequest("/auth/me"), { ...fixtureProfile, id }));
  return { ...view, id };
}
async function changeTabToken(token, { event = true, clear = false } = {}) {
  await act(async () => {
    const oldValue = localStorage.getItem("kdrive.jwt");
    if (clear) localStorage.clear();
    else if (token === null) localStorage.removeItem("kdrive.jwt");
    else localStorage.setItem("kdrive.jwt", token);
    if (event) window.dispatchEvent(new window.StorageEvent("storage", { key: clear ? null : "kdrive.jwt", oldValue, newValue: token, storageArea: dom.window.localStorage }));
  });
}

test("external A to B hides old data/draft/favorites until me verifies B, then resets child state", async () => {
  const view = await mountTab();
  await act(async () => fulfill(takeRequest("/private/tab"), { owner: view.id }));
  await act(async () => view.container.querySelector('[data-testid="draft-edit"]').click());
  assert.equal(text(view.container, "favorites"), '["v1"]');
  assert.ok(view.container.textContent.includes(view.id));
  const oldLogout = currentAuth.logout;
  localStorage.setItem("kdrive.favorites.v1.user.tab-b", '["v2"]');
  await changeTabToken("tab-token-b");
  assert.match(view.container.textContent, /Đang xác định phiên/);
  assert.ok(!view.container.textContent.includes(view.id));
  assert.ok(!view.container.textContent.includes("private-A-draft"));
  assert.equal(localStorage.getItem("kdrive.jwt"), "tab-token-b");
  const hydration = takeRequest("/auth/me");
  assert.equal(hydration.config.headers.get("Authorization"), "Bearer tab-token-b");
  await act(async () => fulfill(hydration, { ...fixtureProfile, id: "tab-b" }));
  assert.equal(text(view.container, "identity"), "tab-b");
  assert.equal(text(view.container, "favorites"), '["v2"]');
  assert.equal(text(view.container, "draft"), "clean");
  const fresh = takeRequest("/private/tab");
  assert.equal(fresh.config.headers.get("Authorization"), "Bearer tab-token-b");
  await act(async () => fulfill(fresh, { owner: "tab-b" }));
  await act(async () => oldLogout());
  assert.equal(localStorage.getItem("kdrive.jwt"), "tab-token-b", "stale logout must not clear shared B");
  assert.equal(text(view.container, "identity"), "tab-b");
  assert.equal(requests.filter(r => r.config.url === "/auth/logout").length, 0);
});

for (const outcome of ["success", 401, 403, 500]) test(`late old-account data cannot replace B or trigger callbacks: ${outcome}`, async () => {
  const view = await mountTab(), old = takeRequest("/private/tab");
  let callbacks = 0;
  setAuthInterceptors({ unauthorized: () => callbacks++, forbidden: () => callbacks++, serverError: () => callbacks++ });
  await changeTabToken("tab-token-b");
  await act(async () => fulfill(takeRequest("/auth/me"), { ...fixtureProfile, id: "late-b" }));
  await act(async () => fulfill(takeRequest("/private/tab"), { owner: "B" }));
  await act(async () => { if (outcome === "success") fulfill(old, { owner: "PRIVATE-A" }); else rejectWithStatus(old, outcome); });
  assert.equal(text(view.container, "identity"), "late-b");
  assert.equal(text(view.container, "request-data"), '{"owner":"B"}');
  assert.equal(callbacks, 0);
  assert.equal(localStorage.getItem("kdrive.jwt"), "tab-token-b");
});

for (const clear of [false, true]) test(`external logout/clear becomes guest without server revoke: clear=${clear}`, async () => {
  const view = await mountTab();
  await changeTabToken(null, { clear });
  assert.equal(text(view.container, "identity"), "guest");
  assert.equal(text(view.container, "favorites"), "[]");
  assert.equal(requests.filter(r => r.config.url === "/auth/logout").length, 0);
  await changeTabToken("relogin-token");
  await act(async () => fulfill(takeRequest("/auth/me"), { ...fixtureProfile, id: "relogged" }));
  assert.equal(text(view.container, "identity"), "relogged");
});

test("request before native storage event is refused and cannot send B credential from A UI", async () => {
  const view = await mountTab();
  await changeTabToken("tab-token-b", { event: false });
  await act(async () => {
    await assert.rejects(apiClient.post("/private/action", { oldOwner: view.id }), /Phiên truy cập đã thay đổi/);
  });
  assert.equal(requests.some(r => r.config.url === "/private/action"), false);
  assert.ok(!view.container.textContent.includes(view.id));
  await act(async () => fulfill(takeRequest("/auth/me"), { ...fixtureProfile, id: "pre-event-b" }));
  assert.equal(text(view.container, "identity"), "pre-event-b");
});

test("logout before storage event never removes or revokes newly shared B session", async () => {
  const view = await mountTab(), logout = currentAuth.logout;
  await changeTabToken("tab-token-b", { event: false });
  await act(async () => logout());
  assert.equal(localStorage.getItem("kdrive.jwt"), "tab-token-b");
  assert.equal(requests.some(r => r.config.url === "/auth/logout"), false);
  await act(async () => fulfill(takeRequest("/auth/me"), { ...fixtureProfile, id: "logout-safe-b" }));
  assert.equal(text(view.container, "identity"), "logout-safe-b");
});

for (const action of ["login", "register"]) test(`pending ${action} cannot overwrite external B before its storage event`, async () => {
  const view = await mountTab();
  let pending;
  await act(async () => { pending = currentAuth[action]({ email: "fixture@example.test", password: "Fixture1!" }).catch(error => error); });
  const request = takeRequest(`/auth/${action}`);
  await changeTabToken("tab-token-b", { event: false });
  await act(async () => {
    fulfill(request, { token: "obsolete-login", user: fixtureProfile });
    assert.match((await pending).message, /đã bị thay thế/);
  });
  assert.equal(localStorage.getItem("kdrive.jwt"), "tab-token-b");
  await act(async () => fulfill(takeRequest("/auth/me"), { ...fixtureProfile, id: "pending-b" }));
  assert.equal(text(view.container, "identity"), "pending-b");
});

test("rapid A to B to C ignores intermediate hydration and verifies only final identity", async () => {
  const view = await mountTab();
  await changeTabToken("tab-token-b");
  const b = takeRequest("/auth/me");
  await changeTabToken("tab-token-c");
  const c = takeRequest("/auth/me");
  await act(async () => fulfill(c, { ...fixtureProfile, id: "final-c" }));
  await act(async () => fulfill(b, { ...fixtureProfile, id: "obsolete-b" }));
  assert.equal(text(view.container, "identity"), "final-c");
  assert.equal(localStorage.getItem("kdrive.jwt"), "tab-token-c");
});

test("focus detects missed storage event; unrelated/user/sessionStorage events do not reset identity", async () => {
  const view = await mountTab(), before = requests.length;
  await act(async () => {
    for (const [key, storageArea] of [["other", localStorage], ["kdrive.user", localStorage], ["kdrive.jwt", dom.window.sessionStorage]]) {
      window.dispatchEvent(new window.StorageEvent("storage", { key, storageArea }));
    }
  });
  assert.equal(text(view.container, "identity"), view.id);
  assert.equal(requests.length, before);
  await changeTabToken("focus-b", { event: false });
  await act(async () => window.dispatchEvent(new window.Event("focus")));
  await act(async () => fulfill(takeRequest("/auth/me"), { ...fixtureProfile, id: "focus-b" }));
  assert.equal(text(view.container, "identity"), "focus-b");
});

test("queued logout/relogin event with same final token invalidates data, logout and profile callbacks", async () => {
  const view = await mountTab(), old = takeRequest("/private/tab");
  const oldLogout = currentAuth.logout, oldRefresh = currentAuth.refreshUser;
  await act(async () => window.dispatchEvent(new window.StorageEvent("storage", {
    key: "kdrive.jwt", oldValue: "tab-token-a", newValue: null, storageArea: localStorage,
  })));
  await act(async () => fulfill(takeRequest("/auth/me"), { ...fixtureProfile, id: view.id }));
  await act(async () => fulfill(takeRequest("/private/tab"), { owner: "new-session" }));
  await act(async () => {
    fulfill(old, { owner: "obsolete-session" });
    await oldLogout();
    oldRefresh({ ...fixtureProfile, id: view.id, fullName: "obsolete-profile" });
  });
  assert.equal(text(view.container, "request-data"), '{"owner":"new-session"}');
  assert.equal(currentAuth.user.fullName, fixtureProfile.fullName);
  assert.equal(currentAuth.isAuthenticated, true);
  assert.equal(localStorage.getItem("kdrive.jwt"), "tab-token-a");
  assert.equal(requests.some(r => r.config.url === "/auth/logout"), false);
});

for (const status of [401, 403, 500]) test(`external identity hydration failure cannot restore A or remain in synchronization loop: ${status}`, async () => {
  const view = await mountTab();
  await changeTabToken("invalid-or-offline-b");
  await act(async () => rejectWithStatus(takeRequest("/auth/me"), status));
  assert.ok(!view.container.textContent.includes(view.id));
  assert.ok(!view.container.textContent.includes("Đang xác định phiên truy cập…"));
  assert.equal(currentAuth.isAuthenticated, false);
  assert.equal(currentAuth.loading, false);
  assert.equal(currentAuth.user, null);
  if (status === 500) assert.ok(currentAuth.sessionError);
  else assert.equal(localStorage.getItem("kdrive.jwt"), null);
  assert.equal(requests.filter(r => r.config.url === "/auth/me").length, 2, "no repeated hydration loop");
});

for (const action of ["login", "register"]) test(`late obsolete ${action} leaves verified B and its new draft untouched`, async () => {
  const view = await mountTab();
  let pending;
  await act(async () => { pending = currentAuth[action]({ email: "fixture@example.test", password: "Fixture1!" }).catch(error => error); });
  const request = takeRequest(`/auth/${action}`);
  await changeTabToken("verified-b");
  await act(async () => fulfill(takeRequest("/auth/me"), { ...fixtureProfile, id: "verified-b" }));
  await act(async () => view.container.querySelector('[data-testid="draft-edit"]').click());
  const count = requests.length;
  await act(async () => {
    fulfill(request, { token: "obsolete", user: fixtureProfile });
    assert.match((await pending).message, /đã bị thay thế/);
  });
  assert.equal(text(view.container, "identity"), "verified-b");
  assert.equal(text(view.container, "draft"), "private-A-draft");
  assert.equal(requests.length, count, "obsolete auth response must not restart B hydration");
  assert.equal(localStorage.getItem("kdrive.jwt"), "verified-b");
});
