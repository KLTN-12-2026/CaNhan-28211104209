import assert from 'node:assert/strict';
import { before, after, test } from 'node:test';
import { fileURLToPath, URL } from 'node:url';
import { act, createElement } from 'react';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';
let dom, vite, Form, client, createRoot, root, container, handler, requests, refreshes;
const globals = new Map();
const settlement={originalAmount:1950000,receivedAmount:0,waivedAmount:0,outstandingAmount:1950000,status:'UNPAID',demo:true,entries:[]};
let booking={id:'receipt/fixture',version:3,status:'RETURNED',vehicleName:'Xe giả lập',returnSettlement:settlement};
function deferred(){let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};}
before(async () => {
  dom=new JSDOM('<!doctype html><body></body>',{url:'http://localhost'});
  for (const [key,value] of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,HTMLElement:dom.window.HTMLElement,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,Node:dom.window.Node,Event:dom.window.Event,IS_REACT_ACT_ENVIRONMENT:true})) {
    globals.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{value,configurable:true,writable:true});
  }
  ({createRoot}=await import('react-dom/client'));
  const dir=fileURLToPath(new URL('../../..',import.meta.url));
  vite=await createServer({root:dir,configFile:`${dir}/vite.config.js`,appType:'custom',logLevel:'error',optimizeDeps:{noDiscovery:true},server:{middlewareMode:true}});
  ({default:Form}=await vite.ssrLoadModule('/src/components/booking/ReturnSettlement.jsx'));
  ({default:client}=await vite.ssrLoadModule('/src/api/client.js'));
});
after(async()=>{
  if(root)await act(async()=>root.unmount());await vite?.close();
  for(const[key,value]of globals){if(value)Object.defineProperty(globalThis,key,value);else delete globalThis[key];}dom?.window.close();
});

async function mount(custom, role="STAFF", extra={}){
  booking={...booking,...extra};
  if(root)await act(async()=>root.unmount());document.body.replaceChildren();requests=[];refreshes=0;
  handler=custom||(c=>c.method==='post'?{}:{booking:{...booking,returnSettlement:{...settlement,entries:[{idempotencyKey:requests.find(r=>r.method==='post').body.idempotencyKey}]}}});
  client.defaults.adapter=async config=>{const body=typeof config.data==='string'?JSON.parse(config.data):config.data;requests.push({...config,body});return {config,data:await handler({...config,body}),status:200,statusText:'OK',headers:{}};};
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
  await act(async()=>root.render(createElement(Form,{booking,role,assignedReturn:true,onSaved:()=>{refreshes++;}})));
}
const button=name=>[...container.querySelectorAll('button')].find(e=>e.textContent.trim()===name);
async function click(name){await act(async()=>button(name).click());}
function failure(status){const e=Error('Fixture request failed');if(status)e.response={status,data:{message:'Cần xem trước lại'}};return e;}

async function field(label,value){await act(async()=>{const l=[...container.querySelectorAll('label')].find(x=>x.textContent.includes(label));const e=l.querySelector('input,textarea,select');const proto=e.tagName==='SELECT'?dom.window.HTMLSelectElement.prototype:e.tagName==='TEXTAREA'?dom.window.HTMLTextAreaElement.prototype:dom.window.HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(e,value);e.dispatchEvent(new dom.window.Event('input',{bubbles:true}));e.dispatchEvent(new dom.window.Event('change',{bubbles:true}));});}
async function ready(){await field('Số tiền (VND)','1000000');await field('Tham chiếu','DEMO-1');await field('Lý do','Fixture ghi thu');await click('Kiểm tra ghi nhận');}
test('Staff reviews receipt, writes once and reads ledger before success',async()=>{
 await mount();assert.match(container.textContent,/Mô phỏng/);await ready();assert.equal(requests.length,0);assert.match(container.querySelector('[role="dialog"]').textContent,/1.000.000/);
 await click('Xác nhận ghi nhận');assert.equal(requests.filter(r=>r.method==='post').length,1);const p=requests[0];assert.equal(p.url,'/bookings/receipt%2Ffixture/return-settlements');assert.equal(p.body.expectedVersion,3);assert.equal(p.body.amountVnd,1000000);assert.equal(p.body.kind,'RECEIPT');assert.equal(p.body.confirmed,true);assert.equal(refreshes,1);
});
test('timeout locks mutation and result check is GET only',async()=>{
 await mount(c=>{if(c.method==='post')throw failure();return {booking:{...booking,returnSettlement:{...settlement,entries:[]}}};});await ready();await click('Xác nhận ghi nhận');await click('Kiểm tra kết quả công nợ');assert.equal(requests.filter(r=>r.method==='post').length,1);assert.equal(refreshes,0);assert.match(container.textContent,/Chưa tìm thấy bản ghi/);
});
test('concurrent clicks cannot send two receipts',async()=>{
 const d=deferred();await mount(c=>c.method==='post'?d.promise:{booking:{...booking,returnSettlement:{...settlement,entries:[{idempotencyKey:requests[0].body.idempotencyKey}]}}});await ready();await act(async()=>{button('Xác nhận ghi nhận').click();button('Xác nhận ghi nhận')?.click();});assert.equal(requests.length,1);await act(async()=>d.resolve({}));assert.equal(refreshes,1);
});
test('409 invalidates review; USER and completed bookings have no controls',async()=>{
 await mount(()=>{throw failure(409);});await ready();await click('Xác nhận ghi nhận');assert.equal(container.querySelector('[role="dialog"]'),null);assert.equal(refreshes,0);
 await mount(null,'USER');assert.equal(container.querySelector('form'),null);await mount(null,'ADMIN',{status:'COMPLETED'});assert.equal(container.querySelector('form'),null);booking={...booking,status:'RETURNED'};
});
test('Admin waiver and reversal use distinct payloads and never impersonate receipt',async()=>{
 await mount(null,'ADMIN');await field('Số tiền','450000');await field('Lý do','Fixture miễn');await click('Kiểm tra ghi nhận');await click('Xác nhận ghi nhận');assert.equal(requests[0].body.kind,'WAIVER');assert.equal(requests[0].body.method,null);
 await mount(null,'ADMIN',{returnSettlement:{...settlement,entries:[{id:'entry-1',kind:'WAIVER',amount:450000,reason:'Fixture miễn',createdAt:'2026-10-26T03:00:00Z'}]}});await field('Thao tác công nợ','REVERSAL');await field('Bản ghi cần đảo','entry-1');await field('Lý do','Fixture sửa sai');await click('Kiểm tra ghi nhận');await click('Xác nhận ghi nhận');assert.equal(requests[0].body.amountVnd,null);assert.equal(requests[0].body.reversesId,'entry-1');assert.match(container.textContent,/không xác nhận toàn bộ tiền thuê/);booking={...booking,returnSettlement:settlement};
});
