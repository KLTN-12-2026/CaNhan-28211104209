import assert from 'node:assert/strict';
import { before, after, test } from 'node:test';
import { fileURLToPath, URL } from 'node:url';
import { act, createElement } from 'react';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

let dom, vite, Form, client, createRoot, root, container, handler, requests, refreshes;
const globals = new Map();
const booking = { id:'abc-123', version:2, customerName:'Khách thử', vehicleName:'Xe thử' };
const ledger = payload => ({ payments:[{ bookingId:booking.id, status:'SUCCEEDED', id:'receipt', amount:payload.amountVnd, providerTransaction:payload.transactionRef, idempotencyKey:payload.idempotencyKey, transferTime:payload.transferTime, sourceAccount:payload.sourceAccount, transferNote:payload.transferNote }] });
function deferred() { let resolve, reject; const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject}; }
before(async () => {
  dom=new JSDOM('<!doctype html><body></body>',{url:'http://localhost'});
  for (const [key,value] of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,HTMLElement:dom.window.HTMLElement,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,Node:dom.window.Node,Event:dom.window.Event,IS_REACT_ACT_ENVIRONMENT:true})) {
    globals.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{value,configurable:true,writable:true});
  }
  ({createRoot}=await import('react-dom/client'));
  const dir=fileURLToPath(new URL('../../..',import.meta.url));
  vite=await createServer({root:dir,configFile:`${dir}/vite.config.js`,appType:'custom',logLevel:'error',optimizeDeps:{noDiscovery:true},server:{middlewareMode:true}});
  ({default:Form}=await vite.ssrLoadModule('/src/components/user/QrReceiptForm.jsx'));
  ({default:client}=await vite.ssrLoadModule('/src/api/client.js'));
});
after(async()=>{
  if(root)await act(async()=>root.unmount());await vite?.close();
  for(const[key,value]of globals){if(value)Object.defineProperty(globalThis,key,value);else delete globalThis[key];}dom?.window.close();
});
async function mount(custom) {
  if(root)await act(async()=>root.unmount());document.body.replaceChildren();requests=[];refreshes=0;
  handler=custom||((c)=>c.method==='post'?{}:c.url.endsWith('/deposit')?{payments:[]}:{booking:{...booking,status:'PENDING',deposit:{missingAmount:90000}}});
  client.defaults.adapter=async config=>{const body=typeof config.data==='string'?JSON.parse(config.data):config.data;requests.push({...config,body});return {config,data:await handler({...config,body}),status:200,statusText:'OK',headers:{}};};
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
  await act(async()=>root.render(createElement(Form,{key:booking.id,booking,onRefresh:()=>{refreshes++;}})));
}
const button=name=>[...container.querySelectorAll('button')].find(e=>e.textContent.trim()===name);
function field(label){return [...container.querySelectorAll('label')].find(l=>l.querySelector('span')?.textContent===label)?.querySelector('input');}
async function change(label,value){await act(async()=>{const e=field(label);Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype,'value').set.call(e,value);e.dispatchEvent(new dom.window.Event('input',{bubbles:true}));e.dispatchEvent(new dom.window.Event('change',{bubbles:true}));});}
async function click(name){await act(async()=>button(name).click());}
async function ready(){
  for(const[label,value]of [['Mã tham chiếu giao dịch','REF-TEST'],['Số tiền thực nhận (VND)','90000'],['Thời điểm chuyển (giờ VN)','2026-10-25T10:30'],['Tài khoản nguồn (giả lập)','DEMO']])await change(label,value);
  await act(async()=>container.querySelector('input[type="checkbox"]').click());await click('Xác minh và ghi nhận');
}
function error(status){const e=Error('fixture error');if(status)e.response={status,data:{message:`HTTP ${status}`}};return e;}

test('time button is opt-in, editable; validation and cancel do not POST',async()=>{
  await mount();assert.equal(field('Thời điểm chuyển (giờ VN)').value,'');await click('Điền thời điểm hiện tại (giờ Việt Nam)');
  assert.match(field('Thời điểm chuyển (giờ VN)').value,/T\d{2}:\d{2}:\d{2}(?:\.000)?$/);
  await change('Thời điểm chuyển (giờ VN)','2026-10-25T10:30');await click('Xác minh và ghi nhận');assert.equal(requests.length,0);
  await ready();assert.match(container.querySelector('[role="dialog"]').textContent,/Khách thử.*Xe thử/);
  assert.match(container.querySelector('[role="dialog"]').textContent,/90.000/);
  await click('Quay lại chỉnh sửa');assert.equal(field('Mã tham chiếu giao dịch').value,'REF-TEST');assert.equal(requests.length,0);
});
test('synchronous guard allows one POST, locks input and resets only after ledger/order readback',async()=>{
  const pending=deferred();let payload;
  await mount(c=>{if(c.method==='post'){payload=c.body;return pending.promise;}return c.url.endsWith('/deposit')?ledger(payload):{booking:{...booking,status:'PENDING',deposit:{missingAmount:90000}}};});
  await ready();await act(async()=>{button('Xác nhận ghi nhận DEMO').click();button('Xác nhận ghi nhận DEMO')?.click();});
  assert.equal(requests.filter(r=>r.method==='post').length,1);assert.equal(field('Mã tham chiếu giao dịch').matches(':disabled'),true);assert.match(container.textContent,/Đang ghi nhận/);
  assert.equal(payload.transferTime,'2026-10-25T03:30:00.000Z');
  await act(async()=>pending.resolve({}));assert.equal(refreshes,1);assert.equal(field('Mã tham chiếu giao dịch').value,'');assert.match(container.textContent,/còn thiếu 90.000/);
});
test('success with failed readback says recorded, never offers retry POST',async()=>{
  let payload,failed=true;
  await mount(c=>{if(c.method==='post'){payload=c.body;return {};}if(failed)throw error();return c.url.endsWith('/deposit')?ledger(payload):{booking:{...booking,status:'CONFIRMED'}};});
  await ready();await click('Xác nhận ghi nhận DEMO');assert.match(container.textContent,/đã ghi nhận.*chưa tải lại/);assert.equal(button('Thử lại đúng yêu cầu cũ'),undefined);
  failed=false;await click('Kiểm tra kết quả ghi nhận');assert.equal(refreshes,1);assert.equal(requests.filter(r=>r.method==='post').length,1);
});
test('timeout after server store correlates existing receipt without retry',async()=>{
  let payload;await mount(c=>{if(c.method==='post'){payload=c.body;throw error();}return c.url.endsWith('/deposit')?ledger(payload):{booking:{...booking,status:'PENDING',deposit:{missingAmount:90000}}};});
  await ready();await click('Xác nhận ghi nhận DEMO');assert.equal(refreshes,1);assert.equal(requests.filter(r=>r.method==='post').length,1);assert.match(container.textContent,/Đã ghi nhận khoản DEMO/);
});
test('timeout before store freezes payload/key; explicit retry uses exact old operation',async()=>{
  let payload,stored=false;await mount(c=>{if(c.method==='post'){if(!payload){payload=c.body;throw error();}assert.deepEqual(c.body,payload);stored=true;return {};}return c.url.endsWith('/deposit')?(stored?ledger(payload):{payments:[]}):{booking:{...booking,status:'PENDING',deposit:{missingAmount:90000}}};});
  await ready();await click('Xác nhận ghi nhận DEMO');assert.match(container.textContent,/Chưa thấy giao dịch không chứng minh/);assert.equal(field('Mã tham chiếu giao dịch').matches(':disabled'),true);
  assert.equal(requests.filter(r=>r.method==='post').length,1);await click('Thử lại đúng yêu cầu cũ');assert.equal(requests.filter(r=>r.method==='post').length,2);assert.equal(refreshes,1);
});
for(const status of [400,403,409])test(`HTTP ${status} is not silently retried or treated as money`,async()=>{
  await mount(c=>{if(c.method==='post')throw error(status);return c.url.endsWith('/deposit')?{payments:[]}:{booking:{...booking,status:'PENDING',deposit:{missingAmount:180000}}};});
  await ready();await click('Xác nhận ghi nhận DEMO');assert.equal(requests.filter(r=>r.method==='post').length,1);assert.equal(refreshes,0);assert.equal(button('Thử lại đúng yêu cầu cũ'),undefined);
  assert.equal(field('Mã tham chiếu giao dịch').matches(':disabled'),status!==400);
});
test('same reference with conflicting payload is blocked, not mistaken for this receipt',async()=>{
  let payload;await mount(c=>{if(c.method==='post'){payload=c.body;throw error();}return c.url.endsWith('/deposit')?{payments:[{...ledger(payload).payments[0],amount:1}]}:{booking};});
  await ready();await click('Xác nhận ghi nhận DEMO');assert.match(container.textContent,/dữ liệu không khớp/);assert.equal(refreshes,0);assert.equal(button('Thử lại đúng yêu cầu cũ'),undefined);
});
test('unmount during POST suppresses all late readback and callbacks',async()=>{
  const pending=deferred();await mount(c=>c.method==='post'?pending.promise:{});await ready();await click('Xác nhận ghi nhận DEMO');
  await act(async()=>root.unmount());root=null;await act(async()=>pending.resolve({}));assert.equal(refreshes,0);assert.equal(requests.length,1);
});

test('matched receipt with failed booking readback stays locked until GET recovers',async()=>{
  let payload,failed=true;
  await mount(c=>{if(c.method==='post'){payload=c.body;return {};}if(c.url.endsWith('/deposit'))return ledger(payload);if(failed)throw error();return {booking:{...booking,status:'CONFIRMED'}};});
  await ready();await click('Xác nhận ghi nhận DEMO');assert.match(container.textContent,/Đã tìm thấy khoản DEMO.*chưa tải lại được đơn/);assert.equal(refreshes,0);assert.equal(button('Thử lại đúng yêu cầu cũ'),undefined);
  failed=false;await click('Kiểm tra kết quả ghi nhận');assert.equal(refreshes,1);assert.equal(requests.filter(r=>r.method==='post').length,1);
});
test('changing booking key prevents old POST from updating the new form',async()=>{
  const pending=deferred();await mount(c=>c.method==='post'?pending.promise:{});await ready();await click('Xác nhận ghi nhận DEMO');
  await act(async()=>root.render(createElement(Form,{key:'new-booking',booking:{...booking,id:'new-booking'},onRefresh:()=>{refreshes++;}})));
  await act(async()=>pending.resolve({}));assert.equal(refreshes,0);assert.equal(requests.length,1);assert.equal(field('Mã tham chiếu giao dịch').value,'');assert.doesNotMatch(container.textContent,/Đã ghi nhận khoản DEMO/);
});
