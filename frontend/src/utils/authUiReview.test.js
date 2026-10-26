import assert from "node:assert/strict";
import { before, after, afterEach, test } from "node:test";
import { act, createElement as h, useEffect } from "react";
import { JSDOM } from "jsdom";
import { createServer } from "vite";
import { fileURLToPath, URL } from "node:url";
import process from "node:process";
import { passwordError, passwordHint } from "./passwordPolicy.js";

let dom, vite, createRoot, AuthProvider, useAuth, RegisterPage, ApiProfilePage, Navbar, WorkspaceTopbar, ApiErrorBridge, Router, Routes, Route, client;
const originals = new Map(), roots = [], writes = [];
const alerts = [];
const profile = {id:"fixture-user",fullName:"Nguyễn Test",email:"fixture@example.test",role:"USER",status:"ACTIVE",version:0};
const oldMode=process.env.VITE_DATA_MODE;
before(async()=>{
  dom=new JSDOM("<!doctype html><html><body></body></html>",{url:"http://localhost"});
  dom.window.alert = message => alerts.push({message, token:localStorage.getItem("kdrive.jwt")});
  for(const [k,v] of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,HTMLElement:dom.window.HTMLElement,Node:dom.window.Node,Event:dom.window.Event,FormData:dom.window.FormData,IS_REACT_ACT_ENVIRONMENT:true})){
    originals.set(k,Object.getOwnPropertyDescriptor(globalThis,k));Object.defineProperty(globalThis,k,{configurable:true,writable:true,value:v});
  }
  process.env.VITE_DATA_MODE="api";
  createRoot=(await import("react-dom/client")).createRoot;
  vite=await createServer({root:fileURLToPath(new URL("../..",import.meta.url)),server:{middlewareMode:true},appType:"custom",logLevel:"error"});
  ({AuthProvider,useAuth}=await vite.ssrLoadModule("/src/context/AuthContext.jsx"));
  ({default:RegisterPage}=await vite.ssrLoadModule("/src/pages/guest/RegisterPage.jsx"));
  ({ApiProfilePage}=await vite.ssrLoadModule("/src/pages/api/ApiScreens.jsx"));
  ({default:Navbar}=await vite.ssrLoadModule("/src/components/common/Navbar.jsx"));
  ({default:WorkspaceTopbar}=await vite.ssrLoadModule("/src/layouts/WorkspaceTopbar.jsx"));
  ({default:ApiErrorBridge}=await vite.ssrLoadModule("/src/routes/ApiErrorBridge.jsx"));
  ({MemoryRouter:Router,Routes,Route}=await vite.ssrLoadModule("react-router-dom"));
  ({default:client}=await vite.ssrLoadModule("/src/api/client.js"));
});
afterEach(async()=>{for(const root of roots.splice(0))await act(async()=>root.unmount());document.body.replaceChildren();localStorage.clear();writes.length=0;alerts.length=0;});
after(async()=>{await vite?.close();for(const [k,v] of originals){if(v)Object.defineProperty(globalThis,k,v);else delete globalThis[k];}if(oldMode===undefined)delete process.env.VITE_DATA_MODE;else process.env.VITE_DATA_MODE=oldMode;dom?.window.close();});
async function mount(path,element,loggedIn=false){
  if(loggedIn)localStorage.setItem("kdrive.jwt","fixture-token");
  client.defaults.adapter=config=>{
    if(config.method==="get")return Promise.resolve({config,data:config.url==="/me/license"?null:profile,status:200,statusText:"OK",headers:{}});
    return new Promise((resolve,reject)=>writes.push({config,resolve,reject}));
  };
  const container=document.createElement("div");document.body.append(container);const root=createRoot(container);roots.push(root);
  await act(async()=>root.render(h(Router,{initialEntries:[path]},h(AuthProvider,null,h(ApiErrorBridge),h(Routes,null,h(Route,{path,element:h(element)}),h(Route,{path:"/",element:h("p",null,"Home marker")}),h(Route,{path:"/login",element:h(LoginMarker)}))))));return container;
}
function LoginMarker(){const auth=useAuth();return h("div",null,h("p",null,"Login marker"),h("p",{role:"alert"},auth.logoutWarning),h("button",{onClick:()=>auth.login({email:profile.email,password:"Strong fixture 2026!"}).catch(()=>{})},"Login again"));}
async function fill(container,name,value){const input=container.querySelector(`[name="${name}"]`);assert.ok(input,name);await act(async()=>{Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype,"value").set.call(input,value);input.dispatchEvent(new dom.window.Event("input",{bubbles:true}));input.dispatchEvent(new dom.window.Event("change",{bubbles:true}));});}
async function submit(form,twice=false){await act(async()=>{form.dispatchEvent(new dom.window.Event("submit",{bubbles:true,cancelable:true}));if(twice)form.dispatchEvent(new dom.window.Event("submit",{bubbles:true,cancelable:true}));});}
function reply(write,data){write.resolve({config:write.config,data,status:200,statusText:"OK",headers:{}});}
test("password policy boundaries and composition are explicit",()=>{
  assert.equal(passwordError("87654321"),"Mật khẩu cần có: chữ hoa, chữ thường, ký tự đặc biệt.");
  for(const value of ["abcdefgh","ABCDEFGH","Abcdefgh","Abcdef12","Password1!","Ab1!"+"é".repeat(35)])assert.ok(passwordError(value));
  assert.equal(passwordError("Ax2#bcdE"),"");assert.equal(passwordError("Ab1!"+"é".repeat(34)),"");
  assert.equal(passwordError("A".repeat(73)), "Mật khẩu quá dài. Vui lòng rút ngắn mật khẩu.");
  assert.equal(passwordError("Ab1!" + "é".repeat(35)), "Mật khẩu quá dài. Vui lòng rút ngắn mật khẩu.");
  assert.doesNotMatch(passwordHint, /byte UTF-8/i);
  assert.doesNotMatch(passwordError("A".repeat(73)), /byte UTF-8/i);
});
test("registration needs one password, toggles visibility and prevents duplicate writes",async()=>{
  const c=await mount("/register",RegisterPage);
  assert.equal(Boolean(c.querySelector('[name="confirmation"]')),false);
  await fill(c,"fullName",profile.fullName);await fill(c,"email",profile.email);await fill(c,"password","abcdefgh");
  await submit(c.querySelector("form"));assert.equal(writes.length,0);assert.match(c.textContent,/chữ hoa/);
  await fill(c,"password","Strong fixture 2026!");
  const show=c.querySelector('button[aria-label="Hiện mật khẩu"]');await act(async()=>show.click());assert.equal(c.querySelector('[name="password"]').type,"text");assert.equal(c.querySelector('[name="password"]').value,"Strong fixture 2026!");
  await submit(c.querySelector("form"),true);assert.equal(writes.length,1);
  assert.deepEqual(JSON.parse(writes[0].config.data),{fullName:profile.fullName,email:profile.email,password:"Strong fixture 2026!"});
  assert.equal(c.querySelector('button[type="submit"]').disabled,true);
  await act(async()=>reply(writes[0],{token:"fixture-created-token",user:profile}));assert.match(c.textContent,/Home marker/);
});
for(const outcome of ["success","expired","forbidden","server-error","network-error"]){
 test(`navbar logout clears device immediately while API is pending: ${outcome}`,async()=>{
  const c=await mount("/account",Navbar,true);
  await act(async()=>c.querySelector('button[aria-expanded]').click());
  const logoutButton=[...c.querySelectorAll('button')].find(b=>b.textContent.includes("Đăng xuất"));assert.ok(logoutButton);
  await act(async()=>logoutButton.click());
  assert.equal(writes.length,1);assert.equal(writes[0].config.url,"/auth/logout");
  assert.equal(writes[0].config.headers.Authorization,"Bearer fixture-token");
  assert.equal(localStorage.getItem("kdrive.jwt"),null);assert.equal(localStorage.getItem("kdrive.user"),null);
  assert.match(c.textContent,/Login marker/);
  await act(async()=>{
   if(outcome==="success")reply(writes[0],null);
   else writes[0].reject(Object.assign(new Error(outcome),outcome==="network-error"?{}:{response:{status:outcome==="expired"?401:outcome==="forbidden"?403:500},config:writes[0].config}));
  });
  assert.equal(localStorage.getItem("kdrive.jwt"),null);
  if(["forbidden","server-error","network-error"].includes(outcome))assert.match(c.textContent,/Đã đăng xuất trên thiết bị này.*Chưa xác nhận/);
  else assert.doesNotMatch(c.textContent,/Chưa xác nhận/);
 });
}
for(const failed of [false,true]){
 test(`new login waits for old logout and survives its completion: failure=${failed}`,async()=>{
  const c=await mount("/workspace",WorkspaceTopbar,true);
  await act(async()=>[...c.querySelectorAll('button')].find(b=>b.textContent.includes("Đăng xuất")).click());
  assert.match(c.textContent,/Login marker/);assert.equal(writes.length,1);
  await act(async()=>[...c.querySelectorAll('button')].find(b=>b.textContent==="Login again").click());
  assert.equal(writes.length,1,"new login must wait until revocation completes");
  await act(async()=>{if(failed)writes[0].reject(new Error("Network down"));else reply(writes[0],null);});
  assert.equal(writes.length,2);assert.equal(writes[1].config.url,"/auth/login");
  await act(async()=>reply(writes[1],{token:"new-session-token",user:profile}));
  assert.equal(localStorage.getItem("kdrive.jwt"),"new-session-token");assert.doesNotMatch(c.textContent,/Chưa xác nhận/);
 });
}
test("profile mismatch blocks API; successful password change clears local session",async()=>{
  const c=await mount("/user/profile",ApiProfilePage,true);
  await fill(c,"current","Current fixture 2026!");await fill(c,"next","Next fixture password 2026!");await fill(c,"confirmation","Mismatch fixture 2026!");
  const form=c.querySelector('[name="current"]').closest("form");await submit(form);assert.equal(writes.length,0);assert.match(c.textContent,/chưa khớp/);
  await fill(c,"confirmation","Next fixture password 2026!");await submit(form,true);assert.equal(writes.length,1);assert.equal(writes[0].config.url,"/me/password");
  assert.deepEqual(JSON.parse(writes[0].config.data),{current:"Current fixture 2026!",next:"Next fixture password 2026!"});
  assert.equal(alerts.length,0,"no premature success while request is pending");
  await act(async()=>reply(writes[0],null));assert.equal(localStorage.getItem("kdrive.jwt"),null);assert.match(c.textContent,/Login marker/);
  assert.deepEqual(alerts,[{message:"Đổi mật khẩu thành công. Các phiên đăng nhập cũ đã được thu hồi. Vui lòng đăng nhập lại.",token:null}]);
});
test("profile reports composition and mismatch together without writing",async()=>{
  const c=await mount("/user/profile",ApiProfilePage,true);
  await fill(c,"current","Current fixture 2026!");await fill(c,"next","87654321");await fill(c,"confirmation","Different fixture 2026!");
  await submit(c.querySelector('[name="current"]').closest("form"));
  assert.equal(writes.length,0);assert.match(c.textContent,/Mật khẩu cần có: chữ hoa, chữ thường, ký tự đặc biệt\./);assert.match(c.textContent,/Mật khẩu nhập lại chưa khớp\./);assert.equal(alerts.length,0);
});
for(const status of [400,429]) test(`failed password update preserves session and fields without success alert: ${status}`,async()=>{
  const c=await mount("/user/profile",ApiProfilePage,true);
  await fill(c,"current","Wrong fixture 2026!");await fill(c,"next","Next fixture 2026!");await fill(c,"confirmation","Next fixture 2026!");
  await submit(c.querySelector('[name="current"]').closest("form"));
  await act(async()=>writes[0].reject(Object.assign(new Error("Rejected"),{response:{status,data:{message:"Password rejected"}},config:writes[0].config})));
  assert.equal(alerts.length,0);assert.equal(localStorage.getItem("kdrive.jwt"),"fixture-token");assert.equal(c.querySelector('[name="next"]').value,"Next fixture 2026!");assert.equal([...c.querySelectorAll('button')].find(b=>b.textContent==='Đổi mật khẩu').disabled,false);
});
test("profile phone format is checked and email remains read-only",async()=>{
  const c=await mount("/user/profile",ApiProfilePage,true);assert.equal(c.querySelector('input[readonly]').value,profile.email);
  await fill(c,"phone","abc");await submit(c.querySelector('[name="phone"]').closest("form"));assert.equal(writes.length,0);assert.match(c.textContent,/7–15 chữ số/);
});

test("late profile refresh cannot restore a logged out session or overwrite another user", async()=>{
  let captured;
  function Probe(){const auth=useAuth();useEffect(()=>{captured=auth;},[auth]);return h("p",null,auth.user?.id||"guest");}
  const c=await mount("/probe",Probe,true);
  const oldRefresh=captured.refreshUser;
  await act(async()=>captured.logout({callApi:false}));
  await act(async()=>oldRefresh({...profile,fullName:"Late profile"}));
  assert.equal(localStorage.getItem("kdrive.user"),null);assert.match(c.textContent,/guest/);
  let pending;
  await act(async()=>{pending=captured.login({email:"another@example.test",password:"Fixture password 2026!"});});
  const another={...profile,id:"another-user",email:"another@example.test"};
  await act(async()=>{reply(writes[0],{token:"another-token",user:another});await pending;});
  await act(async()=>oldRefresh({...profile,fullName:"Late profile"}));
  assert.match(c.textContent,/another-user/);assert.equal(JSON.parse(localStorage.getItem("kdrive.user")).id,"another-user");
  await act(async()=>captured.refreshUser({...another,fullName:"Updated current"}));
  assert.equal(JSON.parse(localStorage.getItem("kdrive.user")).fullName,"Updated current");
});

for(const kind of ["profile","license"]){
 test(`${kind} same-tick double submit sends exactly one write`,async()=>{
  const c=await mount("/user/profile",ApiProfilePage,true);
  if(kind==="profile")await fill(c,"phone","0901234567");
  else {await fill(c,"licenseNumber","FIXTURE-NEW-LICENSE");await fill(c,"expiresOn","2030-12-31");}
  const form=c.querySelector(`[name="${kind==="profile"?"phone":"licenseNumber"}"]`).closest("form");
  await submit(form,true);assert.equal(writes.length,1);
  assert.equal(writes[0].config.url,kind==="profile"?"/me/profile":"/me/license");
  await act(async()=>reply(writes[0],kind==="profile"?{...profile,version:1}:{id:"fixture-license",version:0}));
  assert.equal([...c.querySelectorAll('button')].find(b=>b.textContent==='Lưu hồ sơ').disabled,false);
 });
}
test("late password success does not log out a newly established session",async()=>{
 let captured;
 function Combined(){const auth=useAuth();useEffect(()=>{captured=auth;},[auth]);return h(ApiProfilePage);}
 const c=await mount("/user/profile",Combined,true);
 await fill(c,"current","Current fixture 2026!");await fill(c,"next","Next fixture 2026!");await fill(c,"confirmation","Next fixture 2026!");
 await submit(c.querySelector('[name="current"]').closest("form"));
 const oldWrite=writes[0];await act(async()=>captured.logout({callApi:false}));
 let login;await act(async()=>{login=captured.login({email:"new@example.test",password:"New fixture 2026!"});});
 await act(async()=>{reply(writes[1],{token:"new-user-token",user:{...profile,id:"new-user",email:"new@example.test"}});await login;});
 await act(async()=>reply(oldWrite,null));
 assert.equal(localStorage.getItem("kdrive.jwt"),"new-user-token");assert.equal(alerts.length,0);
 assert.equal(JSON.parse(localStorage.getItem("kdrive.user")).id,"new-user");
});

test("UX03 desktop navbar includes 'Hướng dẫn thuê xe' link next to 'Tìm xe' for guest and user", async () => {
  const guestNav = await mount("/", Navbar, false);
  const guestGuideLink = guestNav.querySelector('a[href="/rental-guide"]');
  assert.ok(guestGuideLink, "Expected /rental-guide link in guest navbar");
  assert.equal(guestGuideLink.textContent.trim(), "Hướng dẫn thuê xe");
  const guestVehiclesLink = guestNav.querySelector('a[href="/vehicles"]');
  assert.ok(guestVehiclesLink, "Expected /vehicles link in guest navbar");

  const userNav = await mount("/", Navbar, true);
  const userGuideLink = userNav.querySelector('a[href="/rental-guide"]');
  assert.ok(userGuideLink, "Expected /rental-guide link in user navbar");
  assert.equal(userGuideLink.textContent.trim(), "Hướng dẫn thuê xe");
});
