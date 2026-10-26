import { useSearchParams, Link } from "react-router-dom";
import { useState } from "react";
import { useApiData } from "../../hooks/useApiData";
import { Page, Notice, money, dateTime } from "../../components/common/PageKit";
import { VehicleVisual } from "../../components/vehicle/VehicleCard";
import { normalizeVehicle } from "./ApiScreens";
import { compareSelection } from "../../utils/vehicleCompare";
import { searchParams, searchSuffix, tripSearch } from "../../utils/vehicleSearch";

function CompareColumn({ id, catalog, branches, search, trip, pricing, budget, onRemove }) {
  const request=useApiData(`/vehicles/${encodeURIComponent(id)}`);
  const price=pricing.data?.items?.find(item=>item.vehicle.id===id);
  const vehicle=request.data ? normalizeVehicle(price?.vehicle || request.data,catalog) : null;
  const bookable=vehicle?.status === "ACTIVE" && (!trip.active || (!trip.error && !pricing.loading && !pricing.error && Boolean(price)));
  return <article className="panel compare-column" aria-label={`So sánh ${vehicle?.name || id}`}>
    <button className="btn-secondary mb-3" onClick={onRemove} aria-label={`Bỏ ${vehicle?.name || id}`}>Bỏ xe</button>
    {request.loading ? <p role="status">Đang tải xe…</p> : request.error ? <div><Notice error>{request.error}</Notice><p>Không thể so sánh xe này.</p><button className="btn-secondary" onClick={request.reload}>Tải lại xe</button></div> : !vehicle ? <p>Xe không còn hiển thị.</p> : <>
      <VehicleVisual vehicle={vehicle} />
      <h2 className="my-3 text-xl font-bold"><Link to={`/vehicles/${encodeURIComponent(id)}${search}`}>{vehicle.name}</Link></h2>
      <dl className="compare-specs">
        <dt>Hãng</dt><dd>{catalog?.brands?.find(b=>b.id===vehicle.brandId)?.name || "Chưa tải được hãng"}</dd>
        <dt>Loại xe</dt><dd>{catalog?.categories?.find(c=>c.id===vehicle.categoryId)?.name || "Chưa tải được loại xe"}</dd>
        <dt>Số chỗ (gồm người lái)</dt><dd>{vehicle.seats}</dd>
        <dt>Hộp số</dt><dd>{vehicle.transmission}</dd>
        <dt>Tiện nghi</dt><dd>{vehicle.amenities?.length ? vehicle.amenities.map(a=>a.name).join(", ") : "Chưa có thông tin tiện nghi được cung cấp; vui lòng xác nhận với chi nhánh."}</dd>
        <dt>Chi nhánh hiện tại</dt><dd>{branches?.find(b=>b.id===vehicle.currentBranchId)?.name || "Chưa tải được chi nhánh"}</dd>
        <dt>Giá / ngày</dt><dd>{money(vehicle.price)}</dd>
      </dl>
      {trip.active ? <section className="mt-4 border-t border-slate-200 pt-4" aria-label={`Giá chuyến ${vehicle.name}`}>
        <h3 className="font-semibold">Tổng giá chuyến</h3>
        {trip.error ? <Notice error>{trip.error}</Notice> : pricing.loading ? <p role="status">Đang kiểm tra giá và lịch…</p> : pricing.error ? <p>Chưa lấy được tổng giá và lịch.</p> : !price ? <p role="status">Không còn phù hợp lịch/tuyến đã chọn. Hãy đổi chuyến hoặc kiểm tra lại.</p> : <>
          <p className="my-2 text-xl font-bold" data-trip-total={price.total}>{money(price.total)}</p>
          <p className="text-sm">{price.rentalDays} ngày thuê · Tiền thuê {money(price.rentalTotal)} · Phí tuyến {money(price.oneWayFee)}</p>
          {budget != null && price.total > budget ? <p className="mt-2 text-sm text-red-700">Vượt ngân sách đã chọn</p> : null}
        </>}
      </section> : <p className="mt-4 text-sm">Chọn thời gian và điểm nhận/trả để so sánh tổng giá chuyến.</p>}
      {bookable ? <Link className="btn mt-4 w-full" to={`/user/bookings/new?vehicleId=${encodeURIComponent(id)}${search.replace(/^\?/,"&")}`}>Chọn xe này & báo giá</Link> : null}
    </>}
  </article>;
}

export default function ApiVehicleComparePage() {
  const [params,setParams]=useSearchParams();
  const selection=compareSelection(params.get("compare") || "");
  const [revision,setRevision]=useState(0);
  const catalog=useApiData("/vehicles/catalog",selection.ids.length>0);
  const branches=useApiData("/branches",selection.ids.length>0);
  const trip=tripSearch(searchParams(params,{budget:""}));
  const pricing=useApiData(trip.path,selection.ids.length>0 && trip.active && !trip.error);
  const budget=/^\d+$/.test(params.get("budget") || "") ? Number(params.get("budget")) : null;
  return <Page title="So sánh xe" description="Đối chiếu tối đa 3 xe. Số chỗ bao gồm người lái.">
    <Link className="btn-secondary mb-5 inline-flex" to={`/vehicles${searchSuffix(params)}`}>Về danh sách xe</Link>
    <div className="panel mb-5">
      <p>{trip.active ? `${params.get("start") || "?"} → ${params.get("end") || "?"} · Giờ Việt Nam` : "Chưa chọn chuyến"}</p>
      {trip.active && !trip.error ? <p className="mt-2 text-sm">{branches.data?.find(b=>b.id===params.get("pickupBranchId"))?.name || "Điểm nhận chưa tải"} → {branches.data?.find(b=>b.id===(params.get("returnBranchId") || params.get("pickupBranchId")))?.name || "Điểm trả chưa tải"}</p> : null}
      {trip.error ? <div className="mt-2"><Notice error>{trip.error}</Notice><p className="mt-1 text-xs text-slate-600">Thông số chuyến chưa hợp lệ: hãy chọn đủ ngày nhận, ngày trả và chi nhánh hợp lệ để kiểm tra giá.</p></div> : null}
      <p className="mt-2 text-xs text-slate-500">Tổng chuyến gồm tiền thuê và phí tuyến, chưa gồm cọc/phát sinh. Kết quả chưa giữ chỗ; báo giá và tạo đơn sẽ kiểm tra lại.</p>
      {!trip.active ? (
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <Link className="btn-secondary" to={`/vehicles${searchSuffix(params)}`}>Chọn chuyến để kiểm tra giá</Link>
          <span className="text-xs text-slate-600">Chọn ngày nhận, ngày trả và chi nhánh để so sánh tổng giá chuyến.</span>
          <button className="btn-secondary" onClick={()=>{setRevision(value=>value+1);catalog.reload();branches.reload();}}>Tải lại thông tin xe</button>
        </div>
      ) : trip.error ? (
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <Link className="btn-secondary" to={`/vehicles${searchSuffix(params)}`}>Thay đổi chuyến</Link>
          <button className="btn-secondary" onClick={()=>{setRevision(value=>value+1);catalog.reload();branches.reload();}}>Tải lại thông tin xe</button>
        </div>
      ) : (
        <div className="mt-3 flex flex-wrap gap-3">
          <Link className="btn-secondary" to={`/vehicles${searchSuffix(params)}`}>Thay đổi chuyến</Link>
          <button className="btn-secondary" onClick={()=>{setRevision(value=>value+1);pricing.reload();catalog.reload();branches.reload();}}>Kiểm tra lại giá & lịch</button>
        </div>
      )}
      {pricing.data?.checkedAt ? <p className="mt-2 text-xs">Kiểm tra lúc {dateTime(pricing.data.checkedAt)}</p> : null}
      {pricing.error ? <Notice error>{pricing.error}</Notice> : null}
    </div>
    <p className="mb-4" aria-live="polite">Đã chọn {selection.ids.length}/3 xe</p>
    {selection.issue ? <Notice error>{selection.issue}</Notice> : null}
    {catalog.error || branches.error ? <div><Notice error>{catalog.error || branches.error}</Notice><button className="btn-secondary mb-4" onClick={()=>{catalog.reload();branches.reload();}}>Tải lại danh mục</button></div> : null}
    {selection.ids.length<2 ? <p className="mb-4">Chọn thêm xe từ danh sách hoặc chi tiết để so sánh 2–3 xe.</p> : null}
    <div className="compare-scroll" tabIndex={0} role="region" aria-label="Bảng so sánh xe"><div className={`compare-grid compare-count-${selection.ids.length}`}>
      {selection.ids.map(id=><CompareColumn key={`${id}:${revision}`} id={id} catalog={catalog.data} branches={branches.data} search={searchSuffix(params)} trip={trip} pricing={pricing} budget={budget} onRemove={()=>setParams(searchParams(params,{compare:selection.ids.filter(value=>value!==id).join(",")}))} />)}
    </div></div>
  </Page>;
}
