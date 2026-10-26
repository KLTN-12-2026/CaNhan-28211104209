import assert from "node:assert/strict";
import test from "node:test";
import { getBookingActions } from "./bookingActions.js";

const now = Date.parse("2026-10-04T10:00:00Z");
const booking = {
  status: "PENDING",
  startAt: "2026-10-05T10:00:00Z",
  endAt: "2026-10-06T10:00:00Z",
  pickupBranchId: "branch-pickup",
  returnBranchId: "branch-return",
};
test('Calendar handover is permitted throughout pickup day only, while legacy time rules remain',()=>{
 const calendar={...booking,status:'CONFIRMED',policyVersion:'calendar-2026-10-17',startAt:'2026-10-16T17:00:00Z',endAt:'2026-10-21T16:59:59.999999Z'};
 const options={role:'STAFF',assignedBranchIds:new Set(['branch-pickup'])};
 assert.deepEqual(getBookingActions(calendar,{...options,now:Date.parse('2026-10-17T16:59:59Z')}),['IN_PROGRESS','CANCELLED']);
 assert.deepEqual(getBookingActions(calendar,{...options,now:Date.parse('2026-10-17T17:00:00Z')}),[]);assert.deepEqual(getBookingActions(calendar,{...options,now:Date.parse('2026-10-16T16:59:59Z')}),[]);
});

test("Staff actions require API-backed assignment to the operational branch", () => {
  assert.deepEqual(
    getBookingActions(booking, {
      role: "STAFF",
      assignedBranchIds: new Set(["branch-pickup"]),
      now,
    }),
    ["CONFIRMED", "REJECTED", "CANCELLED"],
  );
  assert.deepEqual(
    getBookingActions(booking, {
      role: "STAFF",
      assignedBranchIds: new Set(["branch-unrelated"]),
      now,
    }),
    [],
  );
  assert.deepEqual(
    getBookingActions(booking, {
      role: "STAFF",
      assignedBranchIds: new Set(["branch-pickup"]),
      assignmentsReady: false,
      now,
    }),
    [],
  );
});

test("handover and return permissions use pickup and return assignments separately", () => {
  assert.deepEqual(
    getBookingActions(
      { ...booking, status: "IN_PROGRESS" },
      {
        role: "STAFF",
        assignedBranchIds: new Set(["branch-pickup"]),
        now,
      },
    ),
    [],
  );
  assert.deepEqual(
    getBookingActions(
      { ...booking, status: "IN_PROGRESS" },
      {
        role: "STAFF",
        assignedBranchIds: new Set(["branch-return"]),
        now,
      },
    ),
    ["RETURNED"],
  );
  assert.deepEqual(
    getBookingActions(
      { ...booking, status: "RETURNED" },
      {
        role: "STAFF",
        assignedBranchIds: new Set(["branch-return"]),
        now,
      },
    ),
    ["COMPLETED"],
  );
});

test("Admin never receives Staff handover or return actions", () => {
  assert.deepEqual(
    getBookingActions(booking, {
      role: "ADMIN",
      assignedBranchIds: new Set(["branch-pickup", "branch-return"]),
      now,
    }),
    ["CANCELLED"],
  );
  assert.deepEqual(
    getBookingActions(
      { ...booking, status: "IN_PROGRESS" },
      {
        role: "ADMIN",
        assignedBranchIds: new Set(["branch-pickup", "branch-return"]),
        now,
      },
    ),
    [],
  );
});

test("User can cancel PENDING and CONFIRMED bookings only when 24h or more remain until pickup", () => {
  // 6 deterministic combinations: PENDING/CONFIRMED x (24h-1s, exactly 24h, 24h+1s)
  const boundaryCases = [
    { deltaMs: 86400000, status: "PENDING", expected: ["CANCELLED"], desc: "Exactly 24h PENDING" },
    { deltaMs: 86400000 + 1000, status: "PENDING", expected: ["CANCELLED"], desc: "24h+1s PENDING" },
    { deltaMs: 86400000 - 1000, status: "PENDING", expected: [], desc: "24h-1s PENDING" },
    { deltaMs: 86400000, status: "CONFIRMED", expected: ["CANCELLED"], desc: "Exactly 24h CONFIRMED" },
    { deltaMs: 86400000 + 1000, status: "CONFIRMED", expected: ["CANCELLED"], desc: "24h+1s CONFIRMED" },
    { deltaMs: 86400000 - 1000, status: "CONFIRMED", expected: [], desc: "24h-1s CONFIRMED" },
  ];

  for (const tc of boundaryCases) {
    const startAt = new Date(now + tc.deltaMs).toISOString();
    assert.deepEqual(
      getBookingActions(
        { ...booking, status: tc.status, startAt },
        { role: "USER", now },
      ),
      tc.expected,
      `${tc.desc} must yield ${JSON.stringify(tc.expected)}`,
    );
  }

  // > 24h (e.g. 48h)
  const startAt48h = new Date(now + 2 * 86400000).toISOString();
  assert.deepEqual(
    getBookingActions(
      { ...booking, status: "PENDING", startAt: startAt48h },
      { role: "USER", now },
    ),
    ["CANCELLED"],
  );

  // Other statuses never permit USER cancellation
  assert.deepEqual(
    getBookingActions(
      { ...booking, status: "IN_PROGRESS", startAt: startAt48h },
      { role: "USER", now },
    ),
    [],
  );
  assert.deepEqual(
    getBookingActions(
      { ...booking, status: "CANCELLED", startAt: startAt48h },
      { role: "USER", now },
    ),
    [],
  );
  assert.deepEqual(
    getBookingActions(
      { ...booking, status: "RETURNED", startAt: startAt48h },
      { role: "USER", now },
    ),
    [],
  );
  assert.deepEqual(
    getBookingActions(
      { ...booking, status: "COMPLETED", startAt: startAt48h },
      { role: "USER", now },
    ),
    [],
  );
  assert.deepEqual(
    getBookingActions(
      { ...booking, status: "REJECTED", startAt: startAt48h },
      { role: "USER", now },
    ),
    [],
  );
});
