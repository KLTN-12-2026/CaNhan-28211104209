import { validateReceipt, vietnamNowInput } from './qrReceipt.js';

export const refundDate = value => value ? new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Ho_Chi_Minh', dateStyle: 'short', timeStyle: 'medium', hourCycle: 'h23',
}).format(new Date(value)) : '—';
export const providerDate = value => vietnamNowInput(new Date(value)).replace(/\D/g, '');

export function validateRefundAmount(value, reason, maximum, allowZero = false) {
  const amount = Number(value);
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(amount) || amount < (allowZero ? 0 : 1) || amount > maximum)
    return `Nhập VND nguyên ${allowZero ? 'không âm' : 'dương'}, không vượt số tiền được phép.`;
  if (!reason.trim() || reason.trim().length > 1000) return 'Nhập lý do từ 1 đến 1000 ký tự.';
  return '';
}

/** D4: optional customer-facing note (trimmed, empty -> null, at most 500 characters). */
export function customerNoteValue(value) {
  const note = typeof value === 'string' ? value.trim() : '';
  return note ? note : null;
}
export function validateCustomerNote(value) {
  const note = customerNoteValue(value);
  return note && note.length > 500 ? 'Thông báo cho khách tối đa 500 ký tự.' : '';
}

export function validateRefundCompletion(form, sourceAccount, verified) {
  const error = validateReceipt({ transactionRef: form.reference, amountVnd: '1', transferTime: form.transferTime, sourceAccount: form.recipientAccount }, verified);
  if (error) return error;
  if (!sourceAccount || form.recipientAccount.trim() !== sourceAccount) return 'Tài khoản nhận phải khớp nguồn tiền đã xác minh.';
  return '';
}

// Match the target and intended state, never infer success from row position or totals.
export function refundResult(operation, data) {
  const { type, item, body, responseId } = operation;
  if (type === 'create') return data.refunds.find(r =>
    (responseId ? r.id === responseId : !operation.previousIds.includes(r.id)) &&
    r.paymentId === item.id && r.kind === 'MANUAL_EXCESS' && r.amount === body.amount && r.reason === body.reason && (r.customerNote ?? null) === (body.customerNote ?? null));
  if (type === 'reconcile') return data.payments.find(p => p.id === item.id && ['SUCCEEDED', 'FAILED'].includes(p.status));
  const row = data.refunds.find(r => r.id === item.id && r.paymentId === item.paymentId && r.amount === (type === 'decide' ? body.amount : item.amount));
  if (!row || row.version <= item.version) return null;
  if (type === 'reconcile-refund') return ['SUCCEEDED', 'FAILED'].includes(row.status) ? row : null;
  if (type === 'decide') return row.reason === body.reason && (row.customerNote ?? null) === (body.customerNote ?? null) && (body.amount === 0 ? row.status === 'DECLINED' : ['APPROVED', 'PROCESSING', 'SUCCEEDED'].includes(row.status)) ? row : null;
  if (type === 'process') return ['PROCESSING', 'SUCCEEDED'].includes(row.status) ? row : null;
  if (type === 'complete') return row.status === 'SUCCEEDED' && row.providerTransaction === body.reference && row.requestDate === providerDate(body.transferTime) &&
    [...data.payments, ...(data.refundPayments || [])].some(p => p.id === row.paymentId && p.sourceAccount === body.recipientAccount) ? row : null;
  return null;
}

const pendingRefundStatuses = new Set(['REQUESTED', 'APPROVED', 'PROCESSING', 'UNKNOWN']);
const closedRefundStatuses = new Set(['FAILED', 'DECLINED']);

/**
 * Tóm tắt các yêu cầu hoàn trong danh sách đã tải (không phải tổng toàn hệ thống).
 * Chỉ SUCCEEDED tính là đã hoàn; trạng thái lạ đếm riêng để cần kiểm tra.
 */
export function refundSummary(refunds) {
  const summary = { count: 0, refunded: 0, pending: 0, pendingCount: 0, closedCount: 0, unknownCount: 0 };
  if (!Array.isArray(refunds)) return summary;
  for (const refund of refunds) {
    if (!refund) continue;
    summary.count += 1;
    const amount = Number.isSafeInteger(refund.amount) && refund.amount >= 0 ? refund.amount : 0;
    if (refund.status === 'SUCCEEDED') summary.refunded += amount;
    else if (pendingRefundStatuses.has(refund.status)) { summary.pending += amount; summary.pendingCount += 1; }
    else if (closedRefundStatuses.has(refund.status)) summary.closedCount += 1;
    else summary.unknownCount += 1;
  }
  return summary;
}

export function matchesPaymentSearch(row, query) {
  const needle = query.trim().toLowerCase();
  return !needle || [row.id, row.bookingId, row.paymentId, row.providerTransaction].some(value => value?.toLowerCase().includes(needle));
}
