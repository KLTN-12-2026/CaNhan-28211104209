import { useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { useDemo } from "../../context/DemoContext";
import { useAuth } from "../../context/AuthContext";
import useNow from "../../hooks/useNow";
import {
  canCancel,
  canReadBooking,
  currentStatus,
  mainSteps,
  statusLabels,
} from "../../demo/model";
import {
  Page,
  Field,
  Notice,
  Missing,
  Status,
  money,
  dateTime,
} from "../../components/common/PageKit";
import EmptyState from "../../components/common/EmptyState";
import Modal from "../../components/common/Modal";

export function BookingListScreen({ area = "user", mode = "all" }) {
  const { data } = useDemo();
  const { user } = useAuth();
  const now = useNow();
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState(
    mode === "handover" ? "CONFIRMED" : mode === "return" ? "IN_PROGRESS" : "",
  );
  const bookings = data.bookings.filter(
    (b) =>
      canReadBooking(b, user) &&
      (!filter || currentStatus(b, now) === filter) &&
      `${b.id} ${b.vehicleName} ${b.customer}`
        .toLocaleLowerCase("vi")
        .includes(q.toLocaleLowerCase("vi")),
  );
  const title =
    area === "user"
      ? "Đơn thuê của tôi"
      : mode === "handover"
        ? "Bàn giao xe"
        : mode === "return"
          ? "Nhận trả xe"
          : area === "admin"
            ? "Tra cứu đơn thuê"
            : "Hàng đợi & đơn thuê";
  return (
    <Page
      title={title}
      description={
        area === "user"
          ? "Theo dõi chuyến đi, xem hồ sơ và chia sẻ trải nghiệm sau khi thuê."
          : "Tra cứu trạng thái và mở từng đơn để xem các thao tác phù hợp."
      }
      actions={
        area === "user" ? (
          <Link className="btn" to="/vehicles">
            Tìm xe cho chuyến mới
          </Link>
        ) : null
      }
    >
      <div className="mb-6 grid gap-4 sm:grid-cols-2">
        <Field label="Tìm mã đơn, xe hoặc khách hàng">
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="KD-1001, Toyota..."
          />
        </Field>
        <Field label="Trạng thái đơn">
          <select value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="">Tất cả trạng thái</option>
            {Object.entries(statusLabels).map(([key, label]) => (
              <option value={key} key={key}>
                {label}
              </option>
            ))}
          </select>
        </Field>
      </div>
      {bookings.length ? (
        <div className="space-y-4">
          {bookings.map((b) => (
            <article
              className="panel flex flex-wrap items-center justify-between gap-5"
              key={b.id}
            >
              <div>
                <div className="mb-2 flex flex-wrap items-center gap-3">
                  <span className="text-xs font-semibold text-slate-500">
                    {b.id}
                  </span>
                  <Status status={currentStatus(b, now)} />
                </div>
                <h2 className="text-lg font-bold">{b.vehicleName}</h2>
                <p className="mt-2 text-sm text-slate-600">
                  {dateTime(b.startAt)} → {dateTime(b.endAt)}
                </p>
                {area !== "user" ? (
                  <p className="mt-2 text-sm text-slate-500">{b.customer}</p>
                ) : null}
              </div>
              <div className="flex flex-wrap items-center gap-5">
                <div>
                  <strong className="text-teal-800">{money(b.total)}</strong>
                  <p className="text-xs text-slate-500">Tiền thuê cơ bản mẫu</p>
                </div>
                <Link
                  className="btn-secondary"
                  to={
                    mode === "handover" && currentStatus(b, now) === "CONFIRMED"
                      ? `/staff/handover/${b.id}`
                      : mode === "return" &&
                          currentStatus(b, now) === "IN_PROGRESS"
                        ? `/staff/return/${b.id}`
                        : `/${area}/bookings/${b.id}`
                  }
                >
                  Xem chi tiết →
                </Link>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <EmptyState
          title="Không có đơn phù hợp"
          description="Thử đổi từ khóa hoặc trạng thái lọc."
          action={
            <button
              className="btn-secondary"
              onClick={() => {
                setQ("");
                setFilter("");
              }}
            >
              Xóa bộ lọc
            </button>
          }
        />
      )}
    </Page>
  );
}

export function BookingDetailScreen({ area = "user" }) {
  const { bookingId } = useParams();
  const location = useLocation();
  const { data, transition } = useDemo();
  const { user } = useAuth();
  const now = useNow();
  const [action, setAction] = useState(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const booking = data.bookings.find(
    (b) => b.id === bookingId && canReadBooking(b, user),
  );
  const base = `/${area}/bookings`;
  if (!booking)
    return (
      <Page title="Chi tiết đơn">
        <Missing back={area === "staff" ? "/staff/queue" : base} />
      </Page>
    );
  const status = currentStatus(booking, now);
  const stepIndex = mainSteps.indexOf(status);
  const remaining = Math.max(
    0,
    Math.ceil((Date.parse(booking.expiresAt) - now) / 60000),
  );
  function openAction(next) {
    setError("");
    setReason("");
    setAction({ next, version: booking.version });
  }
  function apply(event) {
    event.preventDefault();
    try {
      transition(user, booking.id, action.next, {
        version: action.version,
        reason,
      });
      setMessage(`Đã cập nhật đơn mẫu: ${statusLabels[action.next]}.`);
      setAction(null);
    } catch (err) {
      setError(err.message);
    }
  }
  return (
    <Page
      title={`Đơn ${booking.id}`}
      description={booking.vehicleName}
      actions={
        <Link
          className="btn-secondary"
          to={area === "staff" ? "/staff/queue" : base}
        >
          ← Danh sách đơn
        </Link>
      }
    >
      {location.state?.created ? (
        <Notice>
          Đã tạo yêu cầu thuê mẫu. Bạn có thể đổi sang Staff để thử xử lý đơn
          này.
        </Notice>
      ) : null}
      {message ? <Notice>{message}</Notice> : null}
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <Status status={status} />
        {status === "PENDING" ? (
          <p className="text-sm text-amber-800">
            Giữ lịch đến {dateTime(booking.expiresAt)} · còn khoảng {remaining}{" "}
            phút (demo)
          </p>
        ) : null}
      </div>
      <ol
        className="mb-6 grid grid-cols-2 gap-2 sm:grid-cols-5"
        aria-label="Tiến trình đơn thuê"
      >
        {mainSteps.map((step, i) => (
          <li
            key={step}
            className={`rounded-xl border p-3 text-sm ${stepIndex >= i ? "border-teal-300 bg-teal-50 text-teal-900" : "border-slate-200 bg-white text-slate-500"}`}
            aria-current={step === status ? "step" : undefined}
          >
            <span className="mb-1 block text-xs">Bước {i + 1}</span>
            {statusLabels[step]}
          </li>
        ))}
      </ol>
      <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
        <div className="space-y-6">
          <section className="panel">
            <h2 className="mb-5 text-lg font-semibold">
              Thông tin chuyến thuê
            </h2>
            <dl className="grid gap-5 text-sm sm:grid-cols-2">
              {[
                ["Khách hàng", booking.customer],
                ["Xe", booking.vehicleName],
                ["Nhận xe", dateTime(booking.startAt)],
                ["Trả xe dự kiến", dateTime(booking.endAt)],
                ["Chi nhánh nhận / trả", booking.branch],
                [
                  "Tiền thuê cơ bản mẫu",
                  `${booking.days} ngày × ${money(booking.price)} = ${money(booking.total)}`,
                ],
              ].map(([label, value]) => (
                <div key={label}>
                  <dt className="text-slate-500">{label}</dt>
                  <dd className="mt-1 font-semibold">{value}</dd>
                </div>
              ))}
            </dl>
            <p className="mt-5 text-xs text-slate-500">
              Giờ Việt Nam · Cọc và phụ phí chưa chốt, chưa tính trong tiền thuê
              mẫu.
            </p>
          </section>
          <section className="panel">
            <h2 className="mb-4 text-lg font-semibold">Hợp đồng & hóa đơn</h2>
            <div className="flex flex-wrap gap-3">
              <Link
                className="btn-secondary"
                to={`${base}/${booking.id}/contract`}
              >
                Hợp đồng {booking.contract ? "↗" : "· Chưa phát hành"}
              </Link>
              <Link
                className="btn-secondary"
                to={`${base}/${booking.id}/invoice`}
              >
                Hóa đơn {booking.invoice ? "↗" : "· Chưa phát hành"}
              </Link>
            </div>
          </section>
          {booking.handover || booking.returnRecord ? (
            <section className="panel">
              <h2 className="mb-4 text-lg font-semibold">
                Biên bản giao nhận mẫu
              </h2>
              {[
                ["Giao xe", booking.handover],
                ["Nhận trả", booking.returnRecord],
              ].map(([label, record]) =>
                record ? (
                  <div
                    className="border-t border-slate-100 py-3 text-sm"
                    key={label}
                  >
                    <strong>{label}</strong>
                    <p className="mt-1">{record.condition}</p>
                    <p className="mt-1 text-slate-500">
                      {record.actor} · {dateTime(record.at)}
                      {record.licenseChecked ? " · Đã kiểm tra GPLX" : ""}
                    </p>
                  </div>
                ) : null,
              )}
            </section>
          ) : null}
          {area === "user" && status === "COMPLETED" ? (
            <ReviewForm booking={booking} />
          ) : null}
          <section className="panel">
            <h2 className="mb-4 text-lg font-semibold">Lịch sử xử lý</h2>
            <ol className="space-y-4">
              {booking.history.map((h, i) => (
                <li
                  className="border-l-2 border-teal-200 pl-4 text-sm"
                  key={`${h.at}-${i}`}
                >
                  <p className="font-semibold">{statusLabels[h.status]}</p>
                  <p className="mt-1 text-slate-500">
                    {dateTime(h.at)} · {h.actor}
                  </p>
                  {h.reason ? <p className="mt-1">{h.reason}</p> : null}
                </li>
              ))}
            </ol>
          </section>
        </div>
        <aside className="panel h-fit space-y-4">
          <h2 className="text-lg font-semibold">Thao tác</h2>
          {area === "staff" && status === "PENDING" ? (
            <>
              <button
                className="btn w-full"
                onClick={() => openAction("CONFIRMED")}
              >
                Xác nhận đơn
              </button>
              <button
                className="btn-secondary w-full"
                onClick={() => openAction("REJECTED")}
              >
                Từ chối đơn
              </button>
            </>
          ) : null}
          {area === "staff" && status === "CONFIRMED" ? (
            <Link className="btn w-full" to={`/staff/handover/${booking.id}`}>
              Lập biên bản giao xe
            </Link>
          ) : null}
          {area === "staff" && status === "IN_PROGRESS" ? (
            <Link className="btn w-full" to={`/staff/return/${booking.id}`}>
              Ghi nhận trả xe
            </Link>
          ) : null}
          {area === "staff" && status === "RETURNED" ? (
            <>
              <Notice>
                Hoàn tất demo chỉ phát hành hóa đơn tiền thuê mẫu; không mô
                phỏng các khoản chưa chốt.
              </Notice>
              <button
                className="btn w-full"
                onClick={() => openAction("COMPLETED")}
              >
                Hoàn tất & phát hành hóa đơn
              </button>
            </>
          ) : null}
          {canCancel(booking, user, now) ? (
            <button
              className="btn-secondary w-full"
              onClick={() => openAction("CANCELLED")}
            >
              Hủy đơn
            </button>
          ) : null}
          {area === "user" &&
          ["PENDING", "CONFIRMED"].includes(status) &&
          !canCancel(booking, user, now) ? (
            <Notice>
              Đã sát giờ nhận xe. Vui lòng liên hệ nhân viên để được hỗ trợ hủy.
            </Notice>
          ) : null}
          {area === "user" ? (
            <p className="text-sm leading-6 text-slate-500">
              Bạn có thể tự hủy trước giờ nhận ít nhất 24 giờ khi trạng thái đơn
              cho phép.
            </p>
          ) : null}
          {area === "admin" ? (
            <p className="text-sm text-slate-500">
              Quản trị viên tra cứu và can thiệp hủy theo trạng thái; giao nhận
              thuộc nhân viên.
            </p>
          ) : null}
          {["COMPLETED", "CANCELLED", "REJECTED", "EXPIRED"].includes(
            status,
          ) ? (
            <p className="text-sm text-slate-500">Đơn đã kết thúc quy trình.</p>
          ) : null}
        </aside>
      </div>
      <Modal
        open={Boolean(action)}
        title={action ? `${statusLabels[action.next]} · Đơn mẫu` : ""}
        onClose={() => setAction(null)}
      >
        <form className="space-y-4" onSubmit={apply}>
          {error ? <Notice error>{error}</Notice> : null}
          {["CANCELLED", "REJECTED"].includes(action?.next) ? (
            <Field label="Lý do (bắt buộc)">
              <textarea
                required
                maxLength={500}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                rows={3}
              />
            </Field>
          ) : (
            <p className="text-sm leading-6 text-slate-600">
              Xác nhận thao tác trên dữ liệu demo? Hồ sơ mẫu sẽ được phát hành
              theo bước xử lý tương ứng.
            </p>
          )}
          <div className="flex justify-end gap-3">
            <button
              type="button"
              className="btn-secondary"
              onClick={() => setAction(null)}
            >
              Quay lại
            </button>
            <button className="btn" type="submit">
              Xác nhận thao tác
            </button>
          </div>
        </form>
      </Modal>
    </Page>
  );
}

function ReviewForm({ booking }) {
  const { data, update } = useDemo();
  const { user } = useAuth();
  const [rating, setRating] = useState("5");
  const [content, setContent] = useState("");
  const [error, setError] = useState("");
  const existing = data.reviews.find((r) => r.bookingId === booking.id);
  function submit(e) {
    e.preventDefault();
    setError("");
    try {
      update((state) => {
        const current = state.bookings.find((b) => b.id === booking.id);
        if (
          user.role !== "USER" ||
          current?.userId !== user.id ||
          current.status !== "COMPLETED" ||
          state.reviews.some((r) => r.bookingId === booking.id)
        )
          throw new Error("Đơn không đủ điều kiện hoặc đã có đánh giá.");
        if (!content.trim())
          throw new Error("Vui lòng nhập nội dung đánh giá.");
        return {
          ...state,
          reviews: [
            ...state.reviews,
            {
              id: `r-${Date.now()}`,
              bookingId: booking.id,
              vehicleId: booking.vehicleId,
              userId: user.id,
              rating: Number(rating),
              content: content.trim(),
              hidden: false,
            },
          ],
        };
      });
    } catch (err) {
      setError(err.message);
    }
  }
  return (
    <section className="panel">
      <h2 className="mb-4 text-lg font-semibold">Trải nghiệm chuyến đi</h2>
      {existing ? (
        <Notice>
          Đã gửi đánh giá {existing.rating}/5. {existing.content}
          {existing.hidden
            ? " · Đánh giá đang được ẩn khỏi trang công khai."
            : ""}
        </Notice>
      ) : (
        <form className="space-y-4" onSubmit={submit}>
          {error ? <Notice error>{error}</Notice> : null}
          <Field label="Mức hài lòng">
            <select value={rating} onChange={(e) => setRating(e.target.value)}>
              {[5, 4, 3, 2, 1].map((n) => (
                <option key={n} value={n}>
                  {n} sao
                </option>
              ))}
            </select>
          </Field>
          <Field label="Nội dung đánh giá">
            <textarea
              required
              maxLength={1000}
              rows={3}
              value={content}
              onChange={(e) => setContent(e.target.value)}
            />
          </Field>
          <button className="btn">Gửi đánh giá mẫu</button>
        </form>
      )}
    </section>
  );
}

export function OperationScreen({ kind }) {
  const { bookingId } = useParams();
  const { data, transition } = useDemo();
  const { user } = useAuth();
  const [condition, setCondition] = useState("");
  const [checked, setChecked] = useState(false);
  const [maintenance, setMaintenance] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState(false);
  const booking = data.bookings.find(
    (b) => b.id === bookingId && canReadBooking(b, user),
  );
  const [version] = useState(booking?.version);
  const handover = kind === "handover";
  function submit(e) {
    e.preventDefault();
    try {
      transition(user, booking.id, handover ? "IN_PROGRESS" : "RETURNED", {
        version,
        condition,
        licenseChecked: checked,
        maintenance,
      });
      setDone(true);
    } catch (err) {
      setError(err.message);
    }
  }
  if (!booking)
    return (
      <Page title="Giao nhận xe">
        <Missing back="/staff/queue" />
      </Page>
    );
  return (
    <Page
      title={handover ? "Biên bản bàn giao xe" : "Biên bản nhận trả xe"}
      description={`${booking.id} · ${booking.vehicleName} · ${booking.customer}`}
      actions={
        <Link className="btn-secondary" to={`/staff/bookings/${booking.id}`}>
          ← Chi tiết đơn
        </Link>
      }
    >
      {done ? (
        <Notice>
          Đã lưu biên bản mẫu và cập nhật trạng thái.{" "}
          <Link className="underline" to={`/staff/bookings/${booking.id}`}>
            Xem đơn thuê
          </Link>
        </Notice>
      ) : currentStatus(booking) !==
        (handover ? "CONFIRMED" : "IN_PROGRESS") ? (
        <Notice error>
          Trạng thái hiện tại không cho phép thao tác này. Hãy quay lại chi tiết
          đơn.
        </Notice>
      ) : (
        <form className="panel max-w-2xl space-y-5" onSubmit={submit}>
          <Notice>
            Biên bản mẫu ghi nhận người thực hiện và thời điểm lưu hiện tại.{" "}
            {handover
              ? "Chưa mô phỏng quy tắc giao sớm hoặc no-show."
              : "Phụ phí trả muộn và gia hạn chưa chốt; không tự tính tiền bổ sung."}
          </Notice>
          <Field label="Nhân viên thực hiện">
            <input readOnly value={user.fullName} />
          </Field>
          <Field label="Tình trạng xe / ghi chú">
            <textarea
              required
              maxLength={2000}
              rows={5}
              placeholder="Ghi nhận ngoại thất, nội thất và các điểm cần lưu ý..."
              value={condition}
              onChange={(e) => setCondition(e.target.value)}
            />
          </Field>
          {handover ? (
            <label className="flex gap-3 text-sm">
              <input
                type="checkbox"
                checked={checked}
                onChange={(e) => setChecked(e.target.checked)}
                required
              />
              Đã kiểm tra GPLX thủ công trước giao xe (không lưu ảnh).
            </label>
          ) : (
            <label className="flex gap-3 text-sm">
              <input
                type="checkbox"
                checked={maintenance}
                onChange={(e) => setMaintenance(e.target.checked)}
              />
              Xe cần bảo trì sau khi nhận lại
            </label>
          )}
          {error ? <Notice error>{error}</Notice> : null}
          <button className="btn">
            {handover
              ? "Lưu biên bản & giao xe mẫu"
              : "Lưu biên bản & nhận xe mẫu"}
          </button>
        </form>
      )}
    </Page>
  );
}

export function DocumentScreen({ kind, area }) {
  const { bookingId } = useParams();
  const { data } = useDemo();
  const { user } = useAuth();
  const booking = data.bookings.find(
    (b) => b.id === bookingId && canReadBooking(b, user),
  );
  if (!booking)
    return (
      <Page title="Hồ sơ đơn thuê">
        <Missing back={`/${area}/bookings`} />
      </Page>
    );
  const doc = booking[kind];
  return (
    <Page
      title={kind === "contract" ? "Hợp đồng thuê xe" : "Hóa đơn thuê xe"}
      actions={
        <Link className="btn-secondary" to={`/${area}/bookings/${booking.id}`}>
          ← Chi tiết đơn
        </Link>
      }
    >
      {!doc ? (
        <EmptyState
          title="Hồ sơ chưa được phát hành"
          description={
            kind === "contract"
              ? "Hợp đồng được phát hành khi đơn được xác nhận."
              : "Hóa đơn được phát hành khi đơn hoàn tất."
          }
        />
      ) : (
        <article className="panel mx-auto max-w-3xl">
          <div className="mb-6 flex flex-wrap justify-between gap-3 border-b border-slate-200 pb-5">
            <strong className="text-2xl text-teal-800">KDrive</strong>
            <div>
              <p className="font-semibold">{doc.id}</p>
              <p className="text-xs text-slate-500">
                Phát hành {dateTime(doc.issuedAt)}
              </p>
            </div>
          </div>
          <Notice>
            HỒ SƠ DEMO · Không có giá trị giao dịch. Thông tin giữ theo thời
            điểm phát hành mẫu.
          </Notice>
          <dl className="grid gap-5 text-sm sm:grid-cols-2">
            {[
              ["Khách hàng", doc.customer],
              ["Xe", doc.vehicleName],
              ["Nhận xe", dateTime(doc.startAt)],
              ["Trả xe", dateTime(doc.endAt)],
              ["Chi nhánh", doc.branch],
            ].map(([label, value]) => (
              <div key={label}>
                <dt className="text-slate-500">{label}</dt>
                <dd className="mt-1 font-semibold">{value}</dd>
              </div>
            ))}
          </dl>
          <div className="mt-6 border-t border-slate-200 pt-5">
            {(
              doc.items || [
                {
                  name: "Tiền thuê cơ bản",
                  quantity: doc.days,
                  unitPrice: doc.price,
                  amount: doc.total,
                },
              ]
            ).map((item, i) => (
              <div
                key={i}
                className="flex flex-wrap justify-between gap-3 text-sm"
              >
                <span>
                  {item.name} · {item.quantity} × {money(item.unitPrice)}
                </span>
                <strong>{money(item.amount)}</strong>
              </div>
            ))}
          </div>
          <p className="mt-5 text-sm text-slate-500">
            Cọc, phụ phí và điều khoản chi tiết chưa chốt. Không mô phỏng thanh
            toán hoặc chữ ký.
          </p>
        </article>
      )}
    </Page>
  );
}
