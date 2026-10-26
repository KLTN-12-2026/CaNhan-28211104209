export function currentVietnamMonth(now = new Date()) {
  const local = new Date(now.getTime() + 7 * 3600000);
  const year = local.getUTCFullYear(), month = local.getUTCMonth();
  return { from: `${year}-${String(month + 1).padStart(2, "0")}-01`, to: new Date(Date.UTC(year, month + 1, 0)).toISOString().slice(0, 10) };
}
export function reportDateError(from, to) {
  const valid = value => /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value;
  if (!valid(from) || !valid(to)) return "Vui lòng nhập ngày hợp lệ.";
  const days = (Date.parse(to) - Date.parse(from)) / 86400000 + 1;
  if (days < 1 || days > 366 || from < "1970-01-02" || to > "2038-01-18") return "Chọn từ 1 đến 366 ngày, trong miền 02/01/1970–18/01/2038.";
  return "";
}
