import assert from "node:assert/strict";
import { before, after, afterEach, test } from "node:test";
import { act, createElement as h } from "react";
import { JSDOM } from "jsdom";
import { createServer } from "vite";
import { fileURLToPath, URL, URLSearchParams } from "node:url";
import process from "node:process";
import { searchParams, searchSuffix, tripSearch, tripErrors, formatBudget } from "./vehicleSearch.js";
const query="start=2026-11-20T10%3A00&end=2026-11-21T10%3A00&pickupBranchId=dn&returnBranchId=hn&budget=2200000";
test('Date-only trip sends dates without hidden hours; today is valid and return must be later',()=>{
 const p=new URLSearchParams({start:'2026-10-17',end:'2026-10-21',pickupBranchId:'dn',returnBranchId:'dn'}),result=tripSearch(p),url=new URL(result.path,'http://localhost');
 assert.equal(url.searchParams.get('pickupDate'),'2026-10-17');assert.equal(url.searchParams.get('returnDate'),'2026-10-21');assert.equal(url.searchParams.has('startAt'),false);assert.equal(url.searchParams.has('endAt'),false);
 assert.deepEqual(tripErrors({start:'2026-10-17',end:'2026-10-21',pickupBranchId:'dn'},Date.parse('2026-10-17T15:00:00Z')),{});
 for(const bad of ['2026-02-30','2026-99-01','bad'])assert.ok(tripSearch(new URLSearchParams({...Object.fromEntries(p),start:bad})).error);
 assert.ok(tripErrors({start:'2026-10-16',end:'2026-10-21',pickupBranchId:'dn'},Date.parse('2026-10-17T15:00:00Z')).start);
});
test("trip URL converts Vietnam time, preserves only supported keys, clears brand independently",()=>{
 const p=new URLSearchParams(query+"&brand=a&token=private");
 const result=tripSearch(p); assert.equal(result.error,"");
 const api=new URL(result.path,"http://localhost");assert.equal(api.searchParams.get("startAt"),"2026-11-20T03:00:00.000Z");assert.equal(api.searchParams.get("budget"),"2200000");
 const next=searchParams(p,{brand:""});assert.equal(next.has("token"),false);assert.equal(next.has("brand"),false);assert.equal(next.get("pickupBranchId"),"dn");assert.ok(searchSuffix(next).startsWith("?"));
});
test("partial trip, malformed dates, reversed time, fractional and unsafe budgets cannot fall back to catalog",()=>{
 assert.equal(tripSearch(new URLSearchParams()).active,false);
 for(const suffix of ["start=bad",query.replace("2026-11-21","2026-11-19"),query.replace("2200000","1.5"),query.replace("2200000","9007199254740992")]) {
  const result=tripSearch(new URLSearchParams(suffix));assert.equal(result.active,true);assert.ok(result.error);assert.equal(result.path,"");
 }
});
let dom,vite,createRoot,List,Detail,Booking,Router,Routes,Route,client,Auth;
const roots=[],originals=new Map(),oldMode=process.env.VITE_DATA_MODE;
const car={id:"v1",name:"Search Sedan",brandId:"a",categoryId:"sedan",status:"ACTIVE",transmission:"AUTO",seats:5,dailyPrice:650000};
const catalog={brands:[{id:"a",name:"Alpha"}],categories:[{id:"sedan",name:"Sedan"},{id:"compact",name:"Hatchback"}]};
const branches=[{id:"dn",name:"Đà Nẵng",status:"ACTIVE"},{id:"hn",name:"Hà Nội",status:"ACTIVE"}];
let searchHandler;
before(async()=>{
 dom=new JSDOM("<!doctype html><html><body></body></html>",{url:"http://localhost"});
 for(const [key,value]of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,HTMLElement:dom.window.HTMLElement,Node:dom.window.Node,Event:dom.window.Event,IS_REACT_ACT_ENVIRONMENT:true})){originals.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{configurable:true,writable:true,value});}
 process.env.VITE_DATA_MODE="api";createRoot=(await import("react-dom/client")).createRoot;
 vite=await createServer({root:fileURLToPath(new URL("../..",import.meta.url)),server:{middlewareMode:true},appType:"custom",logLevel:"error"});
 ({ApiVehicleListPage:List,ApiVehicleDetailPage:Detail,ApiNewBookingPage:Booking}=await vite.ssrLoadModule("/src/pages/api/ApiScreens.jsx"));({default:Auth}=await vite.ssrLoadModule("/src/context/AuthContext.jsx"));
 ({MemoryRouter:Router,Routes,Route}=await vite.ssrLoadModule("react-router-dom"));({default:client}=await vite.ssrLoadModule("/src/api/client.js"));
 client.defaults.adapter=async config=>({config,data:config.url.startsWith("/vehicles/search?")?await searchHandler(config):config.url==="/vehicles"?[car]:config.url==="/vehicles/catalog"?catalog:config.url==="/branches"?branches:config.url==="/branch-routes"?[{id:"dn-hn",enabled:true,pickupBranchId:"dn",returnBranchId:"hn"}]:config.url.endsWith("/reviews")?[]:car,status:200,statusText:"OK",headers:{}});
});
afterEach(async()=>{for(const root of roots.splice(0))await act(async()=>root.unmount());document.body.replaceChildren();});
after(async()=>{await vite.close();dom.window.close();for(const[k,v]of originals){if(v)Object.defineProperty(globalThis,k,v);else delete globalThis[k];}if(oldMode===undefined)delete process.env.VITE_DATA_MODE;else process.env.VITE_DATA_MODE=oldMode;});
async function mount(entry){
 const c=document.createElement("div");document.body.append(c);const root=createRoot(c);roots.push(root);
 const routes=h(Routes,null,h(Route,{path:"/vehicles",element:h(List)}),h(Route,{path:"/vehicles/:vehicleId",element:h(Detail)}),h(Route,{path:"/user/bookings/new",element:h(Booking)}));
 await act(async()=>root.render(h(Router,{initialEntries:[entry]},h(Auth.Provider,{value:{user:{id:"u",role:"USER"}}},routes))));return c;
}
test("server total is displayed and trip survives brand, detail, back link and booking defaults",async()=>{
 searchHandler=async()=>({items:[{vehicle:car,total:2150000,rentalDays:1}]});const c=await mount("/vehicles?"+query);
 assert.match(c.querySelector(".vehicle-card").textContent,/Tổng chuyến:.*2\.150\.000/);
 const brand=[...c.querySelectorAll("nav a")].find(a=>a.textContent==="Alpha");assert.equal(new URL(brand.href).searchParams.get("budget"),"2200000");
 await act(async()=>c.querySelector(".vehicle-card h2 a").click());
 const back=[...c.querySelectorAll("a")].find(a=>a.textContent==="Về danh sách xe");assert.equal(new URL(back.href).searchParams.get("pickupBranchId"),"dn");
 const booking=[...c.querySelectorAll("a")].find(a=>a.getAttribute("href").startsWith("/user/bookings/new"));await act(async()=>booking.click());
 assert.deepEqual([...c.querySelectorAll('input[type="date"]')].map(i=>i.value),["2026-11-20","2026-11-21"]);
 assert.deepEqual([...c.querySelectorAll("select")].slice(0,2).map(i=>i.value),["dn","hn"]);
 const listBack=[...c.querySelectorAll("a")].find(a=>a.textContent==="Quay lại danh sách xe");assert.equal(new URL(listBack.href).searchParams.get("budget"),"2200000");
});
test("API error hides catalog and retry fetches real search; empty result remains empty",async()=>{
 searchHandler=async()=>{throw new Error("search unavailable");};const c=await mount("/vehicles?"+query);assert.equal(c.querySelectorAll(".vehicle-card").length,0);assert.match(c.textContent,/search unavailable/);
 searchHandler=async()=>({items:[]});await act(async()=>[...c.querySelectorAll("button")].find(b=>b.textContent==="Thử tải lại").click());assert.equal(c.querySelectorAll(".vehicle-card").length,0);assert.match(c.textContent,/Không tìm thấy xe/);
});

function input(c,key){return c.querySelector(`[id$="-${key}"]`);}
function button(c,text){return [...c.querySelectorAll("button")].find(b=>b.textContent===text);}
async function change(control,value){await act(async()=>{
 const prototype=control.tagName==="SELECT"?dom.window.HTMLSelectElement.prototype:dom.window.HTMLInputElement.prototype;
 Object.getOwnPropertyDescriptor(prototype,"value").set.call(control,value);
 control.dispatchEvent(new dom.window.Event(control.tagName==="SELECT"?"change":"input",{bubbles:true}));
});}
test("validation uses Vietnam future time, 90-day cap, positive interval and integer budget",()=>{
 const now=Date.parse("2026-10-14T00:00:00Z");
 assert.deepEqual(tripErrors({},now),{});
 const valid={start:"2026-11-20T10:00",end:"2026-11-21T10:00",pickupBranchId:"dn",budget:"3000000"};
 assert.deepEqual(tripErrors(valid,now),{});assert.equal(formatBudget("3000000"),"3.000.000");
 assert.ok(tripErrors({...valid,start:"2026-10-13T10:00"},now).start);
 assert.ok(tripErrors({...valid,end:valid.start},now).end);
 assert.ok(tripErrors({...valid,end:"2027-04-20T10:00"},now).end);
 assert.ok(tripErrors({...valid,differentReturn:true},now).returnBranchId);
 assert.ok(tripErrors({...valid,budget:"2.5"},now).budget);
 assert.equal(tripSearch(new URLSearchParams("budget=2&pickupBranchId=dn")).active,false);
 assert.equal(new URL(tripSearch(new URLSearchParams(query.replace("&returnBranchId=hn",""))).path,"http://localhost").searchParams.get("returnBranchId"),"dn");
});
test("one form defaults empty regardless of storage; reset clears unsubmitted draft and preserves brand only",async()=>{
 localStorage.setItem("seats","7");localStorage.setItem("budget","2");
 const c=await mount("/vehicles?brand=a");
 assert.equal(c.querySelectorAll("form").length,1);assert.equal(input(c,"seats").value,"");assert.equal(input(c,"budget").value,"");
 assert.equal(c.textContent.includes("Trạng thái xe"),false);assert.equal(c.querySelector("form").contains(c.querySelector("#vehicle-result-sort")),false);
 assert.equal(button(c,"Bộ lọc nâng cao ▾").getAttribute("aria-expanded"),"false");
 await act(async()=>button(c,"Bộ lọc nâng cao ▾").click());await change(input(c,"budget"),"3000000");assert.equal(input(c,"budget").value,"3.000.000");
 await change(input(c,"start"),"2026-11-20");await act(async()=>button(c,"Xóa bộ lọc").click());
 assert.equal(input(c,"start").value,"");assert.equal(input(c,"budget").value,"");assert.equal(input(c,"seats").value,"");assert.ok(button(c,"Bộ lọc nâng cao ▾"));
 assert.equal([...c.querySelectorAll("nav a")].find(a=>a.textContent==="Alpha").getAttribute("aria-current"),"true");
 localStorage.clear();
});
test("presets toggle and manual changes clear explicit selected state; restored seven/two are intentional URL values",async()=>{
 const c=await mount("/vehicles?brand=a&seats=7&budget=2");
 assert.equal(input(c,"seats").value,"7");assert.equal(input(c,"budget").value,"2");assert.ok(button(c,"Bộ lọc nâng cao ▴"));
 assert.equal(c.querySelectorAll('[aria-pressed="true"]').length,0);
 await act(async()=>button(c,"Gia đình · từ 5 chỗ").click());assert.equal(input(c,"seats").value,"5");assert.equal(button(c,"Gia đình · từ 5 chỗ").getAttribute("aria-pressed"),"true");
 await act(async()=>button(c,"Gia đình · từ 5 chỗ").click());assert.equal(input(c,"seats").value,"");
 await act(async()=>button(c,"Đi nhóm · từ 7 chỗ").click());await change(input(c,"seats"),"5");assert.equal(c.querySelectorAll('[aria-pressed="true"]').length,0);
 await act(async()=>button(c,"Đi phố · xe gọn").click());assert.equal(input(c,"category").value,"compact");
 await change(input(c,"category"),"sedan");assert.equal(button(c,"Đi phố · xe gọn").getAttribute("aria-pressed"),"false");
});
test("draft trip is submitted only on search; same-branch default, different return and field errors",async()=>{
 const requests=[];searchHandler=async config=>{requests.push(new URL(config.url,"http://localhost"));return{items:[{vehicle:car,total:650000,rentalDays:1}]};};
 const c=await mount("/vehicles");await change(input(c,"pickupBranchId"),"dn");await change(input(c,"start"),"2026-11-20");await change(input(c,"end"),"2026-11-21");assert.equal(requests.length,0);
 await change(input(c,"q"),"Search");assert.equal(requests.length,0);
 await act(async()=>button(c,"Tìm xe").click());assert.equal(requests.length,1);assert.equal(requests[0].searchParams.get("returnBranchId"),"dn");
 await act(async()=>input(c,"different").click());assert.ok(input(c,"returnBranchId"));await change(input(c,"returnBranchId"),"hn");await act(async()=>button(c,"Tìm xe").click());assert.equal(requests.at(-1).searchParams.get("returnBranchId"),"hn");
 await change(input(c,"end"),"2026-11-19");assert.equal(button(c,"Tìm xe").disabled,true);assert.match(c.textContent,/Thời gian trả phải sau/);assert.ok(input(c,"end").getAttribute("aria-describedby"));
 await change(input(c,"start"),"2020-01-01");assert.match(c.textContent,/Ngày nhận không được trước hôm nay/);
});
