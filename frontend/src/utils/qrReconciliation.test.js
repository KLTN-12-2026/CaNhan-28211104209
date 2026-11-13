import assert from 'node:assert/strict';
import { test } from 'node:test';
import { qrReconciliationStageLabel, qrReconciliationStages, qrReconciliationWarnings } from './qrReconciliation.js';

const labels = {
  AWAITING_APPROVAL: 'Chờ kiểm tra hồ sơ',
  AWAITING_TRANSFER: 'Chờ khách chuyển cọc',
  REPORTED_NO_RECEIPT: 'Khách đã báo chuyển — chờ đối chiếu',
  RECEIVED_INCOMPLETE: 'Cần đối soát khoản nhận',
  AWAITING_APPLICATION: 'Đã nhận đủ khoản hợp lệ — chờ xác nhận đơn',
  APPLIED: 'Đã ghi nhận cọc vào đơn',
  EXPIRED: 'Đã hết hạn',
  CLOSED: 'Đã kết thúc xử lý cọc',
};

test('all backend reconciliation stages have their agreed Vietnamese labels', () => {
  assert.deepEqual(qrReconciliationStages, Object.keys(labels));
  for (const [stage, label] of Object.entries(labels)) {
    assert.equal(qrReconciliationStageLabel({ stage }), label);
  }
});

test('missing and unknown reconciliation stages use neutral fallback labels', () => {
  for (const summary of [undefined, null, {}, { stage: null }, { stage: '' }]) {
    assert.equal(qrReconciliationStageLabel(summary), 'Chưa có thông tin đối soát');
  }
  assert.equal(qrReconciliationStageLabel({ stage: 'FUTURE_STAGE' }), 'Trạng thái đối soát chưa được hỗ trợ');
  for (const stage of ['toString', 'constructor', '__proto__']) {
    assert.equal(qrReconciliationStageLabel({ stage }), 'Trạng thái đối soát chưa được hỗ trợ');
  }
  assert.doesNotMatch(qrReconciliationStageLabel({ stage: 'FUTURE_STAGE' }), /chưa chuyển|chưa thanh toán|chưa đóng cọc/i);
});

test('backend reconciliation warnings are translated without inferring unknown values', () => {
  assert.deepEqual(qrReconciliationWarnings({
    urgency: 'DUE_SOON',
    processingIssue: 'RETRY_REQUIRED',
    timingIssues: ['TRANSFER_LATE', 'VERIFICATION_LATE', 'TRANSFER_LATE'],
  }), [
    'Sắp đến hạn đối soát',
    'Cần tiếp tục xử lý đối soát',
    'Có khoản chuyển từ hạn đóng cọc trở đi',
    'Xác minh từ hạn đối soát trở đi',
  ]);
  assert.deepEqual(qrReconciliationWarnings({ urgency: 'NORMAL', processingIssue: 'NONE', timingIssues: [] }), []);
  assert.deepEqual(qrReconciliationWarnings({ urgency: 'FUTURE', processingIssue: 'FUTURE', timingIssues: ['FUTURE'] }), []);
  assert.deepEqual(qrReconciliationWarnings({ urgency: 'toString', timingIssues: ['constructor', '__proto__'] }), []);
  assert.deepEqual(qrReconciliationWarnings(undefined), []);
});