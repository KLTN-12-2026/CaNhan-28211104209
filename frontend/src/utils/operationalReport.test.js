import assert from "node:assert/strict";
import {before,after,afterEach,test} from "node:test";
import {JSDOM} from "jsdom";
import {createElement as h,act} from "react";
import {createServer} from "vite";
import {fileURLToPath,URL} from "node:url";
import {currentVietnamMonth,reportDateError} from "./reportDates.js";
let dom,vite,createRoot,Page,client;const roots=[],requests=[],originals=new Map();
const fixture={createdBookings:{total:4,byStatus:{PENDING:0,CONFIRMED:0,IN_PROGRESS:0,RETURNED:1,COMPLETED:1,CANCELLED:1,REJECTED:0,EXPIRED:1}},completedBookings:2,issuedInvoices:2,invoiceTotalVnd:"1150000",asOf:"2026-10-13T00:00:00Z"};
before(async()=>{
 dom=new JSDOM("<html><body></body></html>",{url:"http://localhost"});
 for(const [key,value] of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,HTMLElement:dom.window.HTMLElement,Node:dom.window.Node,Event:dom.window.Event,IS_REACT_ACT_ENVIRONMENT:true})){originals.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{configurable:true,writable:true,value});}
 createRoot=(await import("react-dom/client")).createRoot;
 vite=await createServer({root:fileURLToPath(new URL("../..",import.meta.url)),server:{middlewareMode:true},appType:"custom",logLevel:"error"});
 ({default:Page}=await vite.ssrLoadModule("/src/pages/api/ApiDashboardPage.jsx"));({default:client}=await vite.ssrLoadModule("/src/api/client.js"));
 client.defaults.adapter=config=>new Promise((resolve,reject)=>requests.push({config,resolve,reject}));
});
afterEach(async()=>{for(const root of roots.splice(0))await act(async()=>root.unmount());document.body.replaceChildren();requests.length=0;});
after(async()=>{await vite.close();dom.window.close();for(const [key,value] of originals){if(value)Object.defineProperty(globalThis,key,value);else delete globalThis[key];}});
async function mount(){const c=document.createElement("div");document.body.append(c);const root=createRoot(c);roots.push(root);await act(async()=>root.render(h(Page)));return c;}
async function fulfill(request,data){await act(async()=>request.resolve({config:request.config,data,status:200,headers:{},statusText:"OK"}));}
async function click(c,label){await act(async()=>[...c.querySelectorAll("button")].find(b=>b.textContent===label).click());}
test("Vietnam month crosses UTC boundary; valid leap and366day boundaries",()=>{
 assert.deepEqual(currentVietnamMonth(new Date("2024-01-31T17:00:00Z")),{from:"2024-02-01",to:"2024-02-29"});assert.equal(reportDateError("2024-01-01","2024-12-31"),"");
 for(const [a,b] of [["2026-02-29","2026-03-01"],["2024-01-01","2025-01-01"],["2026-10-13","2026-10-12"],["1970-01-01","1970-01-02"],["2038-01-19","2038-01-19"]])assert.ok(reportDateError(a,b));
});
test("dashboard real request, loading, exact metrics and8currentstatus rows",async()=>{
 const c=await mount();assert.ok(c.querySelector('[aria-busy="true"]'));assert.equal(requests.length,1);assert.match(requests[0].config.url,/^\/admin\/reports\/operations\?from=\d{4}-\d{2}-01&to=/);
 await fulfill(requests[0],fixture);assert.equal(c.querySelector('[data-metric="Đơn tạo trong khoảng"]').textContent,"4");assert.equal(c.querySelector('[data-metric="Đơn hoàn tất trong khoảng"]').textContent,"2");assert.equal(c.querySelector('[data-metric="Hóa đơn phát hành"]').textContent,"2");assert.match(c.querySelector('[data-metric="Tổng giá trị hóa đơn"]').textContent,/1\.150\.000/);assert.equal(c.querySelectorAll('[data-status]').length,8);assert.match(c.textContent,/không xác nhận tiền đã thanh toán/);
});
for(const status of [400,403,500])test(`dashboard ${status} error hides KPI and actual retry recovers`,async()=>{
 const c=await mount();await act(async()=>requests[0].reject(Object.assign(new Error("Server rejected"),{response:{status,data:{message:"Báo cáo không khả dụng"}},config:requests[0].config})));assert.match(c.textContent,/Báo cáo không khả dụng/);assert.equal(c.querySelector('[data-testid="report-metrics"]'),null);
 await click(c,"Thử tải lại");assert.equal(requests.length,2);await fulfill(requests[1],fixture);assert.ok(c.querySelector('[data-testid="report-metrics"]'));
});
test("empty returns honest zeros; refresh hides old metrics; money stays precise",async()=>{
 const c=await mount();const zero={...fixture,createdBookings:{total:0,byStatus:Object.fromEntries(Object.keys(fixture.createdBookings.byStatus).map(k=>[k,0]))},completedBookings:0,issuedInvoices:0,invoiceTotalVnd:"0"};await fulfill(requests[0],zero);assert.match(c.textContent,/Chưa có dữ liệu/);
 await click(c,"Làm mới");assert.equal(c.querySelector('[data-testid="report-metrics"]'),null);await fulfill(requests[1],{...fixture,invoiceTotalVnd:"18446744073709551614"});assert.match(c.querySelector('[data-metric="Tổng giá trị hóa đơn"]').textContent,/18\.446\.744\.073\.709\.551\.614/);
});
