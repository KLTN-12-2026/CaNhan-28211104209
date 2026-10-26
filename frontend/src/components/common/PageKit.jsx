import { formatVietnamDate } from '../../utils/calendarDate';
import AdminAreaContext from "../../context/AdminAreaContext";
import { Link } from "react-router-dom";
import { cloneElement, useId, useState, useContext, useEffect } from "react";
import { useDemo } from "../../context/DemoContext";
import { DEMO_MODE } from "../../demo/config";
import EmptyState from "./EmptyState";
import LoadingSkeleton from "./LoadingSkeleton";
import Badge from "./Badge";
import { statusLabels } from "../../demo/model";
export const money = (value) =>
  new Intl.NumberFormat("vi-VN", {
    style: "currency",
    currency: "VND",
    maximumFractionDigits: 0,
  }).format(value);
export const dateTime = value => formatVietnamDate(value, { withTime: true });
export const localInput = (days = 3) =>
  new Date(Date.now() + days * 86400000 + 7 * 3600000)
    .toISOString()
    .slice(0, 16);
export const toUTC = (value) => new Date(`${value.length === 10 ? value + 'T00:00' : value}:00+07:00`).toISOString();
export const dateOnly = formatVietnamDate;
export const bookingDate = (booking, value) => booking.policyVersion === 'calendar-2026-10-17' ? dateOnly(value) : dateTime(value);
export function Page({ title, description, children, actions }) {
  const isAdmin = useContext(AdminAreaContext);
  return (
    <div className="page-shell">
      <div className="mb-7 flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="eyebrow">{isAdmin ? `Quản trị / ${title}` : "KDRIVE / HÀNH TRÌNH CỦA BẠN"}</p>
          <h1 className="mt-2 text-2xl font-bold tracking-tight sm:text-3xl">
            {title}
          </h1>
          {description ? (
            <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-600">
              {description}
            </p>
          ) : null}
        </div>
        {actions}
      </div>
      {children}
    </div>
  );
}
export function Notice({ children, error = false, toast = false }) {
  const [dismissed, setDismissed] = useState(null);
  const toastId = useId();
  useEffect(() => {
    if (!toast) return undefined;
    const replacePrevious = event => { if (event.detail !== toastId) setDismissed(children); };
    window.addEventListener("kdrive:toast", replacePrevious);
    window.dispatchEvent(new window.CustomEvent("kdrive:toast", { detail: toastId }));
    return () => window.removeEventListener("kdrive:toast", replacePrevious);
  }, [toast, toastId, children]);
  if (toast && dismissed === children) return null;
  return (
    <div
      role={error ? "alert" : "status"}
      className={`notice ${error ? "notice-error" : ""} ${toast ? "admin-toast" : ""}`}
    >
      {children}
      {toast && <button type="button" onClick={() => setDismissed(children)} aria-label="Đóng thông báo">×</button>}
    </div>
  );
}
export function Field({ label, children, hint }) {
  const id = useId();
  return (
    <label className="field">
      <span id={id}>{label}</span>
      {cloneElement(children, {
        "aria-labelledby": id,
        "aria-describedby": hint ? `${id}-hint` : undefined,
      })}
      {hint ? (
        <small id={`${id}-hint`} className="font-normal text-slate-500">
          {hint}
        </small>
      ) : null}
    </label>
  );
}
export function Status({ status }) {
  return (
    <Badge
      tone={{ PENDING: "warning", CONFIRMED: "info", IN_PROGRESS: "rental", RETURNED: "returned", COMPLETED: "success", CANCELLED: "danger", REJECTED: "danger", EXPIRED: "danger" }[status] || "neutral"}
    >
      {statusLabels[status] || "Chưa xác định"}
    </Badge>
  );
}
export function Missing({
  title = "Không tìm thấy dữ liệu",
  back = "/vehicles",
}) {
  return (
    <EmptyState
      title={title}
      description="Dữ liệu không tồn tại hoặc không thuộc quyền truy cập của bạn."
      action={
        <Link className="btn-secondary" to={back}>
          Quay lại danh sách
        </Link>
      }
    />
  );
}
export function DataGate({ children }) {
  const { scenario, setScenario } = useDemo();
  if (!DEMO_MODE)
    return (
      <Page title="Chức năng đang chờ kết nối">
        <Notice>
          Tính năng này chưa được cung cấp. Vui lòng quay lại sau.
        </Notice>
      </Page>
    );
  if (scenario === "loading")
    return (
      <Page title="Đang tải dữ liệu mẫu">
        <LoadingSkeleton />
        <button
          className="btn-secondary mt-4"
          onClick={() => setScenario("ready")}
        >
          Kết thúc mô phỏng tải
        </button>
      </Page>
    );
  if (scenario === "error")
    return (
      <Page title="Không thể tải dữ liệu">
        <Notice error>
          Lỗi kết nối mô phỏng. Dữ liệu mẫu đã lưu vẫn được giữ; biểu mẫu chưa
          lưu sẽ được đặt lại.
        </Notice>
        <button className="btn mt-4" onClick={() => setScenario("ready")}>
          Thử lại
        </button>
      </Page>
    );
  if (scenario === "empty")
    return (
      <Page title="Chưa có kết quả">
        <EmptyState
          description="Trạng thái rỗng để kiểm tra giao diện demo."
          action={
            <button className="btn" onClick={() => setScenario("ready")}>
              Xem dữ liệu mẫu
            </button>
          }
        />
      </Page>
    );
  return children;
}
