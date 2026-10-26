import assert from 'node:assert/strict';
import { before, after, afterEach, test } from 'node:test';
import { act, createElement as h } from 'react';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';
import { fileURLToPath, URL } from 'node:url';
import process from 'node:process';
let dom,vite,createRoot,Widget,Auth,Router,Link,client,auth,c,root;
const oldMode=process.env.VITE_DATA_MODE,originals=new Map(),requests=[];
const branches=[{id:'dn',name:'Đà Nẵng',status:'ACTIVE'},{id:'hn',name:'Hà Nội',status:'ACTIVE'}];
const entry='/vehicles?start=2100-10-20&end=2100-10-24&pickupBranchId=dn&returnBranchId=hn&budget=3000000';
before(async()=>{
 dom=new JSDOM('<html><body></body></html>',{url:'http://localhost'});
 for(const [k,v]of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,localStorage:dom.window.localStorage,sessionStorage:dom.window.sessionStorage,HTMLElement:dom.window.HTMLElement,Node:dom.window.Node,Event:dom.window.Event,IS_REACT_ACT_ENVIRONMENT:true})){originals.set(k,Object.getOwnPropertyDescriptor(globalThis,k));Object.defineProperty(globalThis,k,{value:v,writable:true,configurable:true});}
 process.env.VITE_DATA_MODE='api';createRoot=(await import('react-dom/client')).createRoot;vite=await createServer({root:fileURLToPath(new URL('../..',import.meta.url)),server:{middlewareMode:true},appType:'custom',logLevel:'error'});
 ({default:Widget}=await vite.ssrLoadModule('/src/components/ai/AssistantWidget.jsx'));({default:Auth}=await vite.ssrLoadModule('/src/context/AuthContext.jsx'));({MemoryRouter:Router,Link}=await vite.ssrLoadModule('react-router-dom'));({default:client}=await vite.ssrLoadModule('/src/api/client.js'));
 client.defaults.adapter=config=>new Promise((resolve,reject)=>requests.push({config,resolve,reject}));
});
afterEach(async()=>{if(root)await act(async()=>root.unmount());root=null;document.body.replaceChildren();requests.length=0;localStorage.clear();sessionStorage.clear();});
after(async()=>{await vite.close();dom.window.close();for(const[k,v]of originals){if(v)Object.defineProperty(globalThis,k,v);else delete globalThis[k];}if(oldMode===undefined)delete process.env.VITE_DATA_MODE;else process.env.VITE_DATA_MODE=oldMode;});
async function render(){await act(async()=>root.render(h(Auth.Provider,{value:auth},h(Router,{initialEntries:[entry]},h(Link,{to:'/user/profile'},'Đi hồ sơ'),h(Widget)))));}
async function mount(role='USER'){auth={user:{id:'u1',role},token:'test-token',isAuthenticated:true,loading:false};localStorage.setItem('kdrive.jwt',auth.token);c=document.createElement('div');document.body.append(c);root=createRoot(c);await render();}
const button=name=>[...c.querySelectorAll('button')].find(el=>el.getAttribute('aria-label')===name||el.textContent===name);
const field=name=>[...c.querySelectorAll('label')].find(el=>el.textContent.startsWith(name))?.querySelector('input,select');
async function expandRoutes(){const el=c.querySelector('.assistant-routes');await act(async()=>{el.open=true;el.dispatchEvent(new dom.window.Event('toggle'));});}
async function selectRoute(index,value){const el=field('Điểm '+index);await act(async()=>{Object.getOwnPropertyDescriptor(dom.window.HTMLSelectElement.prototype,'value').set.call(el,value);el.dispatchEvent(new dom.window.Event('change',{bubbles:true}));});}
async function click(el){assert.ok(el);await act(async()=>el.click());}
function take(url){const req=requests.find(r=>r.config.url===url&&!r.claimed);assert.ok(req,url);req.claimed=true;return req;}
async function respond(req,data){await act(async()=>req.resolve({config:req.config,status:200,statusText:'OK',headers:{},data}));}
async function open(){await click(button('Mở Trợ lý KDrive'));await respond(take('/branches'),branches);await respond(take('/vehicles/catalog'),{amenities:[{id:'gps',name:'GPS'}]});}
async function input(name,value){const el=field(name);assert.ok(el,name);await act(async()=>{Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype,'value').set.call(el,value);el.dispatchEvent(new dom.window.Event('input',{bubbles:true}));el.dispatchEvent(new dom.window.Event('change',{bubbles:true}));});}
async function submit(){await act(async()=>c.querySelector('form').dispatchEvent(new dom.window.Event('submit',{bubbles:true,cancelable:true})));}
function ok(req,count=4){return {requestId:JSON.parse(req.config.data).requestId,status:'OK',results:Array.from({length:count},(_,i)=>({vehicleId:'v'+(i+1),vehicleName:'Xe thật '+(i+1),total:2600000,reasonCodes:['ENOUGH_SEATS','WITHIN_TOTAL_BUDGET']}))};}
test('Routes use active catalog IDs, validated facts and invalidate pending responses on edit/reset',async()=>{
 await mount();await open();assert.equal(requests.some(r=>r.config.url==='/ai/locations'),false);
 await expandRoutes();await respond(take('/ai/locations'),[{id:'a',name:'Điểm A',metadata:null},{id:'b',name:'Điểm B',metadata:null}]);
 await selectRoute(1,'a');await selectRoute(2,'b');assert.match(c.textContent,/Chưa xác minh/);
 await act(async()=>c.querySelector('form[aria-label="Xem tuyến tham quan"]').dispatchEvent(new dom.window.Event('submit',{bubbles:true,cancelable:true})));
 const request=take('/ai/routes/preview');assert.deepEqual(JSON.parse(request.config.data),{locationIds:['a','b']});
 await respond(request,{status:'VERIFIED',distanceMeters:30500,durationSeconds:1740,attribution:'OpenStreetMap'});assert.match(c.textContent,/29 phút lái xe/);
 await selectRoute(2,'a');assert.doesNotMatch(c.textContent,/29 phút lái xe/);
 await act(async()=>c.querySelector('form[aria-label="Xem tuyến tham quan"]').dispatchEvent(new dom.window.Event('submit',{bubbles:true,cancelable:true})));
 assert.match(c.textContent,/địa điểm khác nhau/);assert.equal(requests.filter(r=>r.config.url==='/ai/routes/preview').length,1);
 await selectRoute(2,'b');await act(async()=>c.querySelector('form[aria-label="Xem tuyến tham quan"]').dispatchEvent(new dom.window.Event('submit',{bubbles:true,cancelable:true})));
 const late=take('/ai/routes/preview');await click(button('Bắt đầu lại'));await respond(late,{status:'VERIFIED',distanceMeters:10000,durationSeconds:600,attribution:'OpenStreetMap'});assert.doesNotMatch(c.textContent,/10 phút lái xe/);
});

test('Closed initially, supported USER only, no eager requests',async()=>{
 await mount('ADMIN');assert.equal(button('Mở Trợ lý KDrive'),undefined);assert.equal(requests.length,0);auth={...auth,user:{id:'staff',role:'STAFF'}};await render();assert.equal(c.querySelector('[role="dialog"]'),null);
 auth={...auth,user:{id:'u1',role:'USER'},loading:true};await render();assert.equal(button('Mở Trợ lý KDrive'),undefined);auth={...auth,loading:false};await render();assert.ok(button('Mở Trợ lý KDrive'));assert.equal(c.querySelector('[role="dialog"]'),null);assert.equal(requests.length,0);
});
test('Real criteria once, top3 total-price cards and trip links persist across routes; Escape returns focus',async()=>{
 await mount();await open();assert.equal(field('Ngày nhận').value,'2100-10-20');assert.equal(field('Điểm trả').value,'hn');await submit();await submit();assert.equal(requests.filter(r=>r.config.url==='/ai/recommendations').length,1);
 const req=take('/ai/recommendations'),sent=JSON.parse(req.config.data);assert.equal(sent.criteria.budgetVndTotal,3000000);assert.equal(sent.criteria.passengers,4);assert.equal(sent.criteria.preferences.transmission,null);assert.equal('text'in sent,false);
 await respond(req,ok(req));assert.equal(c.querySelectorAll('article').length,3);assert.match(c.textContent,/2.600.000/);assert.match(c.textContent,/Tổng tiền thuê và phí tuyến/);assert.doesNotMatch(c.textContent,/\/ngày/);
 const link=c.querySelector('article a');assert.match(link.getAttribute('href'),/^\/vehicles\/v1\?/);assert.match(link.getAttribute('href'),/start=2100-10-20/);await click(link);assert.equal(field('Tổng ngân sách').value,'3000000');assert.equal(c.querySelectorAll('article').length,3);
 await act(async()=>c.querySelector('[role="dialog"]').dispatchEvent(new dom.window.KeyboardEvent('keydown',{key:'Escape',bubbles:true})));assert.equal(c.querySelector('[role="dialog"]'),null);assert.equal(document.activeElement,button('Mở Trợ lý KDrive'));await click(button('Mở Trợ lý KDrive'));assert.equal(c.querySelectorAll('article').length,3);
});
test('Invalid, no-match and unavailable results never substitute demo and retain form',async()=>{
 await mount();await open();await input('Tổng ngân sách','');await submit();assert.equal(requests.filter(r=>r.config.url==='/ai/recommendations').length,0);assert.ok(c.querySelector('[role="alert"]'));
 await input('Tổng ngân sách','3000000');await submit();let req=take('/ai/recommendations');await respond(req,{requestId:JSON.parse(req.config.data).requestId,status:'NO_MATCH',results:[]});assert.match(c.textContent,/Chưa có xe phù hợp/);assert.equal(c.querySelectorAll('article').length,0);
 await submit();req=take('/ai/recommendations');await act(async()=>req.reject({config:req.config,response:{status:503,data:{message:'Gợi ý xe chưa được bật.'}}}));assert.match(c.querySelector('[role="alert"]').textContent,/chưa được bật/);assert.equal(field('Ngày nhận').value,'2100-10-20');assert.equal(c.querySelectorAll('article').length,0);
});
test('Edit/reset and identity/session changes abort and discard old results',async()=>{
 await mount();await open();await submit();const old=take('/ai/recommendations');await input('Tổng ngân sách','4000000');assert.equal(old.config.signal.aborted,true);await respond(old,ok(old));assert.equal(c.querySelectorAll('article').length,0);
 await submit();const pending=take('/ai/recommendations');auth={...auth,user:{id:'u2',role:'USER'},token:'second-token'};localStorage.setItem('kdrive.jwt',auth.token);await render();assert.equal(pending.config.signal.aborted,true);assert.equal(c.querySelector('[role="dialog"]'),null);await respond(pending,ok(pending));await open();assert.equal(field('Tổng ngân sách').value,'3000000');assert.equal(c.querySelectorAll('article').length,0);
 await input('Tổng ngân sách','5000000');await click(button('Bắt đầu lại'));assert.equal(field('Tổng ngân sách').value,'');assert.equal(field('Ngày nhận').value,'');auth={...auth,token:'new-session'};localStorage.setItem('kdrive.jwt',auth.token);await render();assert.equal(c.querySelector('[role="dialog"]'),null);auth={...auth,isAuthenticated:false,user:null};await render();assert.equal(button('Mở Trợ lý KDrive'),undefined);assert.equal(localStorage.getItem('kdrive.assistant'),null);
});
test('Catalog failure blocks submit; retry loads actual options',async()=>{
 await mount();await click(button('Mở Trợ lý KDrive'));const req=take('/branches');await act(async()=>req.reject({config:req.config,response:{status:503,data:{message:'down'}}}));await respond(take('/vehicles/catalog'),{amenities:[]});assert.match(c.querySelector('[role="alert"]').textContent,/Chưa tải được/);assert.equal(button('Gợi ý xe phù hợp').disabled,true);await click(button('Tải lại danh mục'));await respond(take('/branches'),branches);await respond(take('/vehicles/catalog'),{amenities:[]});assert.equal(button('Gợi ý xe phù hợp').disabled,false);
});

test('Malformed successful response is an error, not a made-up recommendation',async()=>{
 await mount();await open();await submit();const req=take('/ai/recommendations');await respond(req,{requestId:'wrong-correlation',status:'OK',results:[{vehicleId:'invented',vehicleName:'Wrong',total:1,reasonCodes:[]}]});assert.ok(c.querySelector('[role="alert"]'));assert.equal(c.querySelectorAll('article').length,0);
 await submit();const second=take('/ai/recommendations');await respond(second,{requestId:JSON.parse(second.config.data).requestId,status:'OK',results:[]});assert.ok(c.querySelector('[role="alert"]'));assert.equal(c.querySelectorAll('article').length,0);
});
test('Demo assistant has explicit unavailable copy and sends no API calls',async()=>{
 process.env.VITE_DATA_MODE='demo';let demoServer;
 try {
  demoServer=await createServer({root:fileURLToPath(new URL('../..',import.meta.url)),server:{middlewareMode:true},appType:'custom',logLevel:'error'});
  const {default:DemoWidget}=await demoServer.ssrLoadModule('/src/components/ai/AssistantWidget.jsx'),{default:DemoAuth}=await demoServer.ssrLoadModule('/src/context/AuthContext.jsx'),{MemoryRouter:DemoRouter}=await demoServer.ssrLoadModule('react-router-dom'),{default:demoClient}=await demoServer.ssrLoadModule('/src/api/client.js');
  let calls=0;demoClient.defaults.adapter=async()=>{calls++;throw new Error('Demo must not call API');};c=document.createElement('div');document.body.append(c);root=createRoot(c);await act(async()=>root.render(h(DemoAuth.Provider,{value:{user:{id:'demo-user',role:'USER'},isAuthenticated:true,loading:false}},h(DemoRouter,null,h(DemoWidget)))));await click(button('Mở Trợ lý KDrive'));assert.match(c.textContent,/Bản demo chưa kết nối/);assert.equal(c.querySelector('form'),null);assert.equal(calls,0);await act(async()=>root.unmount());root=null;
 } finally {await demoServer?.close();process.env.VITE_DATA_MODE='api';}
});

test('Natural text fills reviewable fields but never automatically recommends; reset clears text',async()=>{
 await mount();await open();
 const area=c.querySelector('textarea');
 await act(async()=>{Object.getOwnPropertyDescriptor(dom.window.HTMLTextAreaElement.prototype,'value').set.call(area,'4 người,3triệu,20/10/2100 đến24/10/2100');area.dispatchEvent(new dom.window.Event('input',{bubbles:true}));});
 await click(button('Điền nhu cầu bằng AI'));await click(button('Điền nhu cầu bằng AI'));
 const req=take('/ai/criteria/parse');assert.equal(req.config.timeout,65000);
 await respond(req,{status:'READY_FOR_REVIEW',unresolvedFields:[],draft:{pickupDate:'2100-10-20',returnDate:'2100-10-24',pickupBranchId:'dn',returnBranchId:'hn',passengers:5,budgetVndTotal:2000000,transmission:null}});
 assert.equal(field('Số người').value,'5');assert.equal(field('Tổng ngân sách').value,'2000000');assert.equal(requests.filter(r=>r.config.url==='/ai/recommendations').length,0);assert.match(c.textContent,/Hãy kiểm tra các trường/);
 await click(button('Bắt đầu lại'));assert.equal(c.querySelector('textarea').value,'');
});

test('Reset ignores a late natural-language reply; model failure keeps the manual form available',async()=>{
 await mount();await open();
 async function text(value){const area=c.querySelector('textarea');await act(async()=>{Object.getOwnPropertyDescriptor(dom.window.HTMLTextAreaElement.prototype,'value').set.call(area,value);area.dispatchEvent(new dom.window.Event('input',{bubbles:true}));});}
 await text('4 người,3triệu');await click(button('Điền nhu cầu bằng AI'));const old=take('/ai/criteria/parse');await click(button('Bắt đầu lại'));
 await respond(old,{status:'READY_FOR_REVIEW',unresolvedFields:[],draft:{passengers:9,budgetVndTotal:999}});assert.equal(field('Tổng ngân sách').value,'');assert.equal(field('Số người').value,'4');
 await text('4 người');await click(button('Điền nhu cầu bằng AI'));const failed=take('/ai/criteria/parse');await act(async()=>failed.reject(new Error('Qwen unavailable')));
 assert.ok(c.querySelector('[role="alert"]'));assert.ok(c.querySelector('form'));assert.equal(button('Gợi ý xe phù hợp').disabled,false);
});
