import { test } from 'node:test';
import assert from 'node:assert/strict';
import { matchesReceipt, validateReceipt, vietnamNowInput } from './qrReceipt.js';

const form = { transactionRef: 'REF', amountVnd: '90000', transferTime: '2026-10-25T10:30', sourceAccount: 'DEMO' };
test('Vietnam input time has explicit zone, includes seconds and crosses midnight correctly', () => {
  assert.equal(vietnamNowInput(new Date('2026-10-24T20:15:07Z')), '2026-10-25T03:15:07');
  assert.equal(vietnamNowInput(new Date('2026-10-25T17:00:00Z')), '2026-10-26T00:00:00');
});
test('receipt validation rejects unsafe VND, invalid dates, missing evidence and length violations', () => {
  assert.equal(validateReceipt(form, true), '');
  assert.equal(validateReceipt({ ...form, transferTime: '2026-10-25T10:30:59' }, true), '');
  for (const [field, value] of [['amountVnd','0'],['amountVnd','-1'],['amountVnd','1.5'],['amountVnd','1e3'],['amountVnd','9007199254740992'],['transactionRef',' '],['transactionRef','X'.repeat(31)],['sourceAccount',''],['sourceAccount','X'.repeat(101)],['transferTime',''],['transferTime','2026-02-30T10:00']]) {
    assert.notEqual(validateReceipt({ ...form, [field]: value }, true), '', `${field}=${value}`);
  }
  assert.notEqual(validateReceipt(form, false), '');
});
test('correlation requires immutable identity and complete payload, not totals or array position', () => {
  const payload = { transactionRef:'REF', idempotencyKey:'key', amountVnd:90000, transferTime:'2026-10-25T03:30:00Z', sourceAccount:'DEMO', transferNote:'KDABC' };
  const payment = { bookingId:'abc', status:'SUCCEEDED', providerTransaction:'REF', idempotencyKey:'key', amount:90000, transferTime:'2026-10-25T10:30:00+07:00', sourceAccount:'DEMO', transferNote:'KDABC' };
  assert.equal(matchesReceipt(payment, payload, 'abc'), true);
  for (const field of ['providerTransaction','idempotencyKey','sourceAccount','transferNote','bookingId','status']) assert.equal(matchesReceipt({ ...payment, [field]:'OTHER' },payload,'abc'),false);
  assert.equal(matchesReceipt({ ...payment, amount:90001 },payload,'abc'),false);
});
