import { Link, useSearchParams } from "react-router-dom";
import { useVehicleFavorites } from "../../hooks/useVehicleFavorites";
import { searchSuffix } from "../../utils/vehicleSearch";

export function FavoriteToggle({ vehicleId, name }) {
  const { ids, issue, ready, toggle } = useVehicleFavorites();
  const selected = ids.includes(vehicleId);
  return <div className="my-2"><button type="button" className="btn-secondary text-xs" disabled={!ready} aria-pressed={selected} aria-label={`${selected ? "Bỏ yêu thích" : "Yêu thích"} ${name}`} onClick={() => toggle(vehicleId)}>{selected ? "♥ Đã yêu thích" : "♡ Yêu thích"}</button>{issue ? <p role="status" className="mt-2 text-xs text-red-700">{issue}</p> : null}</div>;
}
export default function FavoritesLink({ compact = false }) {
  const [params] = useSearchParams();
  const { ids, ready } = useVehicleFavorites();
  const link = <Link className="btn-secondary" to={`/vehicles/favorites${searchSuffix(params)}`}>Xe yêu thích ({ready ? ids.length : "…"})</Link>;
  return compact ? link : <div className="my-3">{link}<p className="mt-2 text-xs text-muted">Chỉ lưu trên trình duyệt này, tách riêng khách và từng tài khoản. Không đồng bộ thiết bị.</p></div>;
}
