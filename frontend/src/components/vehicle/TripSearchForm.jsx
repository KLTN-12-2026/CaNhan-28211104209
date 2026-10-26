import { Notice } from "../common/PageKit";
import { useId, useState } from "react";
import useNow from "../../hooks/useNow";
import { formatBudget, tripErrors } from "../../utils/vehicleSearch";

export default function TripSearchForm({ params, catalog, branches, loading, error, onRetry, onSubmit, onUpdate, onClear }) {
  const id = useId();
  const now = useNow();
  const [draft, setDraft] = useState(() => ({
    ...Object.fromEntries(["start", "end", "pickupBranchId", "returnBranchId", "budget"].map(key => [key, params.get(key) || ""])),
    differentReturn: Boolean(params.get("returnBranchId") && params.get("returnBranchId") !== params.get("pickupBranchId")),
    start: (params.get('start') || '').slice(0,10), end: (params.get('end') || '').slice(0,10),
  }));
  const [advanced, setAdvanced] = useState(() => params.has("budget"));
  const errors = tripErrors(draft, now);
  const change = key => event => setDraft(old => ({ ...old, [key]: event.target.value }));
  const field = (key, label, control, className = "") => <div className={`search-field ${className}`}>
    <label htmlFor={`${id}-${key}`}>{label}</label>
    {control(`${id}-${key}`, errors[key] ? `${id}-${key}-error` : undefined)}
    {errors[key] ? <small className="search-error" id={`${id}-${key}-error`}>{errors[key]}</small> : null}
  </div>;
  const branchSelect = key => function BranchSelect(inputId, description) { return <select id={inputId} aria-describedby={description} aria-invalid={Boolean(errors[key])} disabled={loading || Boolean(error)} value={draft[key]} onChange={change(key)}>
    <option value="">Chọn chi nhánh</option>{branches.map(branch => <option key={branch.id} value={branch.id}>{branch.name}</option>)}
  </select>; };
  const compact = (catalog?.categories || []).find(category => /^(hatchback|xe nhỏ gọn)$/i.test(category.name));
  const presets = [{id:"city",label:"Đi phố · xe gọn",category:compact?.id || "",seats:""}, {id:"family",label:"Gia đình · từ 5 chỗ",category:"",seats:"5"}, {id:"group",label:"Đi nhóm · từ 7 chỗ",category:"",seats:"7"}];
  const value = key => params.get(key) || "";
  const manual = (key, next) => onUpdate({ [key]: next, ...(["category","seats"].includes(key) ? {preset:""} : {}) });
  return <form className="panel trip-search" aria-label="Tìm xe theo chuyến" noValidate onSubmit={event => {
    event.preventDefault();
    if (Object.keys(tripErrors(draft)).length) return;
    onSubmit({start:draft.start,end:draft.end,pickupBranchId:draft.pickupBranchId,returnBranchId:draft.differentReturn ? draft.returnBranchId : draft.pickupBranchId,budget:draft.budget});
  }}>
    <h2 className="text-lg font-bold">Tìm xe cho chuyến đi</h2>
    <p className="search-subtitle">Ngày Việt Nam · Giá theo số ngày giữa ngày nhận và ngày trả · Giữ xe đến hết ngày trả</p>
    <div className="search-trip-row">
      <div className="search-pickup">
        {field("pickupBranchId","Điểm nhận",branchSelect("pickupBranchId"))}
        <div className="search-return"><label className="search-return-toggle" htmlFor={`${id}-different`}><input id={`${id}-different`} type="checkbox" checked={draft.differentReturn} onChange={event => setDraft(old => ({...old,differentReturn:event.target.checked}))} />Trả xe ở điểm khác</label>
          {draft.differentReturn ? field("returnBranchId","Điểm trả",branchSelect("returnBranchId")) : null}</div>
      </div>
      {field("start","Ngày nhận",(inputId,description) => <input id={inputId} type="date" value={draft.start} onChange={change("start")} aria-describedby={description} aria-invalid={Boolean(errors.start)} />)}
      {field("end","Ngày trả",(inputId,description) => <input id={inputId} type="date" value={draft.end} onChange={change("end")} aria-describedby={description} aria-invalid={Boolean(errors.end)} />)}
      <div className="search-submit"><button className="btn" disabled={Object.keys(errors).length > 0 || Boolean(draft.start && (loading || error))}>Tìm xe</button>
        <small>Chưa giữ chỗ khi tìm kiếm. Giá và lịch được kiểm tra lại khi báo giá.</small></div>
    </div>
    <div className="search-vehicle-row">
      {field("q","Tìm xe",inputId => <input id={inputId} placeholder="Tên xe, mẫu xe..." value={value("q")} onChange={event => manual("q",event.target.value)} />, "search-keyword")}
      {field("category","Loại xe",inputId => <select id={inputId} value={value("category")} onChange={event => manual("category",event.target.value)}><option value="">Tất cả</option>{(catalog?.categories || []).map(category => <option key={category.id} value={category.id}>{category.name}</option>)}</select>)}
      {field("seats","Số chỗ",inputId => <select id={inputId} value={value("seats")} onChange={event => manual("seats",event.target.value)}><option value="">Tất cả</option>{[2,4,5,7,9].map(count => <option key={count} value={count}>{count} chỗ trở lên</option>)}</select>)}
      {field("transmission","Hộp số",inputId => <select id={inputId} value={value("transmission")} onChange={event => manual("transmission",event.target.value)}><option value="">Tất cả</option><option value="Tự động">Tự động</option><option value="Số sàn">Số sàn</option></select>)}
    </div>
    <div className="search-actions"><div className="search-presets" role="group" aria-label="Chọn nhanh">{presets.map(preset => {
      const selected = value("preset") === preset.id && value("seats") === preset.seats && value("category") === preset.category;
      return <button type="button" key={preset.id} className="search-preset" aria-pressed={selected} disabled={preset.id === "city" && !compact} title={preset.id === "city" && !compact ? "Danh mục chưa có loại xe nhỏ gọn" : undefined} onClick={() => onUpdate(selected ? {preset:"",category:"",seats:""} : {preset:preset.id,category:preset.category,seats:preset.seats})}>{preset.label}</button>;
    })}</div><button type="button" className="search-clear" onClick={() => {
      setDraft({start:"",end:"",pickupBranchId:"",returnBranchId:"",budget:"",differentReturn:false});
      setAdvanced(false);
      onClear();
    }}>Xóa bộ lọc</button></div>
    <button type="button" className="search-advanced-toggle" aria-expanded={advanced} aria-controls={`${id}-advanced`} onClick={() => setAdvanced(old => !old)}>Bộ lọc nâng cao {advanced ? "▴" : "▾"}</button>
    <div id={`${id}-advanced`} hidden={!advanced} className="search-advanced">
      {field("budget","Ngân sách cả chuyến (VND)",(inputId,description) => <input id={inputId} inputMode="numeric" type="text" placeholder="Ví dụ: 3.000.000" value={formatBudget(draft.budget)} aria-describedby={description} aria-invalid={Boolean(errors.budget)} onChange={event => {
        const next = event.target.value.replaceAll(".", "");
        if (/^\d*$/.test(next)) setDraft(old => ({...old,budget:next.replace(/^0+(?=\d)/,"")}));
      }} />)}
    </div>
    {error ? <div><Notice error>{error}</Notice><button type="button" className="btn-secondary" onClick={onRetry}>Tải lại chi nhánh</button></div> : null}
  </form>;
}
