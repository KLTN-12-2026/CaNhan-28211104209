import assert from "node:assert/strict";
import { before, after, test } from "node:test";
import { createElement as h } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { createServer } from "vite";
import { fileURLToPath, URL } from "node:url";
import { getBookingActions } from "../api/bookingActions.js";

let vite, Progress, Guide, Router;
before(async () => {
  vite = await createServer({ root: fileURLToPath(new URL("../..", import.meta.url)), server: { middlewareMode: true }, appType: "custom", logLevel: "error" });
  ({ default: Progress } = await vite.ssrLoadModule("/src/components/booking/BookingProgress.jsx"));
  ({ default: Guide } = await vite.ssrLoadModule("/src/pages/guest/RentalGuidePage.jsx"));
  ({ MemoryRouter: Router } = await vite.ssrLoadModule("react-router-dom"));
});
after(async () => vite?.close());
const booking = { id: "order-1", pickupBranchId: "dn", returnBranchId: "hn", startAt: "2026-11-01T03:00:00Z", endAt: "2026-11-02T03:00:00Z" };
function render(status, props = {}) { return renderToStaticMarkup(h(Router, null, h(Progress, { booking: { ...booking, status }, ...props }))); }
test("all main and terminal statuses explain actual state without inventing history", () => {
  for (const [status, expected] of Object.entries({ PENDING: "Chờ xác nhận", CONFIRMED: "Đã xác nhận", IN_PROGRESS: "Đang thuê", RETURNED: "Đã trả xe", COMPLETED: "Hoàn tất", CANCELLED: "Đã hủy", REJECTED: "Đã từ chối", EXPIRED: "Hết hạn giữ lịch" })) {
    const html = render(status, { role: "USER" });
    assert.match(html, new RegExp(`Hiện tại: ${expected}`));
    assert.match(html, /<details class="booking-history/);assert.doesNotMatch(html, /<details[^>]* open/);assert.match(html, /<summary>Lịch sử trạng thái<\/summary>/);
    assert.match(html, /Chưa có lịch sử trạng thái từ hệ thống/);assert.doesNotMatch(html, /<li/);
  }
  assert.match(render("NEW_UNKNOWN"), /Trạng thái chưa xác định/);
});
test("history uses actual Vietnamese timestamps/reasons, escaped content and API expiry distinct from recorded pending", () => {
  const html = render("EXPIRED", { history: [{ previous_status: null, next_status: "PENDING", occurred_at: "2026-10-15T03:04:00Z", reason: "<script>bad</script>" }] });
  assert.match(html, /10:04/);assert.match(html, /15\/10\/2026/);
  assert.match(html, /Tạo yêu cầu.*Chờ xác nhận/);assert.match(html, /&lt;script&gt;/);assert.doesNotMatch(html, /<script>/);
  assert.equal((html.match(/<li /g) || []).length, 1);
});
test("document links follow issue stages including contract retained after cancellation", () => {
  assert.doesNotMatch(render("PENDING"), /href="\/user\/bookings\/order-1\/contract"/);
  assert.match(render("CONFIRMED"), /href="\/user\/bookings\/order-1\/contract"/);
  assert.doesNotMatch(render("CONFIRMED"), /href="\/user\/bookings\/order-1\/invoice"/);
  assert.match(render("COMPLETED"), /href="\/user\/bookings\/order-1\/invoice"/);
  assert.match(render("CANCELLED", { history: [{ next_status: "CONFIRMED" }] }), /href="\/user\/bookings\/order-1\/contract"/);
});
test("staff wrong branch and admin get no handover action; user links remain role appropriate", () => {
  const current = { ...booking, status: "CONFIRMED" }, now = Date.parse("2026-11-01T04:00:00Z");
  const actions = getBookingActions(current, { role: "STAFF", assignedBranchIds: new Set(["hn"]), now });
  assert.deepEqual(actions, []);assert.match(render("CONFIRMED", { role: "STAFF", actions }), /Hiện không có thao tác khả dụng/);
  const adminActions = getBookingActions(current, { role: "ADMIN", now });
  assert.deepEqual(adminActions, ["CANCELLED"]);assert.match(render("CONFIRMED", { role: "ADMIN", actions: adminActions }), /Quản trị viên không thực hiện giao hoặc nhận trả xe/);
  assert.doesNotMatch(render("CONFIRMED", { role: "STAFF" }), /href="\/user\/profile"/);
  assert.match(render("CONFIRMED", { role: "USER", actions: [] }), /liên hệ nhân viên chi nhánh nhận/);
  assert.match(render("PENDING", { role: "USER", actions: ["CANCELLED"] }), /Có thể yêu cầu hủy/);
});
test("guide covers actual rental contract, FAQ and valid public entry points", () => {
  const html = renderToStaticMarkup(h(Router, null, h(Guide)));
  for (const text of ["4 ngày thuê", "ngày tiếp theo", "Đơn cũ", "block 24 giờ", "ít nhất 24 giờ", "tối đa 2 giờ", "GPLX bản gốc", "không xác nhận đã thanh toán", "hệ bằng cũ hoặc mới"]) assert.ok(html.includes(text), text);
  assert.match(html, /href="\/vehicles"/);assert.match(html, /href="\/media\/vehicles\/licenses.html"/);
  assert.equal((html.match(/<details /g) || []).length, 7);
  assert.doesNotMatch(html, /href="\/staff|href="\/admin/);
});
