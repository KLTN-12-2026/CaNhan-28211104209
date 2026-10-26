import { useEffect, useRef, useState } from "react";
import { CalendarDays } from "lucide-react";
import { displayCalendarDate, parseCalendarDate } from "../../utils/calendarDate";

export default function DateField({ value = "", onChange, min, max, required, ...accessibility }) {
  const [text, setText] = useState(() => displayCalendarDate(value));
  const lastEmitted = useRef(value);
  const picker = useRef(null);
  const input = useRef(null);
  useEffect(() => {
    if (value !== lastEmitted.current) {
      lastEmitted.current = value;
      setText(displayCalendarDate(value));
      input.current?.setCustomValidity("");
    }
  }, [value]);
  function change(raw) {
    const iso = parseCalendarDate(raw);
    const inRange = iso && (!min || iso >= min) && (!max || iso <= max);
    input.current.setCustomValidity(raw && !inRange ? "Nhập ngày hợp lệ theo dd/mm/yyyy trong khoảng cho phép." : "");
    setText(raw); lastEmitted.current = inRange ? iso : "";
    onChange(lastEmitted.current);
  }
  return <span className="calendar-field">
    <input {...accessibility} ref={input} type="text" inputMode="numeric" placeholder="dd/mm/yyyy" maxLength={10} required={required} value={text} onChange={event => change(event.target.value)} />
    <button type="button" aria-label={`Mở lịch ${accessibility["aria-label"] || "chọn ngày"}`} onClick={() => { if (picker.current.showPicker) picker.current.showPicker(); else picker.current.click(); }}><CalendarDays size={18} /></button>
    <input ref={picker} type="date" aria-label="Lịch chọn ngày" tabIndex={-1} aria-hidden="true" min={min} max={max} value={value} onChange={event => change(displayCalendarDate(event.target.value))} className="calendar-native" />
  </span>;
}
