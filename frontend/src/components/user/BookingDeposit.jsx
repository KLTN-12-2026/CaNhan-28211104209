import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import apiClient from '../../api/client';
import { useApiData } from '../../hooks/useApiData';
import { money, Notice, Page, dateTime } from '../common/PageKit';
import { getErrorMessage } from '../../utils/roles';
import { vietnamDay } from '../../utils/bookingQueue';
import QrReceiptForm from './QrReceiptForm';

export const paymentLabels = { PENDING: 'Đang xác minh', UNKNOWN: 'Cần đối soát', SUCCEEDED: 'Đã nhận', FAILED: 'Thất bại' };
export const refundLabels = { REQUESTED: 'Chờ Admin quyết định', APPROVED: 'Đã duyệt hoàn', PROCESSING: 'Đang xử lý', UNKNOWN: 'Cần đối soát', SUCCEEDED: 'Đã hoàn', FAILED: 'Hoàn thất bại', DECLINED: 'Không hoàn' };
const eventLabels = { PAYMENT_CREATED: 'Tạo phiên thanh toán', DEPOSIT_APPROVED: 'Duyệt hồ sơ, yêu cầu cọc', DEPOSIT_APPROVED_QR: 'Duyệt hồ sơ, tạo mã QR cọc', PAYMENT_RECEIVED: 'Xác minh đã nhận cọc', PAYMENT_FAILED: 'Thanh toán thất bại', PAYMENT_UNKNOWN: 'Thanh toán cần đối soát', DEPOSIT_APPLIED: 'Xác nhận đơn từ cọc', REFUND_REQUESTED: 'Tạo yêu cầu hoàn', REFUND_DECIDED: 'Duyệt khoản hoàn', REFUND_RECONCILED: 'Đối soát khoản hoàn' };
const date = dateTime;

export default function BookingDeposit({ booking, role, assignedPickup, reload, now }) {
  const request = useApiData(`/bookings/${encodeURIComponent(booking.id)}/deposit`);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [contacted, setContacted] = useState(false), [notDelivered, setNotDelivered] = useState(false), [reason, setReason] = useState('');
  const [showQr, setShowQr] = useState(false);
  const [qrData, setQrData] = useState(null);
  const [qrUrl,setQrUrl]=useState('');
  const objectUrl=useRef(null);
  useEffect(() => () => { if(objectUrl.current) window.URL.revokeObjectURL(objectUrl.current); }, []);
  const data = request.data;

  async function action(path, body = {}) {
    if (busy) return;
    setBusy(true); setError('');
    try {
      const response = await apiClient.post(`/bookings/${encodeURIComponent(booking.id)}/${path}`, body);
      if (response.data?.url) window.location.assign(response.data.url);
      else { request.reload(); reload(); }
    } catch (e) { setError(getErrorMessage(e, 'Không thể xử lý khoản cọc.')); }
    finally { setBusy(false); }
  }

  async function loadQr() {
    try {
      const res = await apiClient.get(`/bookings/${encodeURIComponent(booking.id)}/deposit/qr-payload`);
      const png=await apiClient.get(`/bookings/${encodeURIComponent(booking.id)}/deposit/qr`,{responseType:'blob'});
      if(objectUrl.current) window.URL.revokeObjectURL(objectUrl.current);
      objectUrl.current=window.URL.createObjectURL(png.data);setQrUrl(objectUrl.current);
      setQrData(res.data);
      setShowQr(true);
    } catch (e) { setError(getErrorMessage(e, 'Không tải được mã QR.')); }
  }

  async function handleReport() {
    if (busy) return;
    setBusy(true); setError('');
    try {
      await apiClient.post(`/bookings/${encodeURIComponent(booking.id)}/deposit/report`, {
        idempotencyKey: `report-${booking.id}`
      });
      request.reload(); reload(); setShowQr(false);
    } catch (e) { setError(getErrorMessage(e, 'Không thể gửi báo chuyển khoản.')); }
    finally { setBusy(false); }
  }

  const deposit = booking.deposit;
  const isQr = deposit?.method === 'DEMO_QR_MANUAL';
  const isVnpay = deposit?.method === 'VNPAY_SANDBOX';
  const depositApproved = deposit?.approvedAt;
  const depositDue = deposit?.paymentDueAt;
  const reportedAt = deposit?.reportedAt;
  const appliedAmount = deposit?.appliedAmount ?? 0;
  const receivedAmount = deposit?.receivedAmount ?? 0;
  const missingAmount = deposit?.missingAmount ?? 0;
  const excessAmount = deposit?.excessAmount ?? 0;
  const depositMode = deposit?.mode ?? 'FULL';

  return <section className="panel mb-5">
    <h2 className="mb-3 text-lg font-semibold">
      Cọc giữ chỗ {isQr ? '· QR giả lập (DEMO)' : isVnpay ? '· VNPAY Sandbox' : ''}
    </h2>
    {isQr && <p className="text-amber-700 font-medium">Mô phỏng — Không chuyển tiền thật. Chỉ nhân viên đối soát thủ công.</p>}
    <p>Cọc: <strong>{money(deposit?.amount)}</strong> · Còn lại: {money(booking.total - deposit?.amount)}</p>
    <p>{deposit?.txnRef ? 'Đã ghi nhận cọc cho đơn' : booking.status !== 'PENDING' ? 'Đơn không còn chờ đóng cọc. Xem tình trạng thu/hoàn bên dưới.' :
      reportedAt ? 'Khách đã báo · Chờ đối soát' : depositApproved ? 'Chờ đóng cọc' : 'Chờ kiểm tra hồ sơ'}</p>
    <dl className="mt-3 grid gap-2 text-sm" aria-label="Các mốc thời hạn cọc">
      {!depositApproved ? <div><dt>Hạn chờ kiểm tra hồ sơ</dt><dd>{date(booking.expiresAt)}</dd></div> : <>
        <div><dt>Hạn đóng cọc</dt><dd>{date(depositDue)}</dd></div>
        <div><dt>Hạn nhân viên đối soát</dt><dd>{date(deposit.reviewDueAt)}</dd></div>
        <div><dt>Hạn giữ lịch gốc</dt><dd>{date(deposit.originalHoldUntil)}</dd></div>
      </>}
    </dl>
    {depositApproved && booking.status === 'PENDING' && <p className="mt-2 text-sm text-slate-600">Chuyển khoản trước hạn đóng cọc. Hạn đối soát dành cho nhân viên kiểm tra, không gia hạn thời gian chuyển khoản. Báo đã chuyển khoản chưa xác nhận đã nhận tiền.</p>}
    <p className="mt-2 text-xs text-muted">Các mốc theo giờ Việt Nam, do hệ thống cung cấp.</p>

    {booking.status === 'PENDING' && data && !data.acceptingNew && isVnpay ?
      <p>Đang tạm dừng tạo thanh toán mới. Giao dịch đã tạo vẫn được hệ thống đối soát.</p> : null}

    {isVnpay && depositApproved && role === 'USER' && booking.status === 'PENDING' && (
      <button className="btn mt-3" disabled={busy} onClick={() => action('deposit/session')}>Thanh toán cọc thử nghiệm</button>
    )}

    {request.loading ? <p>Đang tải tình trạng thu/hoàn…</p> : null}
    {request.error ? <Notice error>{request.error}<button className="btn-secondary" onClick={request.reload}>Tải lại</button></Notice> : null}
    {error ? <Notice error>{error}</Notice> : null}

    {isQr && depositApproved && role === 'USER' && booking.status === 'PENDING' && (
      <div className="mt-4 space-y-3">
        <button className="btn" disabled={busy} onClick={()=>showQr?setShowQr(false):loadQr()}>
          {showQr ? 'Ẩn mã QR' : 'Hiển thị mã QR cọc'}
        </button>
        {showQr && qrData && (
          <div className="space-y-3 border-t pt-3">
            <p className="text-sm text-amber-700"><strong>Mã yêu cầu:</strong> {qrData.requestCode}</p>
            <button className="btn-secondary" onClick={()=>navigator.clipboard.writeText(`${qrData.amount} ${qrData.requestCode}`)}>Sao chép số tiền và nội dung</button>
            <p className="text-sm text-amber-700"><strong>Số tiền:</strong> {money(qrData.amount)}</p>
            <p className="text-sm text-amber-700"><strong>Hạn thanh toán:</strong> {date(qrData.paymentDueAt)}</p>
            <p className="text-sm text-amber-700"><strong>Hạn đối soát:</strong> {date(qrData.reviewDueAt)}</p>
            <img src={qrUrl} alt="Mã QR cọc giả lập" style={{maxWidth: '200px'}} />
            <p className="text-xs text-red-600"><strong>{qrData.warning}</strong></p>
            <details className="text-sm">
              <summary>Thông tin tài khoản nhận (giả lập)</summary>
              <pre className="mt-2 whitespace-pre-wrap bg-slate-50 p-3 rounded">{qrData.receiverSnapshot}</pre>
            </details>
            <hr />
            <p className="font-medium">Tôi đã chuyển khoản (báo cho nhân viên đối soát)</p>
            {!reportedAt ? <button className="btn mt-2" disabled={busy} onClick={handleReport}>Gửi báo đã chuyển khoản</button> : <p>Đã gửi báo; chưa xác nhận tiền cho đến khi nhân viên đối soát.</p>}
          </div>
        )}
      </div>
    )}

    {isQr && depositApproved && role === 'STAFF' && assignedPickup && booking.status === 'PENDING' && (
      <QrReceiptForm key={booking.id} booking={booking} onRefresh={() => { request.reload(); reload(); }} />
    )}

    {role === 'STAFF' && assignedPickup && booking.status === 'PENDING' && deposit?.required && !depositApproved && (
      <button className="btn mt-3" disabled={busy} onClick={() => action('deposit/approve', { expectedVersion: booking.version })}>
        {isQr ? 'Duyệt hồ sơ và tạo mã QR cọc' : 'Duyệt hồ sơ và yêu cầu đóng cọc'}
      </button>
    )}

    {role === 'STAFF' && assignedPickup && booking.status === 'CONFIRMED' && vietnamDay(now) > vietnamDay(booking.startAt) && (
      <form className="mt-4 space-y-2" onSubmit={e => { e.preventDefault(); action('no-show', { expectedVersion: booking.version, contacted, notDelivered, reason }); }}>
        <p>Khách không đến nhận: chỉ xử lý sau hết ngày nhận; nếu đã giao xe, chuyển xử lý ngoại lệ.</p>
        <label className="block"><input type="checkbox" required checked={contacted} onChange={e => setContacted(e.target.checked)} /> Đã liên hệ khách</label>
        <label className="block"><input type="checkbox" required checked={notDelivered} onChange={e => setNotDelivered(e.target.checked)} /> Xe chưa thực sự được giao</label>
        <label className="block">Lý do<textarea className="input" required maxLength={1000} value={reason} onChange={e => setReason(e.target.value)} /></label>
        <button className="btn-secondary" disabled={busy}>Ghi nhận khách không đến nhận</button>
      </form>
    )}

    {data?.payments?.map(p => <div key={p.id} className="mt-2 panel">
      <p className="font-medium">Giao dịch {p.id.slice(0, 8)} · {money(p.amount)} · {paymentLabels[p.status] || 'Cần kiểm tra'}</p>
      {p.appliedAmount !== undefined && <p className="text-sm">Đã áp dụng: {money(p.appliedAmount)} / {money(p.amount)}</p>}
      {p.providerTransaction && <p className="text-sm">Tham chiếu: {p.providerTransaction}</p>}
      {p.transferTime && <p className="text-sm">Thời điểm chuyển: {date(p.transferTime)}</p>}
      {p.transferNote && <p className="text-sm">Nội dung: {p.transferNote}</p>}
      {p.sourceAccount && <p className="text-sm">Tài khoản nguồn: {p.sourceAccount}</p>}
      {p.verifierId && <p className="text-sm">Xác minh bởi: {p.verifierId} lúc {date(p.verifiedAt)}</p>}
    </div>)}

    {data?.refunds?.map(r => <div key={r.id} className="mt-2 panel">
      <p className="font-medium">Hoàn {money(r.amount)} · {refundLabels[r.status] || 'Cần kiểm tra'} · {r.reason}</p>
      <p className="text-sm">Loại: {r.kind} · Duyệt bởi: {r.approvedBy || '—'} · {r.approvedAt ? date(r.approvedAt) : '—'}</p>
    </div>)}

    {role === 'ADMIN' && data?.events?.length && (
      <details className="mt-3"><summary>Lịch sử xử lý cọc</summary>
      <ol className="mt-2 space-y-2 text-sm">{data.events.map((e, i) => <li key={`${e.created_at}-${i}`}>
        <p>{eventLabels[e.kind] || (e.kind.startsWith('REFUND_') ? `Hoàn tiền: ${refundLabels[e.kind.slice(7)] || 'Cần kiểm tra'}` : 'Cập nhật đối soát')} · {date(e.created_at)}</p>
        <p>{e.note} · {e.actor_id ? `Mã người thực hiện: ${e.actor_id}` : 'Hệ thống tự động'}</p>
      </li>)}</ol></details>
    )}

    {depositApproved && booking.status === 'PENDING' && (
      <div className="mt-3 p-3 bg-slate-50 rounded text-sm">
        <p><strong>Tóm tắt cọc:</strong> Đã nhận {money(receivedAmount)} · Đã áp dụng {money(appliedAmount)} · Còn thiếu {money(missingAmount)} {excessAmount > 0 ? `· Dư {money(excessAmount)}` : ''} · Trạng thái: {depositMode === 'FULL' ? 'Đủ' : depositMode === 'PARTIAL' ? 'Thiếu' : 'Dư'}</p>
        {reportedAt && <p>Khách đã báo chuyển khoản lúc {date(reportedAt)}</p>}
      </div>
    )}
  </section>;
}

export function PaymentReturnPage() {
  const [params] = useSearchParams();
  return <Page title="Kết quả thanh toán thử nghiệm"><section className="panel">
    <p>Đang xác minh với VNPAY Sandbox. Trang quay về không xác nhận đã nhận tiền.</p>
    {params.get('vnp_TxnRef') ? <p>Vui lòng mở đơn để xem kết quả thu cọc và hoàn tiền từ hệ thống.</p> : null}
    <Link className="btn mt-4" to="/user/bookings">Xem đơn thuê của tôi</Link>
  </section></Page>;
}