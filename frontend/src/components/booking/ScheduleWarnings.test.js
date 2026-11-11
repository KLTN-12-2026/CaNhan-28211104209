import assert from 'node:assert/strict';
import { before, after, test } from 'node:test';
import { fileURLToPath, URL } from 'node:url';
import { act, createElement as h } from 'react';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';
import { visibleBookings } from '../../utils/bookingQueue.js';

let dom, vite, root, container, createRoot, Router, Routes, Route, Auth, Warning, List, Detail, client;
let handler;
const globals = new Map();
const base = { id: 'a', status: 'IN_PROGRESS', vehicleName: 'Xe fixture', customerName: 'Khách fixture', createdAt: '2026-10-27T03:00:00Z', startAt: '2026-10-26T00:00:00Z', endAt: '2026-10-28T00:00:00Z', pickupBranchId: 'dn', returnBranchId: 'dn', total: 1000000 };
function booking(kind = 'OVERDUE', id = 'a', relatedBookingId = null) {
  return { ...base, id, scheduleWarnings: { checkedAt: '2026-10-29T03:00:00Z', items: [{ kind, bookingId: id, relatedBookingId, bookedReturnDate: '2026-10-28', lateDays: kind === 'RETURN_DUE_NEXT_DAY_BOOKING' ? 0 : 1 }] } };
}
before(async () => {
  dom = new JSDOM('<!doctype html><body></body>', { url: 'http://localhost' });
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator, HTMLElement: dom.window.HTMLElement, localStorage: dom.window.localStorage, sessionStorage: dom.window.sessionStorage, Node: dom.window.Node, Event: dom.window.Event, IS_REACT_ACT_ENVIRONMENT: true })) {
    globals.set(key, Object.getOwnPropertyDescriptor(globalThis, key)); Object.defineProperty(globalThis, key, { value, configurable: true, writable: true });
  }
  ({ createRoot } = await import('react-dom/client'));
  ({ MemoryRouter: Router, Routes, Route } = await import('react-router-dom'));
  const dir = fileURLToPath(new URL('../../..', import.meta.url));
  vite = await createServer({ root: dir, configFile: `${dir}/vite.config.js`, appType: 'custom', logLevel: 'error', optimizeDeps: { noDiscovery: true }, server: { middlewareMode: true } });
  ({ default: Warning } = await vite.ssrLoadModule('/src/components/booking/ScheduleWarnings.jsx'));
  ({ ApiBookingListPage: List, ApiBookingDetailPage: Detail } = await vite.ssrLoadModule('/src/pages/api/ApiScreens.jsx'));
  ({ default: Auth } = await vite.ssrLoadModule('/src/context/AuthContext.jsx'));
  ({ default: client } = await vite.ssrLoadModule('/src/api/client.js'));
  client.defaults.adapter = async config => ({ config, data: await handler(config), status: 200, statusText: 'OK', headers: {} });
});
after(async () => {
  if (root) await act(async () => root.unmount()); await vite?.close();
  for (const [key, value] of globals) { if (value) Object.defineProperty(globalThis, key, value); else delete globalThis[key]; }
  dom?.window.close();
});
async function mount(element) {
  if (root) await act(async () => root.unmount());
  document.body.replaceChildren(); container = document.createElement('div'); document.body.append(container); root = createRoot(container);
  await act(async () => root.render(h(Router, { initialEntries: ['/staff/bookings/a'] }, element)));
}
const field = text => [...container.querySelectorAll('label')].find(e => e.textContent.startsWith(text))?.querySelector('input,select');
async function change(text, value) {
  const e = field(text); assert.ok(e, text);
  await act(async () => { Object.getOwnPropertyDescriptor(e.tagName === 'SELECT' ? dom.window.HTMLSelectElement.prototype : dom.window.HTMLInputElement.prototype, 'value').set.call(e, value); e.dispatchEvent(new dom.window.Event('input', { bubbles: true })); e.dispatchEvent(new dom.window.Event('change', { bubbles: true })); });
}
async function click(text) { const e = [...container.querySelectorAll('button')].find(e => e.textContent.trim() === text); assert.ok(e, text); await act(async () => e.click()); }

test('renders all server kinds, Vietnamese dates and role-scoped related links without recalculating time', async () => {
  for (const area of ['staff', 'admin']) {
    for (const [kind, title] of [['RETURN_DUE_NEXT_DAY_BOOKING', 'Cần theo dõi'], ['OVERDUE', 'Xe quá hạn'], ['PREDECESSOR_NOT_RETURNED', 'Xe của đơn trước chưa về']]) {
      await mount(h(Warning, { area, booking: booking(kind, 'a', 'b/fixture') }));
      assert.match(container.textContent, new RegExp(title)); assert.match(container.textContent, /28\/10\/2026/); assert.match(container.textContent, /10:00 29\/10\/2026/);
      assert.equal(container.querySelector('a').getAttribute('href'), `/${area}/bookings/b%2Ffixture`);
      assert.equal(container.querySelector('button'), null);
    }
  }
});
test('USER, missing/empty/mismatched/unknown warnings render no internal information', async () => {
  for (const [area, value] of [['user', booking()], ['staff', base], ['admin', { ...base, scheduleWarnings: { items: [] } }], ['staff', booking('UNKNOWN')], ['staff', { ...booking(), id: 'different' }]]) {
    await mount(h(Warning, { area, booking: value })); assert.equal(container.textContent, '');
  }
  const legacy = booking(); legacy.scheduleWarnings.items[0].lateDays = 0;
  await mount(h(Warning, { area: 'staff', booking: legacy })); assert.match(container.textContent, /Xe quá hạn/); assert.doesNotMatch(container.textContent, /Quá hạn 0 ngày/); assert.equal(container.querySelector('a'), null);
});
test('warning filter intersects date, status, query and work queue without mutating input', () => {
  const rows = [booking(), booking('RETURN_DUE_NEXT_DAY_BOOKING', 'b'), { ...booking('OVERDUE', 'c'), status: 'CONFIRMED' }, { ...booking('OVERDUE', 'd'), endAt: '2026-10-30' }, { ...booking('OVERDUE', 'e'), customerName: 'Khác' }];
  const before = JSON.stringify(rows);
  assert.deepEqual(visibleBookings(rows, { mode: 'return', status: 'IN_PROGRESS', queue: 'date', date: '2026-10-28', query: 'Khách fixture', scheduleWarning: 'overdue' }).map(e => e.id), ['a']);
  assert.deepEqual(visibleBookings(rows, { scheduleWarning: 'early' }).map(e => e.id), ['b']);
  assert.equal(JSON.stringify(rows), before);
});
test('Staff list keeps existing filters when selecting warnings; reload updates server data and errors show no stale/demo cards', async () => {
  handler = () => [booking(), booking('RETURN_DUE_NEXT_DAY_BOOKING', 'b')];
  await mount(h(List, { area: 'staff', mode: 'return' }));
  await change('Hàng đợi', 'date'); await change('Ngày trong hàng đợi', '2026-10-28'); await change('Tìm mã đơn', 'fixture'); await change('Cảnh báo lịch xe', 'overdue');
  assert.equal(field('Trạng thái').value, 'IN_PROGRESS'); assert.equal(field('Hàng đợi').value, 'date'); assert.equal(field('Ngày trong hàng đợi').value, '2026-10-28'); assert.equal(field('Tìm mã đơn').value, 'fixture');
  assert.match(container.textContent, /1 đơn phù hợp/);
  handler = () => [{ ...base, scheduleWarnings: { items: [] } }]; await click('Tải lại danh sách'); assert.match(container.textContent, /0 đơn phù hợp/);
  handler = () => { throw Object.assign(Error('Fixture unavailable'), { response: { status: 503, data: { message: 'Fixture unavailable' } } }); };
  await click('Tải lại danh sách'); assert.match(container.textContent, /Fixture unavailable/); assert.equal(container.querySelector('[aria-label="Cảnh báo lịch xe"]'), null);
});
test('Admin table renders warnings; USER list has no warning control or content', async () => {
  handler = () => [booking()]; await mount(h(List, { area: 'admin' }));
  assert.ok(container.querySelector('table [aria-label="Cảnh báo lịch xe"]'));
  await mount(h(List, { area: 'user' })); assert.equal(field('Cảnh báo lịch xe'), undefined); assert.equal(container.querySelector('[aria-label="Cảnh báo lịch xe"]'), null);
});
test('changing the warning filter resets Admin pagination even when both results span multiple pages', async () => {
  handler = () => Array.from({ length: 45 }, (_, i) => booking(i < 42 ? 'OVERDUE' : 'RETURN_DUE_NEXT_DAY_BOOKING', `fixture-${String(i).padStart(2, '0')}`));
  await mount(h(List, { area: 'admin' })); await click('Trang sau'); assert.match(container.textContent, /Trang 2\/3/);
  await change('Cảnh báo lịch xe', 'overdue'); assert.match(container.textContent, /Trang 1\/3/); assert.match(container.textContent, /20\/42 đơn/);
});
test('detail renders warning independently of workflow status and actions', async () => {
  handler = config => config.url === '/me/branches' ? ['dn'] : { booking: booking(), history: [], records: [] };
  await mount(h(Auth.Provider, { value: { user: { role: 'STAFF' } } }, h(Routes, null, h(Route, { path: '/staff/bookings/:bookingId', element: h(Detail, { area: 'staff' }) }))));
  assert.ok(container.querySelector('[aria-label="Cảnh báo lịch xe"]')); assert.match(container.textContent, /Đang thuê/);
});
