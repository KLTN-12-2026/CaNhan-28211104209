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
let ApiAccountsPage;
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

function findFormByHeading(container, headingText) {
  const forms = container.querySelectorAll("form");
  for (const form of forms) {
    const heading = form.querySelector("h2");
    if (heading && heading.textContent.includes(headingText)) {
      return form;
    }
  }
  return null;
}

function findFormByButtonText(container, buttonText) {
  const forms = container.querySelectorAll("form");
  for (const form of forms) {
    const btn = form.querySelector("button[type='submit']");
    if (btn && btn.textContent.trim() === buttonText) {
      return form;
    }
  }
  return null;
}

function clickSubmitButton(formElement) {
  const btn = formElement.querySelector("button[type='submit']");
  if (btn) {
    btn.click();
  } else {
    throw new Error("Submit button not found in form");
  }
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
  ({ ApiAccountsPage } = await viteServer.ssrLoadModule("/src/pages/api/ApiManagementScreens.jsx"));
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
// Tiêu chí 1: Mount actual React component ApiAccountsPage vào DOM
// Kiểm tra loading (skeleton), error (notice + nút retry), empty state
// =============================================================================
test("DOM: ApiAccountsPage handles loading, error with retry, and empty state", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  // 1. Loading state: shows skeleton while all three requests pending
  assert.ok(container.querySelector(".animate-pulse"), "Skeleton must be rendered during loading");
  assert.ok(container.querySelector('[aria-busy="true"]'), "Loading region must have aria-busy");

  // Fulfill all three requests with empty data
  const accountsReq = takeRequest("/admin/accounts");
  const branchesReq = takeRequest("/branches");
  const staffBranchesReq = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq, []);
    fulfill(branchesReq, []);
    fulfill(staffBranchesReq, []);
  });

  // 2. Empty state: shows "Chưa có tài khoản"
  assert.ok(container.textContent.includes("Chưa có tài khoản"), "Empty state must be visible");
  assert.equal(container.querySelector(".animate-pulse"), null, "Skeleton must be removed after load");

  // 3. Error state: reject all requests, show error and retry button
  await unmount();

  const { container: errorContainer, unmount: unmountError } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const errAccountsReq = takeRequest("/admin/accounts");
  const errBranchesReq = takeRequest("/branches");
  const errStaffBranchesReq = takeRequest("/admin/staff-branches");

  await act(async () => {
    rejectWithStatus(errAccountsReq, 500, { message: "Lỗi máy chủ" });
    rejectWithStatus(errBranchesReq, 500, { message: "Lỗi máy chủ" });
    rejectWithStatus(errStaffBranchesReq, 500, { message: "Lỗi máy chủ" });
  });

  // Check error notice - getErrorMessage returns server message "Lỗi máy chủ"
  assert.ok(errorContainer.textContent.includes("Lỗi máy chủ"), "Error notice must show server error message");
  const retryBtn = errorContainer.querySelector("button.btn-secondary");
  assert.ok(retryBtn, "Retry button must be rendered on error");
  assert.equal(retryBtn.textContent.trim(), "Thử tải lại");

  // Actively click retry button and verify recovery
  await act(async () => {
    retryBtn.click();
  });

  const retryAccountsReq = takeRequest("/admin/accounts");
  const retryBranchesReq = takeRequest("/branches");
  const retryStaffBranchesReq = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(retryAccountsReq, []);
    fulfill(retryBranchesReq, []);
    fulfill(retryStaffBranchesReq, []);
  });

  assert.ok(!errorContainer.textContent.includes("Lỗi máy chủ"), "Error notice must be cleared after retry");
  assert.ok(errorContainer.textContent.includes("Chưa có tài khoản"), "Empty state rendered after successful retry");

  await unmountError();
});

// =============================================================================
// Tiêu chí 2: Display accounts with status/role badges and assigned branches
// =============================================================================
test("DOM: ApiAccountsPage displays accounts with status, role, and assigned branches including inactive", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const accountsData = [
    { id: "1", fullName: "Staff One", email: "staff1@test", role: "STAFF", status: "ACTIVE", version: 0 },
    { id: "2", fullName: "User One", email: "user1@test", role: "USER", status: "ACTIVE", version: 0 },
    { id: "3", fullName: "Locked Staff", email: "locked@test", role: "STAFF", status: "LOCKED", version: 1 },
  ];
  const branchesData = [
    { id: "hn", name: "Chi nhánh Hà Nội", status: "ACTIVE" },
    { id: "dn", name: "Chi nhánh Đà Nẵng", status: "ACTIVE" },
    { id: "hcm", name: "Chi nhánh HCM", status: "INACTIVE" },
  ];
  const staffBranchesData = [
    { userId: "1", branchId: "hn" },
    { userId: "1", branchId: "hcm" },
    { userId: "3", branchId: "hn" },
  ];

  const accountsReq = takeRequest("/admin/accounts");
  const branchesReq = takeRequest("/branches");
  const staffBranchesReq = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq, accountsData);
    fulfill(branchesReq, branchesData);
    fulfill(staffBranchesReq, staffBranchesData);
  });

  // Check accounts rendered
  assert.ok(container.textContent.includes("Staff One"), "Staff name must be visible");
  assert.ok(container.textContent.includes("staff1@test"), "Staff email must be visible");
  assert.ok(container.textContent.includes("Nhân viên"), "STAFF role badge must be visible");
  assert.ok(container.textContent.includes("Đang hoạt động"), "ACTIVE status badge must be visible");
  assert.ok(container.textContent.includes("User One"), "User name must be visible");
  assert.ok(container.textContent.includes("Khách hàng"), "USER role badge must be visible");
  assert.ok(container.textContent.includes("Locked Staff"), "Locked staff name must be visible");
  assert.ok(container.textContent.includes("Đã khóa"), "LOCKED status badge must be visible");

  // Check assigned branches including inactive
  assert.ok(container.textContent.includes("Chi nhánh Hà Nội"), "Active assigned branch must be visible");
  assert.ok(container.textContent.includes("Chi nhánh HCM (tạm ngừng)"), "Inactive assigned branch must be visible with label");

  await unmount();
});

// =============================================================================
// Tiêu chí 3: Branch dropdown only shows active branches not already assigned
// =============================================================================
test("DOM: ApiAccountsPage branch dropdown filters active unassigned branches", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const accountsData = [
    { id: "1", fullName: "Staff One", email: "staff1@test", role: "STAFF", status: "ACTIVE", version: 0 },
  ];
  const branchesData = [
    { id: "hn", name: "Chi nhánh Hà Nội", status: "ACTIVE" },
    { id: "dn", name: "Chi nhánh Đà Nẵng", status: "ACTIVE" },
    { id: "hcm", name: "Chi nhánh HCM", status: "INACTIVE" },
  ];
  const staffBranchesData = [
    { userId: "1", branchId: "hn" },
  ];

  const accountsReq = takeRequest("/admin/accounts");
  const branchesReq = takeRequest("/branches");
  const staffBranchesReq = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq, accountsData);
    fulfill(branchesReq, branchesData);
    fulfill(staffBranchesReq, staffBranchesData);
  });

  // Find the select for staff 1
  const select = container.querySelector("select");
  assert.ok(select, "Branch select must be rendered for STAFF");

  const options = Array.from(select.querySelectorAll("option"));
  // Only enabled (non-disabled) options should be unassigned active branches
  const enabledOptionValues = options
    .filter(o => !o.disabled)
    .map(o => o.value)
    .filter(v => v);
  assert.deepEqual(enabledOptionValues, ["dn"], "Only unassigned active branches enabled in dropdown");

  // Check already assigned shown as disabled
  const hnOption = options.find(o => o.value === "hn");
  assert.ok(hnOption, "Already assigned branch option must exist");
  assert.ok(hnOption.disabled, "Already assigned branch must be disabled");
  assert.ok(hnOption.textContent.includes("đã gán"), "Already assigned label must be shown");

  // Inactive branch must not be in dropdown
  const hcmOption = options.find(o => o.value === "hcm");
  assert.equal(hcmOption, undefined, "Inactive branch must not be in dropdown");

  await unmount();
});

// =============================================================================
// Tiêu chí 4: Create staff form submits POST and reloads accounts
// =============================================================================
test("DOM: ApiAccountsPage create staff submits POST and shows success", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  // Initial load with empty
  const accountsReq1 = takeRequest("/admin/accounts");
  const branchesReq1 = takeRequest("/branches");
  const staffBranchesReq1 = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq1, []);
    fulfill(branchesReq1, []);
    fulfill(staffBranchesReq1, []);
  });

  assert.ok(container.textContent.includes("Chưa có tài khoản"), "Initial empty state");

  // Find the create staff form by heading
  const createForm = findFormByHeading(container, "Tạo nhân viên");
  assert.ok(createForm, "Create staff form must be found");

  // Fill create form inputs
  const fullNameInput = createForm.querySelector("input[name='fullName']");
  const emailInput = createForm.querySelector("input[name='email']");
  const passwordInput = createForm.querySelector("input[name='password']");

  await act(async () => {
    changeValue(fullNameInput, "New Staff");
    changeValue(emailInput, "new@test");
    changeValue(passwordInput, "password123");
  });

  // Submit form by clicking the submit button
  await act(async () => {
    clickSubmitButton(createForm);
  });

  // Check busy state
  const submitBtn = createForm.querySelector("button.btn[type='submit']");
  assert.ok(submitBtn.disabled, "Submit button must be disabled during submission");
  assert.ok(submitBtn.textContent.includes("Đang tạo"), "Busy text must be shown");

  // Fulfill create POST
  const createReq = takeRequest("/admin/accounts");
  assert.equal(createReq.config.method, "post");
  const createData = JSON.parse(createReq.config.data);
  assert.equal(createData.fullName, "New Staff");
  assert.equal(createData.email, "new@test");
  assert.equal(createData.password, "password123");

  await act(async () => {
    fulfill(createReq, { id: "new-staff", fullName: "New Staff", email: "new@test", role: "STAFF", status: "ACTIVE", version: 0 });
  });

  // Reload requests after success (only accountsRequest.reload() is called)
  const accountsReq2 = takeRequest("/admin/accounts");

  await act(async () => {
    fulfill(accountsReq2, []);
  });

  assert.ok(container.textContent.includes("Đã tạo tài khoản nhân viên."), "Success notice must be visible");

  await unmount();
});

// =============================================================================
// Tiêu chí 5: Status toggle sends PATCH with version for non-admin
// =============================================================================
test("DOM: ApiAccountsPage status toggle sends PATCH with version for STAFF/USER", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const accountsData = [
    { id: "1", fullName: "Staff One", email: "staff1@test", role: "STAFF", status: "ACTIVE", version: 0 },
  ];

  const accountsReq1 = takeRequest("/admin/accounts");
  const branchesReq1 = takeRequest("/branches");
  const staffBranchesReq1 = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq1, accountsData);
    fulfill(branchesReq1, []);
    fulfill(staffBranchesReq1, []);
  });

  // Click lock button
  const lockBtn = [...container.querySelectorAll("button")].find(b => b.textContent.trim() === "Khóa tài khoản");
  assert.ok(lockBtn.textContent.includes("Khóa tài khoản"), "Lock button for ACTIVE staff");

  await act(async () => {
    lockBtn.click();
  });
  assert.ok(container.querySelector('[role="dialog"]'));
  await act(async () => [...container.querySelectorAll("button")].find(b => b.textContent === "Xác nhận").click());

  // Check PATCH request
  const patchReq = takeRequest("/admin/accounts/1");
  assert.equal(patchReq.config.method, "patch");
  const patchData = JSON.parse(patchReq.config.data);
  assert.equal(patchData.status, "LOCKED");
  assert.equal(patchData.version, 0, "Must send current version");

  // Fulfill PATCH
  await act(async () => {
    fulfill(patchReq, { ...accountsData[0], status: "LOCKED", version: 1 });
  });

  // Reload requests (only accountsRequest.reload() is called)
  const accountsReq2 = takeRequest("/admin/accounts");

  await act(async () => {
    fulfill(accountsReq2, [{ ...accountsData[0], status: "LOCKED", version: 1 }]);
  });

  assert.ok(container.textContent.includes("Đã cập nhật trạng thái tài khoản staff1@test."), "Success notice must be visible");

  await unmount();
});

// =============================================================================
// Tiêu chí 6: Admin account cannot be locked
// =============================================================================
test("DOM: ApiAccountsPage admin account shows no lock button", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const accountsData = [
    { id: "admin", fullName: "Admin", email: "admin@test", role: "ADMIN", status: "ACTIVE", version: 0 },
  ];

  const accountsReq = takeRequest("/admin/accounts");
  const branchesReq = takeRequest("/branches");
  const staffBranchesReq = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq, accountsData);
    fulfill(branchesReq, []);
    fulfill(staffBranchesReq, []);
  });

  assert.ok(container.textContent.includes("Không thể khóa tài khoản quản trị."), "Admin lock restriction message");
  assert.equal(container.querySelector("button.btn-secondary"), null, "No lock button for admin");

  await unmount();
});

// =============================================================================
// Tiêu chí 7: Assign branch sends POST and reloads both accounts and staff-branches
// =============================================================================
test("DOM: ApiAccountsPage assign branch sends POST and reloads both requests", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const accountsData = [
    { id: "1", fullName: "Staff One", email: "staff1@test", role: "STAFF", status: "ACTIVE", version: 0 },
  ];
  const branchesData = [
    { id: "hn", name: "Chi nhánh Hà Nội", status: "ACTIVE" },
    { id: "dn", name: "Chi nhánh Đà Nẵng", status: "ACTIVE" },
  ];

  const accountsReq1 = takeRequest("/admin/accounts");
  const branchesReq1 = takeRequest("/branches");
  const staffBranchesReq1 = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq1, accountsData);
    fulfill(branchesReq1, branchesData);
    fulfill(staffBranchesReq1, []);
  });

  // Find the assign branch form by button text
  const assignForm = findFormByButtonText(container, "Gán chi nhánh");
  assert.ok(assignForm, "Assign branch form must be found");

  // Select branch
  const select = assignForm.querySelector("select");
  await act(async () => {
    changeValue(select, "dn");
  });

  // Submit form by clicking submit button
  await act(async () => {
    clickSubmitButton(assignForm);
  });

  // Check POST request
  const assignReq = takeRequest("/admin/staff-branches");
  assert.equal(assignReq.config.method, "post");
  const assignData = JSON.parse(assignReq.config.data);
  assert.equal(assignData.userId, "1");
  assert.equal(assignData.branchId, "dn");

  // Fulfill assignment
  await act(async () => {
    fulfill(assignReq, {});
  });

  // Should reload both accounts and staff-branches
  const accountsReq2 = takeRequest("/admin/accounts");
  const staffBranchesReq2 = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq2, accountsData);
    fulfill(staffBranchesReq2, [{ userId: "1", branchId: "dn" }]);
  });

  assert.ok(container.textContent.includes("Đã gán chi nhánh cho Staff One."), "Success notice must be visible");

  await unmount();
});

// =============================================================================
// Tiêu chí 8: Assignment domain error shows notice, no false success and recovers
// =============================================================================
test("DOM: ApiAccountsPage assignment domain error shows notice, no false success and recovers", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const accountsData = [
    { id: "1", fullName: "Staff One", email: "staff1@test", role: "STAFF", status: "ACTIVE", version: 0 },
  ];
  const branchesData = [
    { id: "hn", name: "Chi nhánh Hà Nội", status: "ACTIVE" },
  ];

  const accountsReq1 = takeRequest("/admin/accounts");
  const branchesReq1 = takeRequest("/branches");
  const staffBranchesReq1 = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq1, accountsData);
    fulfill(branchesReq1, branchesData);
    fulfill(staffBranchesReq1, []);
  });

  // Find assign form
  const assignForm = findFormByButtonText(container, "Gán chi nhánh");
  assert.ok(assignForm, "Assign branch form must be found");

  const select = assignForm.querySelector("select");
  await act(async () => {
    changeValue(select, "hn");
  });

  const assignBtn = assignForm.querySelector("button[type='submit']");

  await act(async () => {
    clickSubmitButton(assignForm);
  });

  // Fulfill with 409 Conflict
  const assignReq = takeRequest("/admin/staff-branches");
  await act(async () => {
    rejectWithStatus(assignReq, 409, { message: "Đã tồn tại phân công này." });
  });

  // Check error shown, no success
  assert.ok(
    container.textContent.includes("Không thể gán chi nhánh") || container.textContent.includes("Đã tồn tại"),
    "Error notice must be visible on 409"
  );
  assert.ok(!container.textContent.includes("Đã gán chi nhánh"), "No false success message on error");

  // Busy state must be restored
  assert.equal(assignBtn.disabled, false, "Assign button must be re-enabled after error");

  await unmount();
});

// =============================================================================
// Tiêu chí 8b: Repeated branch assignment succeeds idempotently per backend contract
// =============================================================================
test("DOM: ApiAccountsPage repeated branch assignment succeeds idempotently per backend contract", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const accountsData = [
    { id: "1", fullName: "Staff One", email: "staff1@test", role: "STAFF", status: "ACTIVE", version: 0 },
  ];
  const branchesData = [
    { id: "hn", name: "Chi nhánh Hà Nội", status: "ACTIVE" },
  ];

  const accountsReq1 = takeRequest("/admin/accounts");
  const branchesReq1 = takeRequest("/branches");
  const staffBranchesReq1 = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq1, accountsData);
    fulfill(branchesReq1, branchesData);
    fulfill(staffBranchesReq1, [{ userId: "1", branchId: "hn" }]);
  });

  const assignForm = findFormByButtonText(container, "Gán chi nhánh");
  assert.ok(assignForm, "Assign branch form must be found");

  const select = assignForm.querySelector("select");
  await act(async () => {
    changeValue(select, "hn");
  });

  await act(async () => {
    clickSubmitButton(assignForm);
  });

  // Backend C8A contract: repeated assignment returns 200 idempotently
  const assignReq = takeRequest("/admin/staff-branches");
  await act(async () => {
    fulfill(assignReq, {});
  });

  const accountsReq2 = takeRequest("/admin/accounts");
  const staffBranchesReq2 = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq2, accountsData);
    fulfill(staffBranchesReq2, [{ userId: "1", branchId: "hn" }]);
  });

  assert.ok(container.textContent.includes("Đã gán chi nhánh cho Staff One."), "Success notice visible on idempotent repeat");

  await unmount();
});

// =============================================================================
// Tiêu chí 9: Branch name mapping uses full branch list (including inactive)
// =============================================================================
test("DOM: ApiAccountsPage branch name mapping uses full branch list", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const accountsData = [
    { id: "1", fullName: "Staff One", email: "staff1@test", role: "STAFF", status: "ACTIVE", version: 0 },
  ];
  const branchesData = [
    { id: "hn", name: "Chi nhánh Hà Nội", status: "ACTIVE" },
    { id: "dn", name: "Chi nhánh Đà Nẵng", status: "INACTIVE" },
  ];
  const staffBranchesData = [
    { userId: "1", branchId: "dn" },
  ];

  const accountsReq = takeRequest("/admin/accounts");
  const branchesReq = takeRequest("/branches");
  const staffBranchesReq = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq, accountsData);
    fulfill(branchesReq, branchesData);
    fulfill(staffBranchesReq, staffBranchesData);
  });

  // Inactive branch name must be resolved from full branch list
  assert.ok(container.textContent.includes("Chi nhánh Đà Nẵng (tạm ngừng)"), "Inactive branch name mapped from full list");

  await unmount();
});

// =============================================================================
// Tiêu chí 10: No per-account GET fanout - only bulk staff-branches GET
// =============================================================================
test("DOM: ApiAccountsPage uses only bulk GET /admin/staff-branches, no per-account requests", async () => {
  const { unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const accountsData = [
    { id: "1", fullName: "Staff One", email: "staff1@test", role: "STAFF", status: "ACTIVE", version: 0 },
    { id: "2", fullName: "Staff Two", email: "staff2@test", role: "STAFF", status: "ACTIVE", version: 0 },
  ];
  const branchesData = [
    { id: "hn", name: "Chi nhánh Hà Nội", status: "ACTIVE" },
    { id: "dn", name: "Chi nhánh Đà Nẵng", status: "ACTIVE" },
  ];
  const staffBranchesData = [
    { userId: "1", branchId: "hn" },
    { userId: "2", branchId: "dn" },
  ];

  const accountsReq = takeRequest("/admin/accounts");
  const branchesReq = takeRequest("/branches");
  const staffBranchesReq = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq, accountsData);
    fulfill(branchesReq, branchesData);
    fulfill(staffBranchesReq, staffBranchesData);
  });

  // Check no per-account requests were made
  const perAccountRequests = requests.filter(r =>
    r.config.url.match(/\/admin\/accounts\/[^/]+$/),
  );
  assert.equal(perAccountRequests.length, 0, "No per-account GET requests must be made");

  // Check only one bulk staff-branches GET
  const bulkRequests = requests.filter(r => r.config.url === "/admin/staff-branches" && r.config.method?.toLowerCase() === "get");
  assert.equal(bulkRequests.length, 1, "Exactly one bulk staff-branches GET");

  await unmount();
});

// =============================================================================
// Tiêu chí 11: addStaff synchronous pending guard holds double dispatch & recovers
// =============================================================================
test("DOM: ApiAccountsPage addStaff synchronous pending guard holds double dispatch and recovers after error", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const accountsReq1 = takeRequest("/admin/accounts");
  const branchesReq1 = takeRequest("/branches");
  const staffBranchesReq1 = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq1, []);
    fulfill(branchesReq1, []);
    fulfill(staffBranchesReq1, []);
  });

  const createForm = findFormByHeading(container, "Tạo nhân viên");
  const fullNameInput = createForm.querySelector("input[name='fullName']");
  const emailInput = createForm.querySelector("input[name='email']");
  const passwordInput = createForm.querySelector("input[name='password']");

  await act(async () => {
    changeValue(fullNameInput, "Guard Staff");
    changeValue(emailInput, "guard@test");
    changeValue(passwordInput, "password123");
  });

  // Synchronous double submit in same turn
  await act(async () => {
    clickSubmitButton(createForm);
    clickSubmitButton(createForm);
  });

  // Assert exactly 1 POST request was issued (synchronous guard held the second)
  const matchingRequests = requests.filter(r => r.config.url === "/admin/accounts" && r.config.method?.toLowerCase() === "post");
  assert.equal(matchingRequests.length, 1, "Exactly one POST request issued despite double submit");

  const createReq = takeRequest("/admin/accounts");
  const submitBtn = createForm.querySelector("button.btn[type='submit']");
  assert.ok(submitBtn.disabled, "Submit button disabled while request held");

  // Injected error: reject with 400
  await act(async () => {
    rejectWithStatus(createReq, 400, { message: "Email không hợp lệ" });
  });

  assert.ok(container.textContent.includes("Email không hợp lệ"), "Error notice displayed");
  assert.equal(submitBtn.disabled, false, "Submit button re-enabled after failure (safe finally release)");

  // Retry after error: should allow a fresh submission
  await act(async () => {
    clickSubmitButton(createForm);
  });

  const createReq2 = takeRequest("/admin/accounts");
  await act(async () => {
    fulfill(createReq2, { id: "new-staff", email: "guard@test" });
  });

  const accountsReloadReq = takeRequest("/admin/accounts");
  await act(async () => {
    fulfill(accountsReloadReq, [{ id: "new-staff", fullName: "Guard Staff", email: "guard@test", role: "STAFF", status: "ACTIVE", version: 0 }]);
  });

  assert.ok(container.textContent.includes("Đã tạo tài khoản nhân viên."), "Success notice on retry submission");

  await unmount();
});

// =============================================================================
// Tiêu chí 12: changeStatus synchronous pending guard holds double dispatch & handles stale 409
// =============================================================================
test("DOM: ApiAccountsPage changeStatus synchronous pending guard holds double dispatch and handles stale 409 conflict", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const accountsData = [
    { id: "staff-1", fullName: "Staff One", email: "staff1@test", role: "STAFF", status: "ACTIVE", version: 0 },
  ];

  const accountsReq1 = takeRequest("/admin/accounts");
  const branchesReq1 = takeRequest("/branches");
  const staffBranchesReq1 = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq1, accountsData);
    fulfill(branchesReq1, []);
    fulfill(staffBranchesReq1, []);
  });

  const lockBtn = [...container.querySelectorAll("button")].find(b => b.textContent.trim() === "Khóa tài khoản");
  assert.equal(lockBtn.textContent.trim(), "Khóa tài khoản");

  // Synchronous double click
  await act(async () => {
    lockBtn.click();
    lockBtn.click();
  });
  assert.ok(container.querySelector('[role="dialog"]'));
  await act(async () => [...container.querySelectorAll("button")].find(b => b.textContent === "Xác nhận").click());

  const patchRequests = requests.filter(r => r.config.url.startsWith("/admin/accounts/") && r.config.method?.toLowerCase() === "patch");
  assert.equal(patchRequests.length, 1, "Exactly one PATCH request issued despite double click");

  const patchReq = takeRequest("/admin/accounts/staff-1");
  assert.ok(lockBtn.disabled, "Lock button disabled while PATCH held");

  // Reject with stale 409 conflict
  await act(async () => {
    rejectWithStatus(patchReq, 409, { message: "Dữ liệu đã bị thay đổi bởi phiên khác." });
  });

  assert.ok(
    container.textContent.includes("Không thể cập nhật tài khoản") || container.textContent.includes("thay đổi"),
    "Stale conflict error notice displayed"
  );
  assert.equal(lockBtn.disabled, false, "Lock button re-enabled after 409 conflict");

  await unmount();
});

// =============================================================================
// Tiêu chí 13: assignBranch synchronous pending guard holds double dispatch
// =============================================================================
test("DOM: ApiAccountsPage assignBranch synchronous pending guard holds double dispatch", async () => {
  const { container, unmount } = await mount(
    createElement(
      AuthProvider,
      null,
      createElement(ApiAccountsPage, null),
    ),
  );

  const accountsData = [
    { id: "staff-1", fullName: "Staff One", email: "staff1@test", role: "STAFF", status: "ACTIVE", version: 0 },
  ];
  const branchesData = [
    { id: "hn", name: "Chi nhánh Hà Nội", status: "ACTIVE" },
  ];

  const accountsReq1 = takeRequest("/admin/accounts");
  const branchesReq1 = takeRequest("/branches");
  const staffBranchesReq1 = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq1, accountsData);
    fulfill(branchesReq1, branchesData);
    fulfill(staffBranchesReq1, []);
  });

  const assignForm = findFormByButtonText(container, "Gán chi nhánh");
  const select = assignForm.querySelector("select");
  await act(async () => {
    changeValue(select, "hn");
  });

  // Synchronous double submit
  await act(async () => {
    clickSubmitButton(assignForm);
    clickSubmitButton(assignForm);
  });

  const postRequests = requests.filter(r => r.config.url === "/admin/staff-branches" && r.config.method?.toLowerCase() === "post");
  assert.equal(postRequests.length, 1, "Exactly one assign POST request issued despite double submit");

  const assignReq = takeRequest("/admin/staff-branches");
  await act(async () => {
    fulfill(assignReq, {});
  });

  const accountsReq2 = takeRequest("/admin/accounts");
  const staffBranchesReq2 = takeRequest("/admin/staff-branches");

  await act(async () => {
    fulfill(accountsReq2, accountsData);
    fulfill(staffBranchesReq2, [{ userId: "staff-1", branchId: "hn" }]);
  });

  assert.ok(container.textContent.includes("Đã gán chi nhánh cho Staff One."), "Success notice visible");

  await unmount();
});