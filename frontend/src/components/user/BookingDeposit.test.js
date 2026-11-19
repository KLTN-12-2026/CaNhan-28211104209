import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath, URL } from 'node:url';
import { createServer } from 'vite';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { getBookingActions } from '../../api/bookingActions.js';

let server, Deposit, ReturnPage, Progress, Refund;
before(async () => {
  const root = fileURLToPath(new URL('../../..', import.meta.url));
  server = await createServer({ root, configFile: `${root}/vite.config.js`, appType: 'custom', logLevel: 'error', optimizeDeps: { noDiscovery: true }, server: { middlewareMode: true } });
  const module = await server.ssrLoadModule('/src/components/user/BookingDeposit.jsx');
  Deposit = module.default; ReturnPage = module.PaymentReturnPage;
  ({ default: Progress } = await server.ssrLoadModule('/src/components/booking/BookingProgress.jsx'));
  ({ RefundRow: Refund } = await server.ssrLoadModule('/src/pages/api/ApiPaymentsPage.jsx'));
});
after(async () => { await server?.close(); });

test('deadline presentation distinguishes approval/reporting for every role without changing API values', () => {
  for (const role of ['USER', 'STAFF', 'ADMIN']) {
    const booking = { ...baseBooking, expiresAt: '2026-10-24T05:00:00Z', deposit: { ...baseBooking.deposit, method: 'DEMO_QR_MANUAL' } };
    const render = () => renderToStaticMarkup(React.createElement(Deposit, { booking, role, reload() {} }));
    assert.match(render(), /Hạn chờ kiểm tra hồ sơ/);
    assert.match(render(), /12:00 24\/10\/2026/);
    assert.doesNotMatch(render(), /Hạn đóng cọc/);
    Object.assign(booking.deposit, { approvedAt: '2026-10-24T03:00:00Z', paymentDueAt: '2026-10-24T03:30:00Z', reviewDueAt: '2026-10-24T04:00:00Z', originalHoldUntil: booking.expiresAt, reportedAt: '2026-10-24T03:10:00Z' });
    const before = JSON.stringify(booking);
    const html = render();
    assert.match(html, /Hạn đóng cọc<\/dt><dd>10:30 24\/10\/2026/);
    assert.match(html, /Hạn nhân viên đối soát<\/dt><dd>11:00 24\/10\/2026/);
    assert.match(html, /Hạn giữ lịch gốc<\/dt><dd>12:00 24\/10\/2026/);
    assert.match(html, /Khách đã báo · Chờ đối soát/);
    assert.match(html, /không gia hạn thời gian chuyển khoản/);
    assert.doesNotMatch(html, /Đã ghi nhận cọc cho đơn/);
    assert.equal(JSON.stringify(booking), before);
    const progress = renderToStaticMarkup(React.createElement(MemoryRouter, null, React.createElement(Progress, { booking, role })));
    assert.match(progress, /Hiện tại: Khách đã báo · Chờ đối soát/);
    booking.status = 'CANCELLED';
    assert.match(render(), /Đơn không còn chờ đóng cọc/);
    assert.doesNotMatch(render(), /Chuyển khoản trước hạn đóng cọc/);
  }
});

const baseBooking = {
  id: 'synthetic',
  status: 'PENDING',
  total: 2500000,
  pickupBranchId: 'dn',
  deposit: {
    required: true,
    amount: 600000,
    method: 'VNPAY_SANDBOX',
    mode: 'FULL',
    approvedAt: null,
    paymentDueAt: null,
    reviewDueAt: null,
    originalHoldUntil: null,
    reportedAt: null,
    receiverSnapshot: null,
    txnRef: null,
    cancellationKind: null,
    appliedAmount: 0,
    receivedAmount: 0,
    missingAmount: 600000,
    excessAmount: 0
  }
};

test('required deposit removes manual confirmation but keeps reject/cancel', () => {
  const context = { role: 'STAFF', assignedBranchIds: new Set(['dn']), now: Date.now() };
  assert.deepEqual(getBookingActions(baseBooking, context), ['REJECTED', 'CANCELLED']);
  assert.deepEqual(getBookingActions({ ...baseBooking, deposit: { ...baseBooking.deposit, required: false } }, context), ['CONFIRMED', 'REJECTED', 'CANCELLED']);
});

test('panel shows deposit and remaining total without promising payment', () => {
  const text = renderToStaticMarkup(React.createElement(Deposit, { booking: baseBooking, role: 'USER', reload() {} }));
  assert.match(text, /VNPAY Sandbox/);
  assert.match(text, /600\.000/);
  assert.match(text, /1\.900\.000/);
  assert.match(text, /Chờ kiểm tra hồ sơ/);
  assert.doesNotMatch(text, /Đã ghi nhận cọc cho đơn/);
});

test('Staff pickup QR detail shows backend amounts/deadlines and careful reconciliation guidance only', () => {
  const booking = {
    ...baseBooking,
    deposit: {
      ...baseBooking.deposit,
      method: 'DEMO_QR_MANUAL',
      amount: 600000,
      approvedAt: '2026-10-24T03:00:00Z',
      paymentDueAt: '2026-10-24T03:30:00Z',
      reviewDueAt: '2026-10-24T04:00:00Z',
    },
    qrReconciliation: {
      stage: 'RECEIVED_INCOMPLETE',
      urgency: 'DUE_SOON',
      receivedAmount: 750000,
      appliedAmount: 250000,
      eligibleAmount: 0,
      shortfallAmount: 600000,
      reviewDueAt: '2026-10-24T04:00:00Z',
      processingIssue: 'RETRY_REQUIRED',
      timingIssues: ['TRANSFER_LATE', 'VERIFICATION_LATE'],
    },
  };
  const render = (role, assignedPickup) => renderToStaticMarkup(React.createElement(Deposit, { booking, role, assignedPickup, reload() {} }));
  const staffText = render('STAFF', true);
  const staffVisibleText = staffText.replace(/<[^>]*>/g, '').replaceAll('\u00a0', ' ');
  assert.match(staffVisibleText, /Trạng thái đối soát\s*Cần đối soát khoản nhận/);
  assert.match(staffVisibleText, /Cọc yêu cầu\s*600\.000/);
  assert.match(staffVisibleText, /Tổng đã nhận \(lịch sử\)\s*750\.000/);
  assert.match(staffVisibleText, /Đã áp dụng vào đơn\s*250\.000/);
  assert.match(staffVisibleText, /Hạn đóng cọc\s*10:30 24\/10\/2026/);
  assert.match(staffVisibleText, /Hạn đối soát\s*11:00 24\/10\/2026/);
  assert.match(staffVisibleText, /không phải số tiền hiện đang giữ/);
  assert.match(staffVisibleText, /không đồng nghĩa khách chắc chắn đã chuyển thiếu/);
  assert.match(staffVisibleText, /Sắp đến hạn đối soát/);
  assert.match(staffVisibleText, /Có khoản chuyển từ hạn đóng cọc trở đi/);
  assert.match(staffVisibleText, /Xác minh từ hạn đối soát trở đi/);
  assert.doesNotMatch(staffVisibleText, /Còn thiếu|Cần chuyển thêm|Yêu cầu khách đóng thêm/);
  for (const [role, assignedPickup] of [['USER', true], ['ADMIN', true], ['STAFF', false]]) {
    assert.doesNotMatch(render(role, assignedPickup), /aria-label="Chi tiết đối soát cọc QR"/);
  }

  const applied = { ...booking, status: 'CONFIRMED', qrReconciliation: { ...booking.qrReconciliation, stage: 'APPLIED' } };
  const appliedText = renderToStaticMarkup(React.createElement(Deposit, { booking: applied, role: 'STAFF', assignedPickup: true, reload() {} }));
  assert.match(appliedText, /Không yêu cầu khách đóng thêm từ thông tin đối soát/);
});

test('Staff detail renders neutral fallback when QR reconciliation summary is missing', () => {
  const booking = { ...baseBooking, deposit: { ...baseBooking.deposit, method: 'DEMO_QR_MANUAL' } };
  const text = renderToStaticMarkup(React.createElement(Deposit, { booking, role: 'STAFF', assignedPickup: true, reload() {} }));
  assert.match(text, /Chưa có thông tin đối soát/);
  assert.doesNotMatch(text, /Chưa chuyển cọc|Chưa thanh toán/);
});

test('redirect success parameters never assert paid or call a payment mutation', () => {
  const text = renderToStaticMarkup(React.createElement(MemoryRouter, { initialEntries: ['/user/payment-return?vnp_ResponseCode=00&vnp_TxnRef=synthetic'] }, React.createElement(ReturnPage)));
  assert.match(text, /Đang xác minh/);
  assert.match(text, /không xác nhận đã nhận tiền/);
  assert.match(text, /href="\/user\/bookings"/);
});

test('no-show form appears only after pickup day for assigned staff', () => {
  const active = { ...baseBooking, status: 'CONFIRMED', startAt: '2026-10-25T17:00:00Z' };
  const render = (now, assignedPickup = true) => renderToStaticMarkup(React.createElement(Deposit, { booking: active, role: 'STAFF', assignedPickup, now: Date.parse(now), reload() {} }));
  assert.doesNotMatch(render('2026-10-26T16:59:59Z'), /Ghi nhận khách không đến nhận/);
  assert.match(render('2026-10-26T17:00:00Z'), /Ghi nhận khách không đến nhận/);
  assert.doesNotMatch(render('2026-10-26T17:00:00Z', false), /Ghi nhận khách không đến nhận/);
});

test('approved deposit has full year and matching progress stage', () => {
  const approved = {
    ...baseBooking,
    deposit: {
      ...baseBooking.deposit,
      method: 'VNPAY_SANDBOX',
      approvedAt: '2026-10-24T03:00:00Z',
      paymentDueAt: '2026-10-24T03:30:00Z',
      reviewDueAt: '2026-10-24T04:00:00Z',
      originalHoldUntil: '2026-10-24T05:00:00Z',
      mode: 'FULL'
    }
  };
  const panel = renderToStaticMarkup(React.createElement(Deposit, { booking: approved, role: 'USER', reload() {} }));
  assert.match(panel, /24\/10\/2026/);
  const progress = renderToStaticMarkup(React.createElement(MemoryRouter, null, React.createElement(Progress, { booking: approved, role: 'USER' })));
  assert.match(progress, /Hiện tại: Chờ đóng cọc/);
  assert.match(progress, /Hồ sơ đã được duyệt/);
  const legacy = renderToStaticMarkup(React.createElement(MemoryRouter, null, React.createElement(Progress, { booking: { ...baseBooking, deposit: { required: false } }, role: 'USER' })));
  assert.match(legacy, /Hiện tại: Chờ xác nhận/);
});

test('expired booking never asks customer to keep paying', () => {
  const expired = {
    ...baseBooking,
    status: 'EXPIRED',
    deposit: {
      ...baseBooking.deposit,
      method: 'VNPAY_SANDBOX',
      approvedAt: '2026-10-24T03:00:00Z',
      paymentDueAt: '2026-10-24T03:30:00Z',
      reviewDueAt: '2026-10-24T04:00:00Z',
      originalHoldUntil: '2026-10-24T05:00:00Z',
      mode: 'FULL'
    }
  };
  const text = renderToStaticMarkup(React.createElement(Deposit, { booking: expired, role: 'USER', reload() {} }));
  assert.match(text, /Đơn không còn chờ đóng cọc/);
  assert.doesNotMatch(text, /Thanh toán cọc thử nghiệm/);
});

test('Admin refund row exposes decision and provider reference without implying completion', () => {
  const item = { id: 'refund-fixture', paymentId: 'payment-fixture', bookingId: 'booking-fixture', status: 'UNKNOWN', amount: 195000, reason: 'Fixture partial decision', approvedBy: 'admin-fixture', approvedAt: '2026-10-24T03:00:00Z', depositMethod: 'VNPAY_SANDBOX' };
  const text = renderToStaticMarkup(React.createElement(MemoryRouter, null, React.createElement(Refund, { item, run() {}, busy: false })));
  assert.match(text, /admin-fixture/);
  assert.match(text, /payment-fixture/);
  assert.match(text, /Chưa có kết quả xác minh/);
  assert.match(text, /Cần đối soát/);
  assert.doesNotMatch(text, /Đã hoàn/);
});

const visible = html => html.replace(/<[^>]*>/g, ' ').replaceAll('\u00a0', ' ').replace(/\s+/g, ' ');
const qrApproved = (stage, overrides = {}) => ({
  ...baseBooking,
  ...overrides,
  deposit: { ...baseBooking.deposit, method: 'DEMO_QR_MANUAL', approvedAt: '2026-10-24T03:00:00Z', paymentDueAt: '2026-10-24T03:30:00Z', reviewDueAt: '2026-10-24T04:00:00Z', originalHoldUntil: '2026-10-24T05:00:00Z', missingAmount: 600000, ...(overrides.deposit || {}) },
  qrReconciliation: stage === undefined ? undefined : { stage, urgency: 'NORMAL', receivedAmount: 0, appliedAmount: 0, eligibleAmount: 0, shortfallAmount: 600000, reviewDueAt: '2026-10-24T04:00:00Z', processingIssue: 'NONE', timingIssues: [], ...(overrides.summary || {}) },
});
const renderDeposit = (booking, role = 'USER', assignedPickup = false) => visible(renderToStaticMarkup(React.createElement(Deposit, { booking, role, assignedPickup, reload() {} })));
const renderProgress = (booking, role = 'USER') => visible(renderToStaticMarkup(React.createElement(MemoryRouter, null, React.createElement(Progress, { booking, role }))));

test('G3.1 USER QR panel and progress show backend stage content with next action', () => {
  const cases = {
    AWAITING_TRANSFER: [/Chờ bạn chuyển cọc/, /Bước tiếp theo: Sau khi chuyển, gửi báo đã chuyển khoản/, true],
    REPORTED_NO_RECEIPT: [/Đã báo chuyển — chờ nhân viên đối chiếu/, /Không cần báo lại/, true],
    RECEIVED_INCOMPLETE: [/Đã có khoản nhận — đang đối soát/, /Liên hệ chi nhánh nhận để đối chiếu trước khi chuyển thêm/, true],
    AWAITING_APPLICATION: [/Đã nhận cọc — chờ hệ thống xác nhận đơn/, /chưa bảo đảm đơn được xác nhận/, false],
    EXPIRED: [/Đơn đã hết hạn giữ chỗ/, /Không chuyển thêm tiền cho đơn này/, false],
  };
  for (const [stage, [label, detail, transferOpen]] of Object.entries(cases)) {
    const text = renderDeposit(qrApproved(stage));
    assert.match(text, new RegExp(`Tình trạng cọc: ${label.source}`), stage);
    assert.match(text, detail, stage);
    assert.doesNotMatch(text, /Còn thiếu/, stage);
    assert.doesNotMatch(text, /Chi tiết đối soát cọc QR|Ghi nhận khoản nhận|Duyệt hồ sơ và tạo mã QR/, stage);
    assert.equal(/Hiển thị mã QR cọc/.test(text), transferOpen, `${stage} QR button`);
    assert.equal(/Chuyển khoản trước hạn đóng cọc/.test(text), transferOpen, `${stage} transfer note`);
    const progress = renderProgress(qrApproved(stage));
    assert.match(progress, new RegExp(`Hiện tại: ${label.source}`), stage);
  }
});

test('G3.1 raw PENDING with backend EXPIRED never asks USER to pay, while Staff/Admin keep their content', () => {
  const booking = qrApproved('EXPIRED', { deposit: { reportedAt: '2026-10-24T03:10:00Z' } });
  const user = renderDeposit(booking);
  assert.doesNotMatch(user, /Hiển thị mã QR cọc|Gửi báo đã chuyển khoản|Chuyển khoản trước hạn|Còn thiếu/);
  assert.match(user, /Bạn đã báo chuyển khoản lúc 10:10 24\/10\/2026/);
  const admin = renderDeposit(booking, 'ADMIN');
  assert.match(admin, /Tóm tắt cọc:/);
  assert.match(admin, /Khách đã báo · Chờ đối soát/);
  assert.doesNotMatch(admin, /Tình trạng cọc của bạn|Bước tiếp theo/);
  const staff = renderDeposit(booking, 'STAFF', true);
  assert.match(staff, /Chi tiết đối soát cọc QR|Trạng thái đối soát/);
  assert.doesNotMatch(staff, /Bước tiếp theo/);
  assert.match(renderProgress(booking, 'STAFF'), /Hiện tại: Khách đã báo · Chờ đối soát/);
});

test('G3.1 USER QR without summary or with unsupported stage stays neutral', () => {
  const missing = renderDeposit(qrApproved(undefined));
  assert.match(missing, /Tình trạng cọc: Chờ đóng cọc/);
  assert.match(missing, /không có nghĩa bạn đã hoặc chưa chuyển tiền/);
  assert.match(missing, /Hiển thị mã QR cọc/);
  assert.doesNotMatch(missing, /Chưa chuyển cọc|Chưa thanh toán|Còn thiếu/);
  const unknown = renderDeposit(qrApproved('FUTURE_STAGE'));
  assert.match(unknown, /Trạng thái cọc chưa được hỗ trợ/);
  assert.doesNotMatch(unknown, /Hiển thị mã QR cọc/);
});

test('G3.1 APPLIED shows applied amount for USER and confirmed booking keeps closed wording', () => {
  const applied = qrApproved('APPLIED', { summary: { appliedAmount: 600000, receivedAmount: 600000, eligibleAmount: 600000, shortfallAmount: 0 } });
  const pending = renderDeposit(applied);
  assert.match(pending, /Đã ghi nhận vào đơn: 600\.000/);
  assert.match(pending, /Không cần chuyển thêm cọc/);
  const confirmed = renderDeposit({ ...applied, status: 'CONFIRMED' });
  assert.match(confirmed, /Tình trạng cọc: Đã ghi nhận cọc vào đơn/);
  assert.doesNotMatch(confirmed, /Hiển thị mã QR cọc|Còn thiếu/);
  assert.match(renderProgress({ ...applied, status: 'CONFIRMED' }), /Hiện tại: Đã xác nhận/);
});

test('G3.1 legacy summary renders excess amount and VNPAY USER keeps legacy content', () => {
  const vnpay = { ...baseBooking, deposit: { ...baseBooking.deposit, approvedAt: '2026-10-24T03:00:00Z', paymentDueAt: '2026-10-24T03:30:00Z', receivedAmount: 700000, missingAmount: 0, excessAmount: 100000, mode: 'EXCESS' } };
  const text = renderDeposit(vnpay);
  assert.match(text, /Còn thiếu 0 ₫ · Dư 100\.000 ₫|Dư 100\.000/);
  assert.doesNotMatch(text, /\{money\(/);
  assert.doesNotMatch(text, /Tình trạng cọc của bạn|Bước tiếp theo/);
  assert.match(text, /Chờ đóng cọc/);
});

test('G4.4 Admin never inherits Staff pickup actions, even when marked as assigned', () => {
  const pending = { ...baseBooking, deposit: { ...baseBooking.deposit, method: 'DEMO_QR_MANUAL' } };
  const approved = { ...pending, deposit: { ...pending.deposit, approvedAt: '2026-10-24T03:00:00Z', paymentDueAt: '2026-10-24T03:30:00Z', reviewDueAt: '2026-10-24T04:00:00Z' }, qrReconciliation: { stage: 'AWAITING_TRANSFER', receivedAmount: 0, appliedAmount: 0, timingIssues: [] } };
  const confirmed = { ...approved, status: 'CONFIRMED', startAt: '2026-10-25T17:00:00Z' };
  const render = (booking, role) => renderToStaticMarkup(React.createElement(Deposit, { booking, role, assignedPickup: true, now: Date.parse('2026-10-27T00:00:00Z'), reload() {} }));
  for (const booking of [pending, approved, confirmed]) {
    const admin = render(booking, 'ADMIN');
    assert.doesNotMatch(admin, /Duyệt hồ sơ và tạo mã QR cọc|Xác minh và ghi nhận|Ghi nhận khách không đến nhận|Chi tiết đối soát cọc QR|Hiển thị mã QR cọc|Gửi báo đã chuyển khoản/);
  }
  // Đối chứng: Staff chi nhánh nhận vẫn có thao tác tương ứng.
  assert.match(render(pending, 'STAFF'), /Duyệt hồ sơ và tạo mã QR cọc/);
  assert.match(render(confirmed, 'STAFF'), /Ghi nhận khách không đến nhận/);
});
