import { useRef, useState } from 'react';
import { applyBookingAction, getBooking, previewBookingReturn } from '../../api/rental';
import { getErrorMessage } from '../../utils/roles';
import { Field, Notice, dateOnly, dateTime, money } from '../common/PageKit';

export function ReturnAssessmentSummary({ assessment, preview = false, settlement }) {
  if (!assessment) return null;
  return <section className="panel space-y-2" aria-label={preview ? 'Dự kiến nhận trả' : 'Kết quả nhận trả'}>
    <h2 className="text-lg font-semibold">{preview ? 'Xem trước nhận trả' : 'Kết quả nhận trả'}</h2>
    <p>{({ EARLY: 'Trả sớm', ON_TIME: 'Trả đúng ngày', LATE: 'Trả muộn' })[assessment.timing]}</p>
    <p>Ngày trả đăng ký: {dateOnly(assessment.bookedReturnDate)} · Ngày {preview ? 'ghi nhận dự kiến' : 'trả thực tế'}: {dateOnly(assessment.actualReturnDate)}</p>
    {!preview && <p>Ghi nhận lúc {dateTime(assessment.actualReturnedAt)}</p>}
    <p>Vượt hạn: {assessment.lateDays} ngày × {money(assessment.dailyPriceVnd)} = <strong>{money(assessment.amountVnd)}</strong></p>
    <p>{settlement && assessment.amountVnd > 0 ? settlement.outstandingAmount === 0 ? 'Khoản vượt hạn đã xử lý đủ. Xem chi tiết thu và miễn/giảm bên dưới.' : 'Chưa xử lý đủ khoản vượt hạn — chưa thể hoàn tất đơn. Xem số đã thu, miễn/giảm và còn phải thu bên dưới.' : assessment.collectionStatus === 'UNPAID' ? 'Chưa thu — chưa thể hoàn tất đơn. Khoản này không tự trừ cọc hoặc được coi là đã thanh toán.' : 'Không phát sinh khoản vượt hạn. Trả sớm không tự giảm tiền thuê hoặc hoàn cọc.'}</p>
  </section>;
}

export default function ReturnReceipt({ booking, onSaved }) {
  const [condition, setCondition] = useState('');
  const [maintenance, setMaintenance] = useState(false);
  const [preview, setPreview] = useState(null);
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState('');
  const guard = useRef(false);
  async function readback() {
    const detail = await getBooking(booking.id);
    if (detail.booking?.returnAssessment && ['RETURNED', 'COMPLETED'].includes(detail.booking.status)) {
      onSaved();
    } else {
      setError('Chưa đọc được kết quả nhận trả. Tải lại đơn để kiểm tra trước khi thao tác tiếp; không tự gửi lại.');
    }
  }
  async function run(action) {
    if (guard.current) return;
    guard.current = true; setBusy(true); setError('');
    try { await action(); }
    catch (e) { setError(getErrorMessage(e, 'Chưa xác minh được kết quả. Kiểm tra lại đơn trước khi thao tác tiếp.')); }
    finally { guard.current = false; setBusy(false); }
  }
  function edit(setter, value) { setter(value); setPreview(null); setError(''); }
  return <form className="panel space-y-4" aria-label="Ghi nhận trả xe" onSubmit={event => {
    event.preventDefault();
    if (sent) return;
    setPreview(null);
    run(async () => setPreview(await previewBookingReturn(booking.id)));
  }}>
    <h2 className="text-lg font-semibold">Ghi nhận trả xe</h2>
    <p>Chỉ xác nhận sau khi đã nhận xe thực tế. Ngày nhận trả do hệ thống ghi theo giờ Việt Nam.</p>
    <Field label="Tình trạng xe / ghi chú nhận trả"><textarea required maxLength={2000} value={condition} disabled={busy || sent} onChange={e => edit(setCondition, e.target.value)} /></Field>
    <label className="flex gap-3 text-sm"><input type="checkbox" checked={maintenance} disabled={busy || sent} onChange={e => edit(setMaintenance, e.target.checked)} />Xe cần bảo trì sau khi nhận.</label>
    {!sent && <button className="btn-secondary" disabled={busy || !condition.trim()}>Xem trước nhận trả</button>}
    {preview && <>
      <ReturnAssessmentSummary assessment={preview.assessment} preview />
      {!sent && <button type="button" className="btn" disabled={busy} onClick={() => run(async () => {
        try {
          await applyBookingAction(booking.id, { next: 'RETURNED', version: preview.version, condition, maintenance,
            returnPreviewDate: preview.assessment.actualReturnDate, expectedLateAmount: preview.assessment.amountVnd });
        } catch (e) {
          setPreview(null);
          if (e.response?.status >= 400 && e.response?.status < 500) throw e;
          setSent(true); throw e;
        }
        setSent(true);
        await readback();
      })}>Xác nhận đã nhận xe</button>}
    </>}
    {sent && <><Notice>Đã gửi thao tác nhận trả. Không gửi lại trước khi kiểm tra kết quả.</Notice><button className="btn-secondary" type="button" disabled={busy} onClick={() => run(readback)}>Kiểm tra kết quả nhận trả</button></>}
    {error && <Notice error>{error}</Notice>}
  </form>;
}
