import { useEffect, useRef, useState } from "react";
import { getLicensePhoto, saveLicensePhotos, reviewLicensePhotos } from "../../api/rental";
import { Field } from "../common/PageKit";
import { getErrorMessage } from "../../utils/roles";
import { getStoredToken } from "../../utils/storage";

export const photoReviewLabels = {
  MISSING: "Chưa đủ ảnh", PENDING: "Chờ kiểm tra hồ sơ",
  APPROVED: "Đạt kiểm tra hồ sơ", NEEDS_CHANGES: "Yêu cầu bổ sung",
};

function PrivatePhoto({ bookingId, side, version }) {
  const [image, setImage] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true, objectUrl;
    const controller = new window.AbortController();
    getLicensePhoto(bookingId, side, controller.signal)
      .then(data => {
        if (active) { objectUrl = window.URL.createObjectURL(data); setImage(objectUrl); }
      }).catch(() => { if (active) setError("Không tải được ảnh. Vui lòng tải lại hồ sơ."); });
    return () => { active = false; controller.abort(); if (objectUrl) window.URL.revokeObjectURL(objectUrl); };
  }, [bookingId, side, version]);
  return <figure>
    <figcaption>{side === "front" ? "Mặt trước" : "Mặt sau"}</figcaption>
    {error ? <p role="alert">{error}</p> : image ? <img className="max-h-64 max-w-full object-contain" src={image} alt={`Ảnh GPLX ${side === "front" ? "mặt trước" : "mặt sau"}`} /> : <p>Đang tải ảnh…</p>}
  </figure>;
}

export default function LicensePhotos({ license, bookingId, onSaved, readOnly = false }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [show, setShow] = useState(false);
  const inFlight = useRef(false);
  const photos = license?.photos;
  async function submit(event) {
    event.preventDefault();
    if (inFlight.current) return;
    inFlight.current = true;
    const form = event.currentTarget, data = new FormData(form);
    const token = getStoredToken();
    setBusy(true); setError(""); setMessage("");
    try {
      if (bookingId) {
        await reviewLicensePhotos(bookingId, {
          version: license.version, approve: data.get("decision") === "APPROVED", reason: data.get("reason"),
        });
      } else {
        data.set("version", String(license.version));
        await saveLicensePhotos(data);
      }
      if (getStoredToken() !== token) return;
      setShow(false); form.reset();
      setMessage(bookingId ? "Đã lưu kết quả kiểm tra hồ sơ. Vẫn cần đối chiếu bản gốc khi giao xe." : "Đã lưu hai mặt ảnh GPLX. Hồ sơ đang chờ kiểm tra.");
      onSaved();
    } catch (e) {
      if (getStoredToken() === token) setError(getErrorMessage(e, "Không lưu được hồ sơ ảnh GPLX."));
    } finally { inFlight.current = false; setBusy(false); }
  }
  return <section className="space-y-3" aria-label="Ảnh GPLX">
    <h3 className="font-semibold">Ảnh GPLX để kiểm tra trước</h3>
    <p>{photoReviewLabels[photos?.status] || "Chưa đủ ảnh"}</p>
    {photos?.reason ? <p>Kết quả kiểm tra: {photos.reason}</p> : null}
    <p className="text-sm text-slate-600">Ảnh chỉ phục vụ kiểm tra hồ sơ; khách vẫn phải mang GPLX bản gốc khi nhận xe. Ảnh được giữ đến khi bạn thay ảnh.</p>
    {message ? <p role="status">{message}</p> : null}
    {error ? <p role="alert">{error}</p> : null}
    {photos?.front && photos?.back ? <>
      <button type="button" className="btn-secondary" onClick={() => setShow(!show)}>{show ? "Đóng ảnh GPLX" : "Xem hai mặt GPLX"}</button>
      {show ? <div className="grid grid-cols-2 gap-3">{["front", "back"].map(side => <PrivatePhoto key={`${side}-${license.version}`} bookingId={bookingId} side={side} version={license.version} />)}</div> : null}
    </> : null}
    {!readOnly && license ? <form onSubmit={submit} className="space-y-3">
      {bookingId ? <>
        <Field label="Kết quả kiểm tra"><select name="decision" required><option value="">Chọn kết quả</option><option value="APPROVED">Đạt kiểm tra hồ sơ</option><option value="NEEDS_CHANGES">Yêu cầu bổ sung</option></select></Field>
        <Field label="Nhận xét kiểm tra"><textarea name="reason" required maxLength={1000} /></Field>
      </> : <>
        <p className="text-sm">JPEG/PNG, tối đa 5 MB mỗi mặt. Chọn đủ hai ảnh khi thay; ảnh cũ sẽ được xóa sau khi lưu thành công.</p>
        <Field label="Ảnh mặt trước"><input name="front" type="file" accept="image/jpeg,image/png" required /></Field>
        <Field label="Ảnh mặt sau"><input name="back" type="file" accept="image/jpeg,image/png" required /></Field>
      </>}
      <button className="btn-secondary" disabled={busy || (Boolean(bookingId) && !(photos?.front && photos?.back))}>{busy ? "Đang lưu…" : bookingId ? "Lưu kiểm tra hồ sơ" : "Lưu hai mặt ảnh GPLX"}</button>
    </form> : !license ? <p>Lưu thông tin GPLX bên trên trước khi tải ảnh.</p> : null}
  </section>;
}
