import assert from 'node:assert/strict';
import { before, after, test } from 'node:test';
import { fileURLToPath, URL } from 'node:url';
import { act, createElement } from 'react';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

// Hành vi USER của BookingDeposit trên DOM thật (jsdom); chỉ thay adapter HTTP của axios, không mock component.
let dom, vite, Deposit, client, createRoot, root, container, handler, requests, clipboardWrites, clipboardMode, objectUrls, revoked;
const globals = new Map();

const deposit = (extra = {}) => ({
  required: true, method: 'DEMO_QR_MANUAL', amount: 600000, mode: 'FULL',
  approvedAt: '2026-10-24T03:00:00Z', paymentDueAt: '2026-10-24T03:30:00Z', reviewDueAt: '2026-10-24T04:00:00Z',
  originalHoldUntil: '2026-10-24T05:00:00Z', reportedAt: null, appliedAmount: 0, receivedAmount: 0, missingAmount: 600000, excessAmount: 0, ...extra,
});
const summary = (stage, extra = {}) => ({ stage, urgency: 'NORMAL', receivedAmount: 0, appliedAmount: 0, eligibleAmount: 0, shortfallAmount: 600000, reviewDueAt: '2026-10-24T04:00:00Z', processingIssue: 'NONE', timingIssues: [], checkedAt: '2026-10-24T03:05:00Z', ...extra });
const makeBooking = (id = 'booking-a', stage = 'AWAITING_TRANSFER', depositExtra = {}) => ({ id, status: 'PENDING', total: 2500000, version: 3, pickupBranchId: 'dn', deposit: deposit(depositExtra), qrReconciliation: summary(stage) });
const payload = id => ({ payload: `KDRIVE_DEMO|REQ-${id}|600000`, requestCode: `REQ-${id}`, amount: 600000, receiverSnapshot: 'KDRIVE DEMO · 000-DEMO', paymentDueAt: '2026-10-24T03:30:00Z', reviewDueAt: '2026-10-24T04:00:00Z', originalHoldUntil: '2026-10-24T05:00:00Z', warning: 'Mô phỏng — Không chuyển tiền thật' });

function httpError(status, message = 'Lỗi thử nghiệm') { const e = Error('fixture error'); if (status) e.response = { status, data: { message } }; return e; }
const idOf = url => decodeURIComponent(url.split('/')[2]);

function defaultHandler(config) {
  const id = idOf(config.url);
  if (config.url.endsWith('/deposit')) return { deposit: deposit(), payments: [], refunds: [], events: [], qrReconciliation: summary('AWAITING_TRANSFER') };
  if (config.url.endsWith('/deposit/qr-payload')) return payload(id);
  if (config.url.endsWith('/deposit/qr')) return new dom.window.Blob(['png'], { type: 'image/png' });
  if (config.url.endsWith('/deposit/report')) return { id, status: 'PENDING' };
  throw httpError(404);
}

before(async () => {
  dom = new JSDOM('<!doctype html><body></body>', { url: 'http://localhost' });
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator, HTMLElement: dom.window.HTMLElement, localStorage: dom.window.localStorage, sessionStorage: dom.window.sessionStorage, Node: dom.window.Node, Event: dom.window.Event, IS_REACT_ACT_ENVIRONMENT: true })) {
    globals.set(key, Object.getOwnPropertyDescriptor(globalThis, key)); Object.defineProperty(globalThis, key, { value, configurable: true, writable: true });
  }
  Object.defineProperty(dom.window.navigator, 'clipboard', { configurable: true, value: { writeText: async text => { clipboardWrites.push(text); if (clipboardMode === 'fail') throw Error('denied'); } } });
  dom.window.URL.createObjectURL = () => { const url = `blob:fixture-${objectUrls.length + 1}`; objectUrls.push(url); return url; };
  dom.window.URL.revokeObjectURL = url => { revoked.push(url); };
  ({ createRoot } = await import('react-dom/client'));
  const dir = fileURLToPath(new URL('../../..', import.meta.url));
  vite = await createServer({ root: dir, configFile: `${dir}/vite.config.js`, appType: 'custom', logLevel: 'error', optimizeDeps: { noDiscovery: true }, server: { middlewareMode: true } });
  ({ default: Deposit } = await vite.ssrLoadModule('/src/components/user/BookingDeposit.jsx'));
  ({ default: client } = await vite.ssrLoadModule('/src/api/client.js'));
});
after(async () => {
  if (root) await act(async () => root.unmount()); await vite?.close();
  for (const [key, value] of globals) { if (value) Object.defineProperty(globalThis, key, value); else delete globalThis[key]; }
  dom?.window.close();
});

async function settle() { for (let i = 0; i < 5; i++) await act(async () => { await new Promise(r => setTimeout(r, 0)); }); }
async function mount(booking = makeBooking(), custom, role = 'USER') {
  if (root) await act(async () => root.unmount());
  document.body.replaceChildren(); requests = []; clipboardWrites = []; clipboardMode = 'ok'; objectUrls = []; revoked = [];
  handler = custom || defaultHandler;
  client.defaults.adapter = async config => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
    requests.push({ method: config.method, url: config.url, body, responseType: config.responseType });
    return { config, data: await handler({ ...config, body }), status: 200, statusText: 'OK', headers: {} };
  };
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
  await render(booking, role);
}
async function render(booking, role = 'USER') {
  await act(async () => root.render(createElement(Deposit, { key: booking.id, booking, role, assignedPickup: false, reload: () => {} })));
  await settle();
}
const text = () => container.textContent.replaceAll(' ', ' ');

test('G3.1 USER ledger row shows verification time but never the verifier identity', async () => {
  const ledger = { deposit: deposit(), refunds: [], events: [], qrReconciliation: summary('AWAITING_APPLICATION', { receivedAmount: 600000, eligibleAmount: 600000, shortfallAmount: 0 }),
    payments: [{ id: 'payment-0001-fixture', amount: 600000, status: 'SUCCEEDED', appliedAmount: 0, verifierId: 'staff-secret-id', verifiedAt: '2026-10-24T03:20:00Z', transferTime: '2026-10-24T03:10:00Z' }] };
  const custom = c => c.url.endsWith('/deposit') ? ledger : defaultHandler(c);
  await mount(makeBooking('booking-a', 'AWAITING_APPLICATION'), custom);
  assert.match(text(), /Đã xác minh lúc 10:20 24\/10\/2026/);
  assert.doesNotMatch(text(), /staff-secret-id|Xác minh bởi/);
  assert.match(text(), /Tình trạng cọc: Đã nhận cọc — chờ hệ thống xác nhận đơn/);
  await mount(makeBooking('booking-a', 'AWAITING_APPLICATION'), custom, 'ADMIN');
  assert.match(text(), /Xác minh bởi: staff-secret-id/);
});
