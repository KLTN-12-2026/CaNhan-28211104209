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

function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
const button = name => [...container.querySelectorAll('button')].find(e => e.textContent.trim() === name);
async function click(name) { const b = button(name); assert.ok(b, `missing button ${name}`); await act(async () => b.click()); await settle(); }
const qrRequests = () => requests.filter(r => /\/deposit\/qr(-payload)?$/.test(r.url));

test('G3.2 QR block shows amount, request code, VN deadline, DEMO warning and next step; image uses authenticated blob', async () => {
  await mount();
  await click('Hiển thị mã QR cọc');
  const qr = container.querySelector('[aria-label="Mã QR cọc DEMO"]');
  assert.ok(qr, 'QR block rendered');
  const t = qr.textContent.replaceAll(' ', ' ');
  assert.match(t, /Mã QR DEMO — không chuyển tiền thật/);
  assert.match(t, /Số tiền cọc600\.000 ₫/);
  assert.match(t, /Nội dung chuyển khoản \(mã yêu cầu\)REQ-booking-a/);
  assert.match(t, /Hạn đóng cọc \(giờ Việt Nam\)10:30 24\/10\/2026/);
  assert.match(t, /Hạn nhân viên đối soát \(giờ Việt Nam\)11:00 24\/10\/2026/);
  assert.match(t, /không kéo dài thời gian chuyển khoản/);
  assert.match(t, /Bước tiếp theo: chuyển đúng số tiền và nội dung trước hạn đóng cọc, rồi gửi báo đã chuyển khoản/);
  assert.equal(qr.querySelector('img').getAttribute('src'), 'blob:fixture-1');
  const image = requests.find(r => r.url.endsWith('/deposit/qr'));
  assert.equal(image.responseType, 'blob');
  assert.doesNotMatch(image.url, /token|Bearer/i);
  assert.ok(button('Gửi báo đã chuyển khoản'));
  await click('Ẩn mã QR');
  assert.ok(!container.querySelector('[aria-label="Mã QR cọc DEMO"]'));
});

test('G3.2 loading locks the QR button and repeated clicks send no extra GET', async () => {
  const gate = deferred();
  await mount(undefined, async c => { if (c.url.endsWith('/deposit/qr-payload')) { await gate.promise; } return defaultHandler(c); });
  await click('Hiển thị mã QR cọc');
  const loading = button('Đang tải mã QR…');
  assert.ok(loading); assert.equal(loading.disabled, true);
  await act(async () => loading.click()); await settle();
  assert.equal(qrRequests().length, 1);
  gate.resolve(); await settle();
  assert.equal(qrRequests().length, 2);
  assert.ok(container.querySelector('[aria-label="Mã QR cọc DEMO"]'));
});

test('G3.2 payload or image failure shows a neutral retry without stale QR, and retry succeeds', async () => {
  for (const failing of ['/deposit/qr-payload', '/deposit/qr']) {
    let fail = true;
    await mount(undefined, c => { if (fail && c.url.endsWith(failing)) { const e = Error('Network Error'); e.code = 'ERR_NETWORK'; throw e; } return defaultHandler(c); });
    await click('Hiển thị mã QR cọc');
    assert.match(text(), /Không tải được mã QR\. Kiểm tra kết nối rồi thử tải lại/, failing);
    assert.doesNotMatch(text(), /Network Error|ERR_NETWORK|REQ-booking-a/, failing);
    assert.ok(!container.querySelector('img'));
    fail = false;
    await click('Thử tải lại mã QR');
    assert.ok(container.querySelector('[aria-label="Mã QR cọc DEMO"]'), failing);
    assert.doesNotMatch(text(), /Không tải được mã QR/);
  }
  await mount(undefined, c => { if (c.url.endsWith('/deposit/qr-payload')) throw Object.assign(Error('x'), { response: { status: 403, data: { message: 'internal detail' } } }); return defaultHandler(c); });
  await click('Hiển thị mã QR cọc');
  assert.match(text(), /Không mở được mã QR của đơn này/);
  assert.doesNotMatch(text(), /internal detail/);
  await mount(undefined, c => { if (c.url.endsWith('/deposit/qr-payload')) throw Object.assign(Error('x'), { response: { status: 409, data: { message: 'Đơn chưa được duyệt hồ sơ.' } } }); return defaultHandler(c); });
  await click('Hiển thị mã QR cọc');
  assert.match(text(), /Đơn chưa được duyệt hồ sơ\. Tải lại đơn/);
});

test('G3.2 invalid or mismatched payload never renders a QR or fetches the image', async () => {
  for (const bad of [{}, { ...payload('booking-a'), requestCode: '' }, { ...payload('booking-a'), amount: '600000' }, { ...payload('booking-a'), amount: 590000 }]) {
    await mount(undefined, c => c.url.endsWith('/deposit/qr-payload') ? bad : defaultHandler(c));
    await click('Hiển thị mã QR cọc');
    assert.ok(!container.querySelector('[aria-label="Mã QR cọc DEMO"]'));
    assert.equal(requests.filter(r => r.url.endsWith('/deposit/qr')).length, 0);
    assert.match(text(), bad.amount === 590000 ? /khác số cọc của đơn đang xem/ : /Dữ liệu mã QR không hợp lệ/);
  }
});

test('G3.2 copy buttons give feedback and a manual fallback when clipboard fails', async () => {
  await mount();
  await click('Hiển thị mã QR cọc');
  await click('Sao chép nội dung chuyển khoản');
  assert.deepEqual(clipboardWrites, ['REQ-booking-a']);
  assert.equal(container.querySelector('[role="status"]').textContent, 'Đã sao chép nội dung chuyển khoản.');
  clipboardMode = 'fail';
  await click('Sao chép số tiền');
  assert.equal(clipboardWrites[1], '600000');
  assert.equal(container.querySelector('[role="status"]').textContent, 'Không sao chép được số tiền. Hãy chép thủ công: 600000');
  const saved = Object.getOwnPropertyDescriptor(dom.window.navigator, 'clipboard');
  Object.defineProperty(dom.window.navigator, 'clipboard', { configurable: true, value: undefined });
  try {
    await click('Sao chép nội dung chuyển khoản');
    assert.match(container.querySelector('[role="status"]').textContent, /Hãy chép thủ công: REQ-booking-a/);
  } finally { Object.defineProperty(dom.window.navigator, 'clipboard', saved); }
});

test('G3.2 a late QR response for booking A never appears on booking B and its object URL is released', async () => {
  const gate = deferred();
  await mount(makeBooking('booking-a'), async c => { if (idOf(c.url) === 'booking-a' && c.url.endsWith('/deposit/qr')) await gate.promise; return defaultHandler(c); });
  await click('Hiển thị mã QR cọc');
  // Cùng instance nhận prop đơn B (không đổi key) trong khi ảnh của A chưa trả về.
  // Giữ key của lần mount (booking-a) để React tái dùng instance thay vì remount.
  await act(async () => root.render(createElement(Deposit, { key: 'booking-a', booking: makeBooking('booking-b'), role: 'USER', assignedPickup: false, reload: () => {} })));
  await settle();
  gate.resolve(); await settle();
  assert.equal(requests.filter(r => r.url === '/bookings/booking-a/deposit/qr').length, 1, 'image of A was requested and resolved');
  assert.doesNotMatch(text(), /REQ-booking-a/);
  await click('Hiển thị mã QR cọc');
  assert.match(text(), /REQ-booking-b/);
  assert.doesNotMatch(text(), /REQ-booking-a/);
  await act(async () => root.unmount()); root = null;
  assert.ok(objectUrls.length >= 1);
  assert.ok(revoked.includes(objectUrls.at(-1)), 'last object URL revoked on unmount');
});

test('G3.2 no QR request for terminal, unsupported stage or VNPAY bookings', async () => {
  const vnpay = { ...makeBooking('booking-a'), deposit: deposit({ method: 'VNPAY_SANDBOX' }), qrReconciliation: undefined };
  for (const booking of [makeBooking('booking-a', 'EXPIRED'), makeBooking('booking-a', 'FUTURE_STAGE'), vnpay]) {
    // GET /deposit trả cùng summary với đơn (component ưu tiên summary từ GET này).
    await mount(booking, c => c.url.endsWith('/deposit') ? { deposit: booking.deposit, payments: [], refunds: [], events: [], ...(booking.qrReconciliation ? { qrReconciliation: booking.qrReconciliation } : {}) } : defaultHandler(c));
    assert.ok(!button('Hiển thị mã QR cọc'), booking.qrReconciliation?.stage || 'VNPAY');
    assert.equal(qrRequests().length, 0);
  }
});

const reportPosts = () => requests.filter(r => r.method === 'post' && r.url.endsWith('/deposit/report'));
const reportStatus = () => container.querySelector('[aria-label="Kết quả báo đã chuyển khoản"]')?.textContent.replaceAll(' ', ' ') || '';
const networkError = () => Object.assign(Error('Network Error'), { code: 'ERR_NETWORK' });

test('G3.3 report sends exactly one POST with the stable key even under repeated clicks', async () => {
  const gate = deferred();
  await mount(undefined, async c => { if (c.url.endsWith('/deposit/report')) { await gate.promise; return { id: 'booking-a', deposit: deposit({ reportedAt: '2026-10-24T03:12:00Z' }) }; } return defaultHandler(c); });
  await click('Hiển thị mã QR cọc');
  const send = button('Gửi báo đã chuyển khoản');
  await act(async () => { send.click(); send.click(); }); await settle();
  assert.ok(!button('Gửi báo đã chuyển khoản'), 'send hidden while sending');
  assert.match(reportStatus(), /Đang gửi báo đã chuyển khoản/);
  gate.resolve(); await settle();
  assert.equal(reportPosts().length, 1);
  assert.deepEqual(reportPosts()[0].body, { idempotencyKey: 'report-booking-a' });
});

test('G3.3 acknowledged POST stays reported even when reload GET fails, and never offers a new send', async () => {
  let reloadsCalled = 0;
  const custom = c => {
    if (c.url.endsWith('/deposit/report')) return { id: 'booking-a', deposit: deposit({ reportedAt: '2026-10-24T03:12:00Z' }) };
    if (c.url.endsWith('/deposit') && reportPosts().length) throw networkError();
    return defaultHandler(c);
  };
  await mount(undefined, custom);
  const parentReload = () => { reloadsCalled++; };
  await act(async () => root.render(createElement(Deposit, { key: 'booking-a', booking: makeBooking(), role: 'USER', assignedPickup: false, reload: parentReload })));
  await click('Hiển thị mã QR cọc');
  await click('Gửi báo đã chuyển khoản');
  assert.equal(reloadsCalled, 1, 'parent reload requested once');
  assert.match(reportStatus(), /Máy chủ đã nhận báo đã chuyển khoản lúc 10:12 24\/10\/2026/);
  assert.match(reportStatus(), /không cần gửi lại/);
  assert.match(text(), /Bạn đã báo chuyển khoản lúc 10:12 24\/10\/2026/);
  assert.ok(!button('Gửi báo đã chuyển khoản'));
  await click('Hiển thị mã QR cọc');
  assert.ok(!button('Gửi báo đã chuyển khoản'), 'reopened QR still shows reported');
  assert.match(text(), /Đã gửi báo; chưa xác nhận tiền/);
  assert.equal(reportPosts().length, 1);
});

test('G3.3 uncertain POST blocks blind resend until the booking is reloaded, then resend reuses the same key', async () => {
  for (const failure of [networkError(), Object.assign(Error('timeout'), { code: 'ECONNABORTED' }), Object.assign(Error('x'), { response: { status: 502, data: {} } })]) {
    let first = true;
    await mount(undefined, c => { if (c.url.endsWith('/deposit/report') && first) { first = false; throw failure; } if (c.url.endsWith('/deposit/report')) return { id: 'booking-a', deposit: deposit({ reportedAt: '2026-10-24T03:15:00Z' }) }; return defaultHandler(c); });
    await click('Hiển thị mã QR cọc');
    await click('Gửi báo đã chuyển khoản');
    assert.match(reportStatus(), /Chưa rõ báo đã chuyển khoản đã được ghi nhận hay chưa/);
    assert.doesNotMatch(text(), /Network Error|timeout|502/);
    assert.ok(!button('Gửi báo đã chuyển khoản'), 'no blind resend');
    const before = requests.length;
    await click('Kiểm tra lại tình trạng báo');
    assert.ok(requests.slice(before).every(r => r.method === 'get'), 'recheck only GETs');
    assert.ok(!button('Gửi báo đã chuyển khoản'), 'still blocked until booking prop is refreshed');
    // Đơn tải lại (object mới) vẫn chưa có reportedAt → cho gửi lại, cùng khóa.
    await render(makeBooking());
    assert.match(reportStatus(), /chưa ghi nhận báo của bạn\. Có thể gửi lại; báo lặp không kéo dài thời hạn/);
    await click('Gửi báo đã chuyển khoản');
    assert.equal(reportPosts().length, 2);
    assert.deepEqual(reportPosts().map(r => r.body.idempotencyKey), ['report-booking-a', 'report-booking-a']);
  }
});

test('G3.3 uncertain POST resolved by reload showing backend reportedAt needs no resend', async () => {
  await mount(undefined, c => { if (c.url.endsWith('/deposit/report')) throw networkError(); return defaultHandler(c); });
  await click('Hiển thị mã QR cọc');
  await click('Gửi báo đã chuyển khoản');
  await render(makeBooking('booking-a', 'REPORTED_NO_RECEIPT', { reportedAt: '2026-10-24T03:12:00Z' }));
  assert.equal(reportStatus(), '');
  assert.match(text(), /Tình trạng cọc: Chờ bạn chuyển cọc|Đã báo chuyển/);
  assert.match(text(), /Bạn đã báo chuyển khoản lúc 10:12 24\/10\/2026/);
  assert.equal(reportPosts().length, 1);
});

test('G3.3 business rejection is shown as a definite failure, not as uncertain', async () => {
  await mount(undefined, c => { if (c.url.endsWith('/deposit/report')) throw Object.assign(Error('x'), { response: { status: 409, data: { message: 'Đã hết hạn báo chuyển khoản.' } } }); return defaultHandler(c); });
  await click('Hiển thị mã QR cọc');
  await click('Gửi báo đã chuyển khoản');
  assert.match(reportStatus(), /Đã hết hạn báo chuyển khoản\. Tải lại đơn để xem trạng thái mới nhất/);
  assert.doesNotMatch(reportStatus(), /Chưa rõ/);
  await mount(undefined, c => { if (c.url.endsWith('/deposit/report')) throw Object.assign(Error('x'), { response: { status: 404, data: { message: 'NotFound internal' } } }); return defaultHandler(c); });
  await click('Hiển thị mã QR cọc');
  await click('Gửi báo đã chuyển khoản');
  assert.match(reportStatus(), /Không gửi được báo cho đơn này/);
  assert.doesNotMatch(reportStatus(), /internal/);
});

test('G3.3 a late report response for booking A does not change booking B or trigger reloads', async () => {
  const gate = deferred();
  let reloadsCalled = 0;
  await mount(undefined, async c => { if (c.url.endsWith('/deposit/report')) { await gate.promise; return { id: 'booking-a', deposit: deposit({ reportedAt: '2026-10-24T03:12:00Z' }) }; } return defaultHandler(c); });
  await click('Hiển thị mã QR cọc');
  await click('Gửi báo đã chuyển khoản');
  await act(async () => root.render(createElement(Deposit, { key: 'booking-a', booking: makeBooking('booking-b'), role: 'USER', assignedPickup: false, reload: () => { reloadsCalled++; } })));
  await settle();
  const getsBefore = requests.filter(r => r.method === 'get').length;
  gate.resolve(); await settle();
  assert.equal(reloadsCalled, 0);
  assert.equal(requests.filter(r => r.method === 'get').length, getsBefore, 'no reload GET for B from A response');
  assert.equal(reportStatus(), '');
  assert.doesNotMatch(text(), /Máy chủ đã nhận báo/);
});

test('G3.3 no polling or automatic POST after report or error', async () => {
  await mount(undefined, c => { if (c.url.endsWith('/deposit/report')) throw networkError(); return defaultHandler(c); });
  await click('Hiển thị mã QR cọc');
  await click('Gửi báo đã chuyển khoản');
  const count = requests.length;
  await act(async () => { await new Promise(r => setTimeout(r, 300)); });
  assert.equal(requests.length, count);
  assert.equal(reportPosts().length, 1);
});

const ledgerWith = (stage, summaryExtra, refunds, depositExtra = {}) => ({
  deposit: deposit(depositExtra), payments: [], refunds, events: [], qrReconciliation: summary(stage, summaryExtra),
});
const refundRows = [
  { id: 'r1', paymentId: 'p1', amount: 200000, status: 'APPROVED', kind: 'LATE', reason: 'Khoản đến sau hạn', approvedBy: 'admin-secret', approvedAt: '2026-10-24T05:00:00Z' },
  { id: 'r2', paymentId: 'p2', amount: 100000, status: 'SUCCEEDED', kind: 'EXCESS', reason: '', approvedBy: 'admin-secret', approvedAt: '2026-10-24T05:10:00Z' },
  { id: 'r3', paymentId: 'p3', amount: 50000, status: 'PROCESSING', kind: 'FUTURE_KIND', reason: null },
];

test('G3.4 USER refund lifecycle never treats approval/processing as refunded and hides admin identity', async () => {
  const booking = { ...makeBooking('booking-a', 'CLOSED'), status: 'CANCELLED' };
  await mount(booking, c => c.url.endsWith('/deposit') ? ledgerWith('CLOSED', { receivedAmount: 350000, appliedAmount: 0 }, refundRows) : defaultHandler(c));
  const section = container.querySelector('[aria-label="Tình trạng hoàn của bạn"]').textContent.replaceAll(' ', ' ');
  assert.match(section, /Đã hoàn \(đã xác minh\): 100\.000 ₫ · Đang chờ hoặc xử lý, chưa hoàn: 250\.000 ₫/);
  assert.match(section, /Hoàn 200\.000 ₫ · Đã duyệt, chưa hoàn tiền.*Chưa hoàn tiền\..*Nguyên nhân: Khoản nhận không đủ điều kiện thời hạn · Khoản đến sau hạn/);
  assert.match(section, /Hoàn 100\.000 ₫ · Đã hoàn/);
  assert.match(section, /Hoàn 50\.000 ₫ · Đang xử lý hoàn.*Chưa xác nhận đã hoàn tiền.*Nguyên nhân: Khác/);
  assert.doesNotMatch(text(), /admin-secret|Duyệt bởi|Loại: LATE|FUTURE_KIND/);
  assert.match(text(), /Tình trạng cọc: Đã kết thúc xử lý cọc/);
  assert.doesNotMatch(text(), /Hiển thị mã QR cọc|Còn thiếu|Chuyển khoản trước hạn/);
  await mount(booking, c => c.url.endsWith('/deposit') ? ledgerWith('CLOSED', { receivedAmount: 350000 }, refundRows) : defaultHandler(c), 'ADMIN');
  assert.match(text(), /Duyệt bởi: admin-secret/);
  assert.ok(!container.querySelector('[aria-label="Tình trạng hoàn của bạn"]'));
});

test('G3.4 late transfer and late verification are explained without asking for more money or blaming the customer', async () => {
  const late = ledgerWith('RECEIVED_INCOMPLETE', { receivedAmount: 600000, timingIssues: ['TRANSFER_LATE', 'VERIFICATION_LATE'] }, []);
  await mount(makeBooking('booking-a', 'RECEIVED_INCOMPLETE'), c => c.url.endsWith('/deposit') ? late : defaultHandler(c));
  assert.match(text(), /Có khoản chuyển từ hạn đóng cọc trở đi\. Khoản này không được áp dụng vào đơn/);
  assert.match(text(), /xác minh sau hạn đối soát của nhân viên\. Đây không phải lỗi của bạn/);
  const expired = ledgerWith('EXPIRED', { receivedAmount: 600000, timingIssues: ['TRANSFER_LATE'], urgency: 'EXPIRED' }, []);
  await mount(makeBooking('booking-a', 'EXPIRED'), c => c.url.endsWith('/deposit') ? expired : defaultHandler(c));
  assert.match(text(), /Đơn đã hết hạn giữ chỗ/);
  assert.match(text(), /Tổng đã nhận \(lịch sử\): 600\.000 ₫ · Đã áp dụng vào đơn: 0 ₫\. Tổng đã nhận không phải số tiền hệ thống đang giữ/);
  assert.match(text(), /Chưa có yêu cầu hoàn hiển thị cho đơn này\. Liên hệ chi nhánh nhận/);
  assert.doesNotMatch(text(), /Hiển thị mã QR cọc|Gửi báo đã chuyển khoản|Còn thiếu|chuyển thêm cho đơn này nữa/);
  assert.equal(requests.filter(r => r.method === 'post').length, 0, 'no automatic cancel/refund mutation');
});

test('G3.4 expired booking with refunds points to refund status; applied closed booking shows no refund prompt', async () => {
  await mount(makeBooking('booking-a', 'EXPIRED'), c => c.url.endsWith('/deposit') ? ledgerWith('EXPIRED', { receivedAmount: 200000 }, [refundRows[0]]) : defaultHandler(c));
  assert.match(text(), /Xem tình trạng hoàn bên dưới\./);
  assert.doesNotMatch(text(), /Chưa có yêu cầu hoàn hiển thị/);
  const completed = { ...makeBooking('booking-a', 'CLOSED'), status: 'COMPLETED' };
  await mount(completed, c => c.url.endsWith('/deposit') ? ledgerWith('CLOSED', { receivedAmount: 600000, appliedAmount: 600000, eligibleAmount: 600000 }, []) : defaultHandler(c));
  assert.ok(!container.querySelector('[aria-label="Khoản đã nhận chưa áp dụng"]'));
  assert.ok(!container.querySelector('[aria-label="Tình trạng hoàn của bạn"]'));
  assert.doesNotMatch(text(), /Chưa có yêu cầu hoàn/);
});
