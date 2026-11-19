import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import apiClient from '../../api/client';
import { useApiData } from '../../hooks/useApiData';
import { money, Notice, Page, dateTime } from '../common/PageKit';
import { getErrorMessage } from '../../utils/roles';
import { vietnamDay } from '../../utils/bookingQueue';
import { qrReconciliationStageLabel, qrReconciliationWarnings, userQrDepositStatus } from '../../utils/qrReconciliation';
import QrReceiptForm from './QrReceiptForm';

export const paymentLabels = { PENDING: 'Đang xác minh', UNKNOWN: 'Cần đối soát', SUCCEEDED: 'Đã nhận', FAILED: 'Thất bại' };
export const refundLabels = { REQUESTED: 'Chờ Admin quyết định', APPROVED: 'Đã duyệt hoàn', PROCESSING: 'Đang xử lý', UNKNOWN: 'Cần đối soát', SUCCEEDED: 'Đã hoàn', FAILED: 'Hoàn thất bại', DECLINED: 'Không hoàn' };
const eventLabels = { PAYMENT_CREATED: 'Tạo phiên thanh toán', DEPOSIT_APPROVED: 'Duyệt hồ sơ, yêu cầu cọc', DEPOSIT_APPROVED_QR: 'Duyệt hồ sơ, tạo mã QR cọc', PAYMENT_RECEIVED: 'Xác minh đã nhận cọc', PAYMENT_FAILED: 'Thanh toán thất bại', PAYMENT_UNKNOWN: 'Thanh toán cần đối soát', DEPOSIT_APPLIED: 'Xác nhận đơn từ cọc', REFUND_REQUESTED: 'Tạo yêu cầu hoàn', REFUND_DECIDED: 'Duyệt khoản hoàn', REFUND_RECONCILED: 'Đối soát khoản hoàn' };
const date = dateTime;

class QrDataError extends Error {}

function qrLoadError(error) {
  if (error instanceof QrDataError) return error.message;
  const status = error?.response?.status;
  if (status === 401 || status === 403 || status === 404) return 'Không mở được mã QR của đơn này. Đăng nhập đúng tài khoản đặt đơn rồi tải lại trang.';
  if (status >= 400 && status < 500 && typeof error.response.data?.message === 'string') return `${error.response.data.message} Tải lại đơn để xem trạng thái mới nhất.`;
  return 'Không tải được mã QR. Kiểm tra kết nối rồi thử tải lại; chưa chuyển tiền khi chưa thấy mã QR.';
}

export default function BookingDeposit({ booking, role, assignedPickup, reload, now }) {
  const request = useApiData(`/bookings/${encodeURIComponent(booking.id)}/deposit`);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [contacted, setContacted] = useState(false), [notDelivered, setNotDelivered] = useState(false), [reason, setReason] = useState('');
  const [showQr, setShowQr] = useState(false);
  // Kết quả QR gắn với booking.id lúc gửi; chỉ hiển thị khi khớp đơn đang xem.
  const [qr, setQr] = useState({ bookingId: null, status: 'idle', data: null, url: '', error: '' });
  const [copyStatus, setCopyStatus] = useState({ bookingId: null, text: '', failed: false });
  const objectUrl = useRef(null), qrTicket = useRef(0), qrLoadingFor = useRef(null), mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false; qrTicket.current += 1;
      if (objectUrl.current) window.URL.revokeObjectURL(objectUrl.current);
      objectUrl.current = null;
    };
  }, []);
  const qrView = qr.bookingId === booking.id ? qr : { status: 'idle', data: null, url: '', error: '' };
  const qrData = qrView.status === 'ready' ? qrView.data : null;
  const copyText = copyStatus.bookingId === booking.id ? copyStatus : null;
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
    const bookingId = booking.id;
    if (qrLoadingFor.current === bookingId) return;
    const ticket = ++qrTicket.current;
    qrLoadingFor.current = bookingId;
    setShowQr(true); setCopyStatus({ bookingId: null, text: '', failed: false });
    setQr({ bookingId, status: 'loading', data: null, url: '', error: '' });
    const current = () => mounted.current && ticket === qrTicket.current;
    try {
      const res = await apiClient.get(`/bookings/${encodeURIComponent(bookingId)}/deposit/qr-payload`);
      const payload = res.data;
      if (!payload || typeof payload.requestCode !== 'string' || !payload.requestCode.trim() || !Number.isSafeInteger(payload.amount) || payload.amount <= 0) {
        throw new QrDataError('Dữ liệu mã QR không hợp lệ. Tải lại đơn rồi thử lại; không chuyển tiền theo dữ liệu này.');
      }
      if (Number.isFinite(deposit?.amount) && payload.amount !== deposit.amount) {
        throw new QrDataError('Số tiền trên mã QR khác số cọc của đơn đang xem. Tải lại đơn trước khi chuyển tiền.');
      }
      const png = await apiClient.get(`/bookings/${encodeURIComponent(bookingId)}/deposit/qr`, { responseType: 'blob' });
      if (!current()) return;
      if (objectUrl.current) window.URL.revokeObjectURL(objectUrl.current);
      objectUrl.current = window.URL.createObjectURL(png.data);
      setQr({ bookingId, status: 'ready', data: payload, url: objectUrl.current, error: '' });
    } catch (e) {
      if (!current()) return;
      setQr({ bookingId, status: 'error', data: null, url: '', error: qrLoadError(e) });
    } finally {
      if (ticket === qrTicket.current) qrLoadingFor.current = null;
    }
  }

  async function copyValue(value, label) {
    const bookingId = booking.id;
    try {
      if (!window.navigator?.clipboard?.writeText) throw new Error('clipboard unavailable');
      await window.navigator.clipboard.writeText(String(value));
      if (mounted.current) setCopyStatus({ bookingId, text: `Đã sao chép ${label}.`, failed: false });
    } catch {
      if (mounted.current) setCopyStatus({ bookingId, text: `Không sao chép được ${label}. Hãy chép thủ công: ${value}`, failed: true });
    }
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
  const reconciliation = data?.qrReconciliation ?? booking.qrReconciliation;
  const showStaffReconciliation = role === 'STAFF' && assignedPickup && isQr;
  const hasReceivedAmount = Number.isFinite(reconciliation?.receivedAmount);
  const hasAppliedAmount = Number.isFinite(reconciliation?.appliedAmount);
  const reconciliationWarnings = qrReconciliationWarnings(reconciliation);
  const terminalReconciliationStage = ['APPLIED', 'EXPIRED', 'CLOSED'].includes(reconciliation?.stage);
  const userQrStatus = role === 'USER' && isQr ? userQrDepositStatus(booking, reconciliation) : null;
  // USER QR: chỉ mở hướng dẫn chuyển khi stage backend còn chờ chuyển; thiếu summary giữ hành vi cũ theo trường deposit.
  const userTransferOpen = userQrStatus ? (userQrStatus.transferOpen ?? true) : true;

  return <section className="panel mb-5">
    <h2 className="mb-3 text-lg font-semibold">
      Cọc giữ chỗ {isQr ? '· QR giả lập (DEMO)' : isVnpay ? '· VNPAY Sandbox' : ''}
    </h2>
    {isQr && <p className="text-amber-700 font-medium">Mô phỏng — Không chuyển tiền thật. Chỉ nhân viên đối soát thủ công.</p>}
    <p>Cọc: <strong>{money(deposit?.amount)}</strong> · Còn lại: {money(booking.total - deposit?.amount)}</p>
    {userQrStatus ? <section className="mt-2" aria-label="Tình trạng cọc của bạn">
      <p className="font-medium" aria-live="polite">Tình trạng cọc: {userQrStatus.label}</p>
      <p className="mt-1 text-sm">{userQrStatus.message}</p>
      <p className="mt-1 text-sm"><strong>Bước tiếp theo:</strong> {userQrStatus.nextAction}</p>
    </section> : <p>{deposit?.txnRef ? 'Đã ghi nhận cọc cho đơn' : booking.status !== 'PENDING' ? 'Đơn không còn chờ đóng cọc. Xem tình trạng thu/hoàn bên dưới.' :
      reportedAt ? 'Khách đã báo · Chờ đối soát' : depositApproved ? 'Chờ đóng cọc' : 'Chờ kiểm tra hồ sơ'}</p>}
    <dl className="mt-3 grid gap-2 text-sm" aria-label="Các mốc thời hạn cọc">
      {!depositApproved ? <div><dt>Hạn chờ kiểm tra hồ sơ</dt><dd>{date(booking.expiresAt)}</dd></div> : <>
        <div><dt>Hạn đóng cọc</dt><dd>{date(depositDue)}</dd></div>
        <div><dt>Hạn nhân viên đối soát</dt><dd>{date(deposit.reviewDueAt)}</dd></div>
        <div><dt>Hạn giữ lịch gốc</dt><dd>{date(deposit.originalHoldUntil)}</dd></div>
      </>}
    </dl>
    {showStaffReconciliation && <section className="mt-4 border-t pt-3 text-sm" aria-label="Chi tiết đối soát cọc QR">
      <h3 className="font-medium">Đối soát cọc QR</h3>
      <dl className="mt-2 grid gap-2 sm:grid-cols-2">
        <div><dt className="text-slate-500">Trạng thái đối soát</dt><dd>{qrReconciliationStageLabel(reconciliation)}</dd></div>
        {Number.isFinite(deposit?.amount) && <div><dt className="text-slate-500">Cọc yêu cầu</dt><dd>{money(deposit.amount)}</dd></div>}
        {hasReceivedAmount && <div><dt className="text-slate-500">Tổng đã nhận (lịch sử)</dt><dd>{money(reconciliation.receivedAmount)}</dd></div>}
        {hasAppliedAmount && <div><dt className="text-slate-500">Đã áp dụng vào đơn</dt><dd>{money(reconciliation.appliedAmount)}</dd></div>}
        {depositDue && <div><dt className="text-slate-500">Hạn đóng cọc</dt><dd>{date(depositDue)}</dd></div>}
        {reconciliation?.reviewDueAt && <div><dt className="text-slate-500">Hạn đối soát</dt><dd>{date(reconciliation.reviewDueAt)}</dd></div>}
      </dl>
      {hasReceivedAmount && <p className="mt-2 text-slate-600">Tổng đã nhận là số liệu lịch sử, không phải số tiền hiện đang giữ.</p>}
      {reconciliation?.stage === 'RECEIVED_INCOMPLETE' && <p className="mt-1 text-slate-600">Giai đoạn này có thể gồm khoản nhận chưa đủ điều kiện; không đồng nghĩa khách chắc chắn đã chuyển thiếu.</p>}
      {reconciliation?.stage === 'AWAITING_APPLICATION' && <p className="mt-1 text-slate-600">Đủ khoản hợp lệ chưa bảo đảm đơn được xác nhận; hệ thống còn kiểm tra điều kiện đơn.</p>}
      {terminalReconciliationStage && <p className="mt-1 text-slate-600">Không yêu cầu khách đóng thêm từ thông tin đối soát của đơn này.</p>}
      {reconciliationWarnings.length > 0 && <p className="mt-2 font-medium text-amber-700" role="status" aria-label="Cảnh báo đối soát">{reconciliationWarnings.join(' · ')}</p>}
    </section>}
    {depositApproved && booking.status === 'PENDING' && userTransferOpen && <p className="mt-2 text-sm text-slate-600">Chuyển khoản trước hạn đóng cọc. Hạn đối soát dành cho nhân viên kiểm tra, không gia hạn thời gian chuyển khoản. Báo đã chuyển khoản chưa xác nhận đã nhận tiền.</p>}
    <p className="mt-2 text-xs text-muted">Các mốc theo giờ Việt Nam, do hệ thống cung cấp.</p>

    {booking.status === 'PENDING' && data && !data.acceptingNew && isVnpay ?
      <p>Đang tạm dừng tạo thanh toán mới. Giao dịch đã tạo vẫn được hệ thống đối soát.</p> : null}

    {isVnpay && depositApproved && role === 'USER' && booking.status === 'PENDING' && (
      <button className="btn mt-3" disabled={busy} onClick={() => action('deposit/session')}>Thanh toán cọc thử nghiệm</button>
    )}

    {request.loading ? <p>Đang tải tình trạng thu/hoàn…</p> : null}
    {request.error ? <Notice error>{request.error}<button className="btn-secondary" onClick={request.reload}>Tải lại</button></Notice> : null}
    {error ? <Notice error>{error}</Notice> : null}

    {isQr && depositApproved && role === 'USER' && booking.status === 'PENDING' && userTransferOpen && (
      <div className="mt-4 space-y-3">
        <button className="btn" disabled={busy || qrView.status === 'loading'} onClick={() => showQr && qrView.status === 'ready' ? setShowQr(false) : loadQr()}>
          {qrView.status === 'loading' ? 'Đang tải mã QR…' : showQr && qrView.status === 'ready' ? 'Ẩn mã QR' : 'Hiển thị mã QR cọc'}
        </button>
        {showQr && qrView.status === 'error' && <Notice error>{qrView.error} <button className="btn-secondary" onClick={loadQr}>Thử tải lại mã QR</button></Notice>}
        {showQr && qrData && (
          <div className="space-y-3 border-t pt-3" aria-label="Mã QR cọc DEMO">
            <p className="font-medium text-amber-700">Mã QR DEMO — không chuyển tiền thật. Chỉ dùng để thử quy trình đối soát.</p>
            <dl className="grid gap-2 text-sm sm:grid-cols-2">
              <div><dt className="text-slate-500">Số tiền cọc</dt><dd className="font-semibold">{money(qrData.amount)}</dd></div>
              <div><dt className="text-slate-500">Nội dung chuyển khoản (mã yêu cầu)</dt><dd className="font-semibold">{qrData.requestCode}</dd></div>
              <div><dt className="text-slate-500">Hạn đóng cọc (giờ Việt Nam)</dt><dd>{date(qrData.paymentDueAt)}</dd></div>
              <div><dt className="text-slate-500">Hạn nhân viên đối soát (giờ Việt Nam)</dt><dd>{date(qrData.reviewDueAt)}</dd></div>
            </dl>
            <p className="text-xs text-slate-600">Hạn đối soát dành cho nhân viên, không kéo dài thời gian chuyển khoản.</p>
            <div className="flex flex-wrap gap-2">
              <button className="btn-secondary" onClick={() => copyValue(qrData.requestCode, 'nội dung chuyển khoản')}>Sao chép nội dung chuyển khoản</button>
              <button className="btn-secondary" onClick={() => copyValue(qrData.amount, 'số tiền')}>Sao chép số tiền</button>
            </div>
            {copyText?.text ? <p className={copyText.failed ? 'text-sm text-red-700' : 'text-sm text-slate-600'} role="status">{copyText.text}</p> : null}
            <img src={qrView.url} alt="Mã QR cọc giả lập" style={{maxWidth: '200px'}} />
            {qrData.warning ? <p className="text-xs text-red-600"><strong>{qrData.warning}</strong></p> : null}
            <details className="text-sm">
              <summary>Thông tin tài khoản nhận (giả lập)</summary>
              <pre className="mt-2 whitespace-pre-wrap bg-slate-50 p-3 rounded">{qrData.receiverSnapshot}</pre>
            </details>
            <hr />
            <p className="text-sm"><strong>Bước tiếp theo:</strong> chuyển đúng số tiền và nội dung trước hạn đóng cọc, rồi gửi báo đã chuyển khoản. Báo đã chuyển chưa xác nhận đã nhận tiền.</p>
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
      {role === 'USER' ? p.verifiedAt && <p className="text-sm">Đã xác minh lúc {date(p.verifiedAt)}</p>
        : p.verifierId && <p className="text-sm">Xác minh bởi: {p.verifierId} lúc {date(p.verifiedAt)}</p>}
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

    {userQrStatus && depositApproved && booking.status === 'PENDING' && (
      <div className="mt-3 p-3 bg-slate-50 rounded text-sm" aria-label="Tóm tắt cọc của bạn">
        {Number.isFinite(deposit?.amount) && <p>Cọc yêu cầu: {money(deposit.amount)}</p>}
        {hasAppliedAmount && reconciliation.appliedAmount > 0 && <p>Đã ghi nhận vào đơn: {money(reconciliation.appliedAmount)}</p>}
        {reportedAt && <p>Bạn đã báo chuyển khoản lúc {date(reportedAt)}</p>}
      </div>
    )}
    {depositApproved && booking.status === 'PENDING' && !(role === 'STAFF' && isQr) && !userQrStatus && (
      <div className="mt-3 p-3 bg-slate-50 rounded text-sm">
        <p><strong>Tóm tắt cọc:</strong> Đã nhận {money(receivedAmount)} · Đã áp dụng {money(appliedAmount)} · Còn thiếu {money(missingAmount)}{excessAmount > 0 ? ` · Dư ${money(excessAmount)}` : ''} · Trạng thái: {depositMode === 'FULL' ? 'Đủ' : depositMode === 'PARTIAL' ? 'Thiếu' : 'Dư'}</p>
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