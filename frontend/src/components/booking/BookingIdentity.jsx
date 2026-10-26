import { useState } from "react";

export default function BookingIdentity({ id }) {
  const [message, setMessage] = useState("");
  async function copy() {
    try {
      await navigator.clipboard.writeText(id);
      setMessage("Đã sao chép mã đơn đầy đủ.");
    } catch {
      setMessage("Không thể sao chép tự động. Hãy chọn mã đầy đủ phía trên để sao chép.");
    }
  }
  return <div className="booking-identity">
    <span>Mã đơn đầy đủ</span><code>{id}</code>
    <button type="button" className="btn-secondary" onClick={copy}>Sao chép mã đơn</button>
    <p role="status">{message}</p>
  </div>;
}
