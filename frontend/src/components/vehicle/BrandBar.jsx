import { useEffect, useId, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { vehicleBrandOptions } from "../../utils/vehicleBrands";
import { searchParams, searchSuffix } from "../../utils/vehicleSearch";

export default function BrandBar({ vehicles, catalog, loading = false, error = false }) {
  const [params] = useSearchParams();
  const selected = params.get("brand") || "";
  const brands = useMemo(() => error ? [] : vehicleBrandOptions(vehicles, catalog), [vehicles, catalog, error]);
  const primary = brands.slice(0,7), rest = brands.slice(7);
  const [open,setOpen] = useState(false);
  const root = useRef(null), trigger = useRef(null), id = useId();
  useEffect(() => {
    if (!open) return undefined;
    const close = event => { if (!root.current?.contains(event.target)) setOpen(false); };
    document.addEventListener("pointerdown",close);
    return () => document.removeEventListener("pointerdown",close);
  },[open]);
  const item = brand => <Link key={brand.id} to={`/vehicles${searchSuffix(searchParams(params, {brand: brand.id}))}`} aria-current={selected===brand.id?"true":undefined} onClick={()=>setOpen(false)}>{brand.name}</Link>;
  return <nav className="brand-bar" aria-label="Lọc theo hãng xe" aria-busy={loading} ref={root} onKeyDown={event=>{if(event.key==="Escape"){setOpen(false);trigger.current?.focus();}}}>
    <div className="brand-bar-inner">
      <div className="brand-bar-scroll">
        <Link to={`/vehicles${searchSuffix(searchParams(params, {brand: ""}))}`} aria-current={!selected?"true":undefined} onClick={()=>setOpen(false)}>Tất cả xe</Link>
        {primary.map(item)}
        {rest.length ? <button type="button" ref={trigger} aria-expanded={open} aria-controls={id} aria-current={rest.some(b=>b.id===selected)?"true":undefined} onClick={()=>setOpen(value=>!value)}>Khác ▾</button> : null}
      </div>
      {open && rest.length ? <div id={id} className="brand-bar-dropdown">{rest.map(item)}</div> : null}
    </div>
  </nav>;
}
