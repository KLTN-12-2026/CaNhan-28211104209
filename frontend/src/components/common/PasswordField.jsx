import { useId, useState } from "react";
import { Eye, EyeOff } from "lucide-react";

export default function PasswordField({ label, ...props }) {
  const id = useId();
  const [visible, setVisible] = useState(false);
  return <div className="field">
    <label htmlFor={id}>{label}</label>
    <div className="password-input">
      <input {...props} id={id} type={visible ? "text" : "password"} />
      <button type="button" aria-label={`${visible ? "Ẩn" : "Hiện"} ${label.toLowerCase()}`} aria-pressed={visible} onClick={() => setVisible(v => !v)} disabled={props.disabled}>
        {visible ? <EyeOff size={18} aria-hidden="true" /> : <Eye size={18} aria-hidden="true" />}
      </button>
    </div>
  </div>;
}
