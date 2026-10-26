import useConfirmAction from "../../hooks/useConfirmAction";
import { roleLabels, accountStatusLabels, vehicleStatusLabels } from "../../utils/uiLabels";
import VehicleImageEditor from "../../components/vehicle/VehicleImageEditor";
import { useCallback, useEffect, useState, useRef, useMemo } from "react";
import apiClient from "../../api/client";
import { changeBranchStatus } from "../../api/branches";
import { useAuth } from "../../context/AuthContext";
import { useApiData } from "../../hooks/useApiData";
import { getErrorMessage } from "../../utils/roles";
import { Field, money, Notice, Page } from "../../components/common/PageKit";
import EmptyState from "../../components/common/EmptyState";
import LoadingSkeleton from "../../components/common/LoadingSkeleton";
import Badge from "../../components/common/Badge";

export function ApiAccountsPage() {
  const { confirm, confirmation } = useConfirmAction();
  const accountsRequest = useApiData("/admin/accounts");
  const allBranchesRequest = useApiData("/branches");
  const staffBranchesRequest = useApiData("/admin/staff-branches");
  const accounts = Array.isArray(accountsRequest.data)
    ? accountsRequest.data
    : [];
  const allBranches = useMemo(
    () => allBranchesRequest.data || [],
    [allBranchesRequest.data],
  );
  const activeBranches = useMemo(
    () => allBranches.filter((branch) => branch.status === "ACTIVE"),
    [allBranches],
  );
  const branchNameMap = useMemo(
    () => new Map(allBranches.map((b) => [b.id, b.name])),
    [allBranches],
  );
  const staffBranchLinks = useMemo(
    () =>
      Array.isArray(staffBranchesRequest.data) ? staffBranchesRequest.data : [],
    [staffBranchesRequest.data],
  );
  const assignedBranchIdsByUser = useMemo(() => {
    const map = new Map();
    for (const link of staffBranchLinks) {
      if (!map.has(link.userId)) map.set(link.userId, []);
      map.get(link.userId).push(link.branchId);
    }
    return map;
  }, [staffBranchLinks]);

  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busyId, setBusyId] = useState("");
  const [busyIds, setBusyIds] = useState(() => new Set());
  const inFlightRef = useRef(new Set());

  const isBusy = (id) => busyIds.has(id) || busyId === id;

  async function addStaff(event) {
    event.preventDefault();
    if (inFlightRef.current.has("create")) {
      return;
    }
    inFlightRef.current.add("create");
    setBusyIds((prev) => new Set(prev).add("create"));
    setBusyId("create");
    setError("");
    setNotice("");
    const target = event.currentTarget;
    const form = new FormData(target);
    try {
      await apiClient.post("/admin/accounts", {
        fullName: String(form.get("fullName") || ""),
        email: String(form.get("email") || ""),
        password: String(form.get("password") || ""),
      });
      target.reset();
      setNotice("Đã tạo tài khoản nhân viên.");
      accountsRequest.reload();
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Không thể tạo tài khoản nhân viên."));
    } finally {
      inFlightRef.current.delete("create");
      setBusyIds((prev) => {
        const next = new Set(prev);
        next.delete("create");
        return next;
      });
      setBusyId((prev) => (prev === "create" ? "" : prev));
    }
  }

  async function changeStatus(account, status) {
    if (status === "LOCKED" && !(await confirm(`Khóa tài khoản ${account.fullName} (${account.email})? Người dùng sẽ không thể tiếp tục sử dụng tài khoản cho đến khi được mở khóa.`))) return;
    const key = account.id;
    if (inFlightRef.current.has(key)) {
      return;
    }
    inFlightRef.current.add(key);
    setBusyIds((prev) => new Set(prev).add(key));
    setBusyId(key);
    setError("");
    setNotice("");
    try {
      await apiClient.patch(
        `/admin/accounts/${encodeURIComponent(account.id)}`,
        {
          status,
          version: account.version,
        },
      );
      setNotice(`Đã cập nhật trạng thái tài khoản ${account.email}.`);
      accountsRequest.reload();
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Không thể cập nhật tài khoản."));
    } finally {
      inFlightRef.current.delete(key);
      setBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
      setBusyId((prev) => (prev === key ? "" : prev));
    }
  }

  async function assignBranch(event, account) {
    event.preventDefault();
    const key = `assign:${account.id}`;
    const accountKey = account.id;
    if (inFlightRef.current.has(key) || inFlightRef.current.has(accountKey)) {
      return;
    }
    inFlightRef.current.add(key);
    inFlightRef.current.add(accountKey);
    setBusyIds((prev) => new Set(prev).add(key).add(accountKey));
    setBusyId(accountKey);
    setError("");
    setNotice("");
    const target = event.currentTarget;
    const form = new FormData(target);
    try {
      await apiClient.post("/admin/staff-branches", {
        userId: account.id,
        branchId: String(form.get("branchId") || ""),
      });
      setNotice(`Đã gán chi nhánh cho ${account.fullName}.`);
      target.reset();
      accountsRequest.reload();
      staffBranchesRequest.reload();
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Không thể gán chi nhánh."));
    } finally {
      inFlightRef.current.delete(key);
      inFlightRef.current.delete(accountKey);
      setBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(key);
        next.delete(accountKey);
        return next;
      });
      setBusyId((prev) => (prev === accountKey ? "" : prev));
    }
  }

  const reload = () => {
    accountsRequest.reload();
    allBranchesRequest.reload();
    staffBranchesRequest.reload();
  };

  const isLoading =
    accountsRequest.loading ||
    allBranchesRequest.loading ||
    staffBranchesRequest.loading;
  const loadError =
    accountsRequest.error || allBranchesRequest.error || staffBranchesRequest.error;

  return (
    <Page
      title="Quản lý tài khoản"
      description="Quản lý trạng thái khách hàng/nhân viên và phân công phạm vi chi nhánh."
    >
      {confirmation}
      {notice ? <Notice toast>{notice}</Notice> : null}
      {error ? <Notice error toast>{error}</Notice> : null}
      <div className="mb-6 grid gap-6 lg:grid-cols-[minmax(0,1fr)_340px]">
        {isLoading ? (
          <LoadingSkeleton rows={3} />
        ) : loadError ? (
          <div>
            <Notice error>{loadError}</Notice>
            <button className="btn-secondary" onClick={reload}>
              Thử tải lại
            </button>
          </div>
        ) : (
          <>
            {accounts.length ? (
              <div className="space-y-3">
                {accounts.map((account) => (
                  <article className="panel" key={account.id}>
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div>
                        <h2 className="font-semibold">{account.fullName}</h2>
                        <p className="mt-1 text-sm text-slate-500">
                          {account.email}
                        </p>
                      </div>
                      <div className="flex items-center gap-2">
                        <Badge
                          tone={
                            account.status === "ACTIVE" ? "success" : "danger"
                          }
                        >
                          {accountStatusLabels[account.status] || "Chưa xác định"}
                        </Badge>
                        <Badge>{roleLabels[account.role] || "Chưa xác định"}</Badge>
                      </div>
                    </div>
                    <div className="mt-4 flex flex-wrap items-center gap-3">
                      {account.role !== "ADMIN" ? (
                        <button
                          className={account.status === "ACTIVE" ? "btn-danger" : "btn-secondary"}
                          disabled={isBusy(account.id)}
                          onClick={() =>
                            changeStatus(
                              account,
                              account.status === "ACTIVE"
                                ? "LOCKED"
                                : "ACTIVE",
                            )
                          }
                        >
                          {account.status === "ACTIVE"
                            ? "Khóa tài khoản"
                            : "Mở khóa"}
                        </button>
                      ) : (
                        <p className="text-xs text-slate-500">
                          Không thể khóa tài khoản quản trị.
                        </p>
                      )}
                      {account.role === "STAFF" ? (
                        <>
                          <div className="flex flex-wrap gap-2 text-sm text-slate-600">
                            {(() => {
                              const assignedIds =
                                assignedBranchIdsByUser.get(account.id) || [];
                              if (assignedIds.length === 0) {
                                return <span>Chưa phân công chi nhánh</span>;
                              }
                              return assignedIds.map((branchId) => {
                                const branchName =
                                  branchNameMap.get(branchId) || branchId;
                                const branch = allBranches.find(
                                  (b) => b.id === branchId,
                                );
                                const isInactive =
                                  branch && branch.status !== "ACTIVE";
                                return (
                                  <Badge
                                    key={branchId}
                                    tone={isInactive ? "warning" : "default"}
                                  >
                                    {branchName}{isInactive ? " (tạm ngừng)" : ""}
                                  </Badge>
                                );
                              });
                            })()}
                          </div>
                          {activeBranches.length > 0 && (
                            <form
                              className="flex flex-wrap gap-2"
                              onSubmit={(event) =>
                                assignBranch(event, account)
                              }
                            >
                              <select
                                className="min-h-10 rounded-lg border border-slate-300 px-3 text-sm"
                                name="branchId"
                                required
                                defaultValue=""
                                disabled={isBusy(account.id)}
                              >
                                <option value="" disabled>
                                  Chọn chi nhánh để gán
                                </option>
                                {activeBranches.map((branch) => {
                                  const alreadyAssigned =
                                    assignedBranchIdsByUser
                                      .get(account.id)
                                      ?.includes(branch.id);
                                  return (
                                    <option
                                      key={branch.id}
                                      value={branch.id}
                                      disabled={alreadyAssigned}
                                    >
                                      {branch.name}{alreadyAssigned ? " (đã gán)" : ""}
                                    </option>
                                  );
                                })}
                              </select>
                              <button
                                type="submit"
                                className="btn-secondary"
                                disabled={isBusy(account.id)}
                              >
                                Gán chi nhánh
                              </button>
                            </form>
                          )}
                        </>
                      ) : null}
                    </div>
                  </article>
                ))}
              </div>
            ) : (
              <EmptyState title="Chưa có tài khoản" />
            )}
            <form className="panel h-fit space-y-4" onSubmit={addStaff}>
              <h2 className="text-lg font-semibold">Tạo nhân viên</h2>
              <Field label="Họ và tên">
                <input name="fullName" required maxLength={100} />
              </Field>
              <Field label="Email">
                <input name="email" type="email" required maxLength={254} />
              </Field>
              <Field label="Mật khẩu khởi tạo">
                <input
                  name="password"
                  type="password"
                  required
                  minLength={8}
                  maxLength={72}
                  autoComplete="new-password"
                />
              </Field>
              <p className="text-xs text-slate-500">
                Tài khoản tạo mới có vai trò nhân viên; mật khẩu không được hiển thị hoặc
                lưu ở giao diện.
              </p>
              <button type="submit" className="btn" disabled={isBusy("create")}>
                {isBusy("create") ? "Đang tạo..." : "Tạo nhân viên"}
              </button>
            </form>
          </>
        )}
      </div>
    </Page>
  );
}

/**
 * VehicleAmenityEditor — Admin only, chỉ render khi vehicleId đã tồn tại.
 * GET /admin/vehicles/{id}/amenities → {amenities:[{id,name}], revision}
 * PUT /admin/vehicles/{id}/amenities → {amenityIds, expectedRevision}
 * GET /vehicles/catalog → lấy danh sách catalog để chọn
 */
function VehicleAmenityForm({
  vehicleId,
  initialAmenities,
  revision,
  catalogAmenities,
  onReload,
  error,
  notice,
  onError,
  onNotice,
}) {
  const [selectedIds, setSelectedIds] = useState(
    () => new Set((initialAmenities || []).map((a) => a.id)),
  );
  const [busy, setBusy] = useState(false);
  const [hasConflict, setHasConflict] = useState(false);
  const pendingRef = useRef(false);

  const toggleId = useCallback((id) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  async function saveAmenities(event) {
    event.preventDefault();
    if (pendingRef.current) return;
    pendingRef.current = true;
    setBusy(true);
    onError("");
    onNotice("");
    setHasConflict(false);
    try {
      await apiClient.put(
        `/admin/vehicles/${encodeURIComponent(vehicleId)}/amenities`,
        {
          amenityIds: [...selectedIds],
          expectedRevision: revision,
        },
      );
      onNotice("Đã lưu tiện nghi thành công.");
      onReload();
    } catch (err) {
      const status = err?.response?.status;
      if (status === 409) {
        setHasConflict(true);
        onError("Tiện nghi đã thay đổi bởi thao tác khác (409). Vui lòng tải lại bản mới.");
      } else {
        onError(getErrorMessage(err, "Không thể lưu tiện nghi. Kiểm tra lại lựa chọn."));
      }
    } finally {
      pendingRef.current = false;
      setBusy(false);
    }
  }

  const allAmenities = catalogAmenities || [];

  return (
    <form
      className="mt-4 border-t border-slate-100 pt-4 space-y-3"
      onSubmit={saveAmenities}
      aria-label="Tiện nghi xe"
    >
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-700">Tiện nghi</h3>

      </div>

      {notice ? (
        <p className="text-sm font-medium text-teal-700">{notice}</p>
      ) : null}
      {error ? (
        <p className="text-sm font-medium text-red-600">{error}</p>
      ) : null}

      {allAmenities.length === 0 ? (
        <p className="text-xs text-slate-500">
          Chưa có tiện nghi nào trong catalog. Thêm tiện nghi tại trang Danh
          mục.
        </p>
      ) : (
        <fieldset className="grid grid-cols-1 gap-1.5 sm:grid-cols-2" disabled={busy}>
          <legend className="sr-only">Chọn tiện nghi</legend>
          {allAmenities.map((a) => (
            <label
              key={a.id}
              className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-slate-50"
            >
              <input
                type="checkbox"
                className="h-4 w-4 rounded border-slate-300 text-teal-600 focus:ring-teal-500"
                checked={selectedIds.has(a.id)}
                onChange={() => toggleId(a.id)}
                disabled={busy}
              />
              <span>{a.name}</span>
            </label>
          ))}
        </fieldset>
      )}
      {allAmenities.length > 0 ? (
        <div className="flex items-center gap-2 pt-1">
          <button
            type="submit"
            className="btn-secondary text-xs"
            disabled={busy}
          >
            {busy ? "Đang lưu..." : "Lưu tiện nghi"}
          </button>
          {hasConflict ? (
            <button
              type="button"
              className="btn-secondary text-xs"
              disabled={busy}
              onClick={() => {
                setHasConflict(false);
                onError("");
                onReload();
              }}
            >
              Tải lại bản mới
            </button>
          ) : null}
        </div>
      ) : null}
    </form>
  );
}

/**
 * VehicleAmenityEditor — Admin only, chỉ render khi vehicleId đã tồn tại.
 * GET /admin/vehicles/{id}/amenities → {amenities:[{id,name}], revision}
 * PUT /admin/vehicles/{id}/amenities → {amenityIds, expectedRevision}
 * GET /vehicles/catalog → lấy danh sách catalog để chọn
 */
function VehicleAmenityEditor({ vehicleId, catalogAmenities }) {
  const amenityRequest = useApiData(
    `/admin/vehicles/${encodeURIComponent(vehicleId)}/amenities`,
  );
  const [editorError, setEditorError] = useState("");
  const [editorNotice, setEditorNotice] = useState("");

  if (amenityRequest.loading) {
    return (
      <div className="mt-4 border-t border-slate-100 pt-4">
        <p className="text-sm text-slate-500">Đang tải tiện nghi...</p>
      </div>
    );
  }
  if (amenityRequest.error) {
    return (
      <div className="mt-4 border-t border-slate-100 pt-4">
        <p className="text-sm text-red-600">{amenityRequest.error}</p>
        <button
          type="button"
          className="btn-secondary mt-2 text-xs"
          onClick={amenityRequest.reload}
        >
          Thử tải lại tiện nghi
        </button>
      </div>
    );
  }
  if (!amenityRequest.data) return null;

  return (
    <VehicleAmenityForm
      key={`${vehicleId}-${amenityRequest.data.revision}`}
      vehicleId={vehicleId}
      initialAmenities={amenityRequest.data.amenities}
      revision={amenityRequest.data.revision}
      catalogAmenities={catalogAmenities}
      onReload={amenityRequest.reload}
      error={editorError}
      notice={editorNotice}
      onError={setEditorError}
      onNotice={setEditorNotice}
    />
  );
}

const initialVehicleForm = {
  id: "",
  name: "",
  plate: "",
  brandId: "",
  categoryId: "",
  homeBranchId: "",
  seats: "5",
  transmission: "AUTO",
  dailyPrice: "",
  status: "ACTIVE",
  description: "",
  version: 0,
};

export function ApiVehiclesAdminPage({ fleet = false }) {
  const { confirm, confirmation } = useConfirmAction();
  const vehiclesRequest = useApiData("/vehicles");
  const catalogRequest = useApiData("/vehicles/catalog");
  const branchesRequest = useApiData("/branches");
  const assignmentsRequest = useApiData("/me/branches", fleet);
  const [form, setForm] = useState(initialVehicleForm);
  const [vehicleQuery, setVehicleQuery] = useState("");
  const [branchFilter, setBranchFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [page, setPage] = useState(1);
  const [activeTab, setActiveTab] = useState("info");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const deletePending = useRef(false);
  const vehicles = useMemo(
    () => (Array.isArray(vehiclesRequest.data) ? vehiclesRequest.data : []),
    [vehiclesRequest.data],
  );
  const assignedBranchIds = useMemo(
    () => new Set(assignmentsRequest.data || []),
    [assignmentsRequest.data],
  );
  const visibleVehicles = useMemo(
    () =>
      fleet
        ? vehicles.filter((vehicle) =>
            assignedBranchIds.has(vehicle.currentBranchId),
          )
        : vehicles,
    [fleet, vehicles, assignedBranchIds],
  );

  const filteredVehicles = useMemo(() => {
    if (fleet) return visibleVehicles;
    return vehicles.filter((v) => {
      if (vehicleQuery.trim()) {
        const q = vehicleQuery.trim().toLowerCase();
        const matchName = String(v.name || "").toLowerCase().includes(q);
        const matchPlate = String(v.plate || "").toLowerCase().includes(q);
        if (!matchName && !matchPlate) return false;
      }
      if (branchFilter && v.currentBranchId !== branchFilter) {
        return false;
      }
      if (statusFilter && v.status !== statusFilter) {
        return false;
      }
      return true;
    });
  }, [fleet, visibleVehicles, vehicles, vehicleQuery, branchFilter, statusFilter]);

  const pageSize = 20;
  const totalPages = Math.max(1, Math.ceil(filteredVehicles.length / pageSize));
  const activePage = Math.min(Math.max(1, page), totalPages);

  const filterKey = `${vehicleQuery}|${branchFilter}|${statusFilter}`;
  const [prevFilterKey, setPrevFilterKey] = useState(filterKey);
  if (filterKey !== prevFilterKey) {
    setPrevFilterKey(filterKey);
    setPage(1);
  }

  const pagedVehicles = fleet
    ? filteredVehicles
    : filteredVehicles.slice((activePage - 1) * pageSize, activePage * pageSize);

  const catalog = catalogRequest.data || {};
  const brands = useMemo(() => catalog.brands || [], [catalog.brands]);
  const categories = useMemo(() => catalog.categories || [], [catalog.categories]);
  const allBranches = useMemo(() => branchesRequest.data || [], [branchesRequest.data]);
  const branches = useMemo(
    () => allBranches.filter((branch) => branch.status === "ACTIVE"),
    [allBranches],
  );
  const branchInitialized = useRef(false);

  useEffect(() => {
    if (branchInitialized.current || form.homeBranchId || !branches.length) return;
    branchInitialized.current = true;
    setForm((current) => ({ ...current, homeBranchId: branches[0].id }));
  }, [branches, form.homeBranchId]);

  function editVehicle(vehicle) {
    setForm({
      ...vehicle,
      dailyPrice: String(vehicle.dailyPrice),
      seats: String(vehicle.seats),
      homeBranchId: vehicle.homeBranchId || "",
      version: vehicle.version,
    });
    setError("");
    setNotice("");
    setActiveTab("info");
  }

  function resetVehicleForm() {
    setForm({
      ...initialVehicleForm,
      brandId: "",
      categoryId: "",
      homeBranchId: branches[0]?.id || "",
    });
    setActiveTab("info");
  }

  async function deleteVehicle(vehicle) {
    if (busy || deletePending.current || !(await confirm(
      `Xóa xe ${vehicle.name} (${vehicle.plate}) khỏi hệ thống? Thao tác không thể hoàn tác. Xe đã có đơn thuê sẽ không được xóa; hãy dùng Ngừng khai thác.`,
    ))) return;
    if (deletePending.current) return;
    deletePending.current = true;
    setError("");
    setNotice("");
    setBusy(true);
    try {
      await apiClient.delete(`/admin/vehicles/${encodeURIComponent(vehicle.id)}`, {
        params: { version: vehicle.version },
      });
      if (form.id === vehicle.id) resetVehicleForm();
      setNotice(`Đã xóa xe ${vehicle.name}.`);
      vehiclesRequest.reload();
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Không thể xóa xe."));
    } finally {
      deletePending.current = false;
      setBusy(false);
    }
  }

  async function saveVehicle(event) {
    event.preventDefault();
    setError("");
    setNotice("");
    setBusy(true);
    const payload = {
      ...form,
      seats: Number(form.seats),
      dailyPrice: Number(form.dailyPrice),
      currentBranchId: form.currentBranchId || form.homeBranchId,
    };
    try {
      if (form.id) {
        const response = await apiClient.put(
          `/admin/vehicles/${encodeURIComponent(form.id)}`,
          payload,
        );
        const updated = response?.data;
        const targetId = form.id;
        setForm((current) => {
          if (current.id !== targetId) return current;
          if (updated && updated.id === targetId) {
            return {
              ...current,
              ...updated,
              dailyPrice: String(updated.dailyPrice ?? current.dailyPrice),
              seats: String(updated.seats ?? current.seats),
              homeBranchId: updated.homeBranchId || current.homeBranchId || "",
              version: updated.version ?? (Number(current.version) + 1),
            };
          }
          return {
            ...current,
            version: (current.version !== undefined && current.version !== null) ? Number(current.version) + 1 : current.version,
          };
        });
        setNotice("Đã cập nhật xe.");
      } else {
        const response = await apiClient.post("/admin/vehicles", payload);
        const created = response?.data;
        if (created?.id) {
          editVehicle(created);
          setActiveTab("images");
          setNotice(`Đã tạo xe ${created.name}. Bạn có thể tiếp tục bổ sung hình ảnh và tiện nghi.`);
        } else {
          resetVehicleForm();
          setNotice("Đã tạo xe.");
        }
      }
      vehiclesRequest.reload();
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Không thể lưu thông tin xe."));
    } finally {
      setBusy(false);
    }
  }

  async function changeFleetStatus(vehicle, status) {
    setError("");
    setNotice("");
    setBusy(true);
    try {
      await apiClient.patch(
        `/vehicles/${encodeURIComponent(vehicle.id)}/operation`,
        {
          status,
          version: vehicle.version,
        },
      );
      setNotice(`Đã cập nhật tình trạng vận hành xe ${vehicle.name}.`);
      vehiclesRequest.reload();
    } catch (requestError) {
      setError(
        getErrorMessage(requestError, "Không thể cập nhật tình trạng xe."),
      );
    } finally {
      setBusy(false);
    }
  }

  const requests = fleet
    ? [vehiclesRequest, catalogRequest, branchesRequest, assignmentsRequest]
    : [vehiclesRequest, catalogRequest, branchesRequest];
  const reload = () => requests.forEach((request) => request.reload());
  const loading = requests.some((request) => request.loading);
  const loadError = requests.find((request) => request.error)?.error;
  const sectionTitle = fleet ? "Tình trạng đội xe" : "Quản lý xe";
  return (
    <Page
      title={sectionTitle}
      description={
        fleet
          ? "Nhân viên cập nhật tình trạng khai thác trong phạm vi quyền vận hành."
          : "Quản lý xe, giá niêm yết và chi nhánh quản lý. Giá đặt xe được máy chủ kiểm tra lại."
      }
    >
      {confirmation}
      {notice ? <Notice toast={!fleet}>{notice}</Notice> : null}
      {error ? <Notice error toast={!fleet}>{error}</Notice> : null}
      {loading ? (
        <LoadingSkeleton rows={3} />
      ) : loadError ? (
        <div>
          <Notice error>{loadError}</Notice>
          <button className="btn-secondary" onClick={reload}>
            Thử tải lại
          </button>
        </div>
      ) : (
        <div
          className={
            fleet ? "" : "grid gap-6 xl:grid-cols-[minmax(0,1fr)_360px]"
          }
        >
          <div className="space-y-3">
            {!fleet ? (
              <div className="panel space-y-3" aria-label="Bộ lọc quản lý xe">
                <div className="grid gap-3 sm:grid-cols-3">
                  <Field label="Tìm kiếm xe">
                    <input
                      placeholder="Tên xe hoặc biển số..."
                      value={vehicleQuery}
                      onChange={(e) => setVehicleQuery(e.target.value)}
                    />
                  </Field>
                  <Field label="Chi nhánh hiện tại">
                    <select
                      value={branchFilter}
                      onChange={(e) => setBranchFilter(e.target.value)}
                    >
                      <option value="">Tất cả chi nhánh</option>
                      {allBranches.map((b) => (
                        <option key={b.id} value={b.id}>
                          {b.name}{b.status === "INACTIVE" ? " (Ngừng nhận đơn)" : ""}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="Trạng thái khai thác">
                    <select
                      value={statusFilter}
                      onChange={(e) => setStatusFilter(e.target.value)}
                    >
                      <option value="">Tất cả trạng thái</option>
                      <option value="ACTIVE">Đang khai thác</option>
                      <option value="MAINTENANCE">Bảo trì</option>
                      <option value="INACTIVE">Ngừng khai thác</option>
                    </select>
                  </Field>
                </div>
              </div>
            ) : null}

            {!fleet && filteredVehicles.length > 0 ? (
              <div className="flex items-center justify-between text-sm text-slate-600 px-1">
                <span>
                  Hiển thị {pagedVehicles.length}/{filteredVehicles.length} xe · Trang {activePage}/{totalPages}
                </span>
              </div>
            ) : null}

            {pagedVehicles.map((vehicle) => (
              <article
                className="panel space-y-4"
                key={vehicle.id}
              >
                <div className="min-w-0 break-words">
                  <h2 className="font-semibold">{vehicle.name}</h2>
                  <p className="mt-1 text-sm text-slate-500">
                    {vehicle.plate} · {vehicle.seats} chỗ ·{" "}
                    {vehicle.transmission === "AUTO" ? "Tự động" : "Số sàn"}
                  </p>
                  <p className="mt-1 text-sm text-slate-600">
                    {money(vehicle.dailyPrice)} / ngày · Chi nhánh hiện tại:{" "}
                    {(branchesRequest.data || []).find((branch) => branch.id === vehicle.currentBranchId)?.name || vehicle.currentBranchId}
                  </p>
                </div>
                <div className="flex flex-wrap items-center gap-2 border-t border-slate-100 pt-4" aria-label={`Thao tác xe ${vehicle.name}`}>
                  <Badge>{vehicleStatusLabels[vehicle.status] || "Chưa xác định"}</Badge>
                  {fleet ? (
                    <>
                      {vehicle.status === "INACTIVE" ? (
                        <span className="text-xs text-slate-500">
                          Chỉ quản trị viên được quản lý xe ngừng khai thác.
                        </span>
                      ) : (
                        <>
                          <button
                            className="btn-secondary"
                            disabled={busy || vehicle.status === "ACTIVE"}
                            onClick={() => changeFleetStatus(vehicle, "ACTIVE")}
                          >
                            Hoạt động
                          </button>
                          <button
                            className="btn-secondary"
                            disabled={busy || vehicle.status === "MAINTENANCE"}
                            onClick={() =>
                              changeFleetStatus(vehicle, "MAINTENANCE")
                            }
                          >
                            Bảo trì
                          </button>
                        </>
                      )}
                    </>
                  ) : (
                    <><button
                      className="btn-secondary"
                      disabled={busy}
                      onClick={() => editVehicle(vehicle)}
                    >
                      Chỉnh sửa
                    </button>
                    <button className="btn-secondary text-red-700" disabled={busy} onClick={() => deleteVehicle(vehicle)}>
                      Xóa xe
                    </button></>
                  )}
                </div>
              </article>
            ))}
            {!pagedVehicles.length ? (
              <EmptyState
                title={
                  fleet && !assignedBranchIds.size
                    ? "Chưa được gán chi nhánh"
                    : (vehicleQuery || branchFilter || statusFilter)
                      ? "Không có xe phù hợp"
                      : "Chưa có xe trong phạm vi"
                }
                description={
                  fleet && !assignedBranchIds.size
                    ? "Liên hệ quản trị viên để được phân công chi nhánh vận hành."
                    : (vehicleQuery || branchFilter || statusFilter)
                      ? "Thử thay đổi từ khóa hoặc bộ lọc."
                      : undefined
                }
              />
            ) : null}

            {!fleet && totalPages > 1 ? (
              <nav className="flex items-center justify-between border-t border-slate-100 pt-3" aria-label="Phân trang danh sách xe">
                <span className="text-sm text-slate-600">
                  Trang {activePage}/{totalPages} ({filteredVehicles.length} xe)
                </span>
                <div className="flex gap-2">
                  <button
                    type="button"
                    className="btn-secondary"
                    disabled={activePage <= 1}
                    onClick={() => setPage(activePage - 1)}
                  >
                    Trang trước
                  </button>
                  <button
                    type="button"
                    className="btn-secondary"
                    disabled={activePage >= totalPages}
                    onClick={() => setPage(activePage + 1)}
                  >
                    Trang sau
                  </button>
                </div>
              </nav>
            ) : null}
          </div>
          {!fleet ? (
            <div className="space-y-4">
              {form.id ? (
                <div className="flex border-b border-slate-200 gap-2 pb-2" role="tablist" aria-label="Nhóm biên tập xe">
                  <button
                    type="button"
                    role="tab"
                    aria-selected={activeTab === "info"}
                    className={`px-3 py-1.5 text-sm font-medium rounded-lg transition-colors ${
                      activeTab === "info"
                        ? "bg-slate-900 text-white"
                        : "text-slate-600 hover:bg-slate-100"
                    }`}
                    onClick={() => setActiveTab("info")}
                  >
                    Thông tin xe
                  </button>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={activeTab === "images"}
                    className={`px-3 py-1.5 text-sm font-medium rounded-lg transition-colors ${
                      activeTab === "images"
                        ? "bg-slate-900 text-white"
                        : "text-slate-600 hover:bg-slate-100"
                    }`}
                    onClick={() => setActiveTab("images")}
                  >
                    Hình ảnh
                  </button>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={activeTab === "amenities"}
                    className={`px-3 py-1.5 text-sm font-medium rounded-lg transition-colors ${
                      activeTab === "amenities"
                        ? "bg-slate-900 text-white"
                        : "text-slate-600 hover:bg-slate-100"
                    }`}
                    onClick={() => setActiveTab("amenities")}
                  >
                    Tiện nghi
                  </button>
                </div>
              ) : null}

              <p className="text-xs text-slate-500">
                Thông tin xe, hình ảnh và tiện nghi được lưu độc lập theo từng nhóm.
              </p>

              <div className={activeTab === "info" ? "space-y-4" : "hidden"}>
                <form className="panel h-fit space-y-4" onSubmit={saveVehicle}>
                  <h2 className="text-lg font-semibold">
                    {form.id ? `Cập nhật xe: ${form.name} (${form.plate})` : "Thêm xe"}
                  </h2>
                  <Field label="Tên xe">
                    <input
                      required
                      maxLength={120}
                      value={form.name}
                      onChange={(event) =>
                        setForm({ ...form, name: event.target.value })
                      }
                    />
                  </Field>
                  <Field label="Biển số">
                    <input
                      required
                      maxLength={20}
                      value={form.plate}
                      onChange={(event) =>
                        setForm({ ...form, plate: event.target.value })
                      }
                    />
                  </Field>
                  <div className="grid grid-cols-2 gap-3">
                    <Field label="Hãng xe">
                      <select required
                        value={form.brandId}
                        onChange={(event) =>
                          setForm({ ...form, brandId: event.target.value })
                        }
                      >
                        <option value="">Chọn hãng xe…</option>
                        {brands.map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.name}
                          </option>
                        ))}
                      </select>
                    </Field>
                    <Field label="Loại xe">
                      <select required
                        value={form.categoryId}
                        onChange={(event) =>
                          setForm({ ...form, categoryId: event.target.value })
                        }
                      >
                        <option value="">Chọn loại xe…</option>
                        {categories.map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.name}
                          </option>
                        ))}
                      </select>
                    </Field>
                  </div>
                  <Field
                    label={`Chi nhánh quản lý${form.id ? " (giữ nguyên khi cập nhật)" : ""}`}
                  >
                    <select
                      required
                      disabled={Boolean(form.id)}
                      value={form.homeBranchId}
                      onChange={(event) =>
                        setForm({ ...form, homeBranchId: event.target.value })
                      }
                    >
                      {branches.map((branch) => (
                        <option key={branch.id} value={branch.id}>
                          {branch.name}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <div className="grid grid-cols-2 gap-3">
                    <Field label="Số chỗ">
                      <input
                        type="number"
                        min="2"
                        max="9"
                        required
                        value={form.seats}
                        onChange={(event) =>
                          setForm({ ...form, seats: event.target.value })
                        }
                      />
                    </Field>
                    <Field label="Hộp số">
                      <select
                        value={form.transmission}
                        onChange={(event) =>
                          setForm({ ...form, transmission: event.target.value })
                        }
                      >
                        <option value="AUTO">Tự động</option>
                        <option value="MANUAL">Số sàn</option>
                      </select>
                    </Field>
                  </div>
                  <Field label="Giá/ngày (VND)">
                    <input
                      type="number"
                      min="1"
                      max="100000000"
                      required
                      value={form.dailyPrice}
                      onChange={(event) =>
                        setForm({ ...form, dailyPrice: event.target.value })
                      }
                    />
                  </Field>
                  <Field label="Trạng thái">
                    <select
                      value={form.status}
                      onChange={(event) =>
                        setForm({ ...form, status: event.target.value })
                      }
                    >
                      <option value="ACTIVE">Đang khai thác</option>
                      <option value="MAINTENANCE">Bảo trì</option>
                      <option value="INACTIVE">Ngừng khai thác</option>
                    </select>
                  </Field>
                  <Field label="Mô tả">
                    <textarea
                      maxLength={2000}
                      value={form.description || ""}
                      onChange={(event) =>
                        setForm({ ...form, description: event.target.value })
                      }
                    />
                  </Field>
                  <div className="flex flex-wrap gap-2">
                    <button className="btn" disabled={busy}>
                      {busy ? "Đang lưu..." : form.id ? "Lưu thay đổi" : "Tạo xe"}
                    </button>
                    {form.id ? (
                      <>
                        <button
                          type="button"
                          className="btn-secondary"
                          onClick={() => setActiveTab("images")}
                        >
                          Sang Hình ảnh →
                        </button>
                        <button
                          type="button"
                          className="btn-secondary"
                          onClick={resetVehicleForm}
                        >
                          Hủy
                        </button>
                      </>
                    ) : null}
                  </div>
                </form>
              </div>
              {form.id ? (
                <div className={activeTab === "images" ? "space-y-4" : "hidden"}>
                  <VehicleImageEditor key={`images-${form.id}`} vehicleId={form.id} />
                </div>
              ) : null}
              {form.id ? (
                <div className={activeTab === "amenities" ? "space-y-4" : "hidden"}>
                  <div className="panel h-fit">
                    <VehicleAmenityEditor
                      key={form.id}
                      vehicleId={form.id}
                      catalogAmenities={catalog.amenities || []}
                    />
                  </div>
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
      )}
    </Page>
  );
}

export function ApiBranchesPage() {
  const { confirm, confirmation } = useConfirmAction();
  const { user } = useAuth();
  const isAdmin = user?.role === "ADMIN";
  const branchesRequest = useApiData("/branches", isAdmin);
  const routesRequest = useApiData("/branch-routes", isAdmin);
  const [selected, setSelected] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const mutationPending = useRef(false);
  const [conflict, setConflict] = useState(false);
  const [routeConflict, setRouteConflict] = useState(false);
  const [routeSnapshot, setRouteSnapshot] = useState(null);
  const routeFormRef = useRef(null);
  const branches = useMemo(() => branchesRequest.data || [], [branchesRequest.data]);
  const routes = useMemo(() => routesRequest.data || [], [routesRequest.data]);
  const [routeForm, setRouteForm] = useState({
    pickupBranchId: "",
    returnBranchId: "",
    fee: "",
    enabled: false,
  });

  const routeInitialized = useRef(false);
  const [routeSyncPending, setRouteSyncPending] = useState(false);
  const routeSyncPendingRef = useRef(false);
  const routeSyncTargetRef = useRef(null);

  useEffect(() => {
    if (!routeSyncPendingRef.current || routesRequest.loading) return;
    if (routesRequest.error) {
      // Giữ nguyên cờ phục hồi và xung đột khi có lỗi mạng/500 để lần retry tiếp theo hoàn tất phục hồi
      return;
    }
    Promise.resolve().then(() => {
      const target = routeSyncTargetRef.current;
      if (!target) {
        routeSyncPendingRef.current = false;
        setRouteSyncPending(false);
        return;
      }
      if (
        routeForm.pickupBranchId !== target.pickupBranchId ||
        routeForm.returnBranchId !== target.returnBranchId
      ) {
        routeSyncPendingRef.current = false;
        routeSyncTargetRef.current = null;
        setRouteSyncPending(false);
        return;
      }
      const fresh = routes.find(
        (route) =>
          route.pickupBranchId === target.pickupBranchId &&
          route.returnBranchId === target.returnBranchId,
      );
      if (fresh) {
        setRouteForm((current) => {
          if (
            current.pickupBranchId !== target.pickupBranchId ||
            current.returnBranchId !== target.returnBranchId
          ) {
            return current;
          }
          return {
            ...current,
            fee: String(fresh.fee),
            enabled: Boolean(fresh.enabled),
          };
        });
        setRouteSnapshot({ ...fresh });
        setRouteConflict(false);
        routeSyncPendingRef.current = false;
        routeSyncTargetRef.current = null;
        setRouteSyncPending(false);
        setNotice(`Đã tải lại tuyến với phí ${fresh.fee.toLocaleString("vi-VN")} đ.`);
      } else {
        routeSyncPendingRef.current = false;
        routeSyncTargetRef.current = null;
        setRouteSyncPending(false);
        setRouteConflict(true);
        setRouteSnapshot(null);
        setError("Tuyến nhận/trả này không còn tồn tại trên hệ thống. Vui lòng chọn cặp chi nhánh khác hoặc tạo mới.");
      }
    });
  }, [routes, routesRequest.loading, routesRequest.error, routeForm.pickupBranchId, routeForm.returnBranchId]);

  useEffect(() => {
    if (
      routeInitialized.current ||
      routeForm.pickupBranchId ||
      !branches.length ||
      branchesRequest.loading ||
      routesRequest.loading
    )
      return;
    routeInitialized.current = true;
    const initialRoute = routes.find(
      (route) =>
        route.pickupBranchId === branches[0].id &&
        route.returnBranchId === branches[0].id,
    );
    setRouteForm((current) => ({
      ...current,
      pickupBranchId: branches[0].id,
      returnBranchId: branches[0].id,
      fee: initialRoute ? String(initialRoute.fee) : "",
      enabled: initialRoute?.enabled ?? false,
    }));
    setRouteSnapshot(initialRoute ? { ...initialRoute } : null);
    setRouteConflict(false);
  }, [
    branches,
    routes,
    routeForm.pickupBranchId,
    branchesRequest.loading,
    routesRequest.loading,
  ]);

  async function saveBranch(event) {
    event.preventDefault();
    if (!isAdmin || mutationPending.current || conflict) return;
    mutationPending.current = true;
    setError("");
    setNotice("");
    setBusy(true);
    const form = new FormData(event.currentTarget);
    const input = {
      name: String(form.get("name") || ""),
      address: String(form.get("address") || ""),
      version: selected?.version || 0,
    };
    try {
      if (selected) {
        await apiClient.put(
          `/admin/branches/${encodeURIComponent(selected.id)}`,
          input,
        );
        setNotice("Đã cập nhật chi nhánh.");
      } else {
        await apiClient.post("/admin/branches", input);
        setNotice("Đã tạo chi nhánh.");
      }
      setSelected(null);
      branchesRequest.reload();
      routesRequest.reload();
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Không thể lưu chi nhánh."));
      if (requestError.response?.status === 409) setConflict(true);
    } finally {
      mutationPending.current = false;
      setBusy(false);
    }
  }

  function editRoute(route) {
    routeSyncPendingRef.current = false;
    routeSyncTargetRef.current = null;
    setRouteSyncPending(false);
    setRouteForm({
      pickupBranchId: route.pickupBranchId,
      returnBranchId: route.returnBranchId,
      fee: String(route.fee),
      enabled: Boolean(route.enabled),
    });
    setRouteSnapshot({ ...route });
    setRouteConflict(false);
    setError("");
    setNotice(`Đang chỉnh sửa tuyến một chiều: ${
      branches.find((b) => b.id === route.pickupBranchId)?.name || route.pickupBranchId
    } → ${
      branches.find((b) => b.id === route.returnBranchId)?.name || route.returnBranchId
    }`);
    routeFormRef.current?.scrollIntoView?.({ behavior: "smooth", block: "nearest" });
    const feeInput = routeFormRef.current?.querySelector('input[type="number"]');
    feeInput?.focus();
  }

  function reloadFreshRoutes() {
    setError("");
    setNotice("Đang tải lại dữ liệu mới nhất của tuyến...");
    routeSyncPendingRef.current = true;
    setRouteSyncPending(true);
    routeSyncTargetRef.current = {
      pickupBranchId: routeForm.pickupBranchId,
      returnBranchId: routeForm.returnBranchId,
    };
    routesRequest.reload();
  }

  async function saveRoute(event) {
    event.preventDefault();
    if (!isAdmin || mutationPending.current || conflict || routeConflict || routeSyncPendingRef.current || routesRequest.loading) return;
    mutationPending.current = true;
    setError("");
    setNotice("");
    setBusy(true);
    const existingRoute = routes.find(
      (route) =>
        route.pickupBranchId === routeForm.pickupBranchId &&
        route.returnBranchId === routeForm.returnBranchId,
    );
    const expectedVersion = Number(routeSnapshot?.version ?? existingRoute?.version ?? 0);
    try {
      await apiClient.put("/admin/branch-routes", {
        pickupBranchId: routeForm.pickupBranchId,
        returnBranchId: routeForm.returnBranchId,
        fee: Number(routeForm.fee || 0),
        enabled: Boolean(routeForm.enabled),
        expectedVersion,
      });
      setNotice("Đã cập nhật tuyến nhận/trả.");
      routesRequest.reload();
      setRouteSnapshot(existingRoute ? { ...existingRoute, version: expectedVersion + 1, fee: Number(routeForm.fee || 0), enabled: Boolean(routeForm.enabled) } : null);
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Không thể lưu tuyến."));
      if (requestError.response?.status === 409) {
        setRouteConflict(true);
        setError("409: Tuyến nhận/trả đã bị thay đổi ở phiên khác. Tải lại bản mới trước khi lưu tiếp; bản sửa chưa được lưu.");
      }
    } finally {
      mutationPending.current = false;
      setBusy(false);
    }
  }

  async function toggleBranch(branch) {
    if (!isAdmin || mutationPending.current || conflict) return;
    if (branch.status === "ACTIVE" && !(await confirm(`Ngừng nhận đơn mới tại ${branch.name}? Đơn thuê cũ tiếp tục được xử lý theo quyền.`))) return;
    if (mutationPending.current) return;
    mutationPending.current = true;
    setBusy(true);
    setError("");
    setNotice("");
    const next = branch.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
    try {
      await changeBranchStatus(branch, next);
      setNotice(next === "ACTIVE" ? "Đã mở nhận đơn mới tại chi nhánh." : "Đã ngừng nhận đơn mới tại chi nhánh. Đơn cũ tiếp tục được xử lý theo quyền.");
      setSelected(null);
      branchesRequest.reload();
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Không thể đổi trạng thái chi nhánh."));
      if (requestError.response?.status === 409) setConflict(true);
    } finally {
      mutationPending.current = false;
      setBusy(false);
    }
  }

  function reloadFreshBranches() {
    if (mutationPending.current) return;
    setSelected(null);
    setConflict(false);
    setError("");
    setNotice("");
    branchesRequest.reload();
    routesRequest.reload();
  }

  async function deleteBranch(branch) {
    if (!isAdmin || mutationPending.current || conflict || !(await confirm(`Xóa chi nhánh “${branch.name}”? Chi nhánh còn xe, đơn thuê, tuyến hoặc nhân viên sẽ không được xóa. Thao tác không thể hoàn tác.`))) return;
    if (mutationPending.current) return;
    mutationPending.current = true;
    setBusy(true); setError(""); setNotice("");
    try {
      await apiClient.delete(`/admin/branches/${encodeURIComponent(branch.id)}`, { params: { expectedVersion: branch.version } });
      if (selected?.id === branch.id) setSelected(null);
      if ([routeForm.pickupBranchId, routeForm.returnBranchId].includes(branch.id)) {
        routeInitialized.current = false;
        setRouteForm({ pickupBranchId: "", returnBranchId: "", fee: "", enabled: false });
      }
      setNotice(`Đã xóa chi nhánh “${branch.name}”.`);
      branchesRequest.reload(); routesRequest.reload();
    } catch (failure) { setError(getErrorMessage(failure, "Không thể xóa chi nhánh.")); }
    finally { mutationPending.current = false; setBusy(false); }
  }

  function changeRoute(key, value) {
    const next = { ...routeForm, [key]: value };
    routeSyncPendingRef.current = false;
    routeSyncTargetRef.current = null;
    setRouteSyncPending(false);
    const savedRoute = routes.find(
      (route) =>
        route.pickupBranchId === next.pickupBranchId &&
        route.returnBranchId === next.returnBranchId,
    );
    setRouteForm({
      ...next,
      fee: savedRoute ? String(savedRoute.fee) : "",
      enabled: savedRoute?.enabled ?? false,
    });
    setRouteSnapshot(savedRoute ? { ...savedRoute } : null);
    setRouteConflict(false);
  }

  const loading = branchesRequest.loading || routesRequest.loading;
  const loadError = branchesRequest.error || routesRequest.error;
  if (!isAdmin) return <Page title="Chi nhánh & tuyến nhận/trả"><Notice error>Chỉ quản trị viên được quản lý chi nhánh.</Notice></Page>;
  return (
    <Page
      title="Chi nhánh & tuyến nhận/trả"
      description="Quản lý điểm giao nhận và phí tuyến. Không nhầm chi nhánh với địa điểm du lịch."
    >
      {confirmation}
      {notice ? <Notice toast>{notice}</Notice> : null}
      {error ? <Notice error toast>{error}</Notice> : null}
      <p className="mb-4 text-sm text-slate-600">Ngừng nhận đơn mới sẽ chặn báo giá và đặt xe mới. Đơn cũ tiếp tục theo quyền; xe, tuyến và phân công nhân viên được giữ nguyên.</p>
      {conflict ? <div className="mb-4"><Notice error>Chi nhánh đã thay đổi. Tải dữ liệu mới trước khi thử lại; bản sửa chưa được lưu.</Notice><button type="button" className="btn-secondary" disabled={busy} onClick={reloadFreshBranches}>Tải dữ liệu mới</button></div> : null}
      {loading ? (
        <LoadingSkeleton rows={3} />
      ) : loadError ? (
        <div>
          <Notice error>{loadError}</Notice>
          <button
            className="btn-secondary"
            onClick={() => {
              branchesRequest.reload();
              routesRequest.reload();
            }}
          >
            Thử tải lại
          </button>
        </div>
      ) : (
        <div className="grid gap-6 xl:grid-cols-2">
          <div className="space-y-4">
            <div className="space-y-3">
              {!branches.length ? <p>Chưa có chi nhánh.</p> : null}
              {branches.map((branch) => (
                <article
                  className="panel space-y-4"
                  key={branch.id}
                >
                  <div>
                    <h2 className="font-semibold">{branch.name}</h2>
                    <p className="mt-1 text-sm text-slate-500">
                      {branch.address}
                    </p>
                    <p className="mt-1 text-xs text-slate-400">
                      {branch.status === "ACTIVE" ? "Đang nhận đơn mới" : "Ngừng nhận đơn mới"}
                    </p>
                  </div>
                  <div className="flex flex-wrap gap-2"><button
                    className="btn-secondary"
                    disabled={busy || conflict}
                    onClick={() => setSelected(branch)}
                  >
                    Chỉnh sửa
                  </button>
                  <button type="button" className={branch.status === "ACTIVE" ? "btn-danger" : "btn-secondary"} disabled={busy || conflict} onClick={() => toggleBranch(branch)} aria-label={`${branch.status === "ACTIVE" ? "Ngừng" : "Mở"} nhận đơn mới: ${branch.name}`}>{busy ? "Đang xử lý..." : branch.status === "ACTIVE" ? "Ngừng nhận đơn mới" : "Mở nhận đơn mới"}</button>
                  <button type="button" className="btn-secondary text-red-700" disabled={busy || conflict} onClick={() => deleteBranch(branch)} aria-label={`Xóa chi nhánh ${branch.name}`}>Xóa chi nhánh</button></div>
                </article>
              ))}
            </div>
            <form className="panel space-y-4" onSubmit={saveBranch}>
              <h2 className="text-lg font-semibold">
                {selected ? "Sửa chi nhánh" : "Thêm chi nhánh"}
              </h2>
              <Field label="Tên chi nhánh">
                <input
                  key={`${selected?.id || "new"}-name`}
                  name="name"
                  disabled={busy}
                  required
                  maxLength={120}
                  defaultValue={selected?.name || ""}
                />
              </Field>
              <Field label="Địa chỉ">
                <input
                  key={`${selected?.id || "new"}-address`}
                  name="address"
                  disabled={busy}
                  required
                  maxLength={300}
                  defaultValue={selected?.address || ""}
                />
              </Field>
              <div className="flex gap-2">
                <button className="btn" disabled={busy || conflict}>
                  {busy
                    ? "Đang lưu..."
                    : selected
                      ? "Lưu thay đổi"
                      : "Tạo chi nhánh"}
                </button>
                {selected ? (
                  <button
                    type="button"
                    className="btn-secondary"
                    disabled={busy}
                    onClick={() => setSelected(null)}
                  >
                    Hủy
                  </button>
                ) : null}
              </div>
            </form>
          </div>
          <div className="space-y-4">
            <section className="panel space-y-4">
              <h2 className="text-lg font-semibold">
                Các tuyến đã cấu hình
              </h2>
              <p className="text-xs text-slate-500">
                Mỗi tuyến là một chiều (Nhận → Trả) độc lập. Thay đổi tuyến A → B không ảnh hưởng đến B → A.
              </p>
              {routes.length ? (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Tuyến một chiều (Nhận → Trả)</th>
                        <th>Phí</th>
                        <th>Trạng thái</th>
                        <th>Thao tác</th>
                      </tr>
                    </thead>
                    <tbody>
                      {routes.map((route) => {
                        const pickupBranch = branches.find((b) => b.id === route.pickupBranchId);
                        const returnBranch = branches.find((b) => b.id === route.returnBranchId);
                        const pickupInactive = pickupBranch?.status === "INACTIVE";
                        const returnInactive = returnBranch?.status === "INACTIVE";
                        const branchInactive = pickupInactive || returnInactive;
                        return (
                          <tr key={route.id || `${route.pickupBranchId}-${route.returnBranchId}`}>
                            <td>
                              <div className="font-medium">
                                <span>{pickupBranch?.name || route.pickupBranchId}</span>
                                {pickupInactive ? <span className="ml-1 text-xs text-amber-700 font-normal"> (Ngừng nhận đơn)</span> : null}
                                <span className="mx-2 text-slate-400">→</span>
                                <span>{returnBranch?.name || route.returnBranchId}</span>
                                {returnInactive ? <span className="ml-1 text-xs text-amber-700 font-normal"> (Ngừng nhận đơn)</span> : null}
                              </div>
                              <div className="text-xs text-slate-400 mt-0.5">
                                Tuyến đã cấu hình
                              </div>
                            </td>
                            <td>{money(route.fee)}</td>
                            <td>
                              <span className={route.enabled ? "text-emerald-700 font-medium" : "text-slate-500"}>
                                {route.enabled ? "Đang mở" : "Đã đóng"}
                              </span>
                              {branchInactive ? (
                                <p className="text-xs text-amber-700 mt-0.5">
                                  Tạm không nhận đơn mới do chi nhánh ngừng hoạt động
                                </p>
                              ) : null}
                            </td>
                            <td>
                              <button
                                type="button"
                                className="btn-secondary text-xs"
                                disabled={busy || conflict || routeConflict}
                                onClick={() => editRoute(route)}
                                aria-label={`Chỉnh sửa tuyến ${pickupBranch?.name || route.pickupBranchId} đến ${returnBranch?.name || route.returnBranchId}`}
                              >
                                Chỉnh sửa
                              </button>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="text-sm text-slate-500">Chưa có tuyến.</p>
              )}
            </section>
            <form ref={routeFormRef} className="panel space-y-4" onSubmit={saveRoute}>
              <h2 className="text-lg font-semibold">Cấu hình tuyến</h2>
              <p className="text-xs text-slate-500">
                Tuyến nhận/trả là một chiều riêng biệt. Kiểm tra đúng chiều nhận và trả trước khi lưu.
              </p>
              <Field label="Chi nhánh nhận">
                <select
                  required
                  value={routeForm.pickupBranchId}
                  onChange={(event) =>
                    changeRoute("pickupBranchId", event.target.value)
                  }
                >
                  {branches.map((branch) => (
                    <option key={branch.id} value={branch.id}>
                      {branch.name}{branch.status === "INACTIVE" ? " (Ngừng nhận đơn)" : ""}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Chi nhánh trả">
                <select
                  required
                  value={routeForm.returnBranchId}
                  onChange={(event) =>
                    changeRoute("returnBranchId", event.target.value)
                  }
                >
                  {branches.map((branch) => (
                    <option key={branch.id} value={branch.id}>
                      {branch.name}{branch.status === "INACTIVE" ? " (Ngừng nhận đơn)" : ""}
                    </option>
                  ))}
                </select>
              </Field>
              {(() => {
                const p = branches.find((b) => b.id === routeForm.pickupBranchId);
                const r = branches.find((b) => b.id === routeForm.returnBranchId);
                if (p?.status === "INACTIVE" || r?.status === "INACTIVE") {
                  return (
                    <div className="p-3 bg-amber-50 rounded-lg text-xs text-amber-800" role="note">
                      Lưu ý: {p?.status === "INACTIVE" ? `Chi nhánh nhận (${p.name})` : ""}
                      {p?.status === "INACTIVE" && r?.status === "INACTIVE" ? " và " : ""}
                      {r?.status === "INACTIVE" ? `Chi nhánh trả (${r.name})` : ""} đang ngừng nhận đơn mới. Cấu hình tuyến vẫn có thể lưu nhưng khách hàng chưa thể đặt chuyến qua tuyến này.
                    </div>
                  );
                }
                return null;
              })()}
              <Field label="Phí tuyến (VND)">
                <input
                  type="number"
                  required
                  min="0"
                  max="100000000"
                  value={routeForm.fee}
                  onChange={(event) =>
                    setRouteForm({ ...routeForm, fee: event.target.value })
                  }
                />
              </Field>
              <label className="flex gap-3 text-sm">
                <input
                  type="checkbox"
                  checked={routeForm.enabled}
                  onChange={(event) =>
                    setRouteForm({
                      ...routeForm,
                      enabled: event.target.checked,
                    })
                  }
                />{" "}
                Tuyến đang hoạt động
              </label>
              {routeConflict ? (
                <div>
                  <Notice error>Tuyến đã bị thay đổi ở phiên khác. Tải lại bản mới trước khi lưu tiếp.</Notice>
                  <button type="button" className="btn-secondary mt-2" onClick={reloadFreshRoutes}>
                    Tải lại dữ liệu tuyến
                  </button>
                </div>
              ) : null}
              <button className="btn" disabled={busy || conflict || routeConflict || routeSyncPending || routesRequest.loading || branches.length < 1}>
                {busy ? "Đang lưu..." : "Lưu tuyến"}
              </button>
            </form>
          </div>
        </div>
      )}
    </Page>
  );
}

export function ApiReviewsAdminPage() {
  const { confirm, confirmation } = useConfirmAction();
  const reviewsRequest = useApiData("/admin/reviews");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busyId, setBusyId] = useState("");
  const reviews = Array.isArray(reviewsRequest.data) ? reviewsRequest.data : [];
  async function moderate(review) {
    if (busyId) return;
    if (!review.hidden && !(await confirm("Ẩn đánh giá này khỏi phần đánh giá công khai? Bạn có thể hiển thị lại sau."))) return;
    setError("");
    setNotice("");
    setBusyId(review.id);
    try {
      await apiClient.patch(`/admin/reviews/${encodeURIComponent(review.id)}`, {
        hidden: !review.hidden,
        version: review.version,
      });
      setNotice(review.hidden ? "Đã hiển thị đánh giá." : "Đã ẩn đánh giá.");
      reviewsRequest.reload();
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Không thể kiểm duyệt đánh giá."));
      if (requestError.response?.status === 409) {
        reviewsRequest.reload();
      }
    } finally {
      setBusyId("");
    }
  }
  return (
    <Page
      title="Kiểm duyệt đánh giá"
      description="Ẩn hoặc hiển thị đánh giá; thay đổi được ghi nhận phía máy chủ."
    >
      {confirmation}
      {notice ? <Notice toast>{notice}</Notice> : null}
      {error ? <Notice error toast>{error}</Notice> : null}
      {reviewsRequest.loading ? (
        <LoadingSkeleton rows={3} />
      ) : reviewsRequest.error ? (
        <div>
          <Notice error>{reviewsRequest.error}</Notice>
          <button className="btn-secondary" onClick={reviewsRequest.reload}>
            Thử tải lại
          </button>
        </div>
      ) : reviews.length ? (
        <div className="space-y-3">
          {reviews.map((review) => (
            <article className="panel" key={review.id}>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <p className="font-semibold">
                    {review.rating}/5 sao · Đơn{" "}
                    {review.booking_id || review.bookingId}
                  </p>
                  <p className="mt-2 leading-6 text-slate-700">
                    {review.content}
                  </p>
                  <p className="mt-2 text-xs text-slate-500">
                    {review.hidden ? "Đang ẩn" : "Đang công khai"}
                  </p>
                </div>
                <button
                  className={review.hidden ? "btn-secondary" : "btn-danger"}
                  disabled={busyId === review.id}
                  onClick={() => moderate(review)}
                >
                  {review.hidden ? "Hiển thị" : "Ẩn đánh giá"}
                </button>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <EmptyState
          title="Chưa có đánh giá"
          description="Đánh giá mới từ đơn đã hoàn tất sẽ xuất hiện tại đây."
        />
      )}
    </Page>
  );
}
