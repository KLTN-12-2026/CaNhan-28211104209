import assert from 'node:assert/strict';
import { before, after, test } from 'node:test';
import { fileURLToPath, URL } from 'node:url';
import { act, createElement } from 'react';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';
let dom, vite, Form, Summary, client, createRoot, root, container, handler, requests, refreshes;
const globals = new Map();
const booking = {id:'receipt/fixture',version:3};
const assessment = {bookedReturnDate:'2026-10-28',actualReturnDate:'2026-10-31',actualReturnedAt:'2026-10-31T03:00:00Z',timing:'LATE',lateDays:3,dailyPriceVnd:650000,amountVnd:1950000,collectionStatus:'UNPAID'};
function deferred(){let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};}
before(async () => {
  dom=new JSDOM('<!doctype html><body></body>',{url:'http://localhost'});
  for (const [key,value] of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,HTMLElement:dom.window.HTMLElement,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,Node:dom.window.Node,Event:dom.window.Event,IS_REACT_ACT_ENVIRONMENT:true})) {
    globals.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{value,configurable:true,writable:true});
  }
  ({createRoot}=await import('react-dom/client'));
  const dir=fileURLToPath(new URL('../../..',import.meta.url));
  vite=await createServer({root:dir,configFile:`${dir}/vite.config.js`,appType:'custom',logLevel:'error',optimizeDeps:{noDiscovery:true},server:{middlewareMode:true}});
  ({default:Form,ReturnAssessmentSummary:Summary}=await vite.ssrLoadModule('/src/components/booking/ReturnReceipt.jsx'));
  ({default:client}=await vite.ssrLoadModule('/src/api/client.js'));
});
after(async()=>{
  if(root)await act(async()=>root.unmount());await vite?.close();
  for(const[key,value]of globals){if(value)Object.defineProperty(globalThis,key,value);else delete globalThis[key];}dom?.window.close();
});

async function mount(custom){
  if(root)await act(async()=>root.unmount());document.body.replaceChildren();requests=[];refreshes=0;
  handler=custom||(c=>c.url.endsWith('/return-preview')?{version:3,assessment}:c.method==='post'?{}:{booking:{...booking,status:'RETURNED',returnAssessment:assessment}});
  client.defaults.adapter=async config=>{const body=typeof config.data==='string'?JSON.parse(config.data):config.data;requests.push({...config,body});return {config,data:await handler({...config,body}),status:200,statusText:'OK',headers:{}};};
  container=document.createElement('div');document.body.append(container);root=createRoot(container);
  await act(async()=>root.render(createElement(Form,{booking,onSaved:()=>{refreshes++;}})));
}
const button=name=>[...container.querySelectorAll('button')].find(e=>e.textContent.trim()===name);
async function note(text='Xe đã nhận thực tế trong fixture'){
  await act(async()=>{const e=container.querySelector('textarea');Object.getOwnPropertyDescriptor(dom.window.HTMLTextAreaElement.prototype,'value').set.call(e,text);e.dispatchEvent(new dom.window.Event('input',{bubbles:true}));e.dispatchEvent(new dom.window.Event('change',{bubbles:true}));});
}
async function click(name){await act(async()=>button(name).click());}
function failure(status){const e=Error('Fixture request failed');if(status)e.response={status,data:{message:'Cần xem trước lại'}};return e;}

test('preview is read-only and explicit confirmation sends backend version/date/amount once',async()=>{
  await mount();assert.equal(button('Xem trước nhận trả').disabled,true);assert.equal(button('Xác nhận đã nhận xe'),undefined);
  await note();await click('Xem trước nhận trả');assert.equal(requests.length,1);assert.equal(requests[0].method,'get');assert.equal(requests[0].url,'/bookings/receipt%2Ffixture/return-preview');
  assert.match(container.textContent,/31\/10\/2026/);assert.match(container.textContent,/1.950.000/);assert.match(container.textContent,/Chưa thu/);
  await click('Xác nhận đã nhận xe');const posts=requests.filter(r=>r.method==='post');assert.equal(posts.length,1);
  assert.deepEqual(posts[0].body,{next:'RETURNED',version:3,condition:'Xe đã nhận thực tế trong fixture',maintenance:false,returnPreviewDate:'2026-10-31',expectedLateAmount:1950000});
  assert.equal(refreshes,1);assert.equal(button('Xác nhận đã nhận xe'),undefined);
});
test('changing note or maintenance invalidates preview; GET error never reuses old preview',async()=>{
  await mount();await note();await click('Xem trước nhận trả');await note('Đã chỉnh biên bản');assert.equal(button('Xác nhận đã nhận xe'),undefined);
  await click('Xem trước nhận trả');await act(async()=>container.querySelector('input[type="checkbox"]').click());assert.equal(button('Xác nhận đã nhận xe'),undefined);
  handler=()=>{throw failure(503);};await click('Xem trước nhận trả');assert.equal(button('Xác nhận đã nhận xe'),undefined);assert.equal(requests.filter(r=>r.method==='post').length,0);assert.match(container.textContent,/Fixture request failed|Cần xem trước lại/);
});
test('double click while confirming is single-flight and locks fields until readback',async()=>{
  const pending=deferred();await mount(c=>c.url.endsWith('/return-preview')?{version:3,assessment}:c.method==='post'?pending.promise:{booking:{status:'RETURNED',returnAssessment:assessment}});
  await note();await click('Xem trước nhận trả');await act(async()=>{button('Xác nhận đã nhận xe').click();button('Xác nhận đã nhận xe').click();});
  assert.equal(requests.filter(r=>r.method==='post').length,1);assert.equal(container.querySelector('textarea').disabled,true);
  await act(async()=>pending.resolve({}));assert.equal(refreshes,1);
});
test('stale preview conflict requires a new preview, no automatic retry or success',async()=>{
  await mount(c=>c.method==='post'?Promise.reject(failure(409)):{version:3,assessment});await note();await click('Xem trước nhận trả');await click('Xác nhận đã nhận xe');
  assert.equal(requests.filter(r=>r.method==='post').length,1);assert.equal(refreshes,0);assert.equal(button('Xác nhận đã nhận xe'),undefined);assert.equal(button('Xem trước nhận trả').disabled,false);assert.match(container.textContent,/Cần xem trước lại/);
});
test('timeout or failed readback never offers a second POST; check result is GET only',async()=>{
  for(const timeout of [true,false]){
    let reads=0;await mount(c=>{if(c.url.endsWith('/return-preview'))return {version:3,assessment};if(c.method==='post'){if(timeout)throw failure();return {};}
      if(reads++===0)throw failure(503);return {booking:{status:'RETURNED',returnAssessment:assessment}};});
    await note();await click('Xem trước nhận trả');await click('Xác nhận đã nhận xe');assert.equal(refreshes,0);assert.equal(button('Xác nhận đã nhận xe'),undefined);assert.equal(button('Xem trước nhận trả'),undefined);
    await click('Kiểm tra kết quả nhận trả');if(timeout)await click('Kiểm tra kết quả nhận trả');
    assert.equal(requests.filter(r=>r.method==='post').length,1);assert.equal(refreshes,1);
  }
});
test('early and on-time summaries show zero without implying refund or payment',async()=>{
  for(const timing of ['EARLY','ON_TIME']){
    await mount();await act(async()=>root.render(createElement(Summary,{assessment:{...assessment,timing,lateDays:0,amountVnd:0,collectionStatus:'NOT_DUE'}})));
    assert.match(container.textContent,/Không phát sinh khoản vượt hạn/);assert.match(container.textContent,/không tự giảm tiền thuê hoặc hoàn cọc/);assert.doesNotMatch(container.textContent,/Đã thanh toán/);
  }
});

test('saved assessment uses current settlement wording for partial and resolved balances',async()=>{
  await mount();
  for(const outstandingAmount of [500000,0]) {
    await act(async()=>root.render(createElement(Summary,{assessment,settlement:{outstandingAmount}})));
    assert.match(container.textContent,outstandingAmount ? /Chưa xử lý đủ khoản vượt hạn/ : /Khoản vượt hạn đã xử lý đủ/);
    assert.doesNotMatch(container.textContent,/Chưa thu —/);
  }
});
