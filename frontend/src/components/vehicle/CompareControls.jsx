import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { compareSelection, toggleCompare } from "../../utils/vehicleCompare";
import { searchParams, searchSuffix } from "../../utils/vehicleSearch";

export function CompareToggle({ vehicleId, name }) {
  const [params,setParams] = useSearchParams();
  const {ids} = compareSelection(params.get("compare") || "");
  const [issue,setIssue] = useState("");
  const selected = ids.includes(vehicleId);
  return <div className="my-2"><button type="button" className="btn-secondary text-xs" aria-pressed={selected} aria-label={`${selected ? "Bỏ so sánh" : "So sánh"} ${name}`} onClick={() => {
    const next=toggleCompare(ids,vehicleId);setIssue(next.issue);
    if (!next.issue) setParams(searchParams(params,{compare:next.ids.join(",")}));
  }}>{selected ? "✓ Đã chọn so sánh" : "+ So sánh"}</button>{issue && ids.length === 3 ? <p role="status" className="mt-2 text-xs text-red-700">{issue}</p> : null}</div>;
}

export function CompareLink({ linkRef }) {
  const [params] = useSearchParams();
  const {ids} = compareSelection(params.get("compare") || "");
  return <Link ref={linkRef} className="btn-secondary" to={`/vehicles/compare${searchSuffix(params)}`}>So sánh xe ({ids.length})</Link>;
}

export default function CompareTray({ compact = false, vehicles = [], onRemove }) {
  const [params,setParams] = useSearchParams();
  const {ids,issue} = compareSelection(params.get("compare") || "");
  if (compact && !ids.length && !issue) return null;
  const remove = id => {
    setParams(searchParams(params,{compare:ids.filter(value => value !== id).join(",")}));
    onRemove?.();
  };
  return <aside className="panel mb-5" aria-label="Lựa chọn so sánh">
    <div className="flex flex-wrap items-center justify-between gap-3"><p>Đã chọn {ids.length}/3 xe {ids.length === 3 ? "· Đã đạt giới hạn" : "· Chọn 2–3 xe để đối chiếu"}</p>
      <div className="flex flex-wrap gap-2">{!compact ? <CompareLink /> : null}{ids.length ? <button className="btn-secondary" onClick={() => {setParams(searchParams(params,{compare:""}));onRemove?.();}}>Bỏ tất cả xe</button> : null}</div></div>
    {compact && ids.length ? <div className="compare-selection-chips">{ids.map(id => {const name=vehicles.find(vehicle => vehicle.id === id)?.name || id;return <button key={id} className="btn-secondary" aria-label={`Bỏ ${name} khỏi lựa chọn so sánh`} onClick={() => remove(id)}>{name} ×</button>;})}</div> : null}
    {issue ? <p role="status" className="mt-2 text-sm text-red-700">{issue}</p> : null}
  </aside>;
}
