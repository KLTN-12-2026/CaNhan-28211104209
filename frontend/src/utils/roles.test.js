import test from "node:test";
import assert from "node:assert/strict";
import { destinationForRole, homePathForRole } from "./roles.js";

test("role home routes preserve the established Staff and Admin destinations", () => {
  assert.equal(homePathForRole("USER"), "/");
  assert.equal(homePathForRole("STAFF"), "/staff/queue");
  assert.equal(homePathForRole("ADMIN"), "/admin");
});

test("login keeps a requested route only when it belongs to the authenticated role", () => {
  assert.equal(
    destinationForRole("USER", "/user/bookings?status=COMPLETED"),
    "/user/bookings?status=COMPLETED",
  );
  assert.equal(destinationForRole("USER", "/user"), "/user");
  assert.equal(destinationForRole("USER", "/user/profile#license"), "/user/profile#license");
  assert.equal(destinationForRole("USER", undefined), "/");
  assert.equal(destinationForRole("USER", "/vehicles/v1"), "/");
  assert.equal(destinationForRole("STAFF", "/staff/queue"), "/staff/queue");
  assert.equal(destinationForRole("ADMIN", "/admin/reviews"), "/admin/reviews");
  assert.equal(destinationForRole("STAFF", "/user/bookings"), "/staff/queue");
  assert.equal(destinationForRole("ADMIN", "/staff/queue"), "/admin");
  assert.equal(destinationForRole("USER", "/staff"), "/");
});

test("USER login rejects external, protocol-relative, and lookalike destinations", () => {
  for (const destination of [
    "https://attacker.example/user/bookings",
    "//attacker.example/user/bookings",
    "/user-evil",
    "/user.evil",
    "/user%2Fevil",
    "/user\\@attacker.example",
  ]) {
    assert.equal(destinationForRole("USER", destination), "/", destination);
  }
});
