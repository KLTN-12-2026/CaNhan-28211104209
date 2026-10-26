import assert from "node:assert/strict";
import {before,after,test} from "node:test";
import process from "node:process";
import {fileURLToPath, URL} from "node:url";
import {displayCalendarDate, parseCalendarDate} from "./utils/calendarDate.js";
import {act,createElement as h} from "react";
import {JSDOM} from "jsdom";
import {createServer} from "vite";
let dom,vite,createRoot,MemoryRouter,Routes,Route,Link,NavigationEffects,Modal,Status,Notice,Page,DateField,AdminBookingTable,ApiVehicleCatalogPage,ApiVehiclesAdminPage,apiClient,AdminAreaContext;
const descriptors=new Map();
before(async()=>{
 process.env.VITE_DATA_MODE="api";dom=new JSDOM("<!doctype html><body></body>",{url:"http://localhost/"});
 for(const [key,value] of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,HTMLElement:dom.window.HTMLElement,Node:dom.window.Node,localStorage:dom.window.localStorage,IS_REACT_ACT_ENVIRONMENT:true})) {descriptors.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{configurable:true,writable:true,value});}
 ({createRoot}=await import("react-dom/client"));({MemoryRouter,Routes,Route,Link}=await import("react-router-dom"));
 vite=await createServer({root:fileURLToPath(new URL("..",import.meta.url)),logLevel:"error",optimizeDeps:{noDiscovery:true},server:{middlewareMode:true}});
 ({default:NavigationEffects}=await vite.ssrLoadModule("/src/routes/NavigationEffects.jsx"));({default:Modal}=await vite.ssrLoadModule("/src/components/common/Modal.jsx"));
 ({Status,Notice,Page}=await vite.ssrLoadModule("/src/components/common/PageKit.jsx"));
 ({default:DateField}=await vite.ssrLoadModule("/src/components/common/DateField.jsx"));
 ({default:AdminAreaContext}=await vite.ssrLoadModule("/src/context/AdminAreaContext.js"));
 ({default:apiClient}=await vite.ssrLoadModule("/src/api/client.js"));
 ({default:ApiVehicleCatalogPage}=await vite.ssrLoadModule("/src/pages/api/ApiVehicleCatalogPage.jsx"));
 ({ApiVehiclesAdminPage}=await vite.ssrLoadModule("/src/pages/api/ApiManagementScreens.jsx"));
 ({default:AdminBookingTable}=await vite.ssrLoadModule("/src/components/booking/AdminBookingTable.jsx"));
});
after(async()=>{await vite?.close();dom.window.close();for(const [k,d] of descriptors){if(d)Object.defineProperty(globalThis,k,d);else delete globalThis[k];}});
async function mount(node){const host=document.createElement("div");document.body.append(host);const root=createRoot(host);await act(async()=>root.render(node));return {host,async close(){await act(async()=>root.unmount());host.remove();}};}
test("Admin navigation resets its own scroll and focuses new heading",async()=>{
 const shell=document.createElement("main");shell.dataset.workspaceScroll="";document.body.append(shell);shell.scrollTop=230;let windowScrolls=0;window.scrollTo=()=>{windowScrolls++;};
 const page=await mount(h(MemoryRouter,null,h(NavigationEffects),h(Link,{to:"/second"},"Next"),h(Routes,null,h(Route,{path:"*",element:h("main",null,h("h1",null,"Title"))}))));
 assert.equal(shell.scrollTop,0);assert.equal(windowScrolls,0);shell.scrollTop=400;await act(async()=>page.host.querySelector("a").click());assert.equal(shell.scrollTop,0);assert.equal(document.activeElement.textContent,"Title");await page.close();shell.remove();
});
test("Modal locks and restores Admin scroller, Escape and focus",async()=>{
 const scroller=document.createElement("main");scroller.dataset.workspaceScroll="";scroller.style.overflow="auto";document.body.append(scroller);const trigger=document.createElement("button");scroller.append(trigger);trigger.focus();let closed=0;
 const page=await mount(h(Modal,{open:true,title:"Xác nhận",onClose:()=>closed++},h("button",null,"Hủy")));assert.equal(scroller.style.overflow,"hidden");assert.ok(page.host.querySelector('[role="dialog"]'));await act(async()=>window.dispatchEvent(new dom.window.KeyboardEvent("keydown",{key:"Escape",bubbles:true})));assert.equal(closed,1);await page.close();assert.equal(scroller.style.overflow,"auto");assert.equal(document.activeElement,trigger);scroller.remove();
});

test("Date parser rejects impossible days and preserves leap days without timezone conversion",()=>{
 assert.equal(parseCalendarDate("29/02/2028"),"2028-02-29");
 for(const value of ["29/02/2026","31/04/2026","10/31/2026","1/10/2026", ""] ) assert.equal(parseCalendarDate(value),"");
 assert.equal(displayCalendarDate("2026-10-31"),"31/10/2026");
});
test("Admin breadcrumb is scoped and all booking states have distinct tones",async()=>{
 const states=["PENDING","CONFIRMED","IN_PROGRESS","RETURNED","COMPLETED","CANCELLED"];
 const page=await mount(h(MemoryRouter,{initialEntries:["/admin/vehicles"]},h(AdminAreaContext.Provider,{value:true},h(Page,{title:"Quản lý xe"},states.map(s=>h(Status,{key:s,status:s}))))));
 assert.equal(page.host.querySelector(".eyebrow").textContent,"Quản trị / Quản lý xe");
 const badges=[...page.host.querySelectorAll("span.rounded-full")];assert.equal(badges.length,6);assert.equal(new Set(badges.map(x=>x.className)).size,6);
 await page.close();
 const user=await mount(h(MemoryRouter,{initialEntries:["/user/bookings"]},h(Page,{title:"Đơn"})));assert.match(user.host.textContent,/HÀNH TRÌNH CỦA BẠN/);await user.close();
});
test("Report date display is dd/mm/yyyy even when browser locale is English",async()=>{
 const page=await mount(h(DateField,{value:"2026-10-31",onChange:()=>{},required:true,"aria-label":"Đến ngày"}));
 assert.equal(page.host.querySelector('input[type="text"]').value,"31/10/2026");assert.equal(page.host.querySelector('input[type="date"]').value,"2026-10-31");assert.ok(page.host.querySelector('button[aria-label="Mở lịch Đến ngày"]'));await page.close();
});
test("Admin table preserves identity, Vietnamese dates, copy and detail links",async()=>{
 let copied="";const booking={id:"12345678-full-id",customerName:"Khách kiểm thử",vehicleName:"Xe kiểm thử",status:"IN_PROGRESS",total:2100000,policyVersion:"calendar-2026-10-17",startAt:"2026-10-22T00:00:00+07:00",endAt:"2026-10-23T00:00:00+07:00",pickupName:"Đà Nẵng",returnName:"Huế"};
 const page=await mount(h(MemoryRouter,null,h(AdminBookingTable,{bookings:[booking],onCopy:id=>{copied=id;},copyFeedback:{},manualCopyId:null})));
 assert.equal(page.host.querySelectorAll("tbody tr").length,1);assert.equal(page.host.querySelector("a").getAttribute("href"),"/admin/bookings/12345678-full-id");assert.match(page.host.textContent,/22\/10\/2026/);assert.match(page.host.textContent,/Đang thuê/);await act(async()=>page.host.querySelector("button").click());assert.equal(copied,booking.id);assert.equal(page.host.querySelector("input").value,booking.id);await page.close();
});

function button(host,text){const found=[...host.querySelectorAll("button")].find(x=>x.textContent===text);assert.ok(found,`Button ${text}`);return found;}
function mockApi(getData, mutate){apiClient.defaults.adapter=async config=>({data:config.method==="get"?getData(config.url):await mutate(config),status:200,statusText:"OK",headers:{},config});}
test("Catalog cancel never sends DELETE; confirmation keeps revision and success toast",async()=>{
 const calls=[];let removed=false;
 mockApi(url=>url.includes("vehicle-brands")&&!removed?[{id:"brand-1",name:"Hãng thử",revision:7}]:[],async c=>{calls.push(c);removed=true;return {};});
 const page=await mount(h(MemoryRouter,{initialEntries:["/admin/catalog"]},h(ApiVehicleCatalogPage)));
 await act(async()=>page.host.querySelector('button[aria-label="Xóa Hãng thử"]').click());assert.equal(calls.length,0);assert.ok(page.host.querySelector('[role="dialog"]'));
 await act(async()=>button(page.host,"Hủy").click());assert.equal(calls.length,0);assert.equal(page.host.querySelector('[role="dialog"]'),null);
 await act(async()=>page.host.querySelector('button[aria-label="Xóa Hãng thử"]').click());await act(async()=>button(page.host,"Xác nhận").click());assert.equal(calls.length,1);assert.equal(calls[0].params.expectedRevision,7);assert.equal(calls[0].method,"delete");assert.match(page.host.querySelector('.admin-toast').textContent,/Đã xóa/);assert.equal(page.host.querySelector('button[aria-label="Xóa Hãng thử"]'),null);
 await act(async()=>page.host.querySelector('button[aria-label="Đóng thông báo"]').click());assert.equal(page.host.querySelector('.admin-toast'),null);await page.close();
});
test("Catalog API conflict retains data and exposes error toast instead of success",async()=>{
 mockApi(url=>url.includes("vehicle-brands")?[{id:"brand-1",name:"Hãng thử",revision:7}]:[],async()=>{throw {response:{status:409,data:{message:"Danh mục đang được sử dụng"}}};});
 const page=await mount(h(MemoryRouter,{initialEntries:["/admin/catalog"]},h(ApiVehicleCatalogPage)));
 await act(async()=>page.host.querySelector('button[aria-label="Xóa Hãng thử"]').click());await act(async()=>button(page.host,"Xác nhận").click());assert.ok(page.host.querySelector('button[aria-label="Xóa Hãng thử"]'));assert.ok(page.host.querySelector('.admin-toast[role="alert"]'));assert.equal(page.host.querySelector('.admin-toast[role="status"]'),null);await page.close();
});
test("Vehicle create form waits for explicit brand/category and edit keeps existing IDs",async()=>{
 mockApi(url=>url==="/vehicles"?[{id:"v1",name:"Xe thử",plate:"AB-123",brandId:"b1",categoryId:"c1",homeBranchId:"dn",currentBranchId:"dn",seats:5,dailyPrice:600000,status:"ACTIVE",transmission:"AUTO",version:2}]:url==="/vehicles/catalog"?{brands:[{id:"b1",name:"Ford"}],categories:[{id:"c1",name:"Sedan"}]}:url==="/branches"?[{id:"dn",name:"Đà Nẵng",status:"ACTIVE"}]:[],async()=>({}));
 const page=await mount(h(MemoryRouter,{initialEntries:["/admin/vehicles"]},h(ApiVehiclesAdminPage,{})));
 const selects=()=>[...page.host.querySelectorAll("select")].filter(x=>x.querySelector('option[value="b1"],option[value="c1"]'));
 assert.equal(selects().length,2);assert.deepEqual(selects().map(x=>x.value),["",""]);assert.ok(selects().every(x=>x.required));assert.match(selects()[0].options[0].textContent,/Chọn hãng xe/);
 await act(async()=>button(page.host,"Chỉnh sửa").click());assert.deepEqual(selects().map(x=>x.value),["b1","c1"]);await page.close();
});

test("A newer Admin toast replaces the previous toast instead of overlapping",async()=>{
 const page=await mount(h("div",null,h(Notice,{toast:true},"Đã lưu hãng xe"),h(Notice,{toast:true,error:true},"Không thể lưu loại xe")));
 assert.equal(page.host.querySelectorAll(".admin-toast").length,1);assert.match(page.host.querySelector(".admin-toast").textContent,/Không thể lưu loại xe/);await page.close();
});
