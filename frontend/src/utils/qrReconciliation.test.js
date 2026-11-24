import assert from 'node:assert/strict';
import { test } from 'node:test';
import { depositPendingLabel, legacyDepositPendingLabel, qrReconciliationStageLabel, qrReconciliationStages, qrReconciliationWarnings, userQrDepositStages, userQrDepositStatus } from './qrReconciliation.js';

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

const qrBooking = (summary, extra = {}) => ({ status: 'PENDING', deposit: { method: 'DEMO_QR_MANUAL', approvedAt: '2026-10-24T03:00:00Z', reportedAt: null, ...extra }, qrReconciliation: summary });

test('USER QR status content covers every backend stage with message, next action and transfer gate', () => {
  const expected = {
    AWAITING_APPROVAL: ['Chờ kiểm tra hồ sơ', false],
    AWAITING_TRANSFER: ['Chờ bạn chuyển cọc', true],
    REPORTED_NO_RECEIPT: ['Đã báo chuyển — chờ nhân viên đối chiếu', true],
    RECEIVED_INCOMPLETE: ['Đã có khoản nhận — đang đối soát', true],
    AWAITING_APPLICATION: ['Đã nhận cọc — chờ hệ thống xác nhận đơn', false],
    APPLIED: ['Đã ghi nhận cọc vào đơn', false],
    EXPIRED: ['Đơn đã hết hạn giữ chỗ', false],
    CLOSED: ['Đã kết thúc xử lý cọc', false],
  };
  assert.deepEqual(userQrDepositStages, qrReconciliationStages);
  for (const [stage, [label, transferOpen]] of Object.entries(expected)) {
    const status = userQrDepositStatus(qrBooking({ stage }));
    assert.equal(status.known, true);
    assert.equal(status.label, label);
    assert.equal(status.transferOpen, transferOpen, stage);
    assert.ok(status.message.length > 10 && status.nextAction.length > 10, stage);
    // USER không thấy chỉ dẫn thao tác Staff
    assert.doesNotMatch(`${status.message} ${status.nextAction}`, /Ghi nhận khoản|Đối chiếu tài khoản nguồn|Duyệt hồ sơ và|xác minh khoản/i, stage);
  }
  for (const stage of ['APPLIED', 'EXPIRED', 'CLOSED', 'AWAITING_APPLICATION']) {
    const text = Object.values(userQrDepositStatus(qrBooking({ stage }))).join(' ');
    assert.doesNotMatch(text, /Chuyển đúng số tiền|Còn thiếu|đóng thêm/, stage);
  }
  // Đủ khoản hợp lệ không được hứa xác nhận đơn
  assert.match(userQrDepositStatus(qrBooking({ stage: 'AWAITING_APPLICATION' })).message, /chưa bảo đảm đơn được xác nhận/);
  assert.match(userQrDepositStatus(qrBooking({ stage: 'RECEIVED_INCOMPLETE' })).message, /không có nghĩa chắc chắn bạn đã chuyển thiếu/);
});

test('USER QR status stays neutral for missing or unsupported summaries and ignores other methods', () => {
  for (const summary of [undefined, null, {}, { stage: '' }]) {
    const status = userQrDepositStatus(qrBooking(summary));
    assert.equal(status.known, false);
    assert.equal(status.transferOpen, null);
    assert.equal(status.label, 'Chờ đóng cọc');
    assert.match(status.message, /không có nghĩa bạn đã hoặc chưa chuyển tiền/);
  }
  assert.equal(userQrDepositStatus(qrBooking(null, { reportedAt: '2026-10-24T03:10:00Z' })).label, 'Khách đã báo · Chờ đối soát');
  for (const stage of ['FUTURE_STAGE', 'toString', '__proto__']) {
    const status = userQrDepositStatus(qrBooking({ stage }));
    assert.equal(status.label, 'Trạng thái cọc chưa được hỗ trợ');
    assert.equal(status.transferOpen, false);
    assert.doesNotMatch(status.message, /chưa chuyển|chưa thanh toán/i);
  }
  assert.equal(userQrDepositStatus({ status: 'CANCELLED', deposit: { method: 'DEMO_QR_MANUAL' } }), null);
  assert.equal(userQrDepositStatus({ deposit: { method: 'VNPAY_SANDBOX' }, qrReconciliation: { stage: 'APPLIED' } }), null);
  assert.equal(userQrDepositStatus({ deposit: { required: false } }), null);
  assert.equal(userQrDepositStatus(undefined), null);
});

test('pending chip label follows backend stage only for USER and keeps legacy text for other roles', () => {
  const inconsistent = qrBooking({ stage: 'EXPIRED' }, { reportedAt: '2026-10-24T03:10:00Z' });
  assert.equal(depositPendingLabel(inconsistent, 'USER'), 'Đơn đã hết hạn giữ chỗ');
  for (const role of ['STAFF', 'ADMIN', undefined]) assert.equal(depositPendingLabel(inconsistent, role), 'Khách đã báo · Chờ đối soát');
  assert.equal(depositPendingLabel({ deposit: { method: 'VNPAY_SANDBOX', approvedAt: 'x' } }, 'USER'), 'Chờ đóng cọc');
  assert.equal(legacyDepositPendingLabel(undefined), 'Chờ kiểm tra hồ sơ');
  // CONFIRMED + stage chưa áp dụng: phản ánh stage backend, không tự suy đã đủ
  assert.equal(userQrDepositStatus({ ...qrBooking({ stage: 'RECEIVED_INCOMPLETE' }), status: 'CONFIRMED' }).label, 'Đã có khoản nhận — đang đối soát');
});
