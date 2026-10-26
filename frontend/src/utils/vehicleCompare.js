export function compareSelection(value = "") {
  const raw = value ? value.split(",") : [];
  const valid = raw.filter(id => /^[a-zA-Z0-9_-]{1,36}$/.test(id));
  const unique = [...new Set(valid)];
  return { ids: unique.slice(0,3), issue: raw.length !== valid.length ? "Có mã xe không hợp lệ trong lựa chọn." : unique.length !== raw.length ? "Mã xe trùng đã được gộp." : unique.length > 3 ? "Chỉ có thể so sánh tối đa 3 xe." : "" };
}
export function toggleCompare(ids, id) {
  if (ids.includes(id)) return {ids:ids.filter(value => value !== id),issue:""};
  if (ids.length >= 3) return {ids,issue:"Đã chọn 3 xe. Hãy bỏ một xe trước khi thêm xe khác."};
  return {ids:[...ids,id],issue:""};
}
