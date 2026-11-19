// Trình bày vòng đời hoàn cọc cho USER từ dữ liệu backend; không tự suy đã hoàn khi chưa SUCCEEDED.
const statusContent = Object.freeze({
  REQUESTED: { label: 'Chờ quyết định hoàn', detail: 'Yêu cầu hoàn đang chờ quản trị viên quyết định. Chưa hoàn tiền.', pending: true },
  APPROVED: { label: 'Đã duyệt, chưa hoàn tiền', detail: 'Khoản hoàn đã được duyệt nhưng chưa chuyển trả. Chưa hoàn tiền.', pending: true },
  PROCESSING: { label: 'Đang xử lý hoàn', detail: 'Khoản hoàn đang được chuyển trả. Chưa xác nhận đã hoàn tiền.', pending: true },
  UNKNOWN: { label: 'Đang đối soát kết quả hoàn', detail: 'Kết quả chuyển trả chưa được xác minh. Chưa xác nhận đã hoàn tiền.', pending: true },
  SUCCEEDED: { label: 'Đã hoàn', detail: 'Khoản hoàn đã được xác minh chuyển trả.', refunded: true },
  FAILED: { label: 'Hoàn chưa thành công', detail: 'Lần chuyển trả chưa thành công. Liên hệ chi nhánh nhận nếu cần hỗ trợ.' },
  DECLINED: { label: 'Không hoàn', detail: 'Quản trị viên quyết định không hoàn khoản này. Xem lý do bên dưới.' },
});

const kindLabels = Object.freeze({
  LATE: 'Khoản nhận không đủ điều kiện thời hạn',
  UNAPPLIED: 'Khoản nhận không áp dụng được vào đơn',
  EXCESS: 'Tiền nhận vượt số cọc',
  MANUAL_EXCESS: 'Tiền nhận vượt số cọc',
  CANCELLED: 'Hủy đơn',
  NO_SHOW: 'Không đến nhận xe',
});

export const userRefundStatuses = Object.freeze(Object.keys(statusContent));

export function userRefundStatus(status) {
  if (typeof status === 'string' && Object.hasOwn(statusContent, status)) {
    const content = statusContent[status];
    return { label: content.label, detail: content.detail, pending: Boolean(content.pending), refunded: Boolean(content.refunded) };
  }
  return { label: 'Đang kiểm tra tình trạng hoàn', detail: 'Chưa xác nhận đã hoàn tiền. Tải lại đơn hoặc liên hệ chi nhánh nhận.', pending: false, refunded: false };
}

export function userRefundKind(kind) {
  return typeof kind === 'string' && Object.hasOwn(kindLabels, kind) ? kindLabels[kind] : 'Khác';
}

/** Tổng theo vòng đời; bỏ qua số tiền không phải số nguyên an toàn, không cộng FAILED/DECLINED/lạ. */
export function userRefundTotals(refunds) {
  const totals = { refunded: 0, pending: 0 };
  if (!Array.isArray(refunds)) return totals;
  for (const refund of refunds) {
    if (!Number.isSafeInteger(refund?.amount) || refund.amount < 0) continue;
    const status = userRefundStatus(refund.status);
    if (status.refunded) totals.refunded += refund.amount;
    else if (status.pending) totals.pending += refund.amount;
  }
  return totals;
}
