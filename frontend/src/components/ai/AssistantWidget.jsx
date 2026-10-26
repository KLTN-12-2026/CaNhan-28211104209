import { useEffect, useId, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Minus, RotateCcw, Sparkles, X } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { DEMO_MODE } from '../../demo/config';
import { useApiData } from '../../hooks/useApiData';
import { Field, money } from '../common/PageKit';
import { recommendVehicles, parseRentalCriteria } from '../../api/recommendation';
import { assistantCriteria, assistantDraft, assistantReasons, assistantSearch, assistantVisible, extractedAssistantDraft } from '../../utils/assistant';
import { getErrorMessage } from '../../utils/roles';
import './AssistantWidget.css';
import RouteExplorer from './RouteExplorer';

export default function AssistantWidget() {
  const { user, token, isAuthenticated, loading } = useAuth();
  const [session, setSession] = useState({ token, revision: 0 });
  if (session.token !== token) setSession({ token, revision: session.revision + 1 });
  const { pathname } = useLocation();
  if (loading || !isAuthenticated || user?.role !== 'USER') return null;
  return <AssistantSession key={`${user.id}:${session.revision}`} visible={assistantVisible(user.role, pathname)} />;
}
function AssistantSession({ visible }) {
  const { search } = useLocation();
  const [open, setOpen] = useState(false), [draft, setDraft] = useState(assistantDraft), [result, setResult] = useState(null), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const [naturalText, setNaturalText] = useState(''), [parseNote, setParseNote] = useState(''), [routeRevision,setRouteRevision]=useState(0);
  const previousOpen = useRef(false);
  const initialized = useRef(false), pending = useRef(null), generation = useRef(0), busyRef = useRef(false), launcher = useRef(null), panel = useRef(null), reply = useRef(null);
  const title = useId();
  const branches = useApiData('/branches', open && visible && !DEMO_MODE);
  const catalog = useApiData('/vehicles/catalog', open && visible && !DEMO_MODE);
  const activeBranches = (branches.data || []).filter(b => b.status === 'ACTIVE');
  const amenities = catalog.data?.amenities || [];
  useEffect(() => () => { generation.current++; pending.current?.abort(); }, []);
  useEffect(() => {
    if (open && visible) panel.current?.focus();
    else if (previousOpen.current && visible) launcher.current?.focus();
    previousOpen.current = open;
  }, [open, visible]);
  useEffect(() => { if (result && open && visible) reply.current?.scrollIntoView?.({ block: 'nearest' }); }, [result, open, visible]);
  function invalidate() { generation.current++; pending.current?.abort(); busyRef.current = false; setBusy(false); setError(''); setResult(null); setParseNote(''); }
  function change(name, value) { invalidate(); setDraft(old => ({ ...old, [name]: value })); }
  function show() { if (!initialized.current) { setDraft(assistantDraft(new URLSearchParams(search))); initialized.current = true; } setOpen(true); }
  function close() { setOpen(false); }
  function reset() { invalidate(); setDraft(assistantDraft()); setNaturalText(''); setRouteRevision(old=>old+1); }
  async function submit(event) {
    event.preventDefault();
    if (busyRef.current || DEMO_MODE || branches.loading || catalog.loading || branches.error || catalog.error) return;
    setError(''); setResult(null);
    let criteria;
    try { criteria = assistantCriteria(draft, activeBranches); } catch (failure) { setError(failure.message); return; }
    const run = ++generation.current, controller = new globalThis.AbortController(); pending.current?.abort(); pending.current = controller; busyRef.current = true; setBusy(true);
    try {
      const data = await recommendVehicles(criteria, controller.signal);
      if (generation.current === run) setResult({ ...data, criteria, amenities });
    } catch (failure) {
      if (generation.current === run && !controller.signal.aborted) setError(getErrorMessage(failure, failure.message || 'Chưa lấy được gợi ý xe. Hãy thử lại.'));
    } finally { if (generation.current === run) { busyRef.current = false; setBusy(false); } }
  }
  async function readNatural() {
    if (busyRef.current || DEMO_MODE || !naturalText.trim() || branches.loading || branches.error) return;
    invalidate();
    const run = ++generation.current, controller = new globalThis.AbortController(); pending.current = controller; busyRef.current = true; setBusy(true); setParseNote('Đang đọc nhu cầu, có thể mất khoảng một phút…');
    try {
      const data = await parseRentalCriteria(naturalText, controller.signal);
      if (generation.current !== run) return;
      if (data.status === 'OUT_OF_SCOPE') { setParseNote('Trợ lý hiện hỗ trợ đọc nhu cầu tìm xe. Bạn có thể ghi ngày, điểm nhận/trả, số người và ngân sách.'); return; }
      setDraft(extractedAssistantDraft(data, activeBranches));
      setParseNote(data.status === 'READY_FOR_REVIEW' ? 'Đã điền nhu cầu. Hãy kiểm tra các trường bên dưới rồi bấm Gợi ý xe phù hợp.' : 'Đã điền phần đọc được. Hãy bổ sung các ô còn trống, kiểm tra ngày gồm cả năm rồi tìm xe.');
    } catch (failure) {
      if (generation.current === run && !controller.signal.aborted) { setParseNote(''); setError(getErrorMessage(failure, failure.message || 'Chưa đọc được nhu cầu; hãy dùng biểu mẫu.')); }
    } finally { if (generation.current === run) { busyRef.current = false; setBusy(false); } }
  }
  if (!visible) return null;
  return <div className="kdrive-assistant">
    {open && <section ref={panel} tabIndex={-1} role="dialog" aria-modal="false" aria-labelledby={title} className="assistant-panel" onKeyDown={event => { if (event.key === 'Escape') { event.stopPropagation(); close(); } }}>
      <header className="assistant-heading"><div><span className="assistant-mark"><Sparkles size={18} /></span><div><h2 id={title}>Trợ lý KDrive</h2><p>Gợi ý xe theo nhu cầu</p></div></div><div>
        <button type="button" aria-label="Bắt đầu lại" title="Bắt đầu lại" onClick={reset}><RotateCcw size={17} /></button>
        <button type="button" aria-label="Thu nhỏ trợ lý" onClick={close}><Minus size={18} /></button>
        <button type="button" aria-label="Đóng trợ lý" onClick={close}><X size={18} /></button>
      </div></header>
      <div className="assistant-body">
        <p className="assistant-welcome">Bạn đang tìm xe cho chuyến đi nào? Mô tả nhu cầu hoặc điền biểu mẫu bên dưới.</p>
        {DEMO_MODE ? <p role="status">Trợ lý dùng dữ liệu thật ở chế độ API. Bản demo chưa kết nối gợi ý xe.</p> : <>
          {(branches.error || catalog.error) && <div role="alert" className="assistant-error"><p>Chưa tải được chi nhánh hoặc tiện nghi.</p><button type="button" onClick={() => { branches.reload(); catalog.reload(); }}>Tải lại danh mục</button></div>}
          <div className="assistant-natural">
            <Field label="Mô tả nhu cầu"><textarea maxLength={1000} rows={3} value={naturalText} onChange={e => { invalidate(); setNaturalText(e.target.value); }} placeholder="Ví dụ: 4 người, nhận và trả tại Đà Nẵng từ 20/10/2026 đến 24/10/2026, tổng ngân sách 3 triệu, xe tự động." /></Field>
            <p className="assistant-note">Ghi cả năm. Không nhập số điện thoại, email hoặc số giấy tờ.</p>
            <button className="assistant-submit" type="button" onClick={readNatural} disabled={busy || !naturalText.trim() || branches.loading || Boolean(branches.error)}>Điền nhu cầu bằng AI</button>
            {parseNote && <p role="status" className="assistant-note">{parseNote}</p>}
          </div>
          <form onSubmit={submit} aria-label="Nhu cầu thuê xe">
            <div className="assistant-fields">
              <Field label="Ngày nhận"><input type="date" value={draft.start} onChange={e => change('start', e.target.value)} required /></Field>
              <Field label="Ngày trả"><input type="date" value={draft.end} onChange={e => change('end', e.target.value)} required /></Field>
              <Field label="Điểm nhận"><select value={draft.pickupBranchId} onChange={e => change('pickupBranchId', e.target.value)} required><option value="">Chọn chi nhánh</option>{activeBranches.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}</select></Field>
              <Field label="Điểm trả"><select value={draft.returnBranchId} onChange={e => change('returnBranchId', e.target.value)} required><option value="">Chọn chi nhánh</option>{activeBranches.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}</select></Field>
              <Field label="Số người (gồm tài xế)"><input type="number" min="2" max="9" step="1" value={draft.passengers} onChange={e => change('passengers', e.target.value)} required /></Field>
              <Field label="Tổng ngân sách (VND)"><input type="number" min="0" max="9007199254740991" step="1" value={draft.budget} onChange={e => change('budget', e.target.value)} placeholder="Ví dụ: 3000000" required /></Field>
            </div>
            <Field label="Ưu tiên hộp số"><select value={draft.transmission} onChange={e => change('transmission', e.target.value)}><option value="">Không ưu tiên</option><option value="AUTO">Tự động</option><option value="MANUAL">Số sàn</option></select></Field>
            {amenities.length > 0 && <details className="assistant-amenities"><summary>Ưu tiên tiện nghi</summary><div>{amenities.map(a => <label key={a.id}><input type="checkbox" checked={draft.amenityIds.includes(a.id)} disabled={!draft.amenityIds.includes(a.id) && draft.amenityIds.length >= 20} onChange={e => change('amenityIds', e.target.checked ? [...draft.amenityIds, a.id] : draft.amenityIds.filter(id => id !== a.id))} />{a.name}</label>)}</div></details>}
            <button className="assistant-submit" type="submit" disabled={busy || branches.loading || catalog.loading || Boolean(branches.error || catalog.error)}>{busy ? 'Đang tìm xe phù hợp…' : 'Gợi ý xe phù hợp'}</button>
          </form>
          {error && <div role="alert" className="assistant-error"><p>{error}</p><p>Bạn có thể điều chỉnh tiêu chí rồi thử lại hoặc dùng trang <Link to="/vehicles">Tìm xe</Link>.</p></div>}
          <div ref={reply} aria-live="polite" aria-busy={busy}>
            {result?.status === 'NO_MATCH' && <p className="assistant-welcome">Chưa có xe phù hợp. Bạn có thể điều chỉnh ngày, điểm nhận/trả hoặc ngân sách rồi thử lại.</p>}
            {result?.status === 'NEEDS_CLARIFICATION' && <p className="assistant-welcome">Vui lòng bổ sung đủ tiêu chí thuê xe rồi thử lại.</p>}
            {result?.status === 'OK' && <div className="assistant-results"><h3>Xe phù hợp với bạn</h3>{result.results.slice(0, 3).map((item, index) => <article key={item.vehicleId}>
              {index === 0 && <span className="assistant-best">Gợi ý đầu tiên</span>}<h4>{item.vehicleName}</h4><strong>{money(item.total)}</strong><span className="assistant-total">Tổng tiền thuê và phí tuyến</span><p>{assistantReasons(item, result.criteria, result.amenities)}</p>
              <Link to={`/vehicles/${encodeURIComponent(item.vehicleId)}${assistantSearch(result.criteria)}`}>Xem xe <span aria-hidden="true">↗</span></Link>
            </article>)}<Link className="assistant-all" to={`/vehicles${assistantSearch(result.criteria)}`}>Tìm thêm xe theo chuyến đi</Link></div>}
          </div>
          <RouteExplorer key={routeRevision}/>
          <p className="assistant-note">Kiểm tra tiêu chí do trợ lý đọc trước khi tìm xe. Chưa giữ chỗ; giá và lịch được kiểm tra lại khi đặt thuê.</p>
        </>}
      </div>
    </section>}
    <button ref={launcher} type="button" className="assistant-launcher" aria-expanded={open} aria-label="Mở Trợ lý KDrive" onClick={() => open ? close() : show()}><Sparkles size={20} /><span>Trợ lý KDrive</span></button>
  </div>;
}
