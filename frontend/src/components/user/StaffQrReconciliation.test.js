import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { fileURLToPath, URL } from 'node:url';
import { act, createElement as h } from 'react';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

let dom, vite, createRoot, Deposit, Router, client, handler;
const roots = [];
const globals = new Map();
const requests = [];
const booking = {
  id: 'staff-qr-detail',
  status: 'PENDING',
  version: 4,
  total: 2500000,
  startAt: '2026-10-25T03:00:00Z',
  expiresAt: '2026-10-24T05:00:00Z',
  vehicleName: 'Xe fixture',
  customerName: 'Khách fixture',
  pickupBranchId: 'branch-pickup',
  deposit: {
    required: true,
    amount: 600000,
    method: 'DEMO_QR_MANUAL',
    approvedAt: '2026-10-24T03:00:00Z',
    paymentDueAt: '2026-10-24T03:30:00Z',
    reviewDueAt: '2026-10-24T04:00:00Z',
    originalHoldUntil: '2026-10-24T05:00:00Z',
    reportedAt: '2026-10-24T03:10:00Z',
    appliedAmount: 0,
    receivedAmount: 0,
    missingAmount: 600000,
    excessAmount: 0,
  },
};
const summary = {
  checkedAt: '2026-10-24T03:20:00Z',
  reviewDueAt: '2026-10-24T04:00:00Z',
  stage: 'RECEIVED_INCOMPLETE',
  urgency: 'DUE_SOON',
  receivedAmount: 750000,
  appliedAmount: 250000,
  eligibleAmount: 0,
  shortfallAmount: 600000,
  processingIssue: 'RETRY_REQUIRED',
  timingIssues: ['TRANSFER_LATE', 'VERIFICATION_LATE'],
};
let container;

before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost' });
  for (const [key, value] of Object.entries({
    window: dom.window,
    document: dom.window.document,
    navigator: dom.window.navigator,
    localStorage: dom.window.localStorage,
    sessionStorage: dom.window.sessionStorage,
    HTMLElement: dom.window.HTMLElement,
    Node: dom.window.Node,
    Event: dom.window.Event,
    IS_REACT_ACT_ENVIRONMENT: true,
  })) {
    globals.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  createRoot = (await import('react-dom/client')).createRoot;
  const root = fileURLToPath(new URL('../../..', import.meta.url));
  vite = await createServer({
    root,
    server: { middlewareMode: true, hmr: false },
    appType: 'custom',
    logLevel: 'error',
  });
  ({ default: Deposit } = await vite.ssrLoadModule('/src/components/user/BookingDeposit.jsx'));
  ({ MemoryRouter: Router } = await vite.ssrLoadModule('react-router-dom'));
  ({ default: client } = await vite.ssrLoadModule('/src/api/client.js'));
  handler = async config => ({
    config,
    data: { payments: [], refunds: [], events: [], qrReconciliation: summary },
    status: 200,
    statusText: 'OK',
    headers: {},
  });
  client.defaults.adapter = async config => {
    requests.push(config);
    return handler(config);
  };
});

afterEach(async () => {
  for (const root of roots.splice(0)) await act(async () => root.unmount());
  document.body.replaceChildren();
  requests.length = 0;
});

after(async () => {
  await vite?.close();
  dom?.window.close();
  for (const [key, descriptor] of globals) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor);
    else delete globalThis[key];
  }
});

async function mount() {
  container = document.createElement('div');
  document.body.append(container);
  const root = createRoot(container);
  roots.push(root);
  await act(async () => root.render(h(Router, null,
    h(Deposit, { booking, role: 'STAFF', assignedPickup: true, reload() {}, now: Date.parse('2026-10-24T03:20:00Z') }),
  )));
  return container;
}

const settle = () => Promise.resolve();

test('Staff detail loads the backend projection and keeps the summary read-only', async () => {
  let resolveGet;
  handler = config => new Promise(resolve => { resolveGet = data => resolve({ config, data, status: 200, statusText: 'OK', headers: {} }); });
  const view = await mount();
  assert.match(view.textContent, /Đang tải tình trạng thu\/hoàn/);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].method, 'get');
  await act(async () => resolveGet({ payments: [], refunds: [], events: [], qrReconciliation: summary }));
  const panel = view.querySelector('[aria-label="Chi tiết đối soát cọc QR"]');
  assert.ok(panel);
  assert.match(panel.textContent, /Tổng đã nhận \(lịch sử\).*750\.000/);
  assert.match(panel.textContent, /Đã áp dụng vào đơn.*250\.000/);
  assert.match(panel.textContent, /Cần đối soát khoản nhận/);
  assert.doesNotMatch(panel.textContent, /Còn thiếu|Cần chuyển thêm/);
  assert.equal(requests.length, 1);
});

test('Staff detail reports GET failure and explicit retry loads fresh projection only', async () => {
  let attempts = 0;
  handler = async config => {
    attempts += 1;
    if (attempts === 1) throw Object.assign(new Error('synthetic GET unavailable'), { config });
    return { config, data: { payments: [], refunds: [], events: [], qrReconciliation: summary }, status: 200, statusText: 'OK', headers: {} };
  };
  const view = await mount();
  await act(async () => settle());
  assert.ok(view.querySelector('[role="alert"]'));
  assert.match(view.textContent, /synthetic GET unavailable/);
  const fallbackPanel = view.querySelector('[aria-label="Chi tiết đối soát cọc QR"]');
  assert.ok(fallbackPanel);
  assert.match(fallbackPanel.textContent, /Chưa có thông tin đối soát/);
  assert.doesNotMatch(fallbackPanel.textContent, /Chưa chuyển cọc|Chưa thanh toán/);
  const retry = [...view.querySelectorAll('button')].find(button => button.textContent === 'Tải lại');
  assert.ok(retry);
  await act(async () => retry.click());
  await act(async () => settle());
  assert.match(view.querySelector('[aria-label="Chi tiết đối soát cọc QR"]').textContent, /Cần đối soát khoản nhận/);
  assert.equal(attempts, 2);
  assert.ok(requests.every(config => config.method === 'get'));
});