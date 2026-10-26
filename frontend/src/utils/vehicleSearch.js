const keys = ["brand", "q", "category", "seats", "transmission", "sort", "preset", "compare", "start", "end", "pickupBranchId", "returnBranchId", "budget"];
export function isRentalDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) return false;
  const date=new Date(value+'T00:00:00Z');
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0,10)===value;
}
export function searchParams(source, changes = {}) {
  const result = new URLSearchParams();
  for (const key of keys) {
    const value = Object.hasOwn(changes, key) ? changes[key] : source.get(key);
    if (value !== null && value !== undefined && value !== "") result.set(key, value);
  }
  if (result.get("start") && result.get("end") && result.get("pickupBranchId") && !result.get("returnBranchId")) result.set("returnBranchId",result.get("pickupBranchId"));
  return result;
}
export function searchSuffix(params) {
  const value = searchParams(params).toString();
  return value ? `?${value}` : "";
}
export function tripSearch(params) {
  const fields = ["start", "end", "pickupBranchId", "returnBranchId"];
  const active = params.has("start") || params.has("end");
  if (!active) return { active: false, path: "", error: "" };
  const invalid = { active: true, path: "", error: "Hãy chọn đủ thời gian và điểm nhận/trả hợp lệ để tìm xe." };
  if (!params.get("returnBranchId") && params.get("pickupBranchId")) params = searchParams(params, {returnBranchId: params.get("pickupBranchId")});
  if (fields.some(key => !params.get(key))) return invalid;
  const dates = [params.get("start"), params.get("end")];
  if (dates.every(value => /^\d{4}-\d{2}-\d{2}$/.test(value))) {
    if (!dates.every(isRentalDate) || dates[1] <= dates[0]) return invalid;
    const budget=params.get('budget');
    if(budget!==null&&(!/^\d+$/.test(budget)||!Number.isSafeInteger(Number(budget))))return invalid;
    const api=new URLSearchParams({pickupDate:dates[0],returnDate:dates[1],pickupBranchId:params.get('pickupBranchId'),returnBranchId:params.get('returnBranchId')});
    if(budget!==null)api.set('budget',budget);
    return {active:true,path:`/vehicles/search?${api}`,error:''};
  }
  if (dates.some(value => !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value))) return invalid;
  const [start, end] = dates.map(value => new Date(`${value}:00+07:00`));
  if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || end <= start) return invalid;
  if ([start, end].some((value, index) => new Date(value.getTime() + 7 * 3600000).toISOString().slice(0, 16) !== dates[index])) return invalid;
  const budget = params.get("budget");
  if (budget !== null && (!/^\d+$/.test(budget) || !Number.isSafeInteger(Number(budget)))) return invalid;
  const api = new URLSearchParams({ startAt: start.toISOString(), endAt: end.toISOString(), pickupBranchId: params.get("pickupBranchId"), returnBranchId: params.get("returnBranchId") });
  if (budget !== null) api.set("budget", budget);
  return { active: true, path: `/vehicles/search?${api}`, error: "" };
}

export function formatBudget(value) {
  return value ? value.replace(/\B(?=(\d{3})+(?!\d))/g, ".") : "";
}

export function tripErrors(draft, now = Date.now()) {
  const errors = {};
  if (!(draft.start || draft.end || draft.pickupBranchId || draft.budget)) return errors;
  const calendar = /^\d{4}-\d{2}-\d{2}$/.test(draft.start || '');
  const parse = value => /^\d{4}-\d{2}-\d{2}$/.test(value || '') ? (isRentalDate(value) ? Date.parse(value+'T00:00:00+07:00'):NaN) : /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value || "") ? new Date(`${value}:00+07:00`).getTime() : NaN;
  const start = parse(draft.start), end = parse(draft.end);
  if (!draft.pickupBranchId) errors.pickupBranchId = "Chọn điểm nhận xe.";
  if (!Number.isFinite(start)) errors.start = "Chọn thời gian nhận xe.";
  else if (calendar ? draft.start < new Date(now+7*3600000).toISOString().slice(0,10) : start <= now) errors.start = "Ngày nhận không được trước hôm nay.";
  if (!Number.isFinite(end)) errors.end = "Chọn thời gian trả xe.";
  else if (Number.isFinite(start) && end <= start) errors.end = "Thời gian trả phải sau thời gian nhận.";
  else if (end - start > 90 * 86400000) errors.end = "Thời gian thuê không quá 90 ngày.";
  if (draft.differentReturn && !draft.returnBranchId) errors.returnBranchId = "Chọn điểm trả xe.";
  if (draft.budget && (!/^\d+$/.test(draft.budget) || !Number.isSafeInteger(Number(draft.budget)))) errors.budget = "Nhập ngân sách bằng VND nguyên hợp lệ.";
  return errors;
}
