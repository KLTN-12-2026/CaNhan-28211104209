import { test } from 'node:test';
import assert from 'node:assert/strict';
import { matchesPaymentSearch, providerDate, refundDate, refundResult, validateRefundAmount, validateRefundCompletion } from './adminRefund.js';

test('refund amounts and completion require whole VND, reason and exact source', () => {
  assert.equal(validateRefundAmount('0', 'Không hoàn', 100, true), '');
  assert.equal(validateRefundAmount('50', 'Hoàn một phần', 100), '');
  for (const value of ['0', '-1', '1.5', '1e2', '101', '9007199254740992']) assert.notEqual(validateRefundAmount(value, 'Lý do', 100), '');
  assert.notEqual(validateRefundAmount('1', ' ', 100), '');
  const form = { recipientAccount: 'DEMO', reference: 'REF', transferTime: '2026-10-25T10:30:00' };
  assert.equal(validateRefundCompletion(form, 'DEMO', true), '');
  assert.notEqual(validateRefundCompletion(form, 'OTHER', true), '');
  assert.notEqual(validateRefundCompletion(form, 'DEMO', false), '');
  assert.notEqual(validateRefundCompletion({ ...form, transferTime: '2026-02-30T10:00' }, 'DEMO', true), '');
});
test('refund readback matches target, amount, version, reference and source rather than totals', () => {
  const item = { id:'r1', paymentId:'p1', amount:100, version:2 };
  const operation = { type:'complete', item, body:{ recipientAccount:'DEMO', reference:'REF', transferTime:'2026-10-25T03:30:00Z' } };
  const row = { ...item, version:3, status:'SUCCEEDED', providerTransaction:'REF', requestDate:'20261025103000' };
  const data = { refunds:[row], payments:[], refundPayments:[{id:'p1',sourceAccount:'DEMO'}] };
  assert.equal(refundResult(operation,data),row);
  for (const field of ['id','paymentId','providerTransaction','requestDate','status']) assert.equal(refundResult(operation,{...data,refunds:[{...row,[field]:'OTHER'}]}),null);
  assert.equal(refundResult(operation,{...data,refunds:[{...row,amount:101}]}),null);
  assert.equal(refundResult(operation,{...data,refundPayments:[{id:'p1',sourceAccount:'OTHER'}]}),null);
  assert.equal(providerDate('2026-10-24T20:30:00Z'),'20261025033000');
  assert.match(refundDate('2026-10-24T20:30:00Z'),/25\/10\/2026/);
});
test('creation recovery excludes existing or mismatched requests and search supports actual ids/references', () => {
  const operation={type:'create',item:{id:'p1'},body:{amount:50,reason:'fixture'},previousIds:['old']};
  const row={id:'new',paymentId:'p1',kind:'MANUAL_EXCESS',amount:50,reason:'fixture',status:'REQUESTED'};
  assert.equal(refundResult(operation,{refunds:[row]}),row);
  assert.equal(refundResult(operation,{refunds:[{...row,id:'old'}]}),undefined);
  assert.equal(refundResult(operation,{refunds:[{...row,paymentId:'p2'}]}),undefined);
  assert.equal(matchesPaymentSearch({bookingId:'Booking-ABC',providerTransaction:'Ref-123'},' REF-123 '),true);
  assert.equal(matchesPaymentSearch({bookingId:'Booking-ABC'},'other'),false);
});
