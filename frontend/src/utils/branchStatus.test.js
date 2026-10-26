import assert from "node:assert/strict";
import { before, after, afterEach, test } from "node:test";
import { act, createElement as h } from "react";
import { JSDOM } from "jsdom";
import { createServer } from "vite";
import { fileURLToPath, URL } from "node:url";
import process from "node:process";
let dom, vite, createRoot, Page, Auth, Router, client;
const roots=[], requests=[], originals=new Map(), oldMode=process.env.VITE_DATA_MODE;
const branch={id:"dn",name:"Đà Nẵng",address:"Địa chỉ fixture",status:"ACTIVE",version:7};
const route={id:"dn-hn",pickupBranchId:"dn",returnBranchId:"hn",fee:200000,enabled:true,version:3};
before(async()=>{
 dom=new JSDOM("<!doctype html><html><body></body></html>",{url:"http://localhost"});
 for(const [key,value] of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,HTMLElement:dom.window.HTMLElement,Node:dom.window.Node,Event:dom.window.Event,FormData:dom.window.FormData,IS_REACT_ACT_ENVIRONMENT:true})){
  originals.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{configurable:true,writable:true,value});
 }
 process.env.VITE_DATA_MODE="api";createRoot=(await import("react-dom/client")).createRoot;
 vite=await createServer({root:fileURLToPath(new URL("../..",import.meta.url)),server:{middlewareMode:true},appType:"custom",logLevel:"error"});
 ({ApiBranchesPage:Page}=await vite.ssrLoadModule("/src/pages/api/ApiManagementScreens.jsx"));
 ({default:Auth}=await vite.ssrLoadModule("/src/context/AuthContext.jsx"));
 ({MemoryRouter:Router}=await vite.ssrLoadModule("react-router-dom"));
 ({default:client}=await vite.ssrLoadModule("/src/api/client.js"));
});
afterEach(async()=>{for(const root of roots.splice(0))await act(async()=>root.unmount());document.body.replaceChildren();requests.length=0;});
after(async()=>{await vite.close();dom.window.close();for(const [k,v]of originals){if(v)Object.defineProperty(globalThis,k,v);else delete globalThis[k];}if(oldMode===undefined)delete process.env.VITE_DATA_MODE;else process.env.VITE_DATA_MODE=oldMode;});
async function mount(role="ADMIN"){
 client.defaults.adapter=config=>new Promise((resolve,reject)=>requests.push({config,resolve,reject,claimed:false}));
 const c=document.createElement("div");document.body.append(c);const root=createRoot(c);roots.push(root);
 await act(async()=>root.render(h(Router,null,h(Auth.Provider,{value:{user:role?{role}:null}},h(Page)))));return c;
}
function take(url){const r=requests.find(r=>!r.claimed&&r.config.url===url);assert.ok(r,url);r.claimed=true;return r;}
function reply(r,data){r.resolve({config:r.config,data,status:200,statusText:"OK",headers:{}});}
function fail(r,status,message){r.reject(Object.assign(new Error(message),{config:r.config,response:{status,data:{message}}}));}
async function loaded(c,branches=[branch],routes=[route]){await act(async()=>{reply(take("/branches"),branches);reply(take("/branch-routes"),routes);});return c;}
function button(c,label){const b=[...c.querySelectorAll("button")].find(b=>(b.getAttribute("aria-label")||b.textContent.trim())===label);assert.ok(b,label);return b;}
async function click(c,label,twice=false,accept=true){await act(async()=>{const b=button(c,label);b.click();if(twice)b.click();});if(accept && c.querySelector('[role="dialog"]') && /^(Xóa chi nhánh|Ngừng nhận đơn)/.test(label)){await act(async()=>button(c,"Xác nhận").click());}}
async function fill(c,name,value){const i=c.querySelector(`[name="${name}"]`);assert.ok(i);await act(async()=>{Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype,"value").set.call(i,value);i.dispatchEvent(new dom.window.Event("input",{bubbles:true}));});}
async function submit(form){await act(async()=>{form.dispatchEvent(new dom.window.Event("submit",{bubbles:true,cancelable:true}));form.dispatchEvent(new dom.window.Event("submit",{bubbles:true,cancelable:true}));});}

test("branch deletion cancels without request, submits current version once and refreshes selectors",async()=>{
 const c=await loaded(await mount());await click(c,"Xóa chi nhánh Đà Nẵng",false,false);assert.ok(c.querySelector('[role="dialog"]'));await click(c,"Hủy");assert.equal(requests.filter(r=>r.config.method==="delete").length,0);
 await click(c,"Xóa chi nhánh Đà Nẵng",true);const r=take("/admin/branches/dn");assert.equal(r.config.method,"delete");assert.deepEqual(r.config.params,{expectedVersion:7});assert.equal(requests.filter(r=>r.config.method==="delete").length,1);assert.doesNotMatch(c.textContent,/Đã xóa chi nhánh/);
 await act(async()=>reply(r,null));await loaded(c,[],[]);assert.match(c.textContent,/Đã xóa chi nhánh/);assert.equal(c.querySelectorAll("article").length,0);assert.equal(c.querySelectorAll('select option[value="dn"]').length,0);
});
test("referenced branch cannot disappear or claim delete success on409",async()=>{
 const c=await loaded(await mount());await click(c,"Xóa chi nhánh Đà Nẵng");await act(async()=>fail(take("/admin/branches/dn"),409,"Chi nhánh còn dữ liệu. Hãy ngừng nhận đơn mới."));
 assert.match(c.querySelector('[role="alert"]').textContent,/còn dữ liệu/);assert.equal(c.querySelectorAll("article").length,1);assert.doesNotMatch(c.textContent,/Đã xóa chi nhánh/);assert.equal(button(c,"Ngừng nhận đơn mới: Đà Nẵng").disabled,false);
});
test("deleting the selected branch cannot submit its stale ID in a route",async()=>{
 const remaining={id:"hn",name:"Hà Nội",address:"Fixture",status:"ACTIVE",version:1};const c=await loaded(await mount(),[branch,remaining],[]);
 await click(c,"Xóa chi nhánh Đà Nẵng");await act(async()=>reply(take("/admin/branches/dn"),null));await loaded(c,[remaining],[]);
 const form=[...c.querySelectorAll("form")].find(f=>f.textContent.includes("Cấu hình tuyến"));await submit(form);const sent=JSON.parse(take("/admin/branch-routes").config.data);assert.equal(sent.pickupBranchId,"hn");assert.equal(sent.returnBranchId,"hn");
});
test("branch page loading, request failure, retry and actual empty state",async()=>{
 const c=await mount();assert.ok(c.querySelector('[aria-busy="true"]'));
 await act(async()=>{fail(take("/branches"),500,"Không tải được chi nhánh");reply(take("/branch-routes"),[]);});
 assert.match(c.textContent,/Không tải được/);await click(c,"Thử tải lại");await loaded(c,[],[]);assert.match(c.textContent,/Chưa có chi nhánh/);
});
test("close and reopen use exact version; pending duplicate guard and no premature success",async()=>{
 const c=await loaded(await mount());await click(c,"Ngừng nhận đơn mới: Đà Nẵng",true);
 const write=take("/admin/branches/dn/status");assert.deepEqual(JSON.parse(write.config.data),{status:"INACTIVE",expectedVersion:7});
 assert.equal(requests.filter(r=>r.config.method==="put").length,1);assert.doesNotMatch(c.textContent,/Đã ngừng/);assert.equal(button(c,"Chỉnh sửa").disabled,true);
 await act(async()=>reply(write,{...branch,status:"INACTIVE",version:8}));await act(async()=>reply(take("/branches"),[{...branch,status:"INACTIVE",version:8}]));
 assert.match(c.textContent,/Đã ngừng nhận đơn mới/);await click(c,"Mở nhận đơn mới: Đà Nẵng");const open=take("/admin/branches/dn/status");assert.deepEqual(JSON.parse(open.config.data),{status:"ACTIVE",expectedVersion:8});
 await act(async()=>reply(open,{...branch,version:9}));await act(async()=>reply(take("/branches"),[{...branch,version:9}]));assert.match(c.textContent,/Đã mở nhận đơn mới/);assert.doesNotMatch(c.textContent,/Phiên bản/);
});
test("status 400 leaves server state visible and never claims success",async()=>{
 const c=await loaded(await mount());await click(c,"Ngừng nhận đơn mới: Đà Nẵng");await act(async()=>fail(take("/admin/branches/dn/status"),400,"Trạng thái không hợp lệ"));
 assert.match(c.textContent,/Trạng thái không hợp lệ/);assert.doesNotMatch(c.textContent,/Đã ngừng/);assert.equal(button(c,"Ngừng nhận đơn mới: Đà Nẵng").disabled,false);assert.doesNotMatch(c.textContent,/Phiên bản/);
});
test("stale 409 blocks retries until fresh reload, then uses fresh version and succeeds",async()=>{
 const c=await loaded(await mount());await click(c,"Ngừng nhận đơn mới: Đà Nẵng");await act(async()=>fail(take("/admin/branches/dn/status"),409,"Version cũ"));
 assert.equal(button(c,"Ngừng nhận đơn mới: Đà Nẵng").disabled,true);assert.doesNotMatch(c.textContent,/Đã ngừng/);
 await click(c,"Tải dữ liệu mới");await loaded(c,[{...branch,status:"INACTIVE",version:10}]);await click(c,"Mở nhận đơn mới: Đà Nẵng");
 const fresh=take("/admin/branches/dn/status");assert.deepEqual(JSON.parse(fresh.config.data),{status:"ACTIVE",expectedVersion:10});
 await act(async()=>reply(fresh,{...branch,version:11}));await act(async()=>reply(take("/branches"),[{...branch,version:11}]));assert.match(c.textContent,/Đã mở/);
});
test("rename validation keeps input, stale edit reload resets selection and uses new version",async()=>{
 const c=await loaded(await mount(),[{...branch,status:"INACTIVE"}]);await click(c,"Chỉnh sửa");await fill(c,"name","Tên mới");
 const form=c.querySelector('[name="name"]').closest("form");await submit(form);const first=take("/admin/branches/dn");assert.deepEqual(JSON.parse(first.config.data),{name:"Tên mới",address:branch.address,version:7});
 await act(async()=>fail(first,400,"Tên không hợp lệ"));assert.equal(c.querySelector('[name="name"]').value,"Tên mới");assert.match(c.textContent,/Ngừng nhận đơn mới/);
 await submit(form);await act(async()=>fail(take("/admin/branches/dn"),409,"Version cũ"));await click(c,"Tải dữ liệu mới");await loaded(c,[{...branch,name:"Bản mới",status:"INACTIVE",version:12}]);
 await click(c,"Chỉnh sửa");await fill(c,"name","Tên cuối");await submit(c.querySelector('[name="name"]').closest("form"));assert.equal(JSON.parse(take("/admin/branches/dn").config.data).version,12);
});
for(const role of [null,"USER","STAFF"])test(`only ADMIN can load/mutate branches: ${role}`,async()=>{
 const c=await mount(role);assert.match(c.textContent,/Chỉ quản trị viên/);assert.equal(requests.length,0);assert.equal(c.querySelectorAll("button").length,0);
});
test("route configuration retains both inactive branches and version contract",async()=>{
 const c=await loaded(await mount(),[{...branch,status:"INACTIVE"},{id:"hn",name:"Hà Nội",address:"HN",status:"ACTIVE",version:1}]);
 const form=[...c.querySelectorAll("form")].find(f=>f.textContent.includes("Cấu hình tuyến"));assert.equal(form.querySelectorAll("select option").length,4);
 await submit(form);const r=take("/admin/branch-routes");assert.deepEqual(JSON.parse(r.config.data),{pickupBranchId:"dn",returnBranchId:"dn",fee:0,enabled:false,expectedVersion:0});assert.equal(requests.filter(r=>r.config.method==="put").length,1);
});

test("P0-4: click Chỉnh sửa tuyến fills form, scrolls/focuses, sends no mutation, preserves one-way separation A->B vs B->A", async () => {
 const branchDN = { id: "dn", name: "Đà Nẵng", address: "DN", status: "ACTIVE", version: 7 };
 const branchHN = { id: "hn", name: "Hà Nội", address: "HN", status: "INACTIVE", version: 2 };
 const routeDNHN = { id: "dn-hn", pickupBranchId: "dn", returnBranchId: "hn", fee: 250000, enabled: true, version: 3 };
 const routeHNDN = { id: "hn-dn", pickupBranchId: "hn", returnBranchId: "dn", fee: 350000, enabled: false, version: 5 };

 const c = await loaded(await mount(), [branchDN, branchHN], [routeDNHN, routeHNDN]);

 // Check route table has 2 rows with one-way header and edit buttons
 const rows = c.querySelectorAll("table.data-table tbody tr");
 assert.equal(rows.length, 2);
 assert.match(rows[0].textContent, /Đà Nẵng.*→.*Hà Nội \(Ngừng nhận đơn\)/);
 assert.match(rows[0].textContent, /Tạm không nhận đơn mới do chi nhánh ngừng hoạt động/);
 assert.doesNotMatch(rows[0].textContent, /Phiên bản/);
 assert.doesNotMatch(rows[1].textContent, /Phiên bản/);

 // Click "Chỉnh sửa" on row 0 (DN -> HN)
 const editBtn = rows[0].querySelector("button");
 assert.ok(editBtn);
 await act(async () => editBtn.click());

 // Verify NO mutation sent on click
 assert.equal(requests.filter(r => r.config.method === "put").length, 0);

 // Form populated with DN -> HN data
 const form = [...c.querySelectorAll("form")].find(f => f.textContent.includes("Cấu hình tuyến"));
 assert.ok(form);
 const selects = form.querySelectorAll("select");
 assert.equal(selects[0].value, "dn");
 assert.equal(selects[1].value, "hn");
 const feeInput = form.querySelector('input[type="number"]');
 assert.equal(feeInput.value, "250000");
 const enabledCheckbox = form.querySelector('input[type="checkbox"]');
 assert.equal(enabledCheckbox.checked, true);

 // Inactive warning displayed in form
 assert.match(form.textContent, /Chi nhánh trả \(Hà Nội\) đang ngừng nhận đơn mới/);

 // Change fee and submit
 await act(async () => {
  const prototype = Object.getPrototypeOf(feeInput);
  const descriptor = Object.getOwnPropertyDescriptor(prototype, "value");
  if (descriptor?.set) descriptor.set.call(feeInput, "280000");
  else feeInput.value = "280000";
  feeInput.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
  feeInput.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
 });

 await submit(form);
 const putReq = take("/admin/branch-routes");
 assert.equal(putReq.config.method, "put");
 const sentData = JSON.parse(putReq.config.data);
 assert.deepEqual(sentData, {
  pickupBranchId: "dn",
  returnBranchId: "hn",
  fee: 280000,
  enabled: true,
  expectedVersion: 3,
 });

 // Resolve put and routes reload
 await act(async () => {
  reply(putReq, null);
 });
 const reloadReq = take("/branch-routes");
 await act(async () => {
  reply(reloadReq, [{ ...routeDNHN, fee: 280000, version: 4 }, routeHNDN]);
 });

 assert.match(c.textContent, /Đã cập nhật tuyến nhận\/trả/);
});

test("P0-4: route conflict 409 does not overwrite draft, prevents silent bypass, requires explicit fresh reload", async () => {
 const branchDN = { id: "dn", name: "Đà Nẵng", address: "DN", status: "ACTIVE", version: 7 };
 const routeDNHN = { id: "dn-hn", pickupBranchId: "dn", returnBranchId: "dn", fee: 100000, enabled: true, version: 2 };

 const c = await loaded(await mount(), [branchDN], [routeDNHN]);
 const form = [...c.querySelectorAll("form")].find(f => f.textContent.includes("Cấu hình tuyến"));
 const feeInput = form.querySelector('input[type="number"]');

 await act(async () => {
  const prototype = Object.getPrototypeOf(feeInput);
  const descriptor = Object.getOwnPropertyDescriptor(prototype, "value");
  if (descriptor?.set) descriptor.set.call(feeInput, "150000");
  else feeInput.value = "150000";
  feeInput.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
  feeInput.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
 });

 await submit(form);
 const putReq = take("/admin/branch-routes");
 assert.equal(JSON.parse(putReq.config.data).expectedVersion, 2);

 // 409 conflict
 await act(async () => fail(putReq, 409, "Tuyến đã bị sửa ở phiên khác"));

 // Verify conflict notice and disabled save button
 assert.match(c.textContent, /409: Tuyến nhận\/trả đã bị thay đổi ở phiên khác/);
 const saveBtn = [...form.querySelectorAll("button")].find(b => b.textContent.includes("Lưu tuyến"));
 assert.equal(saveBtn.disabled, true);
 // Draft fee is preserved
 assert.equal(feeInput.value, "150000");

 // Explicit fresh reload
 const reloadBtn = [...c.querySelectorAll("button")].find(b => b.textContent.includes("Tải lại dữ liệu tuyến"));
 assert.ok(reloadBtn);
 await act(async () => reloadBtn.click());

 const freshRoutesReq = take("/branch-routes");
 await act(async () => reply(freshRoutesReq, [{ ...routeDNHN, version: 3, fee: 120000 }]));

 // Conflict cleared
 assert.doesNotMatch(c.textContent, /409: Tuyến nhận\/trả đã bị thay đổi/);
});
