import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatVietnamDate } from './calendarDate.js';
import { adminBookingDate } from './bookingQueue.js';

test('numeric Vietnam dates cross UTC midnight without inventing calendar hours', () => {
  assert.equal(formatVietnamDate('2026-10-24T16:59:59Z', { withTime: true }), '23:59 24/10/2026');
  assert.equal(formatVietnamDate('2026-10-24T17:00:00Z', { withTime: true }), '00:00 25/10/2026');
  assert.equal(formatVietnamDate('2030-12-28'), '28/12/2030');
  assert.equal(adminBookingDate({ policyVersion: 'calendar-2026-10-17' }, '2026-10-24T17:00:00Z'), '25/10/2026');
  assert.equal(adminBookingDate({}, '2026-10-24T17:00:00Z'), '00:00 25/10/2026');
  for (const value of [null, undefined, '', 'invalid']) assert.equal(formatVietnamDate(value), '—');
});
