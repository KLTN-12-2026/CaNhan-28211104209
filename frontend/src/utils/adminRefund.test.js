import { test } from 'node:test';
import assert from 'node:assert/strict';
import { customerNoteValue, validateCustomerNote, matchesPaymentSearch, providerDate, refundDate, refundResult, refundSummary, validateRefundAmount, validateRefundCompletion } from './adminRefund.js';

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

test('refund summary counts only SUCCEEDED as refunded and separates pending, closed and unknown statuses', () => {
  assert.deepEqual(refundSummary([
    { status: 'SUCCEEDED', amount: 100 }, { status: 'REQUESTED', amount: 1 }, { status: 'APPROVED', amount: 2 },
    { status: 'PROCESSING', amount: 3 }, { status: 'UNKNOWN', amount: 4 }, { status: 'FAILED', amount: 50 },
    { status: 'DECLINED', amount: 0 }, { status: 'FUTURE', amount: 70 }, { status: 'SUCCEEDED', amount: '9' }, null,
  ]), { count: 9, refunded: 100, pending: 10, pendingCount: 4, closedCount: 2, unknownCount: 1 });
  assert.deepEqual(refundSummary(undefined), { count: 0, refunded: 0, pending: 0, pendingCount: 0, closedCount: 0, unknownCount: 0 });
});

test('D4 customer note is optional, trimmed, limited, and part of the decision/creation match', () => {
  assert.equal(customerNoteValue('  x  '), 'x');assert.equal(customerNoteValue('   '), null);assert.equal(customerNoteValue(undefined), null);
  assert.equal(validateCustomerNote('a'.repeat(500)), '');assert.match(validateCustomerNote('a'.repeat(501)), /tối đa 500/);
  const item = { id: 'r', paymentId: 'p', amount: 1000, version: 1 };
  const data = note => ({ refunds: [{ id: 'r', paymentId: 'p', amount: 500, version: 2, status: 'APPROVED', reason: 'R', customerNote: note }], payments: [] });
  const op = note => ({ type: 'decide', item, body: { amount: 500, reason: 'R', customerNote: note } });
  assert.ok(refundResult(op('Note'), data('Note')));
  assert.equal(refundResult(op('Note'), data(null)), null, 'a missing stored note is not the intended decision');
  assert.ok(refundResult(op(null), data(undefined)), 'no note on either side matches');
  const create = { type: 'create', item: { id: 'p' }, previousIds: [], body: { amount: 300, reason: 'R', customerNote: 'N' } };
  assert.ok(refundResult(create, { refunds: [{ id: 'n', paymentId: 'p', kind: 'MANUAL_EXCESS', amount: 300, reason: 'R', customerNote: 'N' }] }));
  assert.equal(refundResult(create, { refunds: [{ id: 'n', paymentId: 'p', kind: 'MANUAL_EXCESS', amount: 300, reason: 'R', customerNote: null }] }), undefined);
});
