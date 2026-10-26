import { Link } from "react-router-dom";
import { Copy } from "lucide-react";
import { money, Status } from "../common/PageKit";
import { adminBookingDate, adminDateFormat } from "../../utils/bookingQueue";

export default function AdminBookingTable({ bookings, onCopy, copyFeedback, manualCopyId }) {
  return <div className="table-wrap admin-booking-table" tabIndex={0} role="region" aria-label="Danh sách đơn thuê, có thể cuộn ngang">
    <table className="data-table"><thead><tr><th scope="col">Mã đơn</th><th scope="col">Khách hàng / Xe</th><th scope="col">Ngày nhận / trả</th><th scope="col">Chi nhánh nhận / trả</th><th scope="col">Trạng thái</th><th scope="col">Tổng tiền</th><th scope="col">Thao tác</th></tr></thead>
    <tbody>{bookings.map(booking => <tr key={booking.id}>
      <td><span className="font-mono text-xs font-semibold" title={booking.id}>#{booking.id.slice(0, 8)}…</span><button type="button" className="admin-copy" onClick={() => onCopy(booking.id)} aria-label={`Sao chép mã đơn ${booking.id}`}><Copy size={13} aria-hidden="true" /> Sao chép mã đầy đủ</button>
        <details open={manualCopyId === booking.id || undefined}><summary className="text-xs text-slate-500 cursor-pointer">Xem mã đầy đủ</summary><input aria-label={`Mã đầy đủ đơn ${booking.id}`} readOnly value={booking.id} onFocus={event => event.target.select()} className="admin-full-id" /></details>
        {copyFeedback[booking.id] && <p role="status" className="text-xs text-slate-600 mt-1">{copyFeedback[booking.id]}</p>}
      </td>
      <td><p className="font-semibold">{booking.customerName || "Chưa có tên"}</p><p className="mt-1 text-slate-600">{booking.vehicleName}</p>{booking.createdAt && <p className="mt-2 text-xs text-slate-500">Tạo lúc {adminDateFormat(booking.createdAt, { withTime: true })}</p>}</td>
      <td><p>{adminBookingDate(booking, booking.startAt)}</p><p className="mt-1 text-slate-600">→ {adminBookingDate(booking, booking.endAt)}</p></td>
      <td><p>{booking.pickupName}</p><p className="mt-1 text-slate-600">→ {booking.returnName}</p></td>
      <td><Status status={booking.status} /></td><td className="font-semibold whitespace-nowrap">{money(booking.total)}</td>
      <td><Link className="btn-secondary" to={`/admin/bookings/${booking.id}`}>Chi tiết</Link></td>
    </tr>)}</tbody></table>
  </div>;
}
