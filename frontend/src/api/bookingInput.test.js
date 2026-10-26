import assert from "node:assert/strict";
import test from "node:test";
import {
  quoteRequestIsCurrent,
  sameBookingInput,
} from "./bookingInput.js";
import { createRequestGeneration } from "../utils/requestGeneration.js";

const input = {
  vehicleId: "vehicle-1",
  pickupBranchId: "branch-a",
  returnBranchId: "branch-b",
  startAt: "2026-10-10T03:00:00Z",
  endAt: "2026-10-11T03:00:00Z",
};
test('Calendar quote identity includes both selected dates',()=>{
 const dates={vehicleId:'v1',pickupBranchId:'dn',returnBranchId:'dn',pickupDate:'2026-10-17',returnDate:'2026-10-21'};
 assert.equal(sameBookingInput(dates,{...dates}),true);assert.equal(sameBookingInput(dates,{...dates,pickupDate:'2026-10-18'}),false);assert.equal(sameBookingInput(dates,{...dates,returnDate:'2026-10-22'}),false);
});

test("a quote matches only the exact vehicle, route, and time inputs", () => {
  assert.equal(sameBookingInput(input, { ...input }), true);

  for (const field of Object.keys(input)) {
    assert.equal(
      sameBookingInput(input, { ...input, [field]: `${input[field]}-changed` }),
      false,
      `changing ${field} must invalidate the quote`,
    );
  }

  assert.equal(sameBookingInput(input, null), false);
});

test("input changes and out-of-order responses cannot publish an old quote", () => {
  const generation = createRequestGeneration();
  const inputA = { ...input };
  const inputB = { ...input, returnBranchId: "branch-c" };
  const requestA = generation.begin();
  const requestB = generation.begin();
  const published = [];

  if (quoteRequestIsCurrent(generation, requestB, inputB, inputB)) {
    published.push("quote-B");
  }
  if (quoteRequestIsCurrent(generation, requestA, inputA, inputB)) {
    published.push("quote-A");
  }

  assert.deepEqual(published, ["quote-B"]);
  assert.equal(
    quoteRequestIsCurrent(generation, requestB, inputB, input),
    false,
  );
});
