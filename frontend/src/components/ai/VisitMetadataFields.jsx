import { Field } from '../common/PageKit';

export const visitDays = [['MONDAY','Thứ hai'],['TUESDAY','Thứ ba'],['WEDNESDAY','Thứ tư'],['THURSDAY','Thứ năm'],['FRIDAY','Thứ sáu'],['SATURDAY','Thứ bảy'],['SUNDAY','Chủ nhật']];
export const visitStates = { UNKNOWN:'Chưa xác minh', CLOSED:'Đóng cửa', OPEN_24H:'Mở cả ngày', INTERVALS:'Theo khung giờ' };
const unknown = () => ({ state:'UNKNOWN', intervals:[] });
export function emptyVisitMetadata() { return {timezone:'Asia/Ho_Chi_Minh',weekly:Object.fromEntries(visitDays.map(([day])=>[day,unknown()])),exceptions:{},durationMinutes:null,hoursEvidence:null,durationEvidence:null}; }

function HoursFields({label,value,onChange}) {
  const hours=value||unknown();
  const changeInterval=(index,patch)=>onChange({...hours,intervals:hours.intervals.map((x,i)=>i===index?{...x,...patch}:x)});
  return <fieldset className="space-y-2 border rounded-xl p-3"><legend>{label}</legend>
    <Field label={`Giờ hoạt động ${label}`}><select value={hours.state} onChange={e=>onChange({state:e.target.value,intervals:e.target.value==='INTERVALS'?[{start:'08:00',end:'17:00',nextDay:false}]:[]})}>{Object.entries(visitStates).map(([key,text])=><option key={key} value={key}>{text}</option>)}</select></Field>
    {hours.intervals.map((interval,index)=><div key={index} className="space-y-2">
      <Field label={`Mở cửa ${label} ${index+1}`}><input type="time" required value={interval.start} onChange={e=>changeInterval(index,{start:e.target.value})}/></Field>
      <Field label={`Đóng cửa ${label} ${index+1}`}><input type="time" required value={interval.end} onChange={e=>changeInterval(index,{end:e.target.value})}/></Field>
      <label><input type="checkbox" checked={interval.nextDay} onChange={e=>changeInterval(index,{nextDay:e.target.checked})}/> Đóng vào ngày kế tiếp</label>
      {hours.intervals.length>1&&<button type="button" className="btn-secondary" onClick={()=>onChange({...hours,intervals:hours.intervals.filter((_,i)=>i!==index)})}>Bỏ khung giờ {index+1}</button>}
    </div>)}
    {hours.state==='INTERVALS'&&hours.intervals.length<4&&<button type="button" className="btn-secondary" onClick={()=>onChange({...hours,intervals:[...hours.intervals,{start:'18:00',end:'21:00',nextDay:false}]})}>Thêm khung giờ</button>}
  </fieldset>;
}
function EvidenceFields({label,value,onChange,required}) {
  const date=value?.verifiedAt?new globalThis.Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Ho_Chi_Minh'}).format(new Date(value.verifiedAt)):'';
  return <fieldset className="space-y-2"><legend>{label}</legend>
    <Field label={`Đường dẫn nguồn ${label}`}><input type="url" maxLength={500} required={required} value={value?.url||''} onChange={e=>onChange(e.target.value?{...value,url:e.target.value}:null)} placeholder="https://..."/></Field>
    <Field label={`Ngày kiểm chứng ${label}`}><input type="date" required={required||Boolean(value?.url)} value={date} onChange={e=>onChange({...value,verifiedAt:e.target.value?new Date(`${e.target.value}T00:00:00+07:00`).toISOString():null})}/></Field>
  </fieldset>;
}
export default function VisitMetadataFields({value,onChange,disabled}) {
  const data=value||emptyVisitMetadata();
  const known=[...Object.values(data.weekly),...Object.values(data.exceptions)].some(day=>day.state!=='UNKNOWN');
  return <fieldset disabled={disabled} className="space-y-4"><legend className="font-semibold">Thông tin tham quan</legend>
    <p className="text-sm text-slate-500">Giờ Việt Nam. Chưa xác minh không có nghĩa là mở cả ngày. Khoảng qua đêm thuộc ngày bắt đầu.</p>
    <details><summary>Lịch mở cửa theo tuần</summary><div className="space-y-3 mt-3">{visitDays.map(([day,label])=><HoursFields key={day} label={label} value={data.weekly[day]} onChange={hours=>onChange({...data,weekly:{...data.weekly,[day]:hours}})}/>)}</div></details>
    <details><summary>Ngày ngoại lệ ({Object.keys(data.exceptions).length})</summary><div className="space-y-3 mt-3">{Object.entries(data.exceptions).map(([date,hours])=><div key={date}><HoursFields label={date} value={hours} onChange={next=>onChange({...data,exceptions:{...data.exceptions,[date]:next}})}/><button className="btn-secondary" type="button" onClick={()=>onChange({...data,exceptions:Object.fromEntries(Object.entries(data.exceptions).filter(([key])=>key!==date))})}>Bỏ ngày {date}</button></div>)}
      {Object.keys(data.exceptions).length<60&&<Field label="Thêm ngày ngoại lệ"><input type="date" value="" onChange={e=>{if(e.target.value)onChange({...data,exceptions:{...data.exceptions,[e.target.value]:data.exceptions[e.target.value]||unknown()}});}}/></Field>}
    </div></details>
    <EvidenceFields label="lịch mở cửa" value={data.hoursEvidence} required={known} onChange={next=>onChange({...data,hoursEvidence:next})}/>
    <Field label="Thời lượng ghé ước tính (phút)"><input type="number" min="1" max="1440" step="1" value={data.durationMinutes??''} onChange={e=>onChange({...data,durationMinutes:e.target.value===''?null:Number(e.target.value),durationEvidence:e.target.value===''?null:data.durationEvidence})}/></Field>
    <p className="text-sm text-slate-500">Thời lượng do quản trị ước tính, không phải thời gian tham quan bắt buộc.</p>
    <EvidenceFields label="thời lượng" value={data.durationEvidence} required={data.durationMinutes!=null} onChange={next=>onChange({...data,durationEvidence:next})}/>
  </fieldset>;
}
