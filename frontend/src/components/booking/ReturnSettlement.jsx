import { useRef, useState } from 'react';
import { getBooking, recordReturnSettlement } from '../../api/rental';
import { getErrorMessage } from '../../utils/roles';
import { Field, Notice, money, dateTime } from '../common/PageKit';

const statusLabels = { NOT_DUE: 'Không phát sinh', UNPAID: 'Chưa thu', PARTIAL: 'Còn phải xử lý', PAID: 'Đã thu đủ', WAIVED: 'Đã miễn toàn bộ', SETTLED: 'Đã thu và miễn/giảm đủ' };
const kindLabels = { RECEIPT: 'Ghi thu', WAIVER: 'Miễn/giảm', REVERSAL: 'Đảo ghi nhận sai' };
export function SettlementSummary({ settlement, history = true }) {
  if (!settlement || settlement.originalAmount === 0) return null;
  const reversed = new Set(settlement.entries.filter(e => e.reversesId).map(e => e.reversesId));
  return <section className="panel space-y-3" aria-label="Xử lý khoản vượt hạn">
    <h2 className="text-lg font-semibold">Khoản vượt hạn — {statusLabels[settlement.status]}</h2>
    {settlement.demo && <Notice>Mô phỏng — Không chuyển tiền thật. Các khoản ghi nhận dưới đây là DEMO.</Notice>}
    <p>Khoản gốc: {money(settlement.originalAmount)} · Đã thu: {money(settlement.receivedAmount)}</p>
    <p>Miễn/giảm: {money(settlement.waivedAmount)} · <strong>Còn phải thu: {money(settlement.outstandingAmount)}</strong></p>
    <p>Chỉ phản ánh khoản vượt hạn, không xác nhận toàn bộ tiền thuê đã thanh toán. Không tự trừ cọc.</p>
    {history && <ul className="space-y-3">{settlement.entries.map(e => <li key={e.id} className="border-t pt-3">
      <strong>{kindLabels[e.kind]}: {money(e.amount)}</strong>{reversed.has(e.id) && ' — Đã đảo'}
      <p>{e.actorName} · {dateTime(e.createdAt)}{e.method && ` · ${e.method === 'CASH' ? 'Tiền mặt' : 'Chuyển khoản'}`}</p>
      {e.reference && <p>Tham chiếu: {e.reference}</p>}<p>{e.reason}</p>
      {e.kind === 'REVERSAL' && <p>Đảo ghi nhận sai; không xác nhận đã hoàn tiền thực tế.</p>}
    </li>)}</ul>}
  </section>;
}

export default function ReturnSettlement({ booking, role, assignedReturn, onSaved }) {
  const [kind, setKind] = useState(role === 'ADMIN' ? 'WAIVER' : 'RECEIPT');
  const [amount, setAmount] = useState('');
  const [method, setMethod] = useState('CASH');
  const [reference, setReference] = useState('');
  const [reason, setReason] = useState('');
  const [reversesId, setReversesId] = useState('');
  const [review, setReview] = useState(null);
  const [pending, setPending] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const guard = useRef(false);
  const s = booking.returnSettlement;
  if (!s || s.originalAmount === 0) return null;
  const canWrite = booking.status === 'RETURNED' && (role === 'ADMIN' || (role === 'STAFF' && assignedReturn));
  const reversible = s.entries.filter(e => e.kind !== 'REVERSAL' && !s.entries.some(x => x.reversesId === e.id));
  async function run(fn) {
    if (guard.current) return;
    guard.current = true; setBusy(true); setError('');
    try { await fn(); } catch (e) { setError(getErrorMessage(e, 'Chưa xác minh được kết quả. Kiểm tra lại trước khi thao tác tiếp.')); }
    finally { guard.current = false; setBusy(false); }
  }
  async function readback(input) {
    const detail = await getBooking(booking.id);
    if (detail.booking?.returnSettlement?.entries.some(e => e.idempotencyKey === input.idempotencyKey)) onSaved();
    else setError('Chưa tìm thấy bản ghi trong đơn. Không gửi khoản mới; kiểm tra lại hoặc tải lại đơn để đối soát.');
  }
  return <div className="space-y-4"><SettlementSummary settlement={s} />
    {canWrite && <form className="panel space-y-4" aria-label="Xử lý công nợ vượt hạn" onSubmit={e => {
      e.preventDefault(); if (pending || busy) return;
      if (kind !== 'REVERSAL' && (!Number.isSafeInteger(Number(amount)) || Number(amount) <= 0 || Number(amount) > s.outstandingAmount)) { setError('Số tiền phải là VND nguyên dương, không vượt khoản còn thiếu.'); return; }
      setReview({ kind, amountVnd: kind === 'REVERSAL' ? null : Number(amount), method: kind === 'RECEIPT' ? method : null,
        reference: kind === 'RECEIPT' ? reference.trim() : null, reason: reason.trim(), reversesId: kind === 'REVERSAL' ? reversesId : null,
        expectedVersion: booking.version, confirmed: true, idempotencyKey: globalThis.crypto.randomUUID() });
    }}>
      <h2 className="text-lg font-semibold">{role === 'ADMIN' ? 'Quản lý khoản vượt hạn' : 'Ghi nhận khoản đã thu'}</h2>
      <fieldset disabled={busy || Boolean(pending) || Boolean(review)} className="space-y-3">
        {role === 'ADMIN' && <Field label="Thao tác công nợ"><select value={kind} onChange={e => setKind(e.target.value)}><option value="WAIVER">Miễn/giảm</option><option value="REVERSAL">Đảo ghi nhận sai</option></select></Field>}
        {kind === 'REVERSAL' ? <Field label="Bản ghi cần đảo"><select required value={reversesId} onChange={e => setReversesId(e.target.value)}><option value="">Chọn bản ghi</option>{reversible.map(e => <option key={e.id} value={e.id}>{kindLabels[e.kind]} · {money(e.amount)} · {e.reference || e.reason} · {dateTime(e.createdAt)}</option>)}</select></Field> : <Field label="Số tiền (VND)"><input required type="number" min="1" step="1" max={s.outstandingAmount} value={amount} onChange={e => setAmount(e.target.value)} /></Field>}
        {kind === 'RECEIPT' && <><Field label="Phương thức thu"><select value={method} onChange={e => setMethod(e.target.value)}><option value="CASH">Tiền mặt</option><option value="BANK_TRANSFER">Chuyển khoản đã đối chiếu</option></select></Field><Field label="Tham chiếu phiếu thu / giao dịch"><input required maxLength={120} value={reference} onChange={e => setReference(e.target.value)} /></Field></>}
        <Field label="Lý do / ghi chú công nợ"><textarea required maxLength={1000} value={reason} onChange={e => setReason(e.target.value)} /></Field>
        <button className="btn-secondary">Kiểm tra ghi nhận</button>
      </fieldset>
      {review && !pending && <div role="dialog" aria-label="Xác nhận xử lý khoản vượt hạn" className="space-y-3 border-t pt-3">
        <p>{booking.vehicleName} · Đơn {booking.id} · {kindLabels[review.kind]}</p>
        <strong>{money(review.amountVnd ?? reversible.find(e => e.id === review.reversesId)?.amount)}</strong>
        <p>{review.reference} · {review.reason}</p>
        <p>{review.kind === 'RECEIPT' ? 'Chỉ xác nhận khoản đã thực sự đối chiếu; trong DEMO chỉ mô phỏng.' : review.kind === 'REVERSAL' ? 'Đảo ghi nhận sai sẽ mở lại số dư. Không tự hoàn tiền.' : 'Miễn/giảm không phải đã thu tiền.'}</p>
        <button type="button" className="btn" disabled={busy} onClick={() => run(async () => {
          setPending(review);
          try { await recordReturnSettlement(booking.id, review); }
          catch (e) { if (e.response?.status >= 400 && e.response?.status < 500) { setPending(null); setReview(null); } throw e; }
          await readback(review);
        })}>Xác nhận ghi nhận</button>
        <button type="button" className="btn-secondary" disabled={busy} onClick={() => setReview(null)}>Quay lại chỉnh sửa</button>
      </div>}
      {pending && <><Notice>Đã gửi thao tác. Không tạo khoản mới trước khi đọc lại kết quả.</Notice><button type="button" className="btn-secondary" disabled={busy} onClick={() => run(() => readback(pending))}>Kiểm tra kết quả công nợ</button></>}
      {error && <Notice error>{error}</Notice>}
    </form>}
  </div>;
}
