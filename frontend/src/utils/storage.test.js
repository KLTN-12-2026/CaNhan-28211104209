import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { clearSession, getStoredToken, getStoredUser, readStoredSession, setStoredToken, setStoredUser, storeSession } from "./storage.js";
const original = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
afterEach(() => {
  Object.defineProperty(globalThis, "localStorage", { configurable: true, value: { removeItem() {} } });
  clearSession();
  if (original) Object.defineProperty(globalThis, "localStorage", original);
  else delete globalThis.localStorage;
});
function install(storage) { Object.defineProperty(globalThis, "localStorage", { configurable: true, value: storage }); }
const blocked = () => { throw Object.assign(new Error("blocked"), { name: "SecurityError" }); };
test("storage property getter blocked: reads fail closed and removals report failure", () => {
  Object.defineProperty(globalThis, "localStorage", { configurable: true, get: blocked });
  assert.equal(getStoredToken(), null);
  assert.ok(readStoredSession().issue);
  assert.equal(getStoredUser(), null);
  assert.ok(clearSession().issue);
});
test("corrupt user JSON and blocked read never throw or establish identity", () => {
  install({ getItem: () => "{broken", removeItem: blocked });
  assert.equal(getStoredUser(), null);
  install({ getItem: blocked });
  assert.equal(getStoredToken(), null);
  assert.equal(getStoredUser(), null);
  assert.ok(readStoredSession().issue);
});
for (const failedKey of ["kdrive.user", "kdrive.jwt"]) test(`partial persistence rolls back and fails closed: ${failedKey}`, () => {
  const data = new Map([["kdrive.jwt", "old"]]);
  install({ getItem: key => data.get(key) ?? null, setItem(key, value) {
    if (key === failedKey) throw Object.assign(new Error("full"), { name: "QuotaExceededError" });
    data.set(key, value);
  }, removeItem: key => data.delete(key) });
  assert.ok(storeSession("new", { id: "user" }).issue);
  assert.equal(data.size, 0);
  assert.equal(getStoredToken(), null);
  assert.ok(readStoredSession().issue);
});
test("failed removal attempts both keys, blocks leftover token; explicit persistence recovers", () => {
  const data = new Map([["kdrive.jwt", "leftover"], ["kdrive.user", "{}"]]);
  const removed = [];
  const storage = { getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value), removeItem(key) { removed.push(key); if (key === "kdrive.jwt") blocked(); data.delete(key); } };
  install(storage);
  assert.ok(clearSession().issue);
  assert.deepEqual(removed, ["kdrive.jwt", "kdrive.user"]);
  assert.equal(data.get("kdrive.jwt"), "leftover");
  assert.equal(getStoredToken(), null);
  assert.equal(getStoredUser(), null);
  assert.equal(storeSession("replacement", { id: "new" }).issue, "");
  assert.equal(getStoredToken(), "replacement");
  assert.deepEqual(getStoredUser(), { id: "new" });
});
test("normal token/user write and clear preserve the existing key contract", () => {
  const data = new Map();
  install({ getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value), removeItem: key => data.delete(key) });
  assert.equal(setStoredToken("token").issue, "");
  assert.equal(setStoredUser({ id: "user" }).issue, "");
  assert.equal(getStoredToken(), "token");
  assert.deepEqual(getStoredUser(), { id: "user" });
  assert.equal(clearSession().issue, "");
  assert.equal(getStoredToken(), null);
});
