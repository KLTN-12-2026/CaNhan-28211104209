import { ClipboardList, CircleCheck, FileText, Receipt } from "lucide-react";
import DateField from "../../components/common/DateField";
import { displayCalendarDate } from "../../utils/calendarDate";
import { useState } from "react";
import { operationalReportPath } from "../../api/reports";
import { useApiData } from "../../hooks/useApiData";
import { currentVietnamMonth, reportDateError } from "../../utils/reportDates";
import { Page, Field, Notice, money, dateTime, Status } from "../../components/common/PageKit";
import LoadingSkeleton from "../../components/common/LoadingSkeleton";


export default function ApiDashboardPage() {
  const [applied, setApplied] = useState(currentVietnamMonth);
  const [draft, setDraft] = useState(applied);
  const [validation, setValidation] = useState("");
  const request = useApiData(operationalReportPath(applied.from, applied.to));
  const data = request.data;
  function apply(event) {
    event.preventDefault();
    const error = reportDateError(draft.from, draft.to);
    setValidation(error);
    if (error || request.loading) return;
    if (draft.from === applied.from && draft.to === applied.to) request.reload();
    else setApplied({ ...draft });
  }
  return <Page title="Tổng quan" description="Số liệu hệ thống theo ngày Việt Nam. Tổng giá trị hóa đơn không xác nhận tiền đã thanh toán.">
    <form onSubmit={apply} className="panel admin-report-filters mb-6 flex flex-wrap items-end gap-4">
      <Field label="Từ ngày"><DateField min="1970-01-02" max="2038-01-18" required value={draft.from} onChange={from => setDraft({ ...draft, from })} aria-label="Từ ngày" /></Field>
      <Field label="Đến ngày"><DateField min="1970-01-02" max="2038-01-18" required value={draft.to} onChange={to => setDraft({ ...draft, to })} aria-label="Đến ngày" /></Field>
      <button className="btn" type="submit" disabled={request.loading}>Xem báo cáo</button>
      <button className="btn-secondary" type="button" disabled={request.loading} onClick={request.reload}>Làm mới</button>
    </form>
    {validation && <Notice error>{validation}</Notice>}
    <p className="mb-4 text-sm text-slate-600">Khoảng đang xem: {displayCalendarDate(applied.from)} – {displayCalendarDate(applied.to)} (giờ Việt Nam), bao gồm cả hai ngày. Tối đa 366 ngày.</p>
    {request.loading ? <LoadingSkeleton /> : request.error ? <div className="space-y-3"><Notice error>{request.error}</Notice><button type="button" className="btn-secondary" onClick={request.reload}>Thử tải lại</button></div> : data ? <>
      <div className="admin-report-grid">
      <div className="admin-metrics" data-testid="report-metrics">
        {[["Đơn tạo trong khoảng", data.createdBookings.total, ClipboardList, "info"], ["Đơn hoàn tất trong khoảng", data.completedBookings, CircleCheck, "success"], ["Hóa đơn phát hành", data.issuedInvoices, FileText, "warning"], ["Tổng giá trị hóa đơn", money(BigInt(data.invoiceTotalVnd)), Receipt, "total"]].map(([label, value, Icon, tone]) => <section className={`panel admin-metric admin-metric-${tone}`} key={label}><span className="admin-metric-icon"><Icon size={20} aria-hidden="true" /></span><h2>{label}</h2><p data-metric={label}>{value}</p></section>)}
      </div>
      {data.createdBookings.total === 0 && data.completedBookings === 0 && data.issuedInvoices === 0 && <div className="admin-report-empty"><Notice>Chưa có dữ liệu trong khoảng đã chọn.</Notice></div>}
      <section className="panel admin-report-status"><h2 className="text-lg font-semibold">Trạng thái hiện tại của các đơn tạo trong khoảng</h2><p className="my-3 text-sm text-slate-600">Đây là trạng thái lúc xem báo cáo, không phải lịch sử trạng thái tại ngày kết thúc. Đơn chờ đã hết hạn được tính là hết hạn.</p>
        <table className="w-full text-left"><thead><tr><th className="py-2">Trạng thái</th><th className="py-2 text-right">Số đơn</th></tr></thead><tbody>{Object.entries(data.createdBookings.byStatus).map(([status, count]) => <tr key={status}><td className="py-2"><Status status={status} /></td><td className="py-2 text-right" data-status={status}>{count}</td></tr>)}</tbody></table>
      </section></div><p className="mt-4 text-sm text-slate-600">Mốc tính đơn hết hạn: {dateTime(data.asOf)}. Hóa đơn và đơn hoàn tất được lọc theo mốc phát hành/hoàn tất riêng.</p>
    </> : null}
  </Page>;
}
