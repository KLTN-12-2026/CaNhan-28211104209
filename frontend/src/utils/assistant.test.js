import assert from 'node:assert/strict';
import { test } from 'node:test';
import { assistantCriteria, assistantDraft, assistantSearch, assistantVisible, extractedAssistantDraft } from './assistant.js';
const branches=[{id:'dn',status:'ACTIVE'},{id:'hn',status:'ACTIVE'},{id:'closed',status:'INACTIVE'}];
const valid={...assistantDraft(),start:'2100-10-20',end:'2100-10-24',pickupBranchId:'dn',returnBranchId:'hn',passengers:'4',budget:'3000000'};
test('USER assistant main routes exclude roles, auth and documents',()=>{
 for(const path of ['/','/vehicles','/vehicles/v1','/user/bookings','/user/bookings/order','/user/bookings/new','/user/profile'])assert.equal(assistantVisible('USER',path),true,path);
 for(const role of ['ADMIN','STAFF',undefined])assert.equal(assistantVisible(role,'/'),false);
 for(const path of ['/admin','/staff/queue','/login','/register','/unauthorized','/user/bookings/id/contract','/ai/recommend'])assert.equal(assistantVisible('USER',path),false,path);
});
test('Criteria validate dates, budget, capacity and active branches; links preserve trip fields',()=>{
 const c=assistantCriteria(valid,branches);assert.equal(c.passengers,4);assert.equal(c.budgetVndTotal,3000000);assert.equal(c.preferences.transmission,null);
 const params=new URLSearchParams(assistantSearch(c));assert.equal(params.get('start'),'2100-10-20');assert.equal(params.get('returnBranchId'),'hn');assert.equal(params.get('budget'),'3000000');assert.equal(params.has('ownerId'),false);
 for(const change of [{start:''},{end:'2100-10-19'},{end:'2100-02-30'},{passengers:'1'},{passengers:'4.5'},{budget:''},{budget:'3.5'},{budget:'9007199254740992'},{pickupBranchId:'closed'},{returnBranchId:'unknown'}])assert.throws(()=>assistantCriteria({...valid,...change},branches));
 assert.equal(assistantCriteria({...valid,budget:'0'},branches).budgetVndTotal,0);
 const restored=assistantDraft(new URLSearchParams('start=2100-10-20&end=2100-10-24&pickupBranchId=dn&budget=3000000'));assert.equal(restored.returnBranchId,'dn');assert.equal(restored.start,valid.start);assert.equal(assistantDraft(new URLSearchParams('start=bad')).start,'');
});

test('Extracted draft never guesses missing data or accepts unknown branches',()=>{
 const data={draft:{pickupDate:'2100-10-20',returnDate:'2100-10-24',pickupBranchId:'dn',returnBranchId:'hn',passengers:4,budgetVndTotal:3000000,transmission:'AUTO'}};
 assert.equal(extractedAssistantDraft(data,branches).budget,'3000000');
 assert.equal(extractedAssistantDraft({draft:{}},branches).passengers,'');
 for(const delta of [{pickupBranchId:'evil'},{pickupDate:'2100-02-30'},{budgetVndTotal:3.5},{passengers:12},{transmission:'EVIL'}])assert.throws(()=>extractedAssistantDraft({draft:{...data.draft,...delta}},branches));
 assert.throws(()=>extractedAssistantDraft({draft:null},branches));
});
