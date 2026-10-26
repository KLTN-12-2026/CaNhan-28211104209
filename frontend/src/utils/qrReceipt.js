import { toVietnamInstant } from './calendarDate.js';

export function vietnamNowInput(now = new Date()) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Ho_Chi_Minh', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(now).map(p => [p.type, p.value]));
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}`;
}

export function validateReceipt(form, verified) {
  if (!form.transactionRef.trim() || form.transactionRef.trim().length > 30)
    return 'Nhập mã tham chiếu từ 1 đến 30 ký tự.';
  if (!/^\d+$/.test(form.amountVnd) || !Number.isSafeInteger(Number(form.amountVnd)) || Number(form.amountVnd) <= 0)
    return 'Số tiền phải là VND nguyên dương trong giới hạn an toàn.';
  const instant = toVietnamInstant(form.transferTime);
  if (!instant || vietnamNowInput(new Date(instant)) !== (form.transferTime.length === 16 ? `${form.transferTime}:00` : form.transferTime))
    return 'Nhập thời điểm chuyển hợp lệ theo giờ Việt Nam.';
  if (!form.sourceAccount.trim() || form.sourceAccount.trim().length > 100)
    return 'Nhập tài khoản nguồn giả lập từ 1 đến 100 ký tự.';
  if (!verified) return 'Cần xác nhận đã đối chiếu khoản DEMO.';
  return '';
}

export function matchesReceipt(payment, payload, bookingId) {
  return payment.bookingId === bookingId && payment.status === 'SUCCEEDED' &&
    payment.amount === payload.amountVnd && payment.providerTransaction === payload.transactionRef &&
    payment.idempotencyKey === payload.idempotencyKey && payment.sourceAccount === payload.sourceAccount &&
    payment.transferNote === payload.transferNote &&
    new Date(payment.transferTime).getTime() === new Date(payload.transferTime).getTime();
}
