import test from "node:test";
import assert from "node:assert/strict";
import { createSeed, demoActors } from "./seed.js";
import {
  available,
  canCancel,
  canReadBooking,
  currentStatus,
  simulateBooking,
  simulateTransition,
} from "./model.js";
const now = Date.parse("2026-09-26T00:00:00Z");
const at = (hours) => new Date(now + hours * 3600000).toISOString();
const input = { vehicleId: "v3", startAt: at(72), endAt: at(97) };
const make = () =>
  simulateBooking(createSeed(now), demoActors.USER, input, now);

test("demo price snapshot rounds partial days and caps the hold before pickup", () => {
  const booking = make();
  assert.equal(booking.days, 2);
  assert.equal(booking.total, 1200000);
  assert.equal(booking.expiresAt, at(2));
  const urgent = simulateBooking(
    { ...createSeed(now), bookings: [] },
    demoActors.USER,
    { ...input, startAt: at(1), endAt: at(25) },
    now,
  );
  assert.equal(urgent.expiresAt, at(1));
});
test("demo overlap is rechecked and expired holds stop occupying the slot", () => {
  const booking = make();
  const state = { ...createSeed(now), bookings: [booking] };
  assert.throws(
    () => simulateBooking(state, demoActors.USER, input, now + 1),
    /không còn/,
  );
  assert.equal(currentStatus(booking, now + 7200000), "EXPIRED");
  assert.equal(
    available(
      state.vehicles,
      state.bookings,
      "v3",
      input.startAt,
      input.endAt,
      now + 7200000,
    ),
    true,
  );
  assert.throws(
    () =>
      simulateTransition(
        booking,
        demoActors.STAFF,
        "CONFIRMED",
        { version: 1 },
        now + 7200000,
      ),
    /không còn/,
  );
});
test("adjacent demo ranges do not overlap and maintenance is never available", () => {
  const b = { ...make(), status: "CONFIRMED" };
  const state = createSeed(now);
  assert.equal(
    available(state.vehicles, [b], "v3", b.endAt, at(120), now),
    true,
  );
  assert.equal(available(state.vehicles, [], "v4", at(72), at(96), now), false);
});
test("UI ownership and cancellation boundaries do not grant Admin staff operations", () => {
  const b = make();
  assert.equal(canReadBooking(b, { id: "someone-else", role: "USER" }), false);
  assert.equal(
    canCancel({ ...b, startAt: at(24) }, demoActors.USER, now),
    true,
  );
  assert.equal(
    canCancel({ ...b, startAt: at(23.99) }, demoActors.USER, now),
    false,
  );
  assert.equal(canCancel(b, demoActors.ADMIN, now), true);
  assert.throws(
    () =>
      simulateTransition(b, demoActors.ADMIN, "CONFIRMED", { version: 1 }, now),
    /quyền/,
  );
  assert.throws(
    () =>
      simulateTransition(
        b,
        demoActors.USER,
        "CANCELLED",
        { version: 1, reason: " " },
        now,
      ),
    /lý do/,
  );
});
test("stale UI actions are rejected and document snapshots survive catalog edits", () => {
  const state = createSeed(now);
  const pending = make();
  const confirmed = simulateTransition(
    pending,
    demoActors.STAFF,
    "CONFIRMED",
    { version: 1 },
    now + 1000,
  );
  state.vehicles.find((v) => v.id === pending.vehicleId).price = 9999999;
  assert.equal(confirmed.contract.price, 600000);
  assert.throws(
    () =>
      simulateTransition(
        confirmed,
        demoActors.STAFF,
        "IN_PROGRESS",
        { version: 1, licenseChecked: true, condition: "OK" },
        now + 2000,
      ),
    /thay đổi/,
  );
  assert.throws(
    () =>
      simulateTransition(
        confirmed,
        demoActors.STAFF,
        "IN_PROGRESS",
        { version: 2, condition: "OK" },
        now + 2000,
      ),
    /GPLX/,
  );
});
test("return and completion are separate, invoice exists only at completion", () => {
  const actor = demoActors.STAFF;
  let b = simulateTransition(
    make(),
    actor,
    "CONFIRMED",
    { version: 1 },
    now + 1000,
  );
  b = simulateTransition(
    b,
    actor,
    "IN_PROGRESS",
    { version: b.version, licenseChecked: true, condition: "Giao xe mẫu" },
    now + 2000,
  );
  assert.equal(b.invoice, undefined);
  assert.equal(canCancel(b, demoActors.ADMIN, now), false);
  b = simulateTransition(
    b,
    actor,
    "RETURNED",
    { version: b.version, condition: "Nhận xe mẫu" },
    now + 3000,
  );
  assert.equal(b.status, "RETURNED");
  assert.equal(b.invoice, undefined);
  b = simulateTransition(
    b,
    actor,
    "COMPLETED",
    { version: b.version },
    now + 4000,
  );
  assert.equal(b.invoice.total, 1200000);
  assert.equal(b.history.length, 5);
});
