import process from 'node:process';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { toVietnamInstant } from './calendarDate.js';

test('Vietnam transfer time is explicit UTC+7 across host timezones', () => {
  for(const TZ of ['UTC','Asia/Bangkok','America/New_York']) {
    const actual=execFileSync(process.execPath,['--input-type=module','-e',`import {toVietnamInstant} from './src/utils/calendarDate.js'; process.stdout.write(toVietnamInstant('2026-10-24T10:00'));`],{cwd:process.cwd(),env:{...process.env,TZ},encoding:'utf8'});
    assert.equal(actual,'2026-10-24T03:00:00.000Z');
  }
  assert.equal(toVietnamInstant('invalid'),null);
  assert.equal(toVietnamInstant(''),null);
});

import { visibleBookings } from './bookingQueue.js';
test('reported deposits filter pending only and preserve newest-created-first order',()=>{
  const rows=[{id:'late',status:'PENDING',createdAt:'2026-10-24T05:00Z',deposit:{required:true,approvedAt:'x',reportedAt:'x',reviewDueAt:'2026-10-24T04:00Z'}},{id:'early',status:'PENDING',createdAt:'2026-10-24T04:00Z',deposit:{required:true,approvedAt:'x',reportedAt:'x',reviewDueAt:'2026-10-24T03:00Z'}},{id:'done',status:'CONFIRMED',createdAt:'2026-10-24T06:00Z',deposit:{required:true,reportedAt:'x'}}];
  assert.deepEqual(visibleBookings(rows,{depositPhase:'review'}).map(b=>b.id),['late','early']);
});
