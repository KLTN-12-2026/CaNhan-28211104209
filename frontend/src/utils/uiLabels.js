export const bookingStatusLabels = {
  PENDING: 'Chờ xác nhận', CONFIRMED: 'Đã xác nhận', IN_PROGRESS: 'Đang thuê',
  RETURNED: 'Đã trả xe', COMPLETED: 'Hoàn tất', CANCELLED: 'Đã hủy',
  REJECTED: 'Đã từ chối', EXPIRED: 'Hết hạn giữ lịch',
};
export const bookingActionLabels = {
  CONFIRMED: 'Xác nhận đơn thuê', REJECTED: 'Từ chối đơn thuê',
  CANCELLED: 'Hủy đơn thuê', IN_PROGRESS: 'Bàn giao xe',
  RETURNED: 'Nhận trả xe', COMPLETED: 'Hoàn tất đơn thuê',
};
export const roleLabels = { GUEST: 'Khách', USER: 'Khách hàng', STAFF: 'Nhân viên', ADMIN: 'Quản trị viên' };
export const accountStatusLabels = { ACTIVE: 'Đang hoạt động', LOCKED: 'Đã khóa', INACTIVE: 'Ngừng hoạt động' };
export const vehicleStatusLabels = { ACTIVE: 'Đang khai thác', MAINTENANCE: 'Đang bảo trì', INACTIVE: 'Ngừng khai thác' };
export const licenseStatusLabels = { PENDING: 'Chờ đối chiếu', VERIFIED: 'Đã đối chiếu', REJECTED: 'Đã từ chối' };
export const licenseSchemeLabels = { VN_PRE_2025: 'Bằng cấp trước năm 2025', VN_2025: 'Bằng cấp từ năm 2025' };
export const incidentKindLabels = { DAMAGE: 'Hư hỏng', ACCIDENT: 'Tai nạn', VIOLATION: 'Vi phạm' };
export const incidentStatusLabels = { OPEN: 'Đã tiếp nhận', ASSESSED: 'Đã đánh giá', CLAIMED: 'Đã gửi yêu cầu', DISPUTED: 'Đang có phản hồi', CLOSED: 'Đã đóng' };
