import test from "node:test";
import assert from "node:assert/strict";
import { eventsForSelectedIncident } from "./incidentEvents.js";

test("incident history is hidden until events for the selected incident load", () => {
  const history = {
    incidentId: "incident-a",
    events: [{ action: "OPEN" }],
  };

  assert.deepEqual(eventsForSelectedIncident("incident-b", history), []);
  assert.deepEqual(eventsForSelectedIncident("incident-a", history), [
    { action: "OPEN" },
  ]);
});

test("invalid or missing incident history is not rendered", () => {
  assert.deepEqual(eventsForSelectedIncident("incident-a", null), []);
  assert.deepEqual(
    eventsForSelectedIncident("incident-a", {
      incidentId: "incident-a",
      events: {},
    }),
    [],
  );
});
