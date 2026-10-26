import assert from "node:assert/strict";
import test from "node:test";
import {
  createRequestGeneration,
  shouldClearUnauthorizedSession,
} from "./requestGeneration.js";

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

test("a superseded response cannot commit even when it resolves last", async () => {
  const generation = createRequestGeneration();
  const firstResponse = deferred();
  const secondResponse = deferred();
  const committed = [];

  const firstId = generation.begin();
  const firstTask = firstResponse.promise.then((value) => {
    if (generation.isCurrent(firstId)) committed.push(value);
  });

  const secondId = generation.begin();
  const secondTask = secondResponse.promise.then((value) => {
    if (generation.isCurrent(secondId)) committed.push(value);
  });

  secondResponse.resolve("route B");
  await secondTask;
  firstResponse.resolve("route A");
  await firstTask;

  assert.deepEqual(committed, ["route B"]);
});

test("cleaning up an old effect cannot invalidate its replacement", () => {
  const generation = createRequestGeneration();
  const firstId = generation.begin();
  const secondId = generation.begin();

  generation.invalidate(firstId);

  assert.equal(generation.isCurrent(secondId), true);
  generation.invalidate(secondId);
  assert.equal(generation.isCurrent(secondId), false);
});

test("a late unauthorized response only clears the session that made the request", () => {
  assert.equal(shouldClearUnauthorizedSession("old-token", "new-token"), false);
  assert.equal(shouldClearUnauthorizedSession("same-token", "same-token"), true);
  assert.equal(shouldClearUnauthorizedSession(null, "same-token"), false);
});

test("late hydration cannot replace a login that completed later", async () => {
  const generation = createRequestGeneration();
  const hydrationResponse = deferred();
  let currentUser = null;

  const hydrationId = generation.begin();
  const hydrationTask = hydrationResponse.promise.then((profile) => {
    if (generation.isCurrent(hydrationId)) currentUser = profile;
  });

  const loginId = generation.begin();
  if (generation.isCurrent(loginId)) currentUser = "new-session-user";
  hydrationResponse.resolve("stale-hydrated-user");
  await hydrationTask;

  assert.equal(currentUser, "new-session-user");
});

test("logout invalidates a login request that is still in flight", async () => {
  const generation = createRequestGeneration();
  const loginResponse = deferred();
  let currentToken = "new-session-token";
  assert.equal(currentToken, "new-session-token");

  const loginId = generation.begin();
  const loginTask = loginResponse.promise.then((token) => {
    if (generation.isCurrent(loginId)) currentToken = token;
  });

  generation.begin();
  currentToken = null;
  loginResponse.resolve("stale-login-token");
  await loginTask;

  assert.equal(currentToken, null);
});
