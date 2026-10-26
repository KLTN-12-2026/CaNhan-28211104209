import { useRef, useState } from "react";
import {
  createLocation,
  createLocationCategory,
  updateLocation,
  updateLocationCategory,
} from "../../api/locations";
import { useApiData } from "../../hooks/useApiData";
import { getErrorMessage } from "../../utils/roles";
import Badge from "../../components/common/Badge";
import EmptyState from "../../components/common/EmptyState";
import LoadingSkeleton from "../../components/common/LoadingSkeleton";
import { Field, Notice, Page } from "../../components/common/PageKit";
import VisitMetadataFields from "../../components/ai/VisitMetadataFields";

const emptyCategory = { id: "", name: "", expectedRevision: "" };
const emptyLocation = {
  metadata: null,
  id: "",
  categoryId: "",
  name: "",
  latitude: "",
  longitude: "",
  status: "ACTIVE",
  expectedRevision: "",
};

function LoadFailure({ message, retry }) {
  return (
    <div className="space-y-3">
      <Notice error>{message}</Notice>
      <button className="btn-secondary" onClick={retry} type="button">
        Thử tải lại
      </button>
    </div>
  );
}

export default function ApiLocationsPage() {
  const categoriesRequest = useApiData("/admin/location-categories");
  const locationsRequest = useApiData("/admin/locations");
  const categories = Array.isArray(categoriesRequest.data) ? categoriesRequest.data : [];
  const locations = Array.isArray(locationsRequest.data) ? locationsRequest.data : [];
  const [activeTab, setActiveTab] = useState("locations");
  const [categoryDraft, setCategoryDraft] = useState(emptyCategory);
  const [locationDraft, setLocationDraft] = useState(emptyLocation);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const inFlight = useRef(false);

  function reloadAll() {
    categoriesRequest.reload();
    locationsRequest.reload();
  }

  function beginRequest() {
    if (inFlight.current) return false;
    inFlight.current = true;
    setBusy(true);
    setError("");
    setNotice("");
    return true;
  }

  function finishRequest() {
    inFlight.current = false;
    setBusy(false);
  }

  async function saveCategory(event) {
    event.preventDefault();
    if (!beginRequest()) return;
    const isUpdate = Boolean(categoryDraft.id);
    try {
      if (isUpdate) {
        await updateLocationCategory(categoryDraft.id, {
          name: categoryDraft.name,
          expectedRevision: categoryDraft.expectedRevision,
        });
      } else {
        await createLocationCategory({ name: categoryDraft.name });
      }
      setNotice(isUpdate ? "Đã cập nhật loại địa điểm." : "Đã tạo loại địa điểm.");
      setCategoryDraft(emptyCategory);
      reloadAll();
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Không thể lưu loại địa điểm."));
      if (requestError.response?.status === 409) categoriesRequest.reload();
    } finally {
      finishRequest();
    }
  }

  async function saveLocation(event) {
    event.preventDefault();
    if (!beginRequest()) return;
    const isUpdate = Boolean(locationDraft.id);
    const input = {
      categoryId: locationDraft.categoryId,
      name: locationDraft.name,
      latitude: locationDraft.latitude,
      longitude: locationDraft.longitude,
      status: locationDraft.status,
      metadata: locationDraft.metadata,
    };
    try {
      if (isUpdate) {
        await updateLocation(locationDraft.id, {
          ...input,
          expectedRevision: locationDraft.expectedRevision,
        });
      } else {
        await createLocation(input);
      }
      setNotice(isUpdate ? "Đã cập nhật địa điểm." : "Đã tạo địa điểm.");
      setLocationDraft(emptyLocation);
      reloadAll();
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Không thể lưu địa điểm."));
      if (requestError.response?.status === 409) locationsRequest.reload();
    } finally {
      finishRequest();
    }
  }

  const loading = categoriesRequest.loading || locationsRequest.loading;
  const loadError = categoriesRequest.error || locationsRequest.error;
  if (loading) {
    return (
      <Page title="Điểm du lịch" description="Quản lý danh mục địa điểm đã lưu trong hệ thống.">
        <LoadingSkeleton rows={4} />
      </Page>
    );
  }
  if (loadError) {
    return (
      <Page title="Điểm du lịch" description="Quản lý danh mục địa điểm đã lưu trong hệ thống.">
        <LoadFailure message={loadError} retry={reloadAll} />
      </Page>
    );
  }

  return (
    <Page title="Điểm du lịch" description="Quản lý địa điểm, tọa độ và thông tin phục vụ hành trình.">
      <div className="mb-5 flex flex-wrap gap-2" role="tablist" aria-label="Nhóm dữ liệu địa điểm">
        <button
          aria-selected={activeTab === "locations"}
          className={activeTab === "locations" ? "btn" : "btn-secondary"}
          onClick={() => setActiveTab("locations")}
          role="tab"
          type="button"
        >
          Địa điểm ({locations.length})
        </button>
        <button
          aria-selected={activeTab === "categories"}
          className={activeTab === "categories" ? "btn" : "btn-secondary"}
          onClick={() => setActiveTab("categories")}
          role="tab"
          type="button"
        >
          Loại địa điểm ({categories.length})
        </button>
      </div>

      {error ? <Notice error>{error}</Notice> : null}
      {notice ? <Notice>{notice}</Notice> : null}

      {activeTab === "categories" ? (
        <div className="grid gap-6 lg:grid-cols-[minmax(18rem,0.8fr)_minmax(0,1.2fr)]">
          <section className="space-y-4" aria-labelledby="category-form-title">
            <h2 id="category-form-title" className="text-lg font-semibold">
              {categoryDraft.id ? "Sửa loại địa điểm" : "Tạo loại địa điểm"}
            </h2>
            <form className="space-y-4" onSubmit={saveCategory}>
              <Field label="Tên loại">
                <input
                  autoComplete="off"
                  maxLength={100}
                  name="categoryName"
                  onChange={(event) => setCategoryDraft({ ...categoryDraft, name: event.target.value })}
                  required
                  value={categoryDraft.name}
                />
              </Field>
              <div className="flex flex-wrap gap-2">
                <button className="btn" disabled={busy} type="submit">
                  {busy ? "Đang lưu..." : categoryDraft.id ? "Lưu loại" : "Tạo loại"}
                </button>
                {categoryDraft.id ? (
                  <button className="btn-secondary" disabled={busy} onClick={() => setCategoryDraft(emptyCategory)} type="button">
                    Hủy sửa
                  </button>
                ) : null}
              </div>
            </form>
          </section>

          <section className="space-y-3" aria-label="Danh sách loại địa điểm">
            {categories.length === 0 ? (
              <EmptyState title="Chưa có loại địa điểm" description="Tạo loại đầu tiên để phân loại các điểm tham quan." />
            ) : (
              <ul className="divide-y divide-slate-200 border-y border-slate-200">
                {categories.map((category) => (
                  <li className="flex items-center justify-between gap-4 py-3" key={category.id}>
                    <span className="min-w-0 break-words font-medium">{category.name}</span>
                    <button
                      aria-label={`Sửa ${category.name}`}
                      className="btn-secondary shrink-0"
                      data-testid={`edit-category-${category.id}`}
                      disabled={busy}
                      onClick={() => {
                        setError("");
                        setNotice("");
                        setCategoryDraft({ id: category.id, name: category.name, expectedRevision: category.revision });
                      }}
                      type="button"
                    >
                      Sửa
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[minmax(19rem,0.85fr)_minmax(0,1.15fr)]">
          <section className="space-y-4" aria-labelledby="location-form-title">
            <h2 id="location-form-title" className="text-lg font-semibold">
              {locationDraft.id ? "Sửa địa điểm" : "Tạo địa điểm"}
            </h2>
            {categories.length === 0 ? (
              <Notice>Tạo loại địa điểm trước khi thêm địa điểm tham quan.</Notice>
            ) : (
              <form className="space-y-4" onSubmit={saveLocation}>
                <Field label="Tên địa điểm">
                  <input
                    autoComplete="off"
                    maxLength={200}
                    name="locationName"
                    onChange={(event) => setLocationDraft({ ...locationDraft, name: event.target.value })}
                    required
                    value={locationDraft.name}
                  />
                </Field>
                <Field label="Loại địa điểm">
                  <select
                    name="categoryId"
                    onChange={(event) => setLocationDraft({ ...locationDraft, categoryId: event.target.value })}
                    required
                    value={locationDraft.categoryId}
                  >
                    <option value="">Chọn loại địa điểm</option>
                    {categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
                  </select>
                </Field>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label="Vĩ độ" hint="Từ -90 đến 90, tối đa 7 chữ số thập phân.">
                    <input
                      max="90"
                      min="-90"
                      name="latitude"
                      onChange={(event) => setLocationDraft({ ...locationDraft, latitude: event.target.value })}
                      required
                      step="any"
                      type="number"
                      value={locationDraft.latitude}
                    />
                  </Field>
                  <Field label="Kinh độ" hint="Từ -180 đến 180, tối đa 7 chữ số thập phân.">
                    <input
                      max="180"
                      min="-180"
                      name="longitude"
                      onChange={(event) => setLocationDraft({ ...locationDraft, longitude: event.target.value })}
                      required
                      step="any"
                      type="number"
                      value={locationDraft.longitude}
                    />
                  </Field>
                </div>
                <Field label="Trạng thái">
                  <select
                    name="status"
                    onChange={(event) => setLocationDraft({ ...locationDraft, status: event.target.value })}
                    value={locationDraft.status}
                  >
                    <option value="ACTIVE">Đang hoạt động</option>
                    <option value="INACTIVE">Ngừng hoạt động</option>
                  </select>
                </Field>
                <VisitMetadataFields value={locationDraft.metadata} disabled={busy} onChange={metadata=>setLocationDraft({...locationDraft,metadata})}/>
                <div className="flex flex-wrap gap-2">
                  <button className="btn" disabled={busy || !locationDraft.categoryId} type="submit">
                    {busy ? "Đang lưu..." : locationDraft.id ? "Lưu địa điểm" : "Tạo địa điểm"}
                  </button>
                  {locationDraft.id ? (
                    <button className="btn-secondary" disabled={busy} onClick={() => setLocationDraft(emptyLocation)} type="button">
                      Hủy sửa
                    </button>
                  ) : null}
                </div>
              </form>
            )}
          </section>

          <section className="space-y-3" aria-label="Danh sách địa điểm">
            {locations.length === 0 ? (
              <EmptyState title="Chưa có địa điểm" description="Địa điểm sẽ xuất hiện sau khi được lưu thành công." />
            ) : (
              <ul className="divide-y divide-slate-200 border-y border-slate-200">
                {locations.map((location) => (
                  <li className="flex flex-wrap items-start justify-between gap-3 py-4" key={location.id}>
                    <div className="min-w-0 space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="break-words font-semibold">{location.name}</span>
                        <Badge tone={location.status === "ACTIVE" ? "success" : "neutral"}>
                          {location.status === "ACTIVE" ? "Đang hoạt động" : "Ngừng hoạt động"}
                        </Badge>
                      </div>
                      <p className="text-sm text-slate-600">{location.categoryName}</p>
                      <p className="text-sm text-slate-500">{location.latitude}, {location.longitude}</p>
                    </div>
                    <button
                      aria-label={`Sửa ${location.name}`}
                      className="btn-secondary shrink-0"
                      data-testid={`edit-location-${location.id}`}
                      disabled={busy}
                      onClick={() => {
                        setError("");
                        setNotice("");
                        setLocationDraft({
                          id: location.id,
                          categoryId: location.categoryId,
                          name: location.name,
                          latitude: String(location.latitude),
                          longitude: String(location.longitude),
                          status: location.status,
                          expectedRevision: location.revision,
                          metadata: location.metadata || null,
                        });
                      }}
                      type="button"
                    >
                      Sửa
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
    </Page>
  );
}
