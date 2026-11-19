import assert from 'node:assert/strict';
import { test } from 'node:test';
import { userRefundKind, userRefundStatus, userRefundStatuses, userRefundTotals } from './userRefund.js';

test('every backend refund status has a USER label; only SUCCEEDED means refunded', () => {
  assert.deepEqual(userRefundStatuses, ['REQUESTED', 'APPROVED', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'DECLINED']);
  for (const status of ['REQUESTED', 'APPROVED', 'PROCESSING', 'UNKNOWN']) {
    const s = userRefundStatus(status);
    assert.equal(s.refunded, false, status);
    assert.equal(s.pending, true, status);
    assert.match(s.detail, /Chưa (xác nhận đã )?hoàn tiền/, status);
    assert.doesNotMatch(s.label, /^Đã hoàn$/, status);
  }
  assert.deepEqual(userRefundStatus('SUCCEEDED'), { label: 'Đã hoàn', detail: 'Khoản hoàn đã được xác minh chuyển trả.', pending: false, refunded: true });
  assert.equal(userRefundStatus('APPROVED').label, 'Đã duyệt, chưa hoàn tiền');
  for (const status of ['FAILED', 'DECLINED']) assert.deepEqual([userRefundStatus(status).pending, userRefundStatus(status).refunded], [false, false]);
  assert.equal(userRefundStatus('DECLINED').label, 'Không hoàn');
  for (const status of [undefined, null, '', 'FUTURE', 'toString', '__proto__']) {
    const s = userRefundStatus(status);
    assert.equal(s.label, 'Đang kiểm tra tình trạng hoàn');
    assert.equal(s.refunded, false);
    assert.match(s.detail, /Chưa xác nhận đã hoàn tiền/);
  }
});

test('refund kinds are translated and unknown kinds stay neutral', () => {
  assert.equal(userRefundKind('LATE'), 'Khoản nhận không đủ điều kiện thời hạn');
  assert.equal(userRefundKind('UNAPPLIED'), 'Khoản nhận không áp dụng được vào đơn');
  assert.equal(userRefundKind('EXCESS'), 'Tiền nhận vượt số cọc');
  assert.equal(userRefundKind('MANUAL_EXCESS'), 'Tiền nhận vượt số cọc');
  assert.equal(userRefundKind('CANCELLED'), 'Hủy đơn');
  assert.equal(userRefundKind('NO_SHOW'), 'Không đến nhận xe');
  assert.equal(userRefundKind('EARLY_USER'), 'Khách hủy trước mốc nhận');
  assert.equal(userRefundKind('BUSINESS'), 'Doanh nghiệp từ chối hoặc không cung cấp xe');
  assert.equal(userRefundKind('LATE_CUSTOMER'), 'Khách hủy sát giờ');
  for (const kind of [undefined, 'FUTURE', 'constructor']) assert.equal(userRefundKind(kind), 'Khác');
});

test('totals count SUCCEEDED as refunded, open lifecycle as pending, and ignore declined/failed/invalid amounts', () => {
  assert.deepEqual(userRefundTotals([
    { status: 'SUCCEEDED', amount: 100000 }, { status: 'SUCCEEDED', amount: 50000 },
    { status: 'REQUESTED', amount: 1000 }, { status: 'APPROVED', amount: 2000 }, { status: 'PROCESSING', amount: 3000 }, { status: 'UNKNOWN', amount: 4000 },
    { status: 'FAILED', amount: 7000 }, { status: 'DECLINED', amount: 0 }, { status: 'FUTURE', amount: 9000 },
    { status: 'SUCCEEDED', amount: '1' }, { status: 'SUCCEEDED', amount: 1.5 }, { status: 'SUCCEEDED', amount: -1 }, null,
  ]), { refunded: 150000, pending: 10000 });
  assert.deepEqual(userRefundTotals(undefined), { refunded: 0, pending: 0 });
  assert.deepEqual(userRefundTotals([]), { refunded: 0, pending: 0 });
});
