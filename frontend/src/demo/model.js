// UI-only simulation. These rules are not a replacement for server authorization,
// transactions, pricing, availability, or validated AI output.
export { bookingStatusLabels as statusLabels } from "../utils/uiLabels.js";
export const mainSteps = [
  "PENDING",
  "CONFIRMED",
  "IN_PROGRESS",
  "RETURNED",
  "COMPLETED",
];
export function currentStatus(booking, now = Date.now()) {
  return booking.status === "PENDING" &&
    new Date(booking.expiresAt).getTime() <= now
    ? "EXPIRED"
    : booking.status;
}
export function canReadBooking(booking, actor) {
  return Boolean(
    actor &&
    (actor.role === "STAFF" ||
      actor.role === "ADMIN" ||
      (actor.role === "USER" && booking.userId === actor.id)),
  );
}
export function canCancel(booking, actor, now = Date.now()) {
  if (
    !canReadBooking(booking, actor) ||
    !["PENDING", "CONFIRMED"].includes(currentStatus(booking, now))
  )
    return false;
  return (
    actor.role !== "USER" ||
    new Date(booking.startAt).getTime() - now >= 86400000
  );
}
export function available(
  vehicles,
  bookings,
  vehicleId,
  startAt,
  endAt,
  now = Date.now(),
) {
  const vehicle = vehicles.find((v) => v.id === vehicleId);
  if (!vehicle || vehicle.status !== "ACTIVE") return false;
  const start = Date.parse(startAt),
    end = Date.parse(endAt);
  if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end)
    return false;
  return !bookings.some(
    (b) =>
      b.vehicleId === vehicleId &&
      ((currentStatus(b, now) === "IN_PROGRESS" &&
        Date.parse(b.endAt) <= now) ||
        (["PENDING", "CONFIRMED", "IN_PROGRESS"].includes(
          currentStatus(b, now),
        ) &&
          start < Date.parse(b.endAt) &&
          end > Date.parse(b.startAt))),
  );
}
export function simulateBooking(state, actor, input, now = Date.now()) {
  if (actor?.role !== "USER")
    throw new Error("Chỉ khách hàng được tạo đơn mẫu của mình.");
  const start = Date.parse(input.startAt),
    end = Date.parse(input.endAt);
  if (
    !Number.isFinite(start) ||
    !Number.isFinite(end) ||
    start <= now ||
    end <= start
  )
    throw new Error("Chọn giờ nhận trong tương lai và giờ trả sau giờ nhận.");
  if (
    !available(
      state.vehicles,
      state.bookings,
      input.vehicleId,
      input.startAt,
      input.endAt,
      now,
    )
  )
    throw new Error(
      "Xe không còn phù hợp khoảng thuê này. Hãy chọn thời gian hoặc xe khác.",
    );
  const vehicle = state.vehicles.find((v) => v.id === input.vehicleId);
  const days = Math.ceil((end - start) / 86400000);
  return {
    id: `KD-${now}`,
    userId: actor.id,
    customer: actor.fullName,
    vehicleId: vehicle.id,
    vehicleName: vehicle.name,
    branch:
      state.catalog.find((item) => item.type === "Chi nhánh")?.name ||
      "Chi nhánh demo",
    startAt: input.startAt,
    endAt: input.endAt,
    price: vehicle.price,
    days,
    total: vehicle.price * days,
    status: "PENDING",
    createdAt: new Date(now).toISOString(),
    expiresAt: new Date(Math.min(now + 7200000, start)).toISOString(),
    version: 1,
    history: [
      {
        status: "PENDING",
        at: new Date(now).toISOString(),
        actor: actor.fullName,
        reason: "",
      },
    ],
  };
}
export function simulateTransition(
  booking,
  actor,
  next,
  details = {},
  now = Date.now(),
) {
  if (!canReadBooking(booking, actor))
    throw new Error("Bạn không có quyền xử lý đơn này.");
  if (details.version !== booking.version)
    throw new Error("Đơn đã thay đổi. Hãy tải lại thông tin trước khi xử lý.");
  const status = currentStatus(booking, now);
  const transitions = {
    PENDING: ["CONFIRMED", "REJECTED"],
    CONFIRMED: ["IN_PROGRESS"],
    IN_PROGRESS: ["RETURNED"],
    RETURNED: ["COMPLETED"],
  };
  const allowed =
    next === "CANCELLED"
      ? canCancel(booking, actor, now)
      : actor.role === "STAFF" && transitions[status]?.includes(next);
  if (!allowed)
    throw new Error(
      "Thao tác không còn phù hợp trạng thái hoặc quyền hiện tại.",
    );
  if (["CANCELLED", "REJECTED"].includes(next) && !details.reason?.trim())
    throw new Error("Vui lòng ghi lý do.");
  if (
    next === "IN_PROGRESS" &&
    (!details.licenseChecked || !details.condition?.trim())
  )
    throw new Error(
      "Cần xác nhận kiểm tra GPLX và ghi tình trạng xe trước khi giao.",
    );
  if (next === "RETURNED" && !details.condition?.trim())
    throw new Error("Vui lòng ghi tình trạng xe khi nhận lại.");
  const timestamp = new Date(now).toISOString();
  const result = {
    ...booking,
    status: next,
    version: booking.version + 1,
    history: [
      ...booking.history,
      {
        status: next,
        at: timestamp,
        actor: actor.fullName,
        reason: details.reason || details.condition || "",
      },
    ],
  };
  const snapshot = {
    vehicleName: booking.vehicleName,
    customer: booking.customer,
    branch: booking.branch,
    startAt: booking.startAt,
    endAt: booking.endAt,
    price: booking.price,
    days: booking.days,
    total: booking.total,
  };
  if (next === "CONFIRMED")
    result.contract = {
      ...snapshot,
      id: `HD-${booking.id}`,
      issuedAt: timestamp,
    };
  if (next === "IN_PROGRESS")
    result.handover = {
      condition: details.condition,
      licenseChecked: true,
      actor: actor.fullName,
      at: timestamp,
    };
  if (next === "RETURNED")
    result.returnRecord = {
      condition: details.condition,
      actor: actor.fullName,
      at: timestamp,
      maintenance: Boolean(details.maintenance),
    };
  if (next === "COMPLETED")
    result.invoice = {
      ...snapshot,
      id: `HĐ-${booking.id}`,
      issuedAt: timestamp,
      items: [
        {
          name: "Tiền thuê xe mẫu",
          quantity: booking.days,
          unitPrice: booking.price,
          amount: booking.total,
        },
      ],
    };
  return result;
}
