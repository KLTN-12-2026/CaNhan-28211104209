import { Link } from 'react-router-dom';
import { scheduleWarnings } from '../../utils/scheduleWarnings';
import { displayCalendarDate, formatVietnamDate } from '../../utils/calendarDate';

export default function ScheduleWarnings({ booking, area }) {
  if (!['staff', 'admin'].includes(area)) return null;
  const items = scheduleWarnings(booking);
  if (!items.length) return null;
  return <section className="notice mt-3 space-y-2 text-sm" aria-label="Cảnh báo lịch xe">
    {items.map(item => <div key={`${item.kind}-${item.relatedBookingId || ''}`}>
      <p className="font-semibold">{item.kind === 'RETURN_DUE_NEXT_DAY_BOOKING'
        ? 'Cần theo dõi — có đơn nhận xe ngày kế tiếp'
        : item.kind === 'OVERDUE' ? 'Xe quá hạn — chưa nhận trả' : 'Xe của đơn trước chưa về'}</p>
      <p>Ngày trả dự kiến{item.kind === 'PREDECESSOR_NOT_RETURNED' ? ' của đơn trước' : ''}: {displayCalendarDate(item.bookedReturnDate) || '—'}
        {item.kind !== 'RETURN_DUE_NEXT_DAY_BOOKING' && item.lateDays > 0 ? ` · Quá hạn ${item.lateDays} ngày` : ''}.</p>
      {item.relatedBookingId && item.relatedBookingId !== booking.id ? <Link className="text-brand underline" to={`/${area}/bookings/${encodeURIComponent(item.relatedBookingId)}`}>
        {item.kind === 'RETURN_DUE_NEXT_DAY_BOOKING' ? 'Xem đơn nhận xe kế tiếp' : 'Xem đơn chưa trả xe'}
      </Link> : null}
    </div>)}
    <p className="text-xs text-muted">Cập nhật lúc {formatVietnamDate(booking.scheduleWarnings.checkedAt, { withTime: true })}. Dùng nút Tải lại để kiểm tra mới nhất.</p>
    <p className="text-xs">Liên hệ khách và báo quản lý nếu cần xử lý. Cảnh báo không tự hủy hoặc đổi đơn thuê.</p>
  </section>;
}
