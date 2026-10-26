import assert from "node:assert/strict";
import { before, after, afterEach, test } from "node:test";
import { act, createElement as h } from "react";
import { JSDOM } from "jsdom";
import { createServer } from "vite";
import { fileURLToPath, URL } from "node:url";
import process from "node:process";
import { compareSelection, toggleCompare } from "./vehicleCompare.js";
test("comparison accepts zero to three unique IDs; invalid, duplicate and fourth are explicit",()=>{
 assert.deepEqual(compareSelection().ids,[]);
 assert.deepEqual(compareSelection("v1,v2,v3").ids,["v1","v2","v3"]);
 assert.ok(compareSelection("v1,v1").issue);assert.deepEqual(compareSelection("v1,../bad").ids,["v1"]);
 assert.ok(compareSelection("v1,v2,v3,v4").issue);
 assert.ok(toggleCompare(["v1","v2","v3"],"v4").issue);
 assert.deepEqual(toggleCompare(["v1","v2"],"v1").ids,["v2"]);
});
let dom,vite,createRoot,List,Compare,Detail,Auth,Router,Routes,Route,Link,client,Favorites,FavoriteToggle;
const roots=[],originals=new Map(),oldMode=process.env.VITE_DATA_MODE;
const cars=Array.from({length:4},(_,i)=>({id:"v"+(i+1),name:"Car "+(i+1),brandId:"b",categoryId:"sedan",currentBranchId:"dn",status:"ACTIVE",transmission:"AUTO",seats:5,dailyPrice:600000+i*100000,amenities:[{id:"a",name:"Bluetooth"}]}));
const catalog={brands:[{id:"b",name:"Brand"}],categories:[{id:"sedan",name:"Sedan"}]};
let handler;
before(async()=>{
 dom=new JSDOM("<!doctype html><html><body></body></html>",{url:"http://localhost"});
 for(const[key,value]of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,HTMLElement:dom.window.HTMLElement,Node:dom.window.Node,Event:dom.window.Event,IS_REACT_ACT_ENVIRONMENT:true})){originals.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{configurable:true,writable:true,value});}
 process.env.VITE_DATA_MODE="api";createRoot=(await import("react-dom/client")).createRoot;
 vite=await createServer({root:fileURLToPath(new URL("../..",import.meta.url)),server:{middlewareMode:true},appType:"custom",logLevel:"error"});
 ({ApiVehicleListPage:List,ApiVehicleDetailPage:Detail}=await vite.ssrLoadModule("/src/pages/api/ApiScreens.jsx"));({default:Compare}=await vite.ssrLoadModule("/src/pages/api/ApiVehicleComparePage.jsx"));({default:Auth}=await vite.ssrLoadModule("/src/context/AuthContext.jsx"));
 ({MemoryRouter:Router,Routes,Route,Link}=await vite.ssrLoadModule("react-router-dom"));({default:client}=await vite.ssrLoadModule("/src/api/client.js"));
 ({default:Favorites}=await vite.ssrLoadModule("/src/pages/api/ApiVehicleFavoritesPage.jsx"));
 ({FavoriteToggle}=await vite.ssrLoadModule("/src/components/vehicle/FavoriteControls.jsx"));
 client.defaults.adapter=async config=>({config,data:await handler(config),status:200,statusText:"OK",headers:{}});
});
test("favorite controls isolate accounts, hide during hydration, restore guest and same-account selection",async()=>{
 const c=document.createElement('div');document.body.append(c);const root=createRoot(c);roots.push(root);
 const render=async value=>act(async()=>root.render(h(Auth.Provider,{value},h(FavoriteToggle,{vehicleId:'v1',name:'Car 1'}))));
 await render({user:null});await act(async()=>btn(c,'Yêu thích Car 1').click());assert.ok(btn(c,'Bỏ yêu thích Car 1'));
 await render({user:{id:'account-a'}});assert.ok(btn(c,'Yêu thích Car 1'));await act(async()=>btn(c,'Yêu thích Car 1').click());
 await render({loading:true,user:{id:'account-a'}});assert.equal(btn(c,'Yêu thích Car 1').disabled,true);
 await render({user:{id:'account-b'}});assert.equal(btn(c,'Yêu thích Car 1').getAttribute('aria-pressed'),'false');
 await render({user:{id:'account-a'}});assert.ok(btn(c,'Bỏ yêu thích Car 1'));
 await render({user:null});assert.ok(btn(c,'Bỏ yêu thích Car 1'));
});
test("favorites use current API records, retain URL, expose missing IDs and hide stale values on reload failure",async()=>{
 handler=defaults;
 const c=document.createElement('div');document.body.append(c);const root=createRoot(c);roots.push(root);
 const value={user:{id:'favorites-page'}};
 await act(async()=>root.render(h(Auth.Provider,{value},h(FavoriteToggle,{vehicleId:'v1',name:'Car 1'}))));
 await act(async()=>btn(c,'Yêu thích Car 1').click());
 await act(async()=>root.render(h(Auth.Provider,{value},h(FavoriteToggle,{vehicleId:'removed',name:'Removed'}))));
 await act(async()=>btn(c,'Yêu thích Removed').click());
 await act(async()=>root.render(h(Router,{initialEntries:['/vehicles/favorites?brand=b&seats=7']},h(Auth.Provider,{value},h(Favorites)))));
 assert.equal(c.querySelectorAll('.vehicle-card').length,1);assert.match(c.textContent,/không còn trong danh mục/);
 assert.equal(new URL(c.querySelector('a').href).searchParams.get('brand'),'b');
 handler=config=>config.url==='/vehicles'?[{...cars[0],name:'Updated API car',dailyPrice:930000,status:'MAINTENANCE'}]:defaults(config);
 await act(async()=>[...c.querySelectorAll('button')].find(b=>b.textContent==='Tải lại danh sách').click());
 assert.match(c.querySelector('.vehicle-card').textContent,/Updated API car/);assert.match(c.querySelector('.vehicle-card').textContent,/930.000/);assert.doesNotMatch(c.querySelector('.vehicle-card').textContent,/600.000/);
 handler=config=>{if(config.url==='/vehicles')throw new Error('Favorites API offline');return defaults(config);};
 await act(async()=>[...c.querySelectorAll('button')].find(b=>b.textContent==='Tải lại danh sách').click());
 assert.match(c.textContent,/Favorites API offline/);assert.equal(c.querySelectorAll('.vehicle-card').length,0);
 handler=defaults;
});
function defaults(config){return config.url==="/vehicles"?cars:config.url==="/vehicles/catalog"?catalog:config.url==="/branches"?[{id:"dn",name:"Đà Nẵng",status:"ACTIVE"}]:config.url.endsWith("/reviews")?[]:cars.find(car=>config.url==="/vehicles/"+car.id);}
afterEach(async()=>{for(const root of roots.splice(0))await act(async()=>root.unmount());document.body.replaceChildren();});
after(async()=>{await vite.close();dom.window.close();for(const[k,v]of originals){if(v)Object.defineProperty(globalThis,k,v);else delete globalThis[k];}if(oldMode===undefined)delete process.env.VITE_DATA_MODE;else process.env.VITE_DATA_MODE=oldMode;});
async function mount(entry,next=null){handler ||= defaults;const c=document.createElement("div");document.body.append(c);const root=createRoot(c);roots.push(root);
 const compare=next?h("div",null,h(Link,{to:next},"Next trip"),h(Compare)):h(Compare);
 const routes=h(Routes,null,h(Route,{path:"/vehicles",element:h(List)}),h(Route,{path:"/vehicles/compare",element:compare}),h(Route,{path:"/vehicles/:vehicleId",element:h(Detail)}));
 await act(async()=>root.render(h(Router,{initialEntries:[entry]},h(Auth.Provider,{value:{user:null}},routes))));return c;
}
function btn(c,label){return c.querySelector(`button[aria-label="${label}"]`);}
test("R3a compact list preserves entries, truthful cards, filters and removal focus",async()=>{
 handler=defaults;const c=await mount('/vehicles?brand=b&q=Car&sort=price-desc');
 assert.equal(c.querySelector('aside[aria-label="Lựa chọn so sánh"]'),null);
 assert.match(c.querySelector('.vehicle-list-toolbar').textContent,/So sánh xe \(0\)/);
 assert.match(c.querySelector('.vehicle-list-toolbar').textContent,/Xe yêu thích/);
 assert.equal(c.querySelector('.favorites-privacy').open,false);
 for(const card of c.querySelectorAll('.vehicle-card')){
  assert.ok(card.firstElementChild.querySelector('h2'));
  assert.ok(card.querySelector('.vehicle-card-actions button[aria-pressed="false"]'));
  assert.match(card.textContent,/Đang khai thác/);assert.doesNotMatch(card.textContent,/Sẵn sàng thuê|Chưa cập nhật/);
  assert.match(card.querySelector('a.btn').textContent,/Xem chi tiết/);
  assert.equal(new URL(card.querySelector('a.btn').href).searchParams.get('sort'),'price-desc');
 }
 await act(async()=>btn(c,'So sánh Car 1').click());
 const chip=btn(c,'Bỏ Car 1 khỏi lựa chọn so sánh');assert.ok(chip);
 await act(async()=>chip.click());
 assert.equal(c.querySelector('aside[aria-label="Lựa chọn so sánh"]'),null);
 assert.equal(document.activeElement.textContent,'So sánh xe (0)');
 assert.equal(new URL(document.activeElement.href).searchParams.get('brand'),'b');
 assert.equal(new URL(document.activeElement.href).searchParams.get('q'),'Car');
 await act(async()=>btn(c,'So sánh Car 2').click());
 await act(async()=>[...c.querySelectorAll('button')].find(b=>b.textContent==='Bỏ tất cả xe').click());
 assert.equal(document.activeElement.textContent,'So sánh xe (0)');
});
test("R3a invalid empty selection retains explicit feedback",async()=>{
 handler=defaults;const c=await mount('/vehicles?compare=..%2Fbad');
 const tray=c.querySelector('aside[aria-label="Lựa chọn so sánh"]');assert.ok(tray);assert.ok(tray.querySelector('[role="status"]'));
});
test("actual list selection blocks fourth, survives detail and comparison, removal keeps filters",async()=>{
 handler=defaults;const c=await mount("/vehicles?brand=b&q=Car");
 for(const i of [1,2,3]) await act(async()=>btn(c,"So sánh Car "+i).click());
 await act(async()=>btn(c,"So sánh Car 4").click());assert.match(c.textContent,/Hãy bỏ một xe/);
 await act(async()=>btn(c,"Bỏ so sánh Car 3").click());assert.doesNotMatch(c.textContent,/Hãy bỏ một xe/);
 await act(async()=>btn(c,"So sánh Car 3").click());
 const link=[...c.querySelectorAll("a")].find(a=>a.textContent==="So sánh xe (3)");assert.equal(new URL(link.href).searchParams.get("compare"),"v1,v2,v3");
 await act(async()=>link.click());assert.equal(c.querySelectorAll(".compare-column").length,3);assert.match(c.textContent,/Bluetooth/);assert.match(c.textContent,/Đà Nẵng/);
 await act(async()=>btn(c,"Bỏ Car 2").click());assert.equal(c.querySelectorAll(".compare-column").length,2);
 const back=[...c.querySelectorAll("a")].find(a=>a.textContent==="Về danh sách xe");assert.equal(new URL(back.href).searchParams.get("brand"),"b");assert.equal(new URL(back.href).searchParams.get("q"),"Car");
 await act(async()=>back.click());await act(async()=>c.querySelector('.vehicle-card h2 a').click());assert.equal(btn(c,"Bỏ so sánh Car 1").getAttribute("aria-pressed"),"true");
});
test("empty/single/invalid selection and one failed vehicle do not invent data",async()=>{
 handler=defaults;let c=await mount("/vehicles/compare");assert.match(c.textContent,/Chọn thêm xe/);assert.equal(c.querySelectorAll(".compare-column").length,0);
 c=await mount("/vehicles/compare?compare=v1");assert.equal(c.querySelectorAll(".compare-column").length,1);
 handler=config=>{if(config.url==="/vehicles/missing")throw new Error("Vehicle unavailable");return defaults(config);};
 c=await mount("/vehicles/compare?compare=v1,missing,bad%2Fid");assert.match(c.textContent,/mã xe không hợp lệ/);assert.match(c.textContent,/Vehicle unavailable/);assert.match(c.textContent,/Car 1/);
});

const trip="compare=v1,v2&start=2026-11-20T10%3A00&end=2026-11-21T10%3A00&pickupBranchId=dn&returnBranchId=dn&budget=1";
function prices(carsToPrice=cars.slice(0,2),multiplier=1){return{checkedAt:"2026-10-15T01:00:00Z",items:carsToPrice.map(vehicle=>({vehicle,rentalDays:multiplier,rentalTotal:vehicle.dailyPrice*multiplier,oneWayFee:0,total:vehicle.dailyPrice*multiplier}))};}
test("same server trip prices ignore budget exclusion, refresh changed price and correctly target booking",async()=>{
 let response=prices();const paths=[];
 handler=config=>{if(config.url.startsWith("/vehicles/search?")){paths.push(config.url);return response;}return defaults(config);};
 const c=await mount("/vehicles/compare?"+trip);
 assert.deepEqual([...c.querySelectorAll('[data-trip-total]')].map(e=>e.getAttribute('data-trip-total')),["600000","700000"]);
 assert.equal(new URL(paths[0],"http://localhost").searchParams.has("budget"),false);
 assert.match(c.textContent,/Vượt ngân sách/);
 const book=[...c.querySelectorAll('a')].filter(a=>a.textContent==="Chọn xe này & báo giá");
 const target=new URL(book[1].href);assert.equal(target.searchParams.get("vehicleId"),"v2");assert.equal(target.searchParams.get("start"),"2026-11-20T10:00");
 response=prices([{...cars[0],dailyPrice:900000},cars[1]]);
 await act(async()=>[...c.querySelectorAll('button')].find(b=>b.textContent==="Kiểm tra lại giá & lịch").click());assert.equal(c.querySelector('[data-trip-total]').getAttribute('data-trip-total'),"900000");
 response=prices([cars[1]]);await act(async()=>[...c.querySelectorAll('button')].find(b=>b.textContent==="Kiểm tra lại giá & lịch").click());
 const first=c.querySelector('.compare-column');assert.match(first.textContent,/Không còn phù hợp/);assert.equal(first.querySelector('[data-trip-total]'),null);assert.equal(first.querySelector('a.btn')===null,true);
});
test("new trip invalidates old totals and a late response cannot replace new conditions",async()=>{
 let oldResolve,newResolve;
 handler=config=>config.url.startsWith('/vehicles/search?')?new Promise(resolve=>{if(config.url.includes('2026-11-22'))newResolve=resolve;else oldResolve=resolve;}):defaults(config);
 const c=await mount('/vehicles/compare?'+trip,'/vehicles/compare?'+trip.replace('2026-11-21','2026-11-22'));
 assert.equal(c.querySelector('[data-trip-total]'),null);
 await act(async()=>[...c.querySelectorAll('a')].find(a=>a.textContent==='Next trip').click());
 await act(async()=>newResolve(prices(cars.slice(0,2),2)));assert.equal(c.querySelector('[data-trip-total]').getAttribute('data-trip-total'),'1200000');
 await act(async()=>oldResolve(prices()));assert.equal(c.querySelector('[data-trip-total]').getAttribute('data-trip-total'),'1200000');
});
test("price API failure or one vehicle failure never presents zero, stale total or a false cheapest badge",async()=>{
 handler=config=>{if(config.url.startsWith('/vehicles/search?'))throw new Error('Pricing failed');return defaults(config);};
 let c=await mount('/vehicles/compare?'+trip);assert.match(c.textContent,/Pricing failed/);assert.equal(c.querySelector('[data-trip-total]'),null);assert.equal(c.querySelector('a.btn'),null);
 handler=config=>{if(config.url==='/vehicles/v2')throw new Error('Vehicle failed');return config.url.startsWith('/vehicles/search?')?prices():defaults(config);};
 c=await mount('/vehicles/compare?'+trip);assert.match(c.textContent,/Vehicle failed/);assert.equal(c.querySelectorAll('[data-trip-total]').length,1);assert.doesNotMatch(c.textContent,/rẻ nhất/i);
});

test("UX01 compare page displays choose trip CTA when trip is inactive and guides invalid trip without pricing search",async()=>{
 let searchCalled=false;
 handler=config=>{
  if(config.url.startsWith("/vehicles/search?")){
   searchCalled=true;
   return prices();
  }
  return defaults(config);
 };
 let c=await mount("/vehicles/compare?compare=v1,v2&brand=b");
 assert.equal(searchCalled,false);
 assert.match(c.textContent,/Chưa chọn chuyến/);
 const chooseCta=[...c.querySelectorAll("a")].find(a=>a.textContent==="Chọn chuyến để kiểm tra giá");
 assert.ok(chooseCta);
 const ctaUrl=new URL(chooseCta.href,"http://localhost");
 assert.equal(ctaUrl.pathname,"/vehicles");
 assert.equal(ctaUrl.searchParams.get("compare"),"v1,v2");
 assert.equal(ctaUrl.searchParams.get("brand"),"b");
 assert.match(c.textContent,/Chọn ngày nhận, ngày trả và chi nhánh để so sánh tổng giá chuyến/);
 assert.ok([...c.querySelectorAll("button")].find(b=>b.textContent==="Tải lại thông tin xe"));
 assert.equal([...c.querySelectorAll("button")].some(b=>b.textContent==="Kiểm tra lại giá & lịch"),false);

 c=await mount("/vehicles/compare?compare=v1,v2&start=2026-11-20T10%3A00");
 assert.equal(searchCalled,false);
 assert.match(c.textContent,/Thông số chuyến chưa hợp lệ/);
 assert.match(c.textContent,/Hãy chọn đủ thời gian và điểm nhận\/trả hợp lệ để tìm xe/);
 assert.equal([...c.querySelectorAll("button")].some(b=>b.textContent==="Kiểm tra lại giá & lịch"),false);
 assert.ok([...c.querySelectorAll("a")].find(a=>a.textContent==="Thay đổi chuyến"));

 c=await mount("/vehicles/compare?"+trip);
 assert.equal(searchCalled,true);
 assert.ok([...c.querySelectorAll("button")].find(b=>b.textContent==="Kiểm tra lại giá & lịch"));
 assert.ok([...c.querySelectorAll("a")].find(a=>a.textContent==="Thay đổi chuyến"));
});

test("UX03 compare page displays standardized empty amenity advice when amenities is empty", async () => {
  handler = config => {
    if (config.url === "/vehicles/v-empty") {
      return { ...cars[0], id: "v-empty", name: "Car Empty", amenities: [] };
    }
    return defaults(config);
  };
  const c = await mount("/vehicles/compare?compare=v-empty");
  assert.match(c.textContent, /Chưa có thông tin tiện nghi được cung cấp; vui lòng xác nhận với chi nhánh\./);
  assert.doesNotMatch(c.textContent, /Xe chưa có thông tin tiện nghi\./);
});
