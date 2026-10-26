import assert from "node:assert/strict";
import test from "node:test";
import { selectApiRequestState } from "./apiRequestState.js";

test("a previous route result is hidden while the new route is loading", () => {
  const routeA = { path: "/bookings/a" };
  const routeB = { path: "/bookings/b" };

  assert.deepEqual(
    selectApiRequestState(
      { requestKey: routeA, data: ["booking-a"], error: "" },
      routeB,
      true,
    ),
    { data: null, loading: true, error: "" },
  );
});

test("reload exposes only the result for its new request key", () => {
  const initialLoad = { path: "/bookings" };
  const reload = { path: "/bookings", revision: 1 };

  assert.deepEqual(
    selectApiRequestState(
      { requestKey: initialLoad, data: ["stale"], error: "" },
      reload,
      true,
    ),
    { data: null, loading: true, error: "" },
  );
  assert.deepEqual(
    selectApiRequestState(
      {
        requestKey: reload,
        data: null,
        error: "Không thể tải dữ liệu.",
      },
      reload,
      true,
    ),
    { data: null, loading: false, error: "Không thể tải dữ liệu." },
  );
});

test("disabled requests expose neither stale data nor loading/error state", () => {
  const requestKey = { path: "/me/branches" };

  assert.deepEqual(
    selectApiRequestState(
      {
        requestKey,
        data: ["branch-a"],
        error: "old error",
      },
      requestKey,
      false,
    ),
    { data: null, loading: false, error: "" },
  );
});
