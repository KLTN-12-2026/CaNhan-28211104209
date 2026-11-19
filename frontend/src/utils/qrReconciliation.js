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
// Nội dung dành cho USER: chỉ dịch stage backend, không tự phân loại lại bằng đồng hồ hoặc số tiền.
const userStageContent = Object.freeze({
  AWAITING_APPROVAL: {
    label: 'Chờ kiểm tra hồ sơ',
    message: 'Nhân viên chi nhánh nhận kiểm tra ảnh GPLX trước khi mở bước đóng cọc.',
    nextAction: 'Chưa cần chuyển tiền. Chờ hồ sơ được duyệt rồi tải lại đơn.',
    transferOpen: false,
  },
  AWAITING_TRANSFER: {
    label: 'Chờ bạn chuyển cọc',
    message: 'Hồ sơ đã được duyệt. Chuyển đúng số tiền và nội dung của mã QR DEMO trước hạn đóng cọc.',
    nextAction: 'Sau khi chuyển, gửi báo đã chuyển khoản để nhân viên đối chiếu.',
    transferOpen: true,
  },
  REPORTED_NO_RECEIPT: {
    label: 'Đã báo chuyển — chờ nhân viên đối chiếu',
    message: 'Báo đã chuyển chưa phải xác nhận đã nhận tiền. Nhân viên chi nhánh nhận sẽ đối chiếu khoản chuyển.',
    nextAction: 'Không cần báo lại. Tải lại đơn để xem kết quả đối chiếu.',
    transferOpen: true,
  },
  RECEIVED_INCOMPLETE: {
    label: 'Đã có khoản nhận — đang đối soát',
    message: 'Khoản đã nhận chưa đủ điều kiện để ghi nhận cọc. Điều này không có nghĩa chắc chắn bạn đã chuyển thiếu.',
    nextAction: 'Liên hệ chi nhánh nhận để đối chiếu trước khi chuyển thêm.',
    transferOpen: true,
  },
  AWAITING_APPLICATION: {
    label: 'Đã nhận cọc — chờ hệ thống xác nhận đơn',
    message: 'Hệ thống còn kiểm tra hồ sơ, lịch và xe. Nhận đủ cọc chưa bảo đảm đơn được xác nhận.',
    nextAction: 'Không cần chuyển thêm. Tải lại đơn để xem kết quả xác nhận.',
    transferOpen: false,
  },
  APPLIED: {
    label: 'Đã ghi nhận cọc vào đơn',
    message: 'Khoản cọc đã được áp dụng vào đơn. Phần còn lại thanh toán theo hướng dẫn của chi nhánh.',
    nextAction: 'Không cần chuyển thêm cọc. Xem tiến trình đơn và hợp đồng khi đã phát hành.',
    transferOpen: false,
  },
  EXPIRED: {
    label: 'Đơn đã hết hạn giữ chỗ',
    message: 'Thời hạn của đơn đã hết theo hệ thống. Không chuyển thêm tiền cho đơn này.',
    nextAction: 'Khoản đã nhận nhưng không áp dụng được xử lý hoàn theo chính sách; xem tình trạng hoàn bên dưới nếu có.',
    transferOpen: false,
  },
  CLOSED: {
    label: 'Đã kết thúc xử lý cọc',
    message: 'Đơn không còn chờ đóng cọc. Không chuyển thêm tiền cho đơn này.',
    nextAction: 'Xem tình trạng hoàn bên dưới nếu có.',
    transferOpen: false,
  },
});

export function legacyDepositPendingLabel(deposit) {
  return deposit?.reportedAt ? 'Khách đã báo · Chờ đối soát' : deposit?.approvedAt ? 'Chờ đóng cọc' : 'Chờ kiểm tra hồ sơ';
}

/**
 * Trả nội dung trạng thái cọc QR cho USER, hoặc null nếu đơn không dùng QR DEMO.
 * Thiếu summary/stage lạ trả nội dung trung tính, không suy ra đã hoặc chưa chuyển tiền.
 */
export function userQrDepositStatus(booking, summary = booking?.qrReconciliation) {
  if (booking?.deposit?.method !== 'DEMO_QR_MANUAL') return null;
  if (!summary || typeof summary !== 'object' || !summary.stage) {
    // Đơn không còn PENDING và thiếu summary: giữ nội dung cũ theo trạng thái đơn.
    if (booking.status !== 'PENDING') return null;
    return {
      known: false,
      stage: null,
      label: legacyDepositPendingLabel(booking.deposit),
      message: 'Chưa có thông tin đối soát cọc từ hệ thống. Điều này không có nghĩa bạn đã hoặc chưa chuyển tiền.',
      nextAction: 'Tải lại đơn để xem tình trạng cọc mới nhất.',
      transferOpen: null,
    };
  }
  if (!Object.hasOwn(userStageContent, summary.stage)) {
    return {
      known: false,
      stage: summary.stage,
      label: 'Trạng thái cọc chưa được hỗ trợ',
      message: 'Hệ thống trả về trạng thái cọc mà trang này chưa hiển thị được. Không chuyển thêm tiền dựa trên trang này.',
      nextAction: 'Tải lại đơn hoặc liên hệ chi nhánh nhận.',
      transferOpen: false,
    };
  }
  return { known: true, stage: summary.stage, ...userStageContent[summary.stage] };
}

export const userQrDepositStages = Object.freeze(Object.keys(userStageContent));

/** Nhãn chip PENDING: USER theo stage backend (nếu có), vai trò khác giữ nhãn cũ. */
export function depositPendingLabel(booking, role) {
  const status = role === 'USER' ? userQrDepositStatus(booking) : null;
  return status?.label ?? legacyDepositPendingLabel(booking?.deposit);
}
