import useConfirmAction from "../../hooks/useConfirmAction";
import { useRef, useState } from "react";
import apiClient from "../../api/client";
import { useApiData } from "../../hooks/useApiData";
import { getErrorMessage } from "../../utils/roles";
import { Page, Notice, Field } from "../../components/common/PageKit";

const groups = [["vehicle-brands", "Hãng xe"], ["vehicle-categories", "Loại xe"], ["amenities", "Tiện nghi"]];

function CatalogGroup({ kind, title }) {
  const { confirm, confirmation } = useConfirmAction();
  const request = useApiData(`/admin/${kind}`);
  const [editing, setEditing] = useState(null);
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  async function remove(item) {
    if (pending.current || !(await confirm(`Xóa ${title.toLowerCase()} “${item.name}”? Chỉ xóa được danh mục chưa được xe sử dụng. Thao tác không thể hoàn tác.`))) return;
    if (pending.current) return;
    pending.current = true;
    setBusy(true); setError(""); setNotice("");
    try {
      await apiClient.delete(`/admin/${kind}/${encodeURIComponent(item.id)}`, { params: { expectedRevision: item.revision } });
      if (editing?.id === item.id) { setEditing(null); setName(""); }
      setNotice(`Đã xóa ${title.toLowerCase()} “${item.name}”.`); request.reload();
    } catch (failure) { setError(getErrorMessage(failure, "Không thể xóa danh mục.")); }
    finally { pending.current = false; setBusy(false); }
  }
  async function save(event) {
    event.preventDefault();
    if (pending.current) return;
    pending.current = true;
    setBusy(true); setError(""); setNotice("");
    try {
      if (editing) await apiClient.put(`/admin/${kind}/${encodeURIComponent(editing.id)}`, { name, expectedRevision: editing.revision });
      else await apiClient.post(`/admin/${kind}`, { name });
      setEditing(null); setName(""); setNotice("Đã lưu danh mục."); request.reload();
    } catch (failure) { setError(getErrorMessage(failure, "Không thể lưu danh mục.")); }
    finally { pending.current = false; setBusy(false); }
  }
  return <section className="panel space-y-4" aria-label={title}>
    <h2 className="text-xl font-semibold">{title}</h2>{confirmation}
    {error && <Notice error toast>{error}</Notice>}{notice && <Notice toast>{notice}</Notice>}
    {request.loading ? <p role="status">Đang tải danh mục...</p> : request.error ? <><Notice error>{request.error}</Notice><button className="btn-secondary" onClick={request.reload}>Thử tải lại</button></> : <>
      {request.data?.length ? <ul className="space-y-2">{request.data.map(item => <li key={item.id} className="flex items-center justify-between gap-3"><span className="min-w-0 break-words">{item.name}</span><div className="flex shrink-0 gap-2"><button aria-label={`Sửa ${item.name}`} className="btn-secondary whitespace-nowrap" disabled={busy} onClick={() => { setEditing(item); setName(item.name); setError(""); setNotice(""); }}>Sửa</button><button type="button" aria-label={`Xóa ${item.name}`} className="btn-secondary text-red-700" disabled={busy} onClick={() => remove(item)}>Xóa</button></div></li>)}</ul> : <p>Chưa có danh mục</p>}
      <form onSubmit={save} className="space-y-3">
        <Field label={editing ? "Tên mới" : "Tên danh mục"}><input name="name" value={name} onChange={event => setName(event.target.value)} required maxLength={100} disabled={busy} /></Field>
        <button className="btn" disabled={busy}>{busy ? "Đang lưu..." : editing ? "Lưu thay đổi" : "Tạo mới"}</button>
        {editing && <button type="button" className="btn-secondary" disabled={busy} onClick={() => { setEditing(null); setName(""); setError(""); }}>Hủy sửa</button>}
        {editing && <button type="button" className="btn-secondary" disabled={busy} onClick={() => { setEditing(null); request.reload(); }}>Tải lại bản mới</button>}
      </form>
    </>}
  </section>;
}
export default function ApiVehicleCatalogPage() {
  return <Page title="Danh mục xe" description="Quản lý hãng, loại xe và tiện nghi. Chỉ xóa danh mục chưa được xe sử dụng; thao tác này không xóa xe."><div className="grid gap-6 lg:grid-cols-2">{groups.map(([kind, title]) => <CatalogGroup key={kind} kind={kind} title={title} />)}</div></Page>;
}
