import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Field } from '../common/PageKit';
import { useApiData } from '../../hooks/useApiData';
import { previewLocationRoute } from '../../api/locationRoutes';
import { getErrorMessage } from '../../utils/roles';
import { visitDays, visitStates } from './VisitMetadataFields';

function VisitFacts({location}) {
  const data=location.metadata;
  if(!data) return <p>Giờ mở cửa và thời lượng ghé: Chưa xác minh.</p>;
  const rows=[...visitDays.map(([day,label])=>[label,data.weekly[day]]),...Object.entries(data.exceptions)];
  return <div className="space-y-2"><p>Thời lượng ghé: {data.durationMinutes==null?'Chưa xác minh':`${data.durationMinutes} phút (quản trị ước tính)`}</p>
    <details><summary>Lịch mở cửa và nguồn</summary>{rows.map(([day,hours])=><p key={day}>{day}: {visitStates[hours?.state]||'Chưa xác minh'}{hours?.state==='INTERVALS'&&hours.intervals.map((interval,i)=><span key={i}> · {interval.start}–{interval.end}{interval.nextDay?' ngày kế tiếp':''}</span>)}</p>)}
      {[['Lịch mở cửa',data.hoursEvidence],['Thời lượng',data.durationEvidence]].map(([label,evidence])=>evidence?<p key={label}>{label}: <a href={evidence.url} target="_blank" rel="noopener noreferrer">Nguồn kiểm chứng</a> · {new Date(evidence.verifiedAt).toLocaleDateString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh'})}</p>:null)}
      <p>Ngày ngoại lệ ưu tiên lịch tuần. Dữ liệu có thể thay đổi; hãy kiểm tra nguồn trước chuyến đi.</p>
    </details>
  </div>;
}

export default function RouteExplorer() {
  const [expanded,setExpanded]=useState(false),[ids,setIds]=useState(['','']),[result,setResult]=useState(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const catalog=useApiData('/ai/locations',expanded),request=useRef(null),generation=useRef(0),busyRef=useRef(false);
  const locations=Array.isArray(catalog.data)?catalog.data:[];
  useEffect(()=>()=>{generation.current++;request.current?.abort();},[]);
  function invalidate(){generation.current++;request.current?.abort();busyRef.current=false;setBusy(false);setError('');setResult(null);}
  function change(next){invalidate();setIds(next);}
  async function submit(event){
    event.preventDefault();if(busyRef.current)return;
    invalidate();const current=++generation.current,controller=new globalThis.AbortController();request.current=controller;busyRef.current=true;setBusy(true);
    try{const response=await previewLocationRoute(ids,controller.signal);if(current===generation.current)setResult(response);}
    catch(failure){if(current===generation.current&&!controller.signal.aborted)setError(getErrorMessage(failure,failure.message||'Chưa xem được tuyến.'));}
    finally{if(current===generation.current){busyRef.current=false;setBusy(false);}}
  }
  return <details className="assistant-routes" onToggle={event=>{setExpanded(event.currentTarget.open);if(!event.currentTarget.open)invalidate();}}>
    <summary>Khám phá địa điểm và tuyến đường</summary>
    {expanded&&<div className="space-y-3"><p>Phạm vi hiện có: Đà Nẵng–Hội An. Chọn các điểm theo thứ tự bạn muốn đi.</p>
      <p><Link className="btn-secondary" to="/ai/itinerary">Lập hành trình một ngày</Link> <Link to="/user/itineraries">Hành trình đã lưu</Link></p>
      {catalog.loading?<p role="status">Đang tải địa điểm…</p>:catalog.error?<div role="alert"><p>{catalog.error}</p><button type="button" onClick={catalog.reload}>Tải lại địa điểm</button></div>:!locations.length?<p>Chưa có địa điểm đang hoạt động.</p>:<form aria-label="Xem tuyến tham quan" onSubmit={submit} className="space-y-3">
        {ids.map((id,index)=><div key={index}><Field label={`Điểm ${index+1}`}><select required value={id} onChange={event=>change(ids.map((old,i)=>i===index?event.target.value:old))}><option value="">Chọn địa điểm</option>{locations.map(location=><option key={location.id} value={location.id}>{location.name}</option>)}</select></Field>
          {ids.length>2&&<button type="button" onClick={()=>change(ids.filter((_,i)=>i!==index))}>Bỏ điểm {index+1}</button>}
          {id&&locations.some(location=>location.id===id)&&<VisitFacts location={locations.find(location=>location.id===id)}/>}
        </div>)}
        {ids.length<8&&<button type="button" className="btn-secondary" onClick={()=>change([...ids,''])}>Thêm điểm ghé</button>}
        <button type="submit" className="assistant-submit" disabled={busy}>{busy?'Đang xem tuyến…':'Xem tuyến ô tô'}</button>
      </form>}
      {error&&<p role="alert">{error}</p>}
      <div aria-live="polite">{result?.status==='VERIFIED'?<div className="assistant-welcome"><strong>{(result.distanceMeters/1000).toLocaleString('vi-VN',{maximumFractionDigits:1})} km · khoảng {Math.ceil(result.durationSeconds/60)} phút lái xe</strong><p>Chưa tính thời gian ghé và giao thông thực tế. Kết quả không phải lịch trình đã lưu.</p><small>{result.attribution}</small></div>:result&&<p>{result.status==='NO_ROUTE'?'Chưa có tuyến ô tô kiểm chứng cho các điểm đã chọn.':'Dịch vụ tuyến đường đang không sẵn sàng. Hãy thử lại sau.'}</p>}</div>
    </div>}
  </details>;
}
