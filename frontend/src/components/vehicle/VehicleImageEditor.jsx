import { useEffect, useRef, useState } from "react";
import { useApiData } from "../../hooks/useApiData";
import { saveVehicleImage, reorderVehicleImages } from "../../api/vehicleImages";
import { Field, Notice } from "../common/PageKit";
import { getErrorMessage } from "../../utils/roles";

export default function VehicleImageEditor({ vehicleId }) {
  return <Editor key={vehicleId} vehicleId={vehicleId} />;
}
function Editor({ vehicleId }) {
  const request = useApiData(`/admin/vehicles/${encodeURIComponent(vehicleId)}/images`);
  const [form, setForm] = useState({ id: "", url: "", attribution: "" });
  const [saved, setSaved] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [stale, setStale] = useState(false);
  const pending = useRef(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const data = saved || request.data;
  const images = data?.images || [];
  const reload = () => { setSaved(null); setStale(false); setMessage(""); setError(""); setForm({ id: "", url: "", attribution: "" }); request.reload(); };
  const mutate = async (operation) => {
    if (pending.current || stale || !data?.revision || request.loading) return;
    pending.current = true;
    setBusy(true); setMessage(""); setError("");
    try {
      const result = await operation(data.revision);
      if (!alive.current) return;
      setSaved(result); setForm({ id: "", url: "", attribution: "" }); setMessage("Đã lưu ảnh xe.");
    } catch (failure) {
      if (!alive.current) return;
      if (failure.response?.status === 409) { setStale(true); setError("409: Ảnh đã được thay đổi ở phiên khác. Tải lại bản mới trước khi lưu."); }
      else setError(getErrorMessage(failure, "Không thể lưu ảnh xe."));
    } finally { pending.current = false; if (alive.current) setBusy(false); }
  };
  const move = (index, direction) => {
    const ids = images.map((image) => image.id);
    [ids[index], ids[index + direction]] = [ids[index + direction], ids[index]];
    void mutate((revision) => reorderVehicleImages(vehicleId, ids, revision));
  };
  return <section className="panel space-y-4" aria-label="Quản lý ảnh xe">
    <h2 className="text-lg font-semibold">Ảnh xe &amp; nguồn sử dụng</h2>
    <p className="text-sm text-slate-600">URL ảnh đã có quyền sử dụng. Không tải tệp lên hệ thống. Ghi tác giả, nguồn và giấy phép trong thông tin ảnh.</p>
    {request.loading ? <p role="status">Đang tải ảnh...</p> : request.error ? <><Notice error>{request.error}</Notice><button type="button" className="btn-secondary" onClick={reload}>Thử tải lại ảnh</button></> : <>
      {error ? <Notice error>{error}</Notice> : null}
      {message ? <Notice>{message}</Notice> : null}
      {stale ? <button type="button" className="btn-secondary" disabled={busy} onClick={reload}>Tải lại bản mới</button> : null}
      {!images.length ? <p>Xe chưa có ảnh.</p> : <ol className="space-y-3">{images.map((image, index) => <li key={image.id} className="rounded-xl border border-slate-200 p-3">
        <p className="break-all text-sm">{index + 1}. {image.url}</p><p className="text-xs text-slate-600">{image.attribution || "Chưa có nguồn ảnh"}</p>
        <div className="mt-2 flex flex-wrap gap-2">
          <button type="button" className="btn-secondary" disabled={busy || stale} onClick={() => { setForm({ id: image.id, url: image.url, attribution: image.attribution || "" }); setMessage(""); }}>Sửa ảnh {index + 1}</button>
          <button type="button" className="btn-secondary" aria-label={`Đưa ảnh ${index + 1} lên`} disabled={busy || stale || index === 0} onClick={() => move(index, -1)}>↑</button>
          <button type="button" className="btn-secondary" aria-label={`Đưa ảnh ${index + 1} xuống`} disabled={busy || stale || index === images.length - 1} onClick={() => move(index, 1)}>↓</button>
        </div>
      </li>)}</ol>}
      <form className="space-y-3" onSubmit={(event) => { event.preventDefault(); void mutate((revision) => saveVehicleImage(vehicleId, form.id, { url: form.url, attribution: form.attribution, expectedRevision: revision })); }}>
        <Field label="URL ảnh xe"><input required maxLength={500} value={form.url} disabled={busy || stale} onChange={(event) => setForm({ ...form, url: event.target.value })} /></Field>
        <Field label="Tác giả / nguồn / giấy phép"><textarea maxLength={1000} value={form.attribution} disabled={busy || stale} onChange={(event) => setForm({ ...form, attribution: event.target.value })} /></Field>
        <button className="btn" disabled={busy || stale || !data?.revision}>{busy ? "Đang lưu ảnh..." : form.id ? "Lưu thông tin ảnh" : "Thêm ảnh xe"}</button>
        {form.id ? <button type="button" className="btn-secondary ml-2" disabled={busy} onClick={() => setForm({ id: "", url: "", attribution: "" })}>Hủy sửa ảnh</button> : null}
      </form>
    </>}
  </section>;
}
