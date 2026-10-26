import assert from 'node:assert/strict';
import { before, after, test } from 'node:test';
import { fileURLToPath, URL } from 'node:url';
import { act, createElement } from 'react';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

let dom, vite, Page, client, Router, createRoot, root, container, serverData, handler, requests;
const globals = new Map();
const payment = { id:'pay-1', bookingId:'booking-1', amount:100000, appliedAmount:0, status:'SUCCEEDED', depositMethod:'DEMO_QR_MANUAL', providerTransaction:'SOURCE-REF', sourceAccount:'DEMO-SOURCE', createdAt:'2026-10-25T03:00:00Z' };
const refund = { id:'refund-1', bookingId:'booking-1', paymentId:'pay-1', amount:50000, status:'REQUESTED', version:0, kind:'MANUAL_EXCESS', reason:'Fixture request', depositMethod:'DEMO_QR_MANUAL', createdAt:'2026-10-25T03:00:00Z' };
const initial = () => ({payments:[{...payment}],refundPayments:[{...payment}],refunds:[{...refund}],unresolved:[],refundableAmounts:{'pay-1':100000},manualRefundPaymentIds:['pay-1'],historyLimit:200});
const deferred = () => { let resolve; const promise=new Promise(yes=>{resolve=yes;});return {promise,resolve}; };
const failure = status => {const e=Error('Synthetic transport failure');if(status)e.response={status,data:{message:`HTTP ${status}`}};return e;};
before(async()=>{
  dom=new JSDOM('<!doctype html><body></body>',{url:'http://localhost'});
  for(const[key,value]of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,HTMLElement:dom.window.HTMLElement,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,Node:dom.window.Node,Event:dom.window.Event,IS_REACT_ACT_ENVIRONMENT:true})){
    globals.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{value,configurable:true,writable:true});
  }
  ({createRoot}=await import('react-dom/client'));({MemoryRouter:Router}=await import('react-router-dom'));
  const dir=fileURLToPath(new URL('../../..',import.meta.url));
  vite=await createServer({root:dir,configFile:`${dir}/vite.config.js`,appType:'custom',logLevel:'error',optimizeDeps:{noDiscovery:true},server:{middlewareMode:true}});
  ({default:Page}=await vite.ssrLoadModule('/src/pages/api/ApiPaymentsPage.jsx'));({default:client}=await vite.ssrLoadModule('/src/api/client.js'));
});
after(async()=>{if(root)await act(async()=>root.unmount());await vite?.close();for(const[key,value]of globals){if(value)Object.defineProperty(globalThis,key,value);else delete globalThis[key];}dom?.window.close();});
async function mount(custom,setup){
  if(root)await act(async()=>root.unmount());document.body.replaceChildren();serverData=initial();setup?.(serverData);requests=[];handler=custom;
  client.defaults.adapter=async config=>{const body=typeof config.data==='string'?JSON.parse(config.data):config.data;requests.push({method:config.method,url:config.url,body});const data=handler?await handler({...config,body}):copy(serverData);return {config,data,status:200,statusText:'OK',headers:{}};};
  container=document.createElement('div');document.body.append(container);root=createRoot(container);await act(async()=>root.render(createElement(Router,null,createElement(Page))));
}
const button=name=>[...container.querySelectorAll('button')].find(e=>e.textContent.trim()===name);
const field=name=>[...container.querySelectorAll('label')].find(e=>e.firstChild?.textContent===name)?.querySelector('input,textarea,select');
async function change(name,value){await act(async()=>{const e=field(name);const proto=e.tagName==='SELECT'?dom.window.HTMLSelectElement.prototype:e.tagName==='TEXTAREA'?dom.window.HTMLTextAreaElement.prototype:dom.window.HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(e,value);e.dispatchEvent(new dom.window.Event('input',{bubbles:true}));e.dispatchEvent(new dom.window.Event('change',{bubbles:true}));});}
async function click(name){await act(async()=>button(name).click());}
async function approve(){await change('Số tiền hoàn (VND)','40000');await change('Lý do quyết định','Fixture approved');await click('Duyệt khoản hoàn');}
async function completion(){await change('Tài khoản nhận hoàn','DEMO-SOURCE');await change('Tham chiếu hoàn','REFUND-REF');await click('Điền thời điểm hoàn hiện tại (giờ Việt Nam)');await act(async()=>container.querySelector('input[type="checkbox"]').click());await click('Xác nhận đã hoàn DEMO');}
const posts=()=>requests.filter(r=>r.method==='post');
const copy=value=>JSON.parse(JSON.stringify(value));

test('history remains visible with empty queue; search and refund/status filters do not POST',async()=>{
  await mount(null,d=>{d.refunds[0].status='SUCCEEDED';d.refunds[0].providerTransaction='REFUND-DONE';});
  assert.match(container.textContent,/Không có giao dịch cần đối soát/);assert.match(container.textContent,/SOURCE-REF/);assert.equal(container.querySelector('[aria-label="Yêu cầu hoàn refund-1"]'),null);
  await change('Trạng thái hoàn','');assert.ok(container.querySelector('[aria-label="Yêu cầu hoàn refund-1"]'));
  await change('Tìm mã đơn, giao dịch hoặc tham chiếu','not-found');assert.match(container.textContent,/Không có giao dịch phù hợp/);await change('Tìm mã đơn, giao dịch hoặc tham chiếu','SOURCE-REF');assert.match(container.textContent,/SOURCE-REF/);assert.equal(posts().length,0);
});
test('decision preview/cancel and synchronous double click guard; approved is not complete',async()=>{
  const pending=deferred();await mount(async c=>{if(c.method==='post'){await pending.promise;Object.assign(serverData.refunds[0],{...c.body,status:'APPROVED',version:1});return serverData.refunds[0];}return copy(serverData);});
  await approve();assert.match(container.querySelector('[role="dialog"]').textContent,/40.000/);assert.equal(document.activeElement,container.querySelector('[role="dialog"]'));await click('Quay lại chỉnh sửa');assert.equal(posts().length,0);assert.equal(field('Lý do quyết định').value,'Fixture approved');await click('Duyệt khoản hoàn');
  await act(async()=>{button('Xác nhận thao tác').click();button('Xác nhận thao tác')?.click();});assert.equal(posts().length,1);assert.equal(field('Lý do quyết định').matches(':disabled'),true);
  await act(async()=>pending.resolve());assert.match(container.textContent,/Đã duyệt chưa có nghĩa/);assert.ok(button('Bắt đầu xử lý hoàn (QR DEMO)'));assert.equal(button('Xác nhận đã hoàn DEMO'),undefined);
});
test('process then verified completion uses separate review and server result',async()=>{
  await mount(c=>{if(c.method==='post'){const r=serverData.refunds[0];if(c.url.endsWith('/process-manual')){r.status='PROCESSING';r.version++;}else{r.status='SUCCEEDED';r.version++;r.providerTransaction=c.body.reference;const vn=new Date(new Date(c.body.transferTime).getTime()+7*3600000);r.requestDate=vn.toISOString().slice(0,19).replace(/\D/g,'');}return {};}return copy(serverData);},d=>{d.refunds[0].status='APPROVED';});
  await click('Bắt đầu xử lý hoàn (QR DEMO)');await click('Xác nhận thao tác');assert.ok(button('Xác nhận đã hoàn DEMO'));assert.match(container.textContent,/Đang xử lý/);
  await completion();assert.match(container.querySelector('[role="dialog"]').textContent,/DEMO-SOURCE.*REFUND-REF/);assert.equal(posts().length,1);await click('Xác nhận thao tác');assert.equal(posts().length,2);await change('Trạng thái hoàn','');assert.match(container.querySelector('[aria-label="Yêu cầu hoàn refund-1"]').textContent,/Đã hoàn/);assert.equal(button('Xác nhận đã hoàn DEMO'),undefined);
});
test('wrong completion source is rejected locally without mutation',async()=>{
  await mount(null,d=>{d.refunds[0].status='PROCESSING';});await completion();await click('Quay lại chỉnh sửa');await change('Tài khoản nhận hoàn','OTHER');await click('Xác nhận đã hoàn DEMO');assert.match(container.textContent,/Tài khoản nhận phải khớp/);assert.equal(posts().length,0);
});
test('POST accepted and failed GET stays locked; GET recovery never repeats mutation',async()=>{
  let readFail=false;await mount(c=>{if(c.method==='post'){Object.assign(serverData.refunds[0],{amount:c.body.amount,reason:c.body.reason,status:'APPROVED',version:1});readFail=true;return serverData.refunds[0];}if(readFail)throw failure();return copy(serverData);});
  await approve();await click('Xác nhận thao tác');assert.match(container.textContent,/Máy chủ đã nhận thao tác nhưng chưa tải lại/);assert.equal(field('Lý do quyết định').matches(':disabled'),true);readFail=false;await click('Kiểm tra kết quả thao tác');assert.equal(posts().length,1);assert.ok(button('Bắt đầu xử lý hoàn (QR DEMO)'));
});
test('timeout after store is recovered by matching refund, without a second POST',async()=>{
  await mount(c=>{if(c.method==='post'){Object.assign(serverData.refunds[0],{amount:c.body.amount,reason:c.body.reason,status:'APPROVED',version:1});throw failure();}return copy(serverData);});await approve();await click('Xác nhận thao tác');assert.equal(posts().length,1);assert.ok(button('Bắt đầu xử lý hoàn (QR DEMO)'));
});
test('timeout before store freezes action; repeated result checks are GET only',async()=>{
  await mount(c=>{if(c.method==='post')throw failure();return copy(serverData);});await approve();await click('Xác nhận thao tác');assert.match(container.textContent,/không thấy kết quả chưa chứng minh/);await click('Kiểm tra kết quả thao tác');assert.equal(posts().length,1);assert.equal(field('Lý do quyết định').matches(':disabled'),true);
});
for(const status of [400,403,409])test(`HTTP ${status} is not a refund completion or an automatic retry`,async()=>{
  await mount(c=>{if(c.method==='post')throw failure(status);return copy(serverData);});await approve();await click('Xác nhận thao tác');assert.equal(posts().length,1);assert.equal(field('Lý do quyết định').matches(':disabled'),status===409);assert.equal(button('Xác nhận đã hoàn DEMO'),undefined);
});
test('manual request uses server available amount and cannot be offered for old existing manual request',async()=>{
  await mount(c=>{if(c.method==='post'){serverData.refunds.push({...refund,id:'new',amount:c.body.amount,reason:c.body.reason});serverData.manualRefundPaymentIds=['pay-1'];return {id:'new'};}return copy(serverData);},d=>{d.refunds=[];d.manualRefundPaymentIds=[];d.refundableAmounts['pay-1']=30000;});
  await click('Tạo yêu cầu hoàn phần chưa áp dụng');await change('Số tiền yêu cầu hoàn (VND)','20000');await change('Lý do yêu cầu hoàn','Fixture manual');await click('Tạo yêu cầu hoàn');assert.equal(posts().length,0);assert.match(container.querySelector('[role="dialog"]').textContent,/Hiện tại: Đã nhận/);assert.doesNotMatch(container.querySelector('[role="dialog"]').textContent,/Hiện tại: Đã hoàn/);await click('Xác nhận thao tác');assert.equal(posts()[0].body.amount,20000);assert.equal(button('Tạo yêu cầu hoàn phần chưa áp dụng'),undefined);
  await mount(null,d=>{d.refunds=[];d.manualRefundPaymentIds=['pay-1'];});assert.equal(button('Tạo yêu cầu hoàn phần chưa áp dụng'),undefined);
});
test('unmount during mutation ignores late response and does not GET again',async()=>{
  const pending=deferred();await mount(c=>c.method==='post'?pending.promise:copy(serverData));await approve();await click('Xác nhận thao tác');const before=requests.length;await act(async()=>root.unmount());root=null;await act(async()=>pending.resolve({}));assert.equal(requests.length,before);
});
for(const stored of [false,true])test(`completion timeout with server store=${stored} never causes another refund mutation`,async()=>{
  await mount(c=>{
    if(c.method==='post'){
      if(stored){const row=serverData.refunds[0];row.status='SUCCEEDED';row.version++;row.providerTransaction=c.body.reference;row.requestDate=new Date(new Date(c.body.transferTime).getTime()+7*3600000).toISOString().slice(0,19).replace(/\D/g,'');}
      throw failure();
    }
    return copy(serverData);
  },d=>{d.refunds[0].status='PROCESSING';});
  await completion();await click('Xác nhận thao tác');assert.equal(posts().length,1);
  if(stored){await change('Trạng thái hoàn','');assert.match(container.querySelector('[aria-label="Yêu cầu hoàn refund-1"]').textContent,/Đã hoàn/);assert.equal(button('Xác nhận đã hoàn DEMO'),undefined);}
  else {assert.equal(button('Xác nhận đã hoàn DEMO').matches(':disabled'),true);await click('Kiểm tra kết quả thao tác');assert.equal(posts().length,1);assert.match(container.querySelector('[aria-label="Yêu cầu hoàn refund-1"]').textContent,/Đang xử lý/);}
});
