import assert from 'node:assert/strict';
import {before,after,afterEach,test} from 'node:test';
import {act,createElement as h} from 'react';
import {JSDOM} from 'jsdom';
import {createServer} from 'vite';
import {fileURLToPath,URL} from 'node:url';
import process from 'node:process';
import {vietnamDay,visibleBookings,adminDateFormat,adminBookingDate} from './bookingQueue.js';
import {bookingStatusLabels,roleLabels} from './uiLabels.js';
const now=Date.parse('2026-10-15T17:30:00Z');
const rows=[
 {id:'old',vehicleName:'Xe cũ',customerName:'Khách A',status:'CONFIRMED',createdAt:'2026-10-15T16:59:59Z',startAt:'2026-10-15T17:15:00Z',endAt:'2026-10-17T03:00:00Z',total:100,pickupName:'Đà Nẵng',returnName:'Hà Nội'},
 {id:'new',vehicleName:'Xe mới',customerName:'Khách B',status:'CONFIRMED',createdAt:'2026-10-15T17:00:00Z',startAt:'2026-10-16T17:00:00Z',endAt:'2026-10-18T03:00:00Z',total:200},
 {id:'return',vehicleName:'Xe trả',customerName:'Khách C',status:'IN_PROGRESS',createdAt:'2026-10-14T03:00:00Z',startAt:'2026-10-14T03:00:00Z',endAt:'2026-10-15T17:00:00Z',total:300},
 {id:'latest',vehicleName:'Xe vừa tạo',customerName:'Khách D',status:'PENDING',createdAt:'2026-10-15T17:30:00Z',startAt:'2026-10-20T03:00:00Z',endAt:'2026-10-21T03:00:00Z',total:400},
];
const ids=x=>x.map(r=>r.id);
test('Vietnam midnight, invalid dates and actual creation order independent of rental date',()=>{
 assert.equal(vietnamDay('2026-10-15T16:59:59Z'),'2026-10-15');assert.equal(vietnamDay('2026-10-15T17:00:00Z'),'2026-10-16');assert.equal(vietnamDay('invalid'),'');assert.equal(vietnamDay(null),'');
 assert.deepEqual(ids(visibleBookings(rows)),['latest','new','old','return']);assert.deepEqual(ids(rows),['old','new','return','latest']);
 assert.deepEqual(ids(visibleBookings([...rows,{id:'unknown',createdAt:'bad'}])),['latest','new','old','return','unknown']);
 const ties=[{id:'z',createdAt:rows[0].createdAt},{id:'a',createdAt:rows[0].createdAt}];assert.deepEqual(ids(visibleBookings(ties)),['a','z']);
});
test('Today uses creation, pickup or return by work mode and composes status/search; All removes only day limit',()=>{
 assert.deepEqual(ids(visibleBookings(rows,{queue:'today',now})),['latest','new']);
 assert.deepEqual(ids(visibleBookings(rows,{queue:'today',mode:'handover',status:'CONFIRMED',now})),['old']);
 assert.deepEqual(ids(visibleBookings(rows,{queue:'today',mode:'return',status:'IN_PROGRESS',now})),['return']);
 assert.deepEqual(ids(visibleBookings(rows,{queue:'all',mode:'handover',status:'CONFIRMED',query:'Khách',now})),['new','old']);
 assert.deepEqual(ids(visibleBookings(rows,{queue:'today',mode:'handover',query:'Khách B',now})),[]);
 assert.deepEqual(visibleBookings(null,{queue:'today',now}),[]);
});
test('Selected date respects Vietnam midnight and work date; invalid dates fail closed and All ignores date',()=>{
 assert.deepEqual(ids(visibleBookings(rows,{queue:'date',date:'2026-10-15'})),['old']);assert.deepEqual(ids(visibleBookings(rows,{queue:'date',date:'2026-10-16'})),['latest','new']);
 assert.deepEqual(ids(visibleBookings(rows,{queue:'date',date:'2026-10-17',mode:'handover',status:'CONFIRMED',query:'Khách B'})),['new']);assert.deepEqual(ids(visibleBookings(rows,{queue:'date',date:'2026-10-16',mode:'return'})),['return']);
 for(const date of ['', 'bad', '2026-02-30', '2026-10-16T00:00:00Z'])assert.deepEqual(visibleBookings(rows,{queue:'date',date}),[]);
 assert.deepEqual(ids(visibleBookings([...rows,{id:'invalid',createdAt:'bad'}],{queue:'date',date:'2026-10-16'})),['latest','new']);assert.deepEqual(ids(visibleBookings(rows,{queue:'all',date:'bad'})),['latest','new','old','return']);
});
let dom,vite,createRoot,List,Detail,Topbar,Auth,Router,client,originalNow;const originals=new Map(),roots=[],oldMode=process.env.VITE_DATA_MODE;
before(async()=>{
 dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost'});
 for(const[k,v]of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,HTMLElement:dom.window.HTMLElement,Node:dom.window.Node,Event:dom.window.Event,IS_REACT_ACT_ENVIRONMENT:true})){originals.set(k,Object.getOwnPropertyDescriptor(globalThis,k));Object.defineProperty(globalThis,k,{configurable:true,writable:true,value:v});}
 process.env.VITE_DATA_MODE='api';createRoot=(await import('react-dom/client')).createRoot;vite=await createServer({root:fileURLToPath(new URL('../..',import.meta.url)),server:{middlewareMode:true},appType:'custom',logLevel:'error'});
 ({ApiBookingListPage:List,ApiBookingDetailPage:Detail}=await vite.ssrLoadModule('/src/pages/api/ApiScreens.jsx'));({default:Topbar}=await vite.ssrLoadModule('/src/layouts/WorkspaceTopbar.jsx'));({default:Auth}=await vite.ssrLoadModule('/src/context/AuthContext.jsx'));({MemoryRouter:Router}=await vite.ssrLoadModule('react-router-dom'));({default:client}=await vite.ssrLoadModule('/src/api/client.js'));client.defaults.adapter=async config=>({config,data:rows,status:200,statusText:'OK',headers:{}});originalNow=Date.now;Date.now=()=>now;
});
afterEach(async()=>{for(const root of roots.splice(0))await act(async()=>root.unmount());document.body.replaceChildren();});
after(async()=>{Date.now=originalNow;await vite.close();dom.window.close();for(const[k,v]of originals){if(v)Object.defineProperty(globalThis,k,v);else delete globalThis[k];}if(oldMode===undefined)delete process.env.VITE_DATA_MODE;else process.env.VITE_DATA_MODE=oldMode;});
async function mount(mode='all',entry='/',area='staff'){const c=document.createElement('div');document.body.append(c);const root=createRoot(c);roots.push(root);await act(async()=>root.render(h(Router,{initialEntries:[entry]},h(Auth.Provider,{value:{user:{id:'staff',role:'STAFF',fullName:'Nhân viên kiểm thử'}}},h(Topbar,{areaLabel:'Nhân viên'}),h(List,{area,mode})))));return c;}
async function select(el,value){await act(async()=>{el.value=value;el.dispatchEvent(new dom.window.Event('change',{bubbles:true}));});}
const names=c=>[...c.querySelectorAll('article h2')].map(el=>el.textContent);
test('Calendar on queue/handover/return defaults Vietnam today, supports selection, empty, refresh and All',async()=>{
 for(const mode of ['all','handover','return']){
  const c=await mount(mode),queue=c.querySelectorAll('select')[1];assert.equal(c.querySelector('input[type="date"]'),null);await select(queue,'date');let calendar=c.querySelector('input[type="date"]');assert.equal(calendar.value,'2026-10-16');assert.deepEqual(names(c),mode==='all'?['Xe vừa tạo','Xe mới']:mode==='handover'?['Xe cũ']:['Xe trả']);
  async function date(value){await act(async()=>{Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype,'value').set.call(calendar,value);calendar.dispatchEvent(new dom.window.Event('input',{bubbles:true}));calendar.dispatchEvent(new dom.window.Event('change',{bubbles:true}));});}
  await date('2026-10-17');assert.deepEqual(names(c),mode==='handover'?['Xe mới']:[]);await act(async()=>[...c.querySelectorAll('button')].find(b=>b.textContent==='Tải lại danh sách').click());assert.equal(queue.value,'date');assert.equal(calendar.value,'2026-10-17');
  await date('');assert.deepEqual(names(c),[]);assert.match(c.textContent,/Vui lòng chọn ngày/);await date('2026-10-17');await select(queue,'all');assert.equal(c.querySelector('input[type="date"]'),null);assert.deepEqual(names(c),mode==='all'?['Xe vừa tạo','Xe mới','Xe cũ','Xe trả']:mode==='handover'?['Xe mới','Xe cũ']:['Xe trả']);await select(queue,'date');calendar=c.querySelector('input[type="date"]');assert.equal(calendar.value,'2026-10-17');await select(queue,'today');assert.deepEqual(names(c),mode==='all'?['Xe vừa tạo','Xe mới']:mode==='handover'?['Xe cũ']:['Xe trả']);
 }
});
test('Handover status list replaces buttons, scopes All and retains date/search/refresh/deep link',async()=>{
 const c=await mount('handover'),[status,queue]=c.querySelectorAll('select');
 assert.equal(c.querySelector('section[aria-label="Nhóm bàn giao xe"]'),null);assert.deepEqual([...status.options].map(o=>o.textContent),['Tất cả đơn bàn giao','Chờ bàn giao','Đã bàn giao · Đang thuê']);assert.equal(status.value,'CONFIRMED');assert.deepEqual(names(c),['Xe mới','Xe cũ']);
 await select(status,'IN_PROGRESS');assert.deepEqual(names(c),['Xe trả']);assert.match(c.querySelector('article a').getAttribute('href'),/\/staff\/bookings\/return$/);
 await select(queue,'today');assert.deepEqual(names(c),[]);assert.match(c.textContent,/Không có đơn phù hợp/);await select(status,'CONFIRMED');assert.deepEqual(names(c),['Xe cũ']);assert.equal(queue.value,'today');
 await select(queue,'all');await select(status,'');assert.deepEqual(names(c),['Xe mới','Xe cũ','Xe trả']);
 const search=c.querySelector('input');await act(async()=>{Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype,'value').set.call(search,'không khớp');search.dispatchEvent(new dom.window.Event('input',{bubbles:true}));});await select(status,'IN_PROGRESS');assert.deepEqual(names(c),[]);assert.equal(search.value,'không khớp');
 await act(async()=>[...c.querySelectorAll('button')].find(b=>b.textContent==='Tải lại danh sách').click());assert.equal(status.value,'IN_PROGRESS');assert.equal(search.value,'không khớp');
 const d=await mount('handover','/staff/handover?group=delivered');assert.deepEqual(names(d),['Xe trả']);assert.equal(d.querySelector('select').value,'IN_PROGRESS');
 const e=await mount('handover','/staff/handover?group=unknown');assert.equal(e.querySelector('select').value,'CONFIRMED');
});
test('Operational All excludes unrelated statuses and return includes received and completed with dates/search',()=>{
 const fixtures=['PENDING','CONFIRMED','IN_PROGRESS','RETURNED','COMPLETED','CANCELLED','REJECTED','EXPIRED'].map((status,index)=>({...rows[0],id:status,status,createdAt:new Date(now+index*1000).toISOString(),endAt:rows[0].startAt}));
 assert.deepEqual(ids(visibleBookings(fixtures,{mode:'handover'})),['IN_PROGRESS','CONFIRMED']);assert.deepEqual(ids(visibleBookings(fixtures,{mode:'return'})),['COMPLETED','RETURNED','IN_PROGRESS']);assert.deepEqual(visibleBookings(fixtures,{mode:'handover',status:'COMPLETED'}),[]);
 assert.deepEqual(ids(visibleBookings(fixtures,{mode:'return',status:'RETURNED',queue:'today',query:'Khách A',now})),['RETURNED']);assert.deepEqual(visibleBookings(fixtures,{mode:'return',queue:'today',now:now+86400000}),[]);assert.equal(visibleBookings(fixtures).length,8);
});
test('Return offers only work statuses and scoped All; date, routes and refresh stay usable',async()=>{
 const c=await mount('return'),[status,queue]=c.querySelectorAll('select');assert.deepEqual([...status.options].map(o=>o.textContent),['Tất cả đơn nhận trả','Chờ nhận trả · Đang thuê','Đã nhận xe · Chờ hoàn tất','Đã hoàn tất']);assert.equal(status.value,'IN_PROGRESS');assert.deepEqual(names(c),['Xe trả']);
 for(const value of ['RETURNED','COMPLETED']){await select(status,value);assert.deepEqual(names(c),[]);assert.match(c.textContent,/Không có đơn phù hợp/);}
 await select(status,'');assert.deepEqual(names(c),['Xe trả']);await select(queue,'today');assert.deepEqual(names(c),['Xe trả']);await act(async()=>[...c.querySelectorAll('button')].find(b=>b.textContent==='Tải lại danh sách').click());assert.equal(status.value,'');assert.equal(queue.value,'today');
});
test('Actual staff page renders Vietnamese enum labels, newest first, Today and refresh without losing filters',async()=>{
 const c=await mount();assert.deepEqual(names(c),['Xe vừa tạo','Xe mới','Xe cũ','Xe trả']);assert.match(c.querySelector('header').textContent,/Nhân viên/);assert.doesNotMatch(c.querySelector('header').textContent,/STAFF/);
 const [status,queue]=c.querySelectorAll('select');assert.deepEqual([...status.options].slice(1).map(o=>o.textContent),Object.values(bookingStatusLabels));assert.equal(status.options[1].value,'PENDING');assert.equal(queue.value,'all');
 await select(queue,'today');assert.deepEqual(names(c),['Xe vừa tạo','Xe mới']);await select(status,'CONFIRMED');assert.deepEqual(names(c),['Xe mới']);await act(async()=>[...c.querySelectorAll('button')].find(b=>b.textContent==='Tải lại danh sách').click());assert.equal(queue.value,'today');assert.equal(status.value,'CONFIRMED');assert.deepEqual(names(c),['Xe mới']);await select(queue,'all');assert.deepEqual(names(c),['Xe mới','Xe cũ']);assert.doesNotMatch(c.textContent,/Lọc theo ngày tạo đơn/);
 assert.equal(roleLabels.ADMIN,'Quản trị viên');
});
test('Actual handover/return pages apply work dates and retain operation route IDs',async()=>{
 const c=await mount('handover'),[status,queue]=c.querySelectorAll('select');assert.equal(status.value,'CONFIRMED');await select(queue,'today');assert.deepEqual(names(c),['Xe cũ']);assert.match(c.querySelector('article a').getAttribute('href'),/\/staff\/handover\/old$/);assert.doesNotMatch(c.textContent,/Lọc theo ngày nhận xe/);
 const d=await mount('return'),fields=d.querySelectorAll('select');assert.equal(fields[0].value,'IN_PROGRESS');await select(fields[1],'today');assert.deepEqual(names(d),['Xe trả']);assert.match(d.querySelector('article a').getAttribute('href'),/\/staff\/return\/return$/);assert.doesNotMatch(d.textContent,/Lọc theo ngày trả xe/);
});

test('Booking lists omit explanatory hints for every account and retain calendar controls',async()=>{
 for(const [area,mode] of [['user','all'],['admin','all'],['staff','all'],['staff','handover'],['staff','return']]){
  const c=await mount(mode,'/',area);
  assert.doesNotMatch(c.textContent,/Đơn mới tạo được hiển thị trước|Lọc theo|Tất cả không giới hạn ngày/);
  assert.ok([...c.querySelectorAll('button')].find(b=>b.textContent==='Tải lại danh sách'));
  await select(c.querySelectorAll('select')[1],'date');
  assert.ok(c.querySelector('input[type="date"]'));
  assert.doesNotMatch(c.textContent,/Đây là bộ lọc danh sách/);
 }
});

test('Admin booking list supports sort, pagination, copy UUID feedback, and resets on filter change',async()=>{
 const manyBookings = Array.from({length: 45}, (_, i) => ({
  id: `booking-${String(i + 1).padStart(2, '0')}`,
  vehicleName: `Xe ${i + 1}`,
  customerName: `Khách ${i + 1}`,
  status: i % 2 === 0 ? 'CONFIRMED' : 'PENDING',
  createdAt: new Date(now - i * 3600000).toISOString(),
  startAt: new Date(now + (45 - i) * 3600000).toISOString(),
  endAt: new Date(now + (50 - i) * 3600000).toISOString(),
  total: (i + 1) * 100000,
  pickupName: 'Đà Nẵng',
  returnName: 'Hà Nội'
 }));

 client.defaults.adapter = async (config) => ({
  config,
  data: manyBookings,
  status: 200,
  statusText: 'OK',
  headers: {}
 });

 const c = await mount('all', '/admin/bookings', 'admin');
 assert.match(c.textContent, /Tra cứu đơn thuê/);
 assert.match(c.textContent, /Hiển thị 20\/45 đơn · Trang 1\/3/);

 // Default sort is newest first
 const articlesPage1 = c.querySelectorAll('tbody tr');
 assert.equal(articlesPage1.length, 20);
 assert.match(articlesPage1[0].textContent, /Khách 1/);
 assert.match(articlesPage1[0].textContent, /#booking-/);

 // Test pagination
 const nextBtn = [...c.querySelectorAll('nav button')].find(b => b.textContent === 'Trang sau');
 const prevBtn = [...c.querySelectorAll('nav button')].find(b => b.textContent === 'Trang trước');
 assert.equal(prevBtn.disabled, true);
 assert.equal(nextBtn.disabled, false);

 await act(async () => nextBtn.click());
 assert.match(c.textContent, /Hiển thị 20\/45 đơn · Trang 2\/3/);
 assert.match(c.querySelectorAll('tbody tr')[0].textContent, /Khách 21/);
 assert.equal(prevBtn.disabled, false);

 await act(async () => nextBtn.click());
 assert.match(c.textContent, /Hiển thị 5\/45 đơn · Trang 3\/3/);
 assert.equal(nextBtn.disabled, true);

 // Changing filter resets to page 1
 const queryInput = c.querySelector('input');
 await act(async () => {
  Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(queryInput, 'Khách 1');
  queryInput.dispatchEvent(new dom.window.Event('input', {bubbles: true}));
 });
 assert.match(c.textContent, /Trang 1\//);

 // Changing sort
 const sortSelect = c.querySelectorAll('select')[2];
 assert.ok(sortSelect);
 await select(sortSelect, 'createdAt_asc');
 assert.match(c.textContent, /Cũ tạo trước/);

 // Copy UUID button interaction
 const copyBtn = c.querySelector('button[aria-label^="Sao chép mã đơn"]');
 assert.ok(copyBtn);
 let copiedText = '';
 dom.window.navigator.clipboard = {
  writeText: async (text) => { copiedText = text; }
 };
 await act(async () => copyBtn.click());
 assert.equal(copiedText.startsWith('booking-'), true);
 assert.match(c.textContent, /Đã sao chép mã đầy đủ/);

 // Copy failure fallback
 dom.window.navigator.clipboard = {
  writeText: async () => { throw new Error('Blocked'); }
 };
 await act(async () => copyBtn.click());
 assert.match(c.textContent, /Không thể sao chép tự động/);
 const manualInput = c.querySelector('input[aria-label^="Mã đầy đủ đơn"]');
 assert.ok(manualInput);
 assert.equal(manualInput.value.startsWith('booking-'), true);

 // F03 Date formatting checks
 assert.match(c.textContent, /\d{2}\/\d{2}\/\d{4}/);
 assert.match(c.textContent, /Tạo lúc \d{2}:\d{2} \d{2}\/\d{2}\/\d{4}/);

 // Restore default rows adapter for other tests
 client.defaults.adapter = async config => ({config, data: rows, status: 200, statusText: 'OK', headers: {}});
});

test('F03: adminDateFormat formats dd/mm/yyyy and audit HH:mm with VN timezone', () => {
 const d = '2026-10-20T07:30:00Z';
 assert.equal(adminDateFormat(d), '20/10/2026');
 assert.equal(adminDateFormat(d, { withTime: true }), '14:30 20/10/2026');
 assert.equal(adminBookingDate({ policyVersion: 'calendar-2026-10-17' }, d), '20/10/2026');
 assert.equal(adminBookingDate({ policyVersion: 'legacy' }, d), '14:30 20/10/2026');
});

test('F03-R: Admin booking detail renders dd/mm/yyyy and audit HH:mm in VN timezone', async () => {
 const detailBooking = {
  id: 'detail-admin-01',
  vehicleName: 'Toyota Vios 2023',
  customerName: 'Trần Văn An',
  status: 'PENDING',
  version: 1,
  policyVersion: 'calendar-2026-10-17',
  startAt: '2026-10-20T03:00:00Z',
  endAt: '2026-10-22T03:00:00Z',
  expiresAt: '2026-10-20T07:30:00Z',
  createdAt: '2026-10-20T02:00:00Z',
  pickupName: 'Chi nhánh Quận 1',
  returnName: 'Chi nhánh Sân Bay',
  dailyPrice: 600000,
  rentalDays: 2,
  oneWayFee: 100000,
  total: 1300000,
 };
 const detailData = {
  booking: detailBooking,
  history: [
   { previous_status: null, next_status: 'PENDING', occurred_at: '2026-10-20T02:00:00Z', reason: 'Tạo đơn' },
  ],
  records: [
   { kind: 'HANDOVER', occurred_at: '2026-10-20T03:30:00Z', condition_note: 'Giao xe tốt' },
  ],
 };

 client.defaults.adapter = async (config) => {
  if (config.url.includes('/bookings/detail-admin-01/review')) {
   return { config, data: null, status: 200, statusText: 'OK', headers: {} };
  }
  if (config.url.includes('/bookings/detail-admin-01')) {
   return { config, data: detailData, status: 200, statusText: 'OK', headers: {} };
  }
  if (config.url.includes('/incidents')) {
   return { config, data: [], status: 200, statusText: 'OK', headers: {} };
  }
  return { config, data: rows, status: 200, statusText: 'OK', headers: {} };
 };

 const Routes = (await vite.ssrLoadModule('react-router-dom')).Routes;
 const Route = (await vite.ssrLoadModule('react-router-dom')).Route;

 // Mount in Admin area
 const c = document.createElement('div');
 document.body.append(c);
 const root = createRoot(c);
 roots.push(root);

 await act(async () => {
  root.render(
   h(Router, { initialEntries: ['/admin/bookings/detail-admin-01'] },
    h(Auth.Provider, { value: { user: { id: 'admin', role: 'ADMIN', fullName: 'Quản trị viên' } } },
     h(Routes, null,
      h(Route, { path: '/:area/bookings/:bookingId', element: h(Detail, { area: 'admin' }) })
     )
    )
   )
  );
 });

 // Verify Admin dates
 assert.match(c.textContent, /20\/10\/2026/);
 assert.match(c.textContent, /22\/10\/2026/);
 assert.match(c.textContent, /Yêu cầu giữ lịch đến 14:30 20\/10\/2026/);
 assert.match(c.textContent, /09:00 20\/10\/2026/); // history occurred_at 02:00 UTC = 09:00 VN
 assert.match(c.textContent, /10:30 20\/10\/2026/); // handover occurred_at 03:30 UTC = 10:30 VN

 // Mount in User area to ensure regression check: user preserves standard format
 const cUser = document.createElement('div');
 document.body.append(cUser);
 const rootUser = createRoot(cUser);
 roots.push(rootUser);

 await act(async () => {
  rootUser.render(
   h(Router, { initialEntries: ['/user/bookings/detail-admin-01'] },
    h(Auth.Provider, { value: { user: { id: 'user1', role: 'USER', fullName: 'Khách hàng' } } },
     h(Routes, null,
      h(Route, { path: '/:area/bookings/:bookingId', element: h(Detail, { area: 'user' }) })
     )
    )
   )
  );
 });

 // User shares numeric Vietnamese dates with Admin.
 assert.match(cUser.textContent, /20\/10\/2026/);
 assert.doesNotMatch(cUser.textContent, /thg 10/);

 // Restore default rows adapter
 client.defaults.adapter = async config => ({ config, data: rows, status: 200, statusText: 'OK', headers: {} });
});

test('documents render one escaped terms section and preserve calendar/legacy snapshot dates for all roles', async () => {
 const { ApiDocumentPage: Document } = await vite.ssrLoadModule('/src/pages/api/ApiScreens.jsx');
 const { Routes, Route } = await vite.ssrLoadModule('react-router-dom');
 try {
  for (const area of ['user', 'staff', 'admin']) for (const calendar of [true, false]) for (const kind of ['contract', 'invoice']) {
   const snapshot = { customer: 'Khách thử', vehicle: 'Xe thử', startAt: '2026-10-24T17:00:00Z', endAt: '2026-10-26T17:00:00Z', policyVersion: calendar ? 'calendar-2026-10-17' : 'legacy', terms: '<script>Điều khoản mẫu</script>', total: 1200000, returnPolicy: calendar ? { version: 'same-branch-return-v1', terms: { dailyPriceVnd: 600000, text: 'Chính sách từ snapshot <không thực thi>' }, acceptedAt: '2026-10-20T02:00:00Z' } : null };
   const serialized = JSON.stringify(snapshot);
   client.defaults.adapter = async config => ({ config, data: { id: 'contract-test', snapshot: serialized }, status: 200, statusText: 'OK', headers: {} });
   const c = document.createElement('div'); document.body.append(c);
   const root = createRoot(c); roots.push(root);
   await act(async () => root.render(h(Router, { initialEntries: [`/${area}/bookings/doc-test/${kind}`] }, h(Routes, null, h(Route, { path: '/:area/bookings/:bookingId/:kind', element: h(Document, { kind, area }) })))));
   assert.equal(c.querySelectorAll('h3').length, (kind === 'contract' ? 1 : 0) + Number(calendar));
   assert.equal(Boolean(c.querySelector('[aria-label="Chính sách trả xe đã lưu"]')), calendar);
   if(calendar){ assert.match(c.textContent, /Chính sách từ snapshot <không thực thi>/); assert.match(c.textContent, /600\.000/); assert.match(c.textContent, /09:00 20\/10\/2026/); }
   if (kind === 'contract') assert.ok([...c.querySelectorAll('h3')].some(el => el.textContent === 'Điều khoản'));
   assert.equal((c.textContent.match(/Điều khoản mẫu/g) || []).length, kind === 'contract' ? 1 : 0);
   assert.equal(c.querySelectorAll('script').length, 0);
   assert.match(c.textContent, /25\/10\/2026/);
   assert.match(c.textContent, calendar ? /Ngày nhận/ : /Thời gian nhận/);
   if (calendar) assert.doesNotMatch(c.textContent, /00:00 25\/10\/2026/);
   else assert.match(c.textContent, /00:00 25\/10\/2026/);
   assert.equal(JSON.stringify(snapshot), serialized);
  }
 } finally { client.defaults.adapter = async config => ({ config, data: rows, status: 200, statusText: 'OK', headers: {} }); }
});
