import assert from "node:assert/strict";
import { before, after, afterEach, test } from "node:test";
import { act, createElement as h } from "react";
import { JSDOM } from "jsdom";
import { createServer } from "vite";
import { fileURLToPath, URL } from "node:url";
import process from "node:process";
import { vehicleBrandOptions } from "./vehicleBrands.js";
let dom,vite,createRoot,BrandBar,List,Auth,Router,Routes,Route,useNavigate;
const roots=[],originals=new Map(),requests=[],oldMode=process.env.VITE_DATA_MODE;
const catalog={brands:[{id:"alpha",name:"Alpha"},{id:"beta",name:"Beta"},{id:"unused",name:"Unused"}],categories:[{id:"sedan",name:"Sedan"}]};
const vehicles=[{id:"v1",name:"Alpha Sedan",brandId:"alpha",categoryId:"sedan",status:"ACTIVE",transmission:"AUTO",seats:5,dailyPrice:650000},{id:"v2",name:"Beta Van",brandId:"beta",categoryId:"sedan",status:"MAINTENANCE",transmission:"MANUAL",seats:7,dailyPrice:750000}];
before(async()=>{
 dom=new JSDOM("<!doctype html><html><body></body></html>",{url:"http://localhost"});
 for(const [key,value]of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,HTMLElement:dom.window.HTMLElement,Node:dom.window.Node,Event:dom.window.Event,IS_REACT_ACT_ENVIRONMENT:true})){originals.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{configurable:true,writable:true,value});}
 process.env.VITE_DATA_MODE="api";createRoot=(await import("react-dom/client")).createRoot;
 vite=await createServer({root:fileURLToPath(new URL("../..",import.meta.url)),server:{middlewareMode:true},appType:"custom",logLevel:"error"});
 ({default:BrandBar}=await vite.ssrLoadModule("/src/components/vehicle/BrandBar.jsx"));({ApiVehicleListPage:List}=await vite.ssrLoadModule("/src/pages/api/ApiScreens.jsx"));({default:Auth}=await vite.ssrLoadModule("/src/context/AuthContext.jsx"));
 ({MemoryRouter:Router,Routes,Route,useNavigate}=await vite.ssrLoadModule("react-router-dom"));const {default:client}=await vite.ssrLoadModule("/src/api/client.js");
 client.defaults.adapter=async config=>{requests.push(config);return{config,data:config.url==="/vehicles"?vehicles:catalog,status:200,statusText:"OK",headers:{}};};
});
afterEach(async()=>{for(const r of roots.splice(0))await act(async()=>r.unmount());document.body.replaceChildren();requests.length=0;});
after(async()=>{await vite.close();dom.window.close();for(const[k,v]of originals){if(v)Object.defineProperty(globalThis,k,v);else delete globalThis[k];}if(oldMode===undefined)delete process.env.VITE_DATA_MODE;else process.env.VITE_DATA_MODE=oldMode;});
async function mount(element,entry="/vehicles"){const c=document.createElement("div");document.body.append(c);const r=createRoot(c);roots.push(r);await act(async()=>r.render(h(Router,{initialEntries:[entry]},h(Auth.Provider,{value:{user:null}},element))));return c;}
function HistoryButtons(){const go=useNavigate();return h("div",null,h("button",{onClick:()=>go(-1)},"Back"),h("button",{onClick:()=>go(1)},"Forward"));}
function titles(c){return [...c.querySelectorAll('.vehicle-card h2')].map(e=>e.textContent);}
async function change(input,value){await act(async()=>{const p=input.tagName==="SELECT"?dom.window.HTMLSelectElement.prototype:dom.window.HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(p,"value").set.call(input,value);input.dispatchEvent(new dom.window.Event(input.tagName==="SELECT"?"change":"input",{bubbles:true}));});}
test("brands come only from actual vehicles, sorted by count then name; no classified data falls back empty",()=>{
 assert.deepEqual(vehicleBrandOptions([...vehicles,{brandId:"beta"},{brandId:null}],catalog).map(b=>[b.id,b.count]),[["beta",2],["alpha",1]]);
 assert.deepEqual(vehicleBrandOptions([{brand:"not-a-real-id"}],catalog),[]);assert.deepEqual(vehicleBrandOptions(null,catalog),[]);
 assert.equal(vehicleBrandOptions([{brandId:"new-id"}],{} )[0].name,"new-id");
});
test("top seven and other dropdown are dynamic, accessible, and use exact selected query",async()=>{
 const brands=Array.from({length:10},(_,i)=>({id:"brand-"+i,name:"Hãng "+i}));const raw=brands.map(b=>({brandId:b.id}));
 const c=await mount(h(BrandBar,{vehicles:raw,catalog:{brands}}),"/vehicles?brand=brand-9");const nav=c.querySelector('nav[aria-label="Lọc theo hãng xe"]');assert.ok(nav);assert.equal(nav.querySelectorAll("a").length,8);
 const more=nav.querySelector("button");assert.equal(more.getAttribute("aria-current"),"true");await act(async()=>more.click());assert.equal(more.getAttribute("aria-expanded"),"true");
 assert.equal(nav.querySelectorAll('.brand-bar-dropdown a').length,3);assert.equal(nav.querySelector('a[aria-current="true"]').textContent,"Hãng 9");assert.equal(nav.querySelector('a[aria-current="true"]').getAttribute("href"),"/vehicles?brand=brand-9");
 await act(async()=>more.dispatchEvent(new dom.window.KeyboardEvent("keydown",{key:"Escape",bubbles:true})));assert.equal(more.getAttribute("aria-expanded"),"false");assert.equal(document.activeElement,more);
});
test("missing brand data or API error keeps only All and no fabricated brand links",async()=>{
 const c=await mount(h(BrandBar,{vehicles, catalog,error:true}));assert.equal(c.querySelectorAll('nav a').length,1);assert.equal(c.querySelector('nav a').textContent,"Tất cả xe");
});
test("query brand filters actual list; All clears brand; browser back/forward recomputes without duplicate fetches",async()=>{
 const c=await mount(h(Routes,null,h(Route,{path:"/vehicles",element:h("div",null,h(List),h(HistoryButtons))})),"/vehicles?brand=alpha");assert.deepEqual(titles(c),["Alpha Sedan"]);assert.equal(requests.length,3);
 await act(async()=>[...c.querySelectorAll('nav a')].find(e=>e.textContent==="Beta").click());assert.deepEqual(titles(c),["Beta Van"]);assert.equal(requests.length,3);
 await act(async()=>[...c.querySelectorAll('button')].find(e=>e.textContent==="Back").click());assert.deepEqual(titles(c),["Alpha Sedan"]);
 await act(async()=>[...c.querySelectorAll('button')].find(e=>e.textContent==="Forward").click());assert.deepEqual(titles(c),["Beta Van"]);
 await act(async()=>[...c.querySelectorAll('nav a')].find(e=>e.textContent==="Tất cả xe").click());assert.deepEqual(titles(c),["Alpha Sedan","Beta Van"]);
});
test("brand composes with instant text and seat filters",async()=>{
 const c=await mount(h(List),"/vehicles?brand=alpha");assert.deepEqual(titles(c),["Alpha Sedan"]);
 const seats=c.querySelector('select[id$="-seats"]');await change(seats,"7");assert.deepEqual(titles(c),[]);await change(seats,"");assert.deepEqual(titles(c),["Alpha Sedan"]);
 await change(c.querySelector('input[placeholder="Tên xe, mẫu xe..."]'),"Beta");assert.deepEqual(titles(c),[]);assert.equal(c.querySelectorAll('nav a').length,3,"bar counts not derived from filtered cars");
});
test("unknown query never silently returns all vehicles",async()=>{const c=await mount(h(List),"/vehicles?brand=unknown");assert.deepEqual(titles(c),[]);assert.equal(c.querySelector('nav a[aria-current="true"]'),null);});
