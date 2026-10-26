import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import apiClient from '../../api/client';
import { useApiData } from '../../hooks/useApiData';
import { Page, Notice, money } from '../../components/common/PageKit';
import { refundLabels, paymentLabels } from '../../components/user/BookingDeposit';
import { getErrorMessage } from '../../utils/roles';
import { toVietnamInstant } from '../../utils/calendarDate';
import { vietnamNowInput } from '../../utils/qrReceipt';
import { matchesPaymentSearch, refundDate, refundResult, validateRefundAmount, validateRefundCompletion } from '../../utils/adminRefund';

const activeRefunds = new Set(['REQUESTED', 'APPROVED', 'PROCESSING', 'UNKNOWN', 'FAILED']);
const actionLabels = { create: 'Tạo yêu cầu hoàn', decide: 'Duyệt khoản hoàn', process: 'Bắt đầu xử lý hoàn', complete: 'Xác nhận đã hoàn DEMO', reconcile: 'Truy vấn kết quả' };

export function RefundRow({ item, run, busy, depositMethod = item.depositMethod, payment }) {
  const [amount, setAmount] = useState(String(item.amount)), [reason, setReason] = useState('');
  const [form, setForm] = useState({ recipientAccount: '', reference: '', transferTime: '' });
  const [verified, setVerified] = useState(false), [error, setError] = useState('');
  const isQr = depositMethod === 'DEMO_QR_MANUAL';
  function decide(e) {
    e.preventDefault(); const invalid = validateRefundAmount(amount, reason, item.amount, true);
    if (invalid) { setError(invalid); return; } setError('');
    run(`/admin/refunds/${item.id}/decide`, { expectedVersion: item.version, amount: Number(amount), reason: reason.trim() }, { type: 'decide', item });
  }
  function complete(e) {
    e.preventDefault(); const invalid = validateRefundCompletion(form, payment?.sourceAccount, verified);
    if (invalid) { setError(invalid); return; } setError('');
    run(`/admin/refunds/${item.id}/complete-manual`, { recipientAccount: form.recipientAccount.trim(), reference: form.reference.trim(), transferTime: toVietnamInstant(form.transferTime), expectedVersion: item.version }, { type: 'complete', item });
  }
  return <section className="panel space-y-3" aria-label={`Yêu cầu hoàn ${item.id}`}>
    <Link to={`/admin/bookings/${item.bookingId}`}>Đơn {item.bookingId.slice(0, 8)}</Link>
    <p><strong>{refundLabels[item.status] || 'Cần kiểm tra'}</strong> · {money(item.amount)} · {item.reason}</p>
    <p>{isQr ? 'QR DEMO · Không chuyển tiền thật' : 'VNPAY Sandbox'} · Tạo lúc {refundDate(item.createdAt)}</p>
    <details><summary>Thông tin đối soát</summary><dl className="mt-2 space-y-2 break-all">
      <div><dt>Mã yêu cầu hoàn</dt><dd>{item.id}</dd></div>
      <div><dt>Tham chiếu thanh toán</dt><dd>{payment?.providerTransaction || item.paymentId}</dd></div>
      <div><dt>{isQr ? 'Tham chiếu hoàn DEMO' : 'Tham chiếu VNPAY'}</dt><dd>{item.providerTransaction || 'Chưa có kết quả xác minh'}</dd></div>
      <div><dt>Người duyệt (mã tài khoản)</dt><dd>{item.approvedBy || (item.status === 'REQUESTED' ? 'Chưa quyết định' : 'Theo chính sách tự động')}</dd></div>
      <div><dt>Thời điểm duyệt</dt><dd>{refundDate(item.approvedAt)}</dd></div>
    </dl></details>
    {error && <Notice error>{error}</Notice>}
    <fieldset disabled={busy} className="space-y-3">
      {item.status === 'REQUESTED' && <form className="space-y-2" onSubmit={decide}>
        <label className="field">Số tiền hoàn (VND)<input type="number" min="0" max={item.amount} step="1" required value={amount} onChange={e => setAmount(e.target.value)} /></label>
        <p>Tối đa {money(item.amount)}. Quyết định 0đ là không hoàn, bắt buộc có lý do.</p>
        <label className="field">Lý do quyết định<textarea required maxLength={1000} value={reason} onChange={e => setReason(e.target.value)} /></label>
        <button className="btn" type="submit">Duyệt khoản hoàn</button>
      </form>}
      {item.status === 'APPROVED' && <>
        <p>Đã duyệt chưa có nghĩa đã hoàn tiền.</p>
        <button className="btn-secondary" onClick={() => run(`/admin/refunds/${item.id}/${isQr ? 'process-manual' : 'process'}`, {}, { type: 'process', item })}>{isQr ? 'Bắt đầu xử lý hoàn (QR DEMO)' : 'Gửi hoàn VNPAY Sandbox'}</button>
      </>}
      {['PROCESSING', 'UNKNOWN'].includes(item.status) && <p>Cần kiểm tra kết quả. Không gửi lại yêu cầu hoàn khi chưa rõ kết quả.</p>}
      {['PROCESSING', 'UNKNOWN'].includes(item.status) && !isQr && <button className="btn-secondary" onClick={() => run(`/admin/refunds/${item.id}/reconcile`, {}, { type: 'reconcile-refund', item })}>Truy vấn kết quả hoàn</button>}
      {isQr && item.status === 'PROCESSING' && <form className="space-y-2" onSubmit={complete}>
        <p>Chỉ ghi nhận kết quả hoàn DEMO đã đối chiếu. Nguồn tiền gốc: <strong>{payment?.sourceAccount || 'Chưa tải được — cần tải lại'}</strong>.</p>
        <label className="field">Tài khoản nhận hoàn<input required maxLength={100} value={form.recipientAccount} onChange={e => setForm({ ...form, recipientAccount: e.target.value })} /></label>
        <label className="field">Tham chiếu hoàn<input required maxLength={30} value={form.reference} onChange={e => setForm({ ...form, reference: e.target.value })} /></label>
        <label className="field">Thời điểm hoàn (giờ Việt Nam)<input required type="datetime-local" step="1" value={form.transferTime} onChange={e => setForm({ ...form, transferTime: e.target.value })} /></label>
        <button className="btn-secondary" type="button" onClick={() => setForm({ ...form, transferTime: vietnamNowInput() })}>Điền thời điểm hoàn hiện tại (giờ Việt Nam)</button>
        <label className="block"><input type="checkbox" checked={verified} onChange={e => setVerified(e.target.checked)} /> Đã đối chiếu tài khoản, số tiền và chứng từ hoàn DEMO</label>
        <button className="btn" type="submit" disabled={!payment}>Xác nhận đã hoàn DEMO</button>
      </form>}
    </fieldset>
  </section>;
}

export function ManualRefundForm({ payment, available, run, busy }) {
  const [amount, setAmount] = useState(''), [reason, setReason] = useState(''), [error, setError] = useState('');
  function submit(e) {
    e.preventDefault(); const invalid = validateRefundAmount(amount, reason, available);
    if (invalid) { setError(invalid); return; } setError('');
    run('/admin/refunds/create-manual', { paymentId: payment.id, amount: Number(amount), reason: reason.trim() }, { type: 'create', item: payment });
  }
  return <section className="panel space-y-3" aria-label="Tạo yêu cầu hoàn thủ công">
    <p>QR DEMO · Khoản nhận {payment.providerTransaction || payment.id.slice(0, 8)}</p>
    <p>Có thể yêu cầu tối đa {money(available)} cho phần chưa áp dụng, sau khi trừ khoản hoàn đã dành. Máy chủ kiểm tra lại khi gửi.</p>
    {error && <Notice error>{error}</Notice>}
    <fieldset disabled={busy}><form className="space-y-2" onSubmit={submit}>
      <label className="field">Số tiền yêu cầu hoàn (VND)<input type="number" min="1" max={available} step="1" required value={amount} onChange={e => setAmount(e.target.value)} /></label>
      <label className="field">Lý do yêu cầu hoàn<textarea required maxLength={1000} value={reason} onChange={e => setReason(e.target.value)} /></label>
      <button className="btn" type="submit">Tạo yêu cầu hoàn</button>
    </form></fieldset>
  </section>;
}

export default function ApiPaymentsPage() {
  const request = useApiData('/admin/payments');
  const [snapshot, setSnapshot] = useState(null), [phase, setPhase] = useState('idle');
  const [review, setReview] = useState(null), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [query, setQuery] = useState(''), [paymentStatus, setPaymentStatus] = useState(''), [refundStatus, setRefundStatus] = useState('active');
  const [manualPayment, setManualPayment] = useState(null);
  const alive = useRef(false), locked = useRef(false), pending = useRef(null);
  const reviewElement = useRef(null);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => { if (phase === 'confirming') reviewElement.current?.focus(); }, [phase]);
  const data = snapshot || request.data;
  const busy = phase !== 'idle';
  function prepare(path, body, action) {
    if (locked.current || pending.current || busy || !data) return;
    setError(''); setNotice(''); setReview({ ...action, path, body: { ...body }, previousIds: data.refunds.map(r => r.id) }); setPhase('confirming');
  }
  async function readBack(operation) {
    try {
      const response = await apiClient.get('/admin/payments');
      if (!alive.current) return;
      const updated = response.data;
      if (!Array.isArray(updated?.payments) || !Array.isArray(updated?.refunds) || !Array.isArray(updated?.unresolved)) throw Error('Invalid readback');
      setSnapshot(updated); setError('');
      const matched = operation && refundResult(operation, updated);
      if (!operation || matched) {
        pending.current = null; setReview(null); setManualPayment(null); setPhase('idle');
        setNotice(matched ? `Đã kiểm tra kết quả: ${(operation.type === 'reconcile' ? paymentLabels : refundLabels)[matched.status] || 'Đã tạo yêu cầu'}. Chỉ trạng thái “Đã hoàn” xác nhận hoàn tất khoản hoàn.` : 'Đã tải lại dữ liệu từ máy chủ.');
      } else {
        setPhase('unknown');
        setNotice(operation.acknowledged ? 'Máy chủ đã nhận thao tác nhưng dữ liệu tải lại chưa xác nhận kết quả tương ứng. Không gửi lại; kiểm tra đơn và lịch sử.' : 'Chưa xác định kết quả thao tác. Danh sách đã được tải lại; không thấy kết quả chưa chứng minh lần gửi trước thất bại. Không tạo yêu cầu thay thế.');
      }
    } catch (e) {
      if (!alive.current) return;
      setPhase(operation ? 'unknown' : 'idle'); setError(getErrorMessage(e, 'Chưa tải lại được kết quả.'));
      setNotice(operation?.acknowledged ? 'Máy chủ đã nhận thao tác nhưng chưa tải lại được kết quả. Không gửi lại khoản này.' : operation ? 'Chưa xác định kết quả; chỉ kiểm tra lại, không gửi yêu cầu mới.' : 'Dữ liệu đang hiển thị có thể chưa cập nhật.');
    }
  }
  async function submit() {
    if (locked.current || pending.current || !review) return;
    const operation = { ...review }; pending.current = operation; locked.current = true; setPhase('sending'); setError('');
    try {
      const response = await apiClient.post(operation.path, operation.body);
      operation.acknowledged = true; operation.responseId = response.data?.id;
    } catch (e) {
      if (!alive.current) { locked.current = false; return; }
      if ([400, 403].includes(e.response?.status)) {
        pending.current = null; setReview(null); setPhase('idle'); setError(getErrorMessage(e, 'Thao tác bị từ chối; kiểm tra dữ liệu và quyền.')); locked.current = false; return;
      }
      setError(getErrorMessage(e, 'Kết quả chưa rõ; cần kiểm tra trước khi tiếp tục.'));
    }
    try { if (alive.current) await readBack(operation); } finally { locked.current = false; }
  }
  async function reload() {
    if (locked.current || phase === 'confirming') return;
    locked.current = true; setPhase('checking');
    try { await readBack(pending.current); } finally { locked.current = false; }
  }
  const payments = data?.payments || [], refunds = data?.refunds || [];
  const refundPayments = [...payments, ...(data?.refundPayments || [])];
  const filteredPayments = payments.filter(p => matchesPaymentSearch(p, query) && (!paymentStatus || p.status === paymentStatus));
  const filteredRefunds = refunds.filter(r => (matchesPaymentSearch(r, query) || matchesPaymentSearch(refundPayments.find(p => p.id === r.paymentId) || {}, query)) && (refundStatus === 'active' ? activeRefunds.has(r.status) : !refundStatus || r.status === refundStatus));
  const manual = payments.find(p => p.id === manualPayment);
  return <Page title="Cọc và hoàn tiền · Sandbox" actions={<button className="btn-secondary" disabled={['sending', 'checking', 'confirming'].includes(phase)} onClick={reload}>{phase === 'unknown' ? 'Kiểm tra kết quả thao tác' : 'Tải lại'}</button>}>
    <p className="mb-4">QR DEMO / VNPAY Sandbox · Không chuyển tiền thật. Duyệt, đang xử lý và đã hoàn là các trạng thái riêng.</p>
    {(error || (!snapshot && request.error)) && <Notice error>{error || request.error}</Notice>}
    {notice && <p role="status" className="mb-4">{notice}</p>}
    {['sending', 'checking'].includes(phase) && <p role="status">{phase === 'sending' ? 'Đang gửi thao tác… Không gửi lại.' : 'Đang kiểm tra kết quả…'}</p>}
    {phase === 'confirming' && review && <section ref={reviewElement} tabIndex={-1} role="dialog" aria-label="Xác nhận thao tác hoàn tiền" className="panel mb-4 space-y-2">
      <h2>{actionLabels[review.type] || 'Truy vấn kết quả hoàn'}</h2>
      <p>Đơn {review.item.bookingId.slice(0, 8)} · Khoản {review.item.id.slice(0, 8)} · Hiện tại: {(['create', 'reconcile'].includes(review.type) ? paymentLabels : refundLabels)[review.item.status]}</p>
      <p>Số tiền: {money(review.body.amount ?? review.item.amount)} · {review.item.depositMethod === 'DEMO_QR_MANUAL' ? 'QR DEMO — Không chuyển tiền thật' : 'VNPAY Sandbox'}</p>
      {review.body.reason && <p>Lý do: {review.body.reason}</p>}
      {review.type === 'complete' && <><p>Tài khoản nhận giả lập: {review.body.recipientAccount} · Tham chiếu: {review.body.reference}</p><p>Thời điểm hoàn (giờ Việt Nam): {refundDate(review.body.transferTime)}</p></>}
      <p>{review.type === 'complete' ? 'Chỉ xác nhận sau khi đã đối chiếu kết quả hoàn DEMO.' : 'Thao tác này chưa xác nhận tiền đã hoàn.'}</p>
      <button className="btn" onClick={submit}>Xác nhận thao tác</button>
      <button className="btn-secondary" onClick={() => { setReview(null); setPhase('idle'); }}>Quay lại chỉnh sửa</button>
    </section>}
    {!data && request.loading && <p>Đang tải…</p>}
    {data && <>
      <section className="panel mb-4 space-y-2" aria-label="Bộ lọc cọc và hoàn">
        <label className="field">Tìm mã đơn, giao dịch hoặc tham chiếu<input value={query} onChange={e => setQuery(e.target.value)} /></label>
        <p>Lọc trong {data.historyLimit || 200} giao dịch và yêu cầu hoàn gần nhất. Mở chi tiết đơn để xem toàn bộ lịch sử của đơn.</p>
      </section>
      <h2 className="mb-3 text-lg font-semibold">Giao dịch cần đối soát</h2>
      {!data.unresolved.length && <p>Không có giao dịch cần đối soát trong danh sách hiện tại.</p>}
      {data.unresolved.filter(p => matchesPaymentSearch(p, query)).map(p => <section className="panel mb-3" key={p.id}>
        <Link to={`/admin/bookings/${p.bookingId}`}>Đơn {p.bookingId.slice(0, 8)}</Link>
        <p>{p.providerTransaction || p.id.slice(0, 8)} · {money(p.amount)} · {paymentLabels[p.status]}</p>
        {p.depositMethod === 'DEMO_QR_MANUAL' ? <p>QR DEMO · Kiểm tra khoản nhận, đơn và lịch sử hoàn trước khi xử lý.</p> : <button className="btn-secondary" disabled={busy} onClick={() => prepare(`/admin/payments/${p.id}/reconcile`, {}, { type: 'reconcile', item: p })}>Đối soát với VNPAY</button>}
      </section>)}
      <h2 className="my-3 text-lg font-semibold">Yêu cầu và lịch sử hoàn tiền</h2>
      <label className="field mb-3">Trạng thái hoàn<select value={refundStatus} onChange={e => setRefundStatus(e.target.value)}><option value="active">Cần xử lý</option><option value="">Tất cả</option>{Object.entries(refundLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <div className="space-y-3">{filteredRefunds.map(r => <RefundRow key={`${r.id}-${r.version}`} item={r} run={prepare} busy={busy} payment={refundPayments.find(p => p.id === r.paymentId)} />)}</div>
      {!filteredRefunds.length && <p>Không có yêu cầu hoàn phù hợp bộ lọc.</p>}
      <h2 className="my-3 text-lg font-semibold">Lịch sử nhận cọc</h2>
      <label className="field mb-3">Trạng thái giao dịch<select value={paymentStatus} onChange={e => setPaymentStatus(e.target.value)}><option value="">Tất cả</option>{Object.entries(paymentLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      {!filteredPayments.length && <p>Không có giao dịch phù hợp bộ lọc.</p>}
      {filteredPayments.map(p => {
        const available = data.refundableAmounts?.[p.id] ?? 0;
        const hasManualRequest = data.manualRefundPaymentIds?.includes(p.id) || refunds.some(r => r.paymentId === p.id && r.kind === 'MANUAL_EXCESS');
        return <section className="panel mb-3 space-y-2" key={p.id} aria-label={`Khoản nhận ${p.id}`}>
          <Link to={`/admin/bookings/${p.bookingId}`}>Đơn {p.bookingId.slice(0, 8)}</Link>
          <p>{p.providerTransaction || p.id.slice(0, 8)} · {money(p.amount)} · {paymentLabels[p.status] || 'Cần kiểm tra'} · {p.depositMethod === 'DEMO_QR_MANUAL' ? 'QR DEMO' : 'VNPAY Sandbox'}</p>
          <p>Ghi nhận lúc {refundDate(p.verifiedAt || p.createdAt)} · Đã áp dụng {money(p.appliedAmount || 0)}</p>
          {p.depositMethod === 'DEMO_QR_MANUAL' && p.status === 'SUCCEEDED' && <>
            <p>Phần chưa áp dụng còn được yêu cầu hoàn: {money(available)} (máy chủ tính).</p>
            {hasManualRequest ? <p>Đã có yêu cầu hoàn thủ công; xem trạng thái trong lịch sử hoàn hoặc chi tiết đơn.</p> : available > 0 && <button className="btn-secondary" disabled={busy} onClick={() => setManualPayment(p.id)}>Tạo yêu cầu hoàn phần chưa áp dụng</button>}
            {manual?.id === p.id && !hasManualRequest && <ManualRefundForm key={p.id} payment={p} available={available} run={prepare} busy={busy} />}
          </>}
        </section>;
      })}
    </>}
  </Page>;
}
