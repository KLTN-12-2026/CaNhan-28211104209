import { Link, useSearchParams } from "react-router-dom";
import { useApiData } from "../../hooks/useApiData";
import { useVehicleFavorites } from "../../hooks/useVehicleFavorites";
import { FavoriteToggle } from "../../components/vehicle/FavoriteControls";
import VehicleCard from "../../components/vehicle/VehicleCard";
import { normalizeVehicle } from "./ApiScreens";
import { searchSuffix } from "../../utils/vehicleSearch";

export default function ApiVehicleFavoritesPage() {
  const [params] = useSearchParams();
  const { ids, issue, ready, toggle } = useVehicleFavorites();
  const vehicles = useApiData("/vehicles", ready);
  const catalog = useApiData("/vehicles/catalog", ready);
  const loaded = ready && !vehicles.loading && !catalog.loading && !vehicles.error && !catalog.error;
  const missing = loaded ? ids.filter(id => !(vehicles.data || []).some(vehicle => vehicle.id === id)) : [];
  return <section className="page-shell">
    <h1 className="text-3xl font-bold">Xe yêu thích</h1>
    <p className="my-3 text-sm text-muted">Chỉ lưu trên trình duyệt này, tách riêng khách và từng tài khoản. Giá và trạng thái được lấy mới; xe chưa được giữ chỗ.</p>
    <Link className="btn-secondary" to={`/vehicles${searchSuffix(params)}`}>Về danh sách xe</Link>
    <button type="button" className="btn-secondary ml-2" disabled={!ready || vehicles.loading || catalog.loading} onClick={() => { vehicles.reload(); catalog.reload(); }}>Tải lại danh sách</button>
    {issue ? <p role="status">{issue}</p> : null}
    {!ready ? <p role="status">Đang xác định phiên truy cập. Chưa hiển thị danh sách đã lưu.</p> : null}
    {ready && (vehicles.loading || catalog.loading) ? <p role="status">Đang tải xe yêu thích…</p> : null}
    {vehicles.error || catalog.error ? <p role="alert">{vehicles.error || catalog.error}</p> : null}
    {loaded && ids.length === 0 ? <p className="my-6">Chưa có xe yêu thích. Chọn biểu tượng trái tim ở danh sách hoặc chi tiết xe.</p> : null}
    {loaded ? <div className="mt-6 grid gap-6 md:grid-cols-2 xl:grid-cols-3">{ids.map(id => {
      const raw = (vehicles.data || []).find(vehicle => vehicle.id === id);
      return raw ? <VehicleCard key={id} vehicle={normalizeVehicle(raw, catalog.data)} search={searchSuffix(params)} compareControl={<FavoriteToggle vehicleId={id} name={raw.name} />} /> : null;
    })}</div> : null}
    {missing.map(id => <div key={id} className="panel mt-4 p-4"><p>Xe đã lưu không còn trong danh mục hiện tại.</p><button type="button" className="btn-secondary mt-2" onClick={() => toggle(id)}>Bỏ xe không còn trong danh mục</button></div>)}
  </section>;
}
