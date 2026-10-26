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
