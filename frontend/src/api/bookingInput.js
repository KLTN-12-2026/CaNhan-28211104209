const BOOKING_INPUT_FIELDS = [
  "vehicleId",
  "pickupBranchId",
  "returnBranchId",
  "startAt",
  "endAt",
  "pickupDate",
  "returnDate",
];

export function sameBookingInput(left, right) {
  return Boolean(
    left &&
      right &&
      BOOKING_INPUT_FIELDS.every((field) => left[field] === right[field]),
  );
}

export function quoteRequestIsCurrent(
  requestGeneration,
  requestId,
  requestInput,
  currentInput,
) {
  return (
    requestGeneration.isCurrent(requestId) &&
    sameBookingInput(requestInput, currentInput)
  );
}
