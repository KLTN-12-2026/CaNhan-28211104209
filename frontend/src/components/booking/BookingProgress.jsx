import { Link } from "react-router-dom";
import { dateTime } from "../common/PageKit";
import { adminDateFormat } from "../../utils/bookingQueue";

import { bookingStatusLabels as bookingStageLabels } from "../../utils/uiLabels";
export { bookingStageLabels };
const explanations = {
  PENDING: "Đang chờ nhân viên chi nhánh nhận xử lý yêu cầu. Kiểm tra thời hạn giữ lịch trên đơn.",
  CONFIRMED: "Chuẩn bị GPLX bản gốc và đến chi nhánh nhận đúng thời gian. Nhân viên đối chiếu trước khi giao xe.",
  IN_PROGRESS: "Bạn đang giữ xe. Trả xe tại chi nhánh trả trên đơn; nhân viên chi nhánh trả ghi nhận nhận xe.",
  RETURNED: "Đã ghi nhận trả xe. Đang chờ nhân viên chi nhánh trả kiểm tra và hoàn tất đơn.",
  COMPLETED: "Đơn đã hoàn tất. Bạn có thể xem hóa đơn và gửi đánh giá. Hóa đơn không xác nhận đã thanh toán.",
  CANCELLED: "Đơn đã hủy. Xem lý do trong lịch sử; tìm và báo giá lại nếu cần chuyến khác.",
  REJECTED: "Yêu cầu đã bị từ chối. Xem lý do trong lịch sử trước khi chọn chuyến khác.",
  EXPIRED: "Thời hạn giữ lịch đã hết theo phản hồi hệ thống. Tìm và báo giá lại nếu vẫn cần thuê xe.",
};
export default function BookingProgress({ booking, history = [], role, actions = [], area = "user" }) {
  const events = Array.isArray(history) ? history : [];
  const contract = ["CONFIRMED", "IN_PROGRESS", "RETURNED", "COMPLETED"].includes(booking.status) || events.some(event => event.next_status === "CONFIRMED");
  const invoice = booking.status === "COMPLETED" || events.some(event => event.next_status === "COMPLETED");
  const label = status => bookingStageLabels[status] || "Trạng thái chưa xác định";
  const depositPending = booking.status === 'PENDING' && booking.deposit?.required;
  const currentLabel = depositPending ? (booking.deposit.reportedAt ? 'Khách đã báo · Chờ đối soát' : booking.deposit.approvedAt ? 'Chờ đóng cọc' : 'Chờ kiểm tra hồ sơ') : label(booking.status);
  const explanation = depositPending ? (booking.deposit.reportedAt ? 'Khách đã báo chuyển khoản. Đơn vẫn chờ xử lý; chỉ xác nhận sau khi nhân viên đối soát đủ cọc. Xem hạn đối soát trong phần cọc giữ chỗ.' : booking.deposit.approvedAt ? 'Hồ sơ đã được duyệt. Thanh toán cọc trong thời hạn hiển thị; hệ thống xác nhận đơn sau khi xác minh khoản cọc.' : 'Nhân viên chi nhánh nhận kiểm tra hồ sơ trước khi mở bước đóng cọc.') : explanations[booking.status];
  return <section className="panel" aria-label="Tiến trình đơn thuê">
    <h2 className="text-lg font-semibold">Tiến trình đơn thuê</h2>
    <p className="mt-3 font-semibold" aria-live="polite">Hiện tại: {currentLabel}</p>
    <p className="mt-2 text-sm leading-6">{explanation || "Tải lại đơn để kiểm tra trạng thái hiện tại."}</p>
    {role === "STAFF" ? <p className="mt-2 text-sm">Nhận/xác nhận và giao xe thuộc chi nhánh nhận; nhận trả và hoàn tất thuộc chi nhánh trả. {actions.length ? "Chỉ sử dụng các thao tác được phép bên dưới." : "Hiện không có thao tác khả dụng theo phân công, trạng thái hoặc thời gian thuê."}</p> : null}
    {role === "ADMIN" ? <p className="mt-2 text-sm">Quản trị viên không thực hiện giao hoặc nhận trả xe. Theo dõi đơn và các thao tác được phép bên dưới.</p> : null}
    {role === "USER" && ["PENDING", "CONFIRMED"].includes(booking.status) ? <p className="mt-2 text-sm">{actions.includes("CANCELLED") ? "Có thể yêu cầu hủy tại phần thao tác bên dưới, kèm lý do." : "Nếu cần hủy sát giờ nhận, hãy liên hệ nhân viên chi nhánh nhận."} <Link className="underline" to="/user/profile">Kiểm tra GPLX</Link></p> : null}
    <p className="mt-3 text-xs text-muted">Giờ Việt Nam · Dùng nút Tải lại đơn để kiểm tra cập nhật mới nhất.</p>
    <details className="booking-history mt-5">
    <summary>Lịch sử trạng thái</summary>
    {events.length ? <ol className="mt-3 space-y-3">{events.map((event, index) => <li className="border-l-2 border-teal-200 pl-4 text-sm" key={`${event.occurred_at}-${index}`}>
      <p className="font-semibold">{event.previous_status ? label(event.previous_status) : "Tạo yêu cầu"} → {label(event.next_status)}</p>
      <p className="mt-1 text-muted">{area === "admin" ? adminDateFormat(event.occurred_at, { withTime: true }) : dateTime(event.occurred_at)}</p>
      {event.reason ? <p className="mt-1 whitespace-pre-wrap break-words">{event.reason}</p> : null}
    </li>)}</ol> : <p className="mt-3 text-sm text-muted">Chưa có lịch sử trạng thái từ hệ thống.</p>}
    </details>
    <h3 className="mt-5 font-semibold">Hồ sơ đơn</h3>
    <p className="mt-2 text-sm">Hợp đồng phát hành khi xác nhận; hóa đơn khi hoàn tất. Chứng từ không xác nhận đã thanh toán.</p>
    <div className="mt-3 flex flex-wrap gap-3">
      {contract ? <Link className="btn-secondary" to={`/${area}/bookings/${booking.id}/contract`}>Hợp đồng</Link> : null}
      {invoice ? <Link className="btn-secondary" to={`/${area}/bookings/${booking.id}/invoice`}>Hóa đơn</Link> : null}
      <Link className="btn-secondary" to="/rental-guide">Hướng dẫn thuê xe</Link>
    </div>
  </section>;
}
