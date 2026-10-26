export function displayCalendarDate(iso) {
  return /^\d{4}-\d{2}-\d{2}$/.test(iso) ? iso.split("-").reverse().join("/") : "";
}
export function parseCalendarDate(text) {
  if (!/^\d{2}\/\d{2}\/\d{4}$/.test(text)) return "";
  const iso = text.split("/").reverse().join("-");
  const date = new Date(iso + "T00:00:00Z");
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === iso ? iso : "";
}

export function toVietnamInstant(value) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(value ?? '')) return null;
  const date=new Date(`${value}+07:00`);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}
// Display only: never use a formatted value to calculate a booking deadline.
export function formatVietnamDate(value, { withTime = false } = {}) {
  if (value == null || value === '') return '—';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '—';
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Ho_Chi_Minh', day: '2-digit', month: '2-digit', year: 'numeric',
    hour: withTime ? '2-digit' : undefined, minute: withTime ? '2-digit' : undefined,
    hourCycle: 'h23',
  }).formatToParts(date);
  const part = type => parts.find(p => p.type === type)?.value;
  const day = `${part('day')}/${part('month')}/${part('year')}`;
  return withTime ? `${part('hour')}:${part('minute')} ${day}` : day;
}
