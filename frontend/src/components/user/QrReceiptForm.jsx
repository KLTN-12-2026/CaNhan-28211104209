import { useEffect, useRef, useState } from 'react';
import apiClient from '../../api/client';
import { money, Notice } from '../common/PageKit';
import { toVietnamInstant } from '../../utils/calendarDate';
import { matchesReceipt, validateReceipt, vietnamNowInput } from '../../utils/qrReceipt';
import { getErrorMessage } from '../../utils/roles';

const emptyForm = () => ({ transactionRef: '', amountVnd: '', transferTime: '', sourceAccount: '' });

// Mounted with booking.id as key so pending receipts never move between bookings.
export default function QrReceiptForm({ booking, onRefresh }) {
  const [form, setForm] = useState(emptyForm);
  const [verified, setVerified] = useState(false);
  const [phase, setPhase] = useState('idle');
  const [candidate, setCandidate] = useState(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [canRetry, setCanRetry] = useState(false);
  const alive = useRef(false), locked = useRef(false), pending = useRef(null);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const base = `/bookings/${encodeURIComponent(booking.id)}`;

  function edit(name, value) { setForm(current => ({ ...current, [name]: value })); setError(''); }
  function confirm() {
    if (locked.current || pending.current || phase !== 'idle') return;
    const invalid = validateReceipt(form, verified);
    if (invalid) { setError(invalid); return; }
    setError(''); setNotice('');
    setCandidate({ transactionRef: form.transactionRef.trim(), amountVnd: Number(form.amountVnd),
      transferTime: toVietnamInstant(form.transferTime), sourceAccount: form.sourceAccount.trim(),
      transferNote: `KD${booking.id.replaceAll('-', '').toUpperCase()}`,
      expectedVersion: booking.version, verified: true });
    setPhase('confirming');
  }

  async function readBack(operation) {
    const [ledger, order] = await Promise.allSettled([apiClient.get(`${base}/deposit`), apiClient.get(base)]);
    if (!alive.current) return;
    if (ledger.status !== 'fulfilled' || !Array.isArray(ledger.value.data?.payments)) {
      setPhase(operation.acknowledged ? 'recorded' : 'unknown'); setCanRetry(false);
      setNotice(operation.acknowledged ? 'Máy chủ đã ghi nhận khoản DEMO nhưng chưa tải lại được giao dịch. Không nhập lại khoản này.' : 'Chưa xác định kết quả; chưa tải được giao dịch. Kiểm tra trước khi thử lại.');
      return;
    }
    const payments = ledger.value.data.payments;
    const receipt = payments.find(p => matchesReceipt(p, operation.payload, booking.id));
    if (receipt) {
      operation.acknowledged = true;
      setPhase('recorded'); setCanRetry(false);
      if (order.status !== 'fulfilled') {
        setNotice('Đã tìm thấy khoản DEMO đã nhận, nhưng chưa tải lại được đơn. Không ghi nhận lại khoản này.');
        return;
      }
      const updated = order.value.data.booking || order.value.data;
      const missing = updated.deposit?.missingAmount;
      setNotice(`Đã ghi nhận khoản DEMO ${money(receipt.amount)} · Tham chiếu ${receipt.providerTransaction}. ${updated.status === 'CONFIRMED' ? 'Đơn đã xác nhận.' : `Đơn chưa xác nhận; còn thiếu ${money(missing)}.`}`);
      pending.current = null; setForm(emptyForm()); setVerified(false); setCandidate(null); setPhase('idle');
      try { onRefresh(); } catch { setNotice('Khoản DEMO đã được ghi nhận. Cần tải lại đơn; không nhập lại khoản này.'); }
      return;
    }
    if (payments.some(p => p.idempotencyKey === operation.payload.idempotencyKey || p.providerTransaction === operation.payload.transactionRef)) {
      setPhase('blocked'); setCanRetry(false);
      setNotice('Có giao dịch dùng cùng khóa hoặc tham chiếu nhưng dữ liệu không khớp. Cần đối soát, không tạo khoản thay thế.');
      return;
    }
    setPhase(operation.blocked ? 'blocked' : operation.acknowledged ? 'recorded' : 'unknown');
    setCanRetry(!operation.blocked && !operation.acknowledged && order.status === 'fulfilled');
    setNotice(operation.acknowledged ? 'Máy chủ đã ghi nhận nhưng giao dịch chưa xuất hiện trong lần tải này. Không ghi nhận lại; hãy kiểm tra kết quả.' : 'Chưa thấy giao dịch không chứng minh lần gửi trước thất bại. Chỉ kiểm tra lại hoặc thử lại đúng yêu cầu cũ; không đổi tham chiếu.');
  }

  async function send(operation) {
    if (locked.current) return;
    locked.current = true; setPhase('sending'); setError(''); setCanRetry(false);
    try {
      await apiClient.post(`${base}/deposit/verify`, operation.payload);
      operation.acknowledged = true;
    } catch (e) {
      if (!alive.current) return;
      const status = e.response?.status;
      if (status === 400) {
        pending.current = null; setCandidate(null); setPhase('idle');
        setError(getErrorMessage(e, 'Dữ liệu bị từ chối; kiểm tra lại trước khi gửi.'));
        locked.current = false;
        return;
      }
      if (status === 403) {
        operation.blocked = true; locked.current = false;
        setPhase('blocked'); setNotice('Không có quyền ghi nhận khoản này. Dừng thao tác và kiểm tra phân công.');
        return;
      }
      setNotice(status === 409 ? 'Đơn hoặc tham chiếu đã thay đổi. Đang kiểm tra kết quả; không tạo khoản thay thế.' : 'Chưa xác định kết quả ghi nhận. Đang kiểm tra giao dịch.');
      if (status === 409) operation.blocked = true;
    }
    finally { if (!alive.current) locked.current = false; }
    try { if (alive.current) await readBack(operation); }
    finally { locked.current = false; }
    if (alive.current && operation.blocked && pending.current) { setPhase('blocked'); setCanRetry(false); }
  }

  function submitConfirmed() {
    if (phase !== 'confirming' || locked.current || pending.current) return;
    const operation = { payload: { ...candidate, idempotencyKey: window.crypto.randomUUID() }, acknowledged: false };
    pending.current = operation;
    void send(operation);
  }
  async function checkResult() {
    if (locked.current || !pending.current) return;
    locked.current = true; setCanRetry(false);
    try { await readBack(pending.current); } finally { locked.current = false; }
  }

  return <section className="mt-4 space-y-3 border-t pt-3" aria-label="Ghi nhận cọc QR DEMO">
    <h3 className="font-medium">Xác minh khoản cọc (Staff chi nhánh nhận)</h3>
    <p className="text-sm">Chỉ ghi nhận khoản DEMO đã đối chiếu. Không chuyển tiền thật.</p>
    <fieldset disabled={phase !== 'idle'} className="space-y-3">
      <div className="grid gap-2 sm:grid-cols-2">
        <label className="field"><span>Mã tham chiếu giao dịch</span><input maxLength={30} value={form.transactionRef} onChange={e => edit('transactionRef', e.target.value)} /></label>
        <label className="field"><span>Số tiền thực nhận (VND)</span><input type="number" min="1" step="1" value={form.amountVnd} onChange={e => edit('amountVnd', e.target.value)} /></label>
      </div>
      <label className="field"><span>Thời điểm chuyển (giờ VN)</span><input type="datetime-local" step="1" value={form.transferTime} onChange={e => edit('transferTime', e.target.value)} /></label>
      <button type="button" className="btn-secondary" onClick={() => edit('transferTime', vietnamNowInput())}>Điền thời điểm hiện tại (giờ Việt Nam)</button>
      <p className="text-sm text-muted">Đối chiếu thời điểm thực tế trên chứng từ và sửa lại nếu cần; đây không phải thời điểm tự xác minh.</p>
      <label className="field"><span>Tài khoản nguồn (giả lập)</span><input maxLength={100} value={form.sourceAccount} onChange={e => edit('sourceAccount', e.target.value)} /></label>
      <label className="block"><input type="checkbox" checked={verified} onChange={e => setVerified(e.target.checked)} /> Đã đối chiếu đúng đơn, nội dung, bên nhận DEMO và số tiền</label>
      <button type="button" className="btn" onClick={confirm}>Xác minh và ghi nhận</button>
    </fieldset>
    {phase === 'confirming' && <div role="dialog" aria-label="Xác nhận khoản DEMO" className="panel space-y-2">
      <h4>Xác nhận ghi nhận khoản DEMO</h4>
      <p>Đơn {booking.id.slice(0, 8)} · {booking.customerName} · {booking.vehicleName}</p>
      <p>Số tiền: {money(candidate.amountVnd)} · Tham chiếu: {candidate.transactionRef}</p>
      <p>Thời điểm chuyển (giờ Việt Nam): {candidate.transferTime && new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Ho_Chi_Minh', dateStyle: 'short', timeStyle: 'medium', hourCycle: 'h23' }).format(new Date(candidate.transferTime))}</p>
      <p>Tài khoản nguồn giả lập: {candidate.sourceAccount} · Bên nhận DEMO theo yêu cầu cọc của đơn.</p>
      <button className="btn" onClick={submitConfirmed}>Xác nhận ghi nhận DEMO</button>
      <button className="btn-secondary" onClick={() => { setCandidate(null); setPhase('idle'); }}>Quay lại chỉnh sửa</button>
    </div>}
    {phase === 'sending' && <p role="status">Đang ghi nhận… Không gửi lại yêu cầu.</p>}
    {notice && <p role="status">{notice}</p>}
    {error && <Notice error>{error}</Notice>}
    {['unknown', 'recorded', 'blocked'].includes(phase) && <button className="btn-secondary" onClick={checkResult}>Kiểm tra kết quả ghi nhận</button>}
    {phase === 'unknown' && canRetry && <button className="btn-secondary" onClick={() => send(pending.current)}>Thử lại đúng yêu cầu cũ</button>}
  </section>;
}
