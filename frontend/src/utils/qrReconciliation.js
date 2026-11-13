const stageLabels = Object.freeze({
  AWAITING_APPROVAL: 'Chờ kiểm tra hồ sơ',
  AWAITING_TRANSFER: 'Chờ khách chuyển cọc',
  REPORTED_NO_RECEIPT: 'Khách đã báo chuyển — chờ đối chiếu',
  RECEIVED_INCOMPLETE: 'Cần đối soát khoản nhận',
  AWAITING_APPLICATION: 'Đã nhận đủ khoản hợp lệ — chờ xác nhận đơn',
  APPLIED: 'Đã ghi nhận cọc vào đơn',
  EXPIRED: 'Đã hết hạn',
  CLOSED: 'Đã kết thúc xử lý cọc',
});

export const qrReconciliationStages = Object.freeze(Object.keys(stageLabels));

const urgencyWarnings = Object.freeze({
  DUE_SOON: 'Sắp đến hạn đối soát',
  EXPIRED: 'Đã hết hạn đối soát',
});
const timingWarnings = Object.freeze({
  TRANSFER_LATE: 'Có khoản chuyển từ hạn đóng cọc trở đi',
  VERIFICATION_LATE: 'Xác minh từ hạn đối soát trở đi',
});

export function qrReconciliationStageLabel(summary) {
  if (!summary || typeof summary !== 'object' || !summary.stage) {
    return 'Chưa có thông tin đối soát';
  }
  return Object.hasOwn(stageLabels, summary.stage)
    ? stageLabels[summary.stage]
    : 'Trạng thái đối soát chưa được hỗ trợ';
}

export function qrReconciliationWarnings(summary) {
  if (!summary || typeof summary !== 'object') return [];

  const warnings = [];
  if (Object.hasOwn(urgencyWarnings, summary.urgency)) warnings.push(urgencyWarnings[summary.urgency]);
  if (summary.processingIssue === 'RETRY_REQUIRED') warnings.push('Cần tiếp tục xử lý đối soát');
  if (Array.isArray(summary.timingIssues)) {
    for (const issue of summary.timingIssues) {
      if (Object.hasOwn(timingWarnings, issue) && !warnings.includes(timingWarnings[issue])) {
        warnings.push(timingWarnings[issue]);
      }
    }
  }
  return warnings;
}