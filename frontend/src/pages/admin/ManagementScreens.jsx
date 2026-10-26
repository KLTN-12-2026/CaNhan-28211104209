import { vehicleStatusLabels } from "../../utils/uiLabels";
import { useState } from "react";
import { Link } from "react-router-dom";
import { useDemo } from "../../context/DemoContext";
import { useAuth } from "../../context/AuthContext";
import { currentStatus } from "../../demo/model";
import { Page, Field, Notice, money } from "../../components/common/PageKit";
import Modal from "../../components/common/Modal";
import Badge from "../../components/common/Badge";
import EmptyState from "../../components/common/EmptyState";

const definitions = {
  accounts: {
    title: "Tài khoản & nhân viên",
    description: "Quản lý tài khoản User/Staff trong bản demo.",
    fields: [
      { key: "fullName", label: "Họ và tên" },
      { key: "email", label: "Email", type: "email" },
      { key: "role", label: "Vai trò", options: ["USER", "STAFF"] },
      { key: "status", label: "Trạng thái", options: ["ACTIVE", "LOCKED"] },
    ],
    columns: [
      ["fullName", "Họ và tên"],
      ["email", "Email"],
      ["role", "Vai trò"],
      ["status", "Trạng thái"],
    ],
  },
  vehicles: {
    title: "Quản lý xe",
    description: "Danh mục xe, thông số và trạng thái khai thác.",
    fields: [
      { key: "name", label: "Tên xe" },
      { key: "plate", label: "Biển số / mã mẫu" },
      { key: "brand", label: "Hãng xe" },
      { key: "category", label: "Loại xe" },
      { key: "seats", label: "Số chỗ", type: "number", min: 2, max: 9 },
      { key: "price", label: "Giá mẫu / 24 giờ (VND)", type: "number", min: 1 },
      { key: "transmission", label: "Hộp số", options: ["Tự động", "Số sàn"] },
      {
        key: "fuel",
        label: "Nhiên liệu",
        options: ["Xăng", "Dầu", "Điện", "Hybrid"],
      },
      { key: "amenitiesText", label: "Tiện nghi (phân tách dấu phẩy)" },
      { key: "description", label: "Mô tả", type: "textarea" },
      {
        key: "status",
        label: "Trạng thái",
        options: ["ACTIVE", "MAINTENANCE", "INACTIVE"],
      },
    ],
    columns: [
      ["name", "Xe"],
      ["plate", "Mã mẫu"],
      ["seats", "Số chỗ"],
      ["price", "Giá / 24 giờ"],
      ["status", "Trạng thái"],
    ],
  },
  catalog: {
    title: "Danh mục & chi nhánh",
    description:
      "Hãng xe, loại xe, tiện nghi và chi nhánh giao nhận. Demo vận hành một chi nhánh.",
    fields: [
      {
        key: "type",
        label: "Nhóm danh mục",
        options: ["Hãng xe", "Loại xe", "Tiện nghi", "Chi nhánh"],
      },
      { key: "name", label: "Tên danh mục" },
      { key: "status", label: "Trạng thái", options: ["ACTIVE", "INACTIVE"] },
    ],
    columns: [
      ["type", "Nhóm"],
      ["name", "Tên"],
      ["status", "Trạng thái"],
    ],
  },
  locations: {
    title: "Điểm du lịch",
    description:
      "Catalog phục vụ lập hành trình; tách biệt với chi nhánh nhận/trả xe.",
    fields: [
      { key: "name", label: "Tên địa điểm" },
      {
        key: "category",
        label: "Phân loại",
        options: ["Văn hóa", "Thiên nhiên", "Ẩm thực", "Giải trí"],
      },
      {
        key: "lat",
        label: "Vĩ độ",
        type: "number",
        min: -90,
        max: 90,
        step: "any",
      },
      {
        key: "lng",
        label: "Kinh độ",
        type: "number",
        min: -180,
        max: 180,
        step: "any",
      },
      { key: "status", label: "Trạng thái", options: ["ACTIVE", "INACTIVE"] },
    ],
    columns: [
      ["name", "Địa điểm"],
      ["category", "Phân loại"],
      ["lat", "Vĩ độ"],
      ["lng", "Kinh độ"],
      ["status", "Trạng thái"],
    ],
  },
};

export function ManagementScreen({ collection }) {
  const config = definitions[collection];
  const { data, update } = useDemo();
  const { user } = useAuth();
  const [q, setQ] = useState("");
  const [draft, setDraft] = useState(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const rows = data[collection].filter((row) =>
    config.columns.some(([key]) =>
      String(row[key] || "")
        .toLocaleLowerCase("vi")
        .includes(q.toLocaleLowerCase("vi")),
    ),
  );
  function edit(row) {
    const defaults = Object.fromEntries(
      config.fields.map((field) => [field.key, field.options?.[0] || ""]),
    );
    setDraft(
      row
        ? { ...row, amenitiesText: row.amenities?.join(", ") || "" }
        : {
            ...defaults,
            ...(collection === "accounts" ? { role: "STAFF" } : {}),
            ...(collection === "vehicles"
              ? { seats: 5, price: 650000, color: "#0f766e" }
              : {}),
          },
    );
    setError("");
  }
  function save(event) {
    event.preventDefault();
    setError("");
    try {
      const record = {
        ...draft,
        id: draft.id || `${collection}-${Date.now()}`,
      };
      config.fields.forEach((field) => {
        if (field.type === "number")
          record[field.key] = Number(record[field.key]);
        else if (typeof record[field.key] === "string")
          record[field.key] = record[field.key].trim();
        if (
          !record[field.key] &&
          !["amenitiesText", "description"].includes(field.key) &&
          field.type !== "number"
        )
          throw new Error(
            "Vui lòng nhập đầy đủ thông tin, không chỉ khoảng trắng.",
          );
      });
      if (collection === "accounts") record.email = record.email.toLowerCase();
      if (collection === "vehicles") {
        record.plate = record.plate.toUpperCase();
        record.amenities = record.amenitiesText
          .split(",")
          .map((a) => a.trim())
          .filter(Boolean);
        delete record.amenitiesText;
      }
      update((state) => {
        if (user.role !== "ADMIN")
          throw new Error("Chỉ Admin được quản lý danh mục.");
        if (
          collection === "accounts" &&
          (record.role === "ADMIN" ||
            state.accounts.find((a) => a.id === record.id)?.role === "ADMIN")
        )
          throw new Error("Màn hình này chỉ quản lý User và Staff.");
        if (collection === "accounts" && !draft.id && record.role !== "STAFF")
          throw new Error(
            "Admin tạo tài khoản Staff; khách hàng đăng ký qua luồng riêng.",
          );
        const uniqueKey =
          collection === "accounts"
            ? "email"
            : collection === "vehicles"
              ? "plate"
              : "name";
        if (
          state[collection].some(
            (r) =>
              r.id !== record.id &&
              String(r[uniqueKey]).toLowerCase() ===
                String(record[uniqueKey]).toLowerCase() &&
              (collection !== "catalog" || r.type === record.type),
          )
        )
          throw new Error("Thông tin này đã tồn tại trong dữ liệu mẫu.");
        if (
          collection === "catalog" &&
          record.type === "Chi nhánh" &&
          state.catalog.some(
            (r) => r.type === "Chi nhánh" && r.id !== record.id,
          )
        )
          throw new Error("Demo chỉ sử dụng một chi nhánh.");
        if (
          collection === "catalog" &&
          state.catalog.find((item) => item.id === record.id)?.type ===
            "Chi nhánh" &&
          record.type !== "Chi nhánh"
        )
          throw new Error("Không đổi nhóm chi nhánh demo.");
        if (
          collection === "catalog" &&
          record.type === "Chi nhánh" &&
          record.status === "INACTIVE"
        )
          throw new Error(
            "Chi nhánh duy nhất cần hoạt động để thử luồng demo.",
          );
        return {
          ...state,
          [collection]: draft.id
            ? state[collection].map((r) => (r.id === draft.id ? record : r))
            : [...state[collection], record],
        };
      });
      setDraft(null);
      setMessage("Đã lưu thay đổi vào dữ liệu demo.");
    } catch (err) {
      setError(err.message);
    }
  }
  return (
    <Page
      title={config.title}
      description={config.description}
      actions={
        <button className="btn" onClick={() => edit(null)}>
          {collection === "accounts" ? "Tạo Staff mẫu" : "Thêm mới"}
        </button>
      }
    >
      {message ? <Notice>{message}</Notice> : null}
      {collection === "accounts" ? (
        <Notice>
          Thay đổi tài khoản chỉ minh họa màn hình quản trị; không tạo thông tin
          đăng nhập hoặc thu hồi phiên thật. Bộ chọn vai trò demo luôn độc lập.
        </Notice>
      ) : null}
      <div className="mb-5 max-w-md">
        <Field label="Tìm kiếm">
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Nhập từ khóa..."
          />
        </Field>
      </div>
      {rows.length ? (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                {config.columns.map(([key, title]) => (
                  <th key={key}>{title}</th>
                ))}
                <th>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id}>
                  {config.columns.map(([key]) => (
                    <td key={key}>
                      {key === "price" ? (
                        money(row[key])
                      ) : key === "status" ? (
                        <Badge
                          tone={
                            ["LOCKED", "INACTIVE", "MAINTENANCE"].includes(
                              row[key],
                            )
                              ? "warning"
                              : "brand"
                          }
                        >
                          {row[key] || "ACTIVE"}
                        </Badge>
                      ) : (
                        row[key]
                      )}
                    </td>
                  ))}
                  <td>
                    {row.role === "ADMIN" ? (
                      <span className="text-xs text-slate-500">Chỉ xem</span>
                    ) : (
                      <button
                        className="btn-secondary"
                        onClick={() => edit(row)}
                      >
                        Chỉnh sửa
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <EmptyState
          title="Không có kết quả"
          description="Thử từ khóa khác hoặc thêm dữ liệu mẫu."
        />
      )}
      <Modal
        open={Boolean(draft)}
        title={draft?.id ? "Chỉnh sửa dữ liệu mẫu" : "Thêm dữ liệu mẫu"}
        onClose={() => setDraft(null)}
      >
        <form className="space-y-4" onSubmit={save}>
          {error ? <Notice error>{error}</Notice> : null}
          {config.fields.map((field) => (
            <Field key={field.key} label={field.label}>
              {field.options ? (
                <select
                  value={draft?.[field.key] || field.options[0]}
                  disabled={
                    collection === "accounts" &&
                    field.key === "role" &&
                    !draft?.id
                  }
                  onChange={(e) =>
                    setDraft({ ...draft, [field.key]: e.target.value })
                  }
                >
                  {field.options.map((value) => (
                    <option key={value}>{value}</option>
                  ))}
                </select>
              ) : field.type === "textarea" ? (
                <textarea
                  maxLength={1000}
                  rows={3}
                  value={draft?.[field.key] || ""}
                  onChange={(e) =>
                    setDraft({ ...draft, [field.key]: e.target.value })
                  }
                />
              ) : (
                <input
                  required={field.key !== "amenitiesText"}
                  maxLength={200}
                  type={field.type || "text"}
                  min={field.min}
                  max={field.max}
                  step={field.step || (field.type === "number" ? 1 : undefined)}
                  value={draft?.[field.key] ?? ""}
                  onChange={(e) =>
                    setDraft({ ...draft, [field.key]: e.target.value })
                  }
                />
              )}
            </Field>
          ))}
          <div className="flex justify-end gap-3">
            <button
              type="button"
              className="btn-secondary"
              onClick={() => setDraft(null)}
            >
              Hủy
            </button>
            <button className="btn">Lưu dữ liệu mẫu</button>
          </div>
        </form>
      </Modal>
    </Page>
  );
}

export function FleetScreen() {
  const { data, update } = useDemo();
  const { user } = useAuth();
  const [draft, setDraft] = useState(null),
    [reason, setReason] = useState(""),
    [message, setMessage] = useState("");
  function save(e) {
    e.preventDefault();
    if (!reason.trim()) return;
    update((state) => {
      if (user.role !== "STAFF")
        throw new Error("Chỉ nhân viên cập nhật vận hành.");
      return {
        ...state,
        vehicles: state.vehicles.map((v) =>
          v.id === draft.id
            ? { ...v, status: draft.status, operationNote: reason.trim() }
            : v,
        ),
      };
    });
    setDraft(null);
    setMessage("Đã cập nhật tình trạng vận hành mẫu.");
  }
  return (
    <Page
      title="Trạng thái đội xe"
      description="Theo dõi khai thác và ghi chú tình trạng. Lịch thuê được kiểm tra riêng theo từng khoảng thời gian."
    >
      {message ? <Notice>{message}</Notice> : null}
      <div className="grid gap-4 sm:grid-cols-2">
        {data.vehicles.map((v) => (
          <article className="panel" key={v.id}>
            <div className="flex flex-wrap justify-between gap-3">
              <h2 className="font-bold">{v.name}</h2>
              <Badge tone={v.status === "ACTIVE" ? "brand" : "warning"}>
                {vehicleStatusLabels[v.status] || "Chưa xác định"}
              </Badge>
            </div>
            <p className="mt-2 text-sm text-slate-500">{v.plate}</p>
            {v.operationNote ? (
              <p className="mt-3 text-sm">{v.operationNote}</p>
            ) : null}
            <button
              className="btn-secondary mt-4"
              disabled={v.status === "INACTIVE"}
              onClick={() => {
                setDraft({ ...v });
                setReason("");
              }}
            >
              Cập nhật vận hành
            </button>
            {v.status === "INACTIVE" ? (
              <p className="mt-2 text-xs text-slate-500">
                Xe ngừng khai thác do quản trị quản lý.
              </p>
            ) : null}
          </article>
        ))}
      </div>
      <Modal
        open={Boolean(draft)}
        title="Ghi nhận tình trạng xe"
        onClose={() => setDraft(null)}
      >
        <form className="space-y-4" onSubmit={save}>
          <Field label="Tình trạng">
            <select
              value={draft?.status || "ACTIVE"}
              onChange={(e) => setDraft({ ...draft, status: e.target.value })}
            >
              <option value="ACTIVE">Đang khai thác</option>
              <option value="MAINTENANCE">Cần bảo trì</option>
            </select>
          </Field>
          <Field label="Lý do / ghi chú">
            <textarea
              required
              maxLength={1000}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </Field>
          <button className="btn">Lưu tình trạng mẫu</button>
        </form>
      </Modal>
    </Page>
  );
}

export function ReviewsScreen() {
  const { data, update } = useDemo();
  const [filter, setFilter] = useState("all");
  const rows = data.reviews.filter(
    (r) => filter === "all" || (filter === "hidden" ? r.hidden : !r.hidden),
  );
  return (
    <Page
      title="Kiểm duyệt đánh giá"
      description="Ẩn hoặc hiển thị đánh giá mẫu; giữ lại nội dung và lịch sử thao tác."
    >
      <div className="mb-5 max-w-xs">
        <Field label="Trạng thái hiển thị">
          <select value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="all">Tất cả</option>
            <option value="visible">Công khai</option>
            <option value="hidden">Đã ẩn</option>
          </select>
        </Field>
      </div>
      {rows.length ? (
        <div className="space-y-4">
          {rows.map((r) => (
            <article className="panel" key={r.id}>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <strong>
                  {r.rating}/5 ★ · {r.bookingId}
                </strong>
                <Badge>{r.hidden ? "Đã ẩn" : "Công khai"}</Badge>
              </div>
              <p className="my-4 text-sm">{r.content}</p>
              <button
                className="btn-secondary"
                onClick={() =>
                  update((state) => ({
                    ...state,
                    reviews: state.reviews.map((review) =>
                      review.id === r.id
                        ? {
                            ...review,
                            hidden: !review.hidden,
                            moderation: [
                              ...(review.moderation || []),
                              {
                                hidden: !review.hidden,
                                at: new Date().toISOString(),
                                actor: "Admin demo",
                              },
                            ],
                          }
                        : review,
                    ),
                  }))
                }
              >
                {r.hidden ? "Hiển thị đánh giá" : "Ẩn đánh giá"}
              </button>
            </article>
          ))}
        </div>
      ) : (
        <EmptyState
          title="Chưa có đánh giá phù hợp"
          description="Đổi sang User, mở đơn đã hoàn tất và gửi đánh giá để thử luồng kiểm duyệt."
        />
      )}
    </Page>
  );
}

export function DashboardScreen() {
  const { data } = useDemo();
  return (
    <Page
      title="Tổng quan quản trị"
      description="Ảnh chụp vận hành từ dữ liệu demo trong phiên này."
    >
      <div className="mb-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {[
          ["Tổng đơn thuê", data.bookings.length],
          [
            "Chờ xác nhận",
            data.bookings.filter((b) => currentStatus(b) === "PENDING").length,
          ],
          [
            "Xe đang khai thác",
            data.vehicles.filter((v) => v.status === "ACTIVE").length,
          ],
          ["Đánh giá công khai", data.reviews.filter((r) => !r.hidden).length],
        ].map(([label, count]) => (
          <div key={label} className="panel">
            <p className="text-sm text-slate-500">{label}</p>
            <p className="mt-3 text-3xl font-bold text-teal-800">{count}</p>
          </div>
        ))}
      </div>
      <div className="grid gap-6 md:grid-cols-2">
        <section className="panel">
          <h2 className="mb-4 text-lg font-semibold">Công việc quản trị</h2>
          <div className="flex flex-wrap gap-3">
            <Link className="btn-secondary" to="/admin/bookings">
              Tra cứu đơn
            </Link>
            <Link className="btn-secondary" to="/admin/vehicles">
              Quản lý xe
            </Link>
            <Link className="btn-secondary" to="/admin/reviews">
              Kiểm duyệt
            </Link>
          </div>
        </section>
        <section className="panel">
          <h2 className="mb-4 text-lg font-semibold">Tích hợp</h2>
          <Notice>Gemini / Map: chưa kết nối trong bản demo.</Notice>
          <p className="text-sm text-slate-500">
            Không hiển thị số liệu quota hoặc trạng thái dịch vụ giả.
          </p>
        </section>
      </div>
    </Page>
  );
}
