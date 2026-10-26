import assert from "node:assert/strict";
import { test } from "node:test";
import { createFavoritesStore, favoriteScope, readFavorites } from "./vehicleFavorites.js";

function memory() { const values = new Map(); return { key: "favorites", values, getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) }; }
test("guest and each account have distinct namespaces; unverified identity has no scope", () => {
  const guest = favoriteScope({ user: null });
  assert.notEqual(guest, favoriteScope({ user: { id: "a" } }));
  assert.notEqual(favoriteScope({ user: { id: "a" } }), favoriteScope({ user: { id: "b" } }));
  assert.equal(favoriteScope({ loading: true, user: { id: "a" } }), null);
  assert.equal(favoriteScope({ sessionError: "offline" }), null);
});
test("add/remove survives new store and external-tab changes, stores IDs only", () => {
  const storage = memory(), first = createFavoritesStore(storage);
  let notifications = 0; const unsubscribe = first.subscribe(() => notifications++);
  first.toggle("v1"); first.toggle("v2"); first.toggle("v1");
  assert.equal(storage.values.get(storage.key), '["v2"]');
  assert.deepEqual(createFavoritesStore(storage).getSnapshot().ids, ["v2"]);
  storage.setItem(storage.key, '["v3"]'); first.refresh();
  assert.deepEqual(first.getSnapshot().ids, ["v3"]); assert.equal(notifications, 4); unsubscribe();
  first.toggle("../bad"); assert.equal(notifications, 4);
});
test("blocked/quota storage keeps session choices without throwing or losing previous toggles", () => {
  const storage = { key: "x", getItem() { throw new Error("blocked"); }, setItem() { throw new Error("quota"); } };
  const store = createFavoritesStore(storage);
  assert.ok(store.getSnapshot().issue);
  store.toggle("v1"); store.toggle("v2");
  assert.deepEqual(store.getSnapshot().ids, ["v1", "v2"]); assert.match(store.getSnapshot().issue, /phiên hiện tại/);
  store.toggle("v1"); assert.deepEqual(store.getSnapshot().ids, ["v2"]);
});
test("corrupt/object/invalid entries are rejected without deleting storage, duplicates normalized", () => {
  const storage = memory();
  for (const raw of ['broken', '{}', '["../bad"]', '[null]']) {
    storage.setItem(storage.key, raw);
    assert.deepEqual(readFavorites(storage, storage.key).ids, []);
    assert.ok(readFavorites(storage, storage.key).issue); assert.equal(storage.getItem(storage.key), raw);
  }
  storage.setItem(storage.key, '["v1","v1"]');
  assert.deepEqual(readFavorites(storage, storage.key).ids, ["v1"]);
});

test("blocked refresh preserves volatile IDs and later toggles keep the same RAM selection", () => {
  const store = createFavoritesStore({ key: "blocked", getItem() { throw new Error("blocked"); }, setItem() { throw new Error("quota"); } });
  let notifications = 0;
  store.subscribe(() => notifications++);
  store.toggle("v1"); store.refresh(); store.refresh();
  assert.deepEqual(store.getSnapshot().ids, ["v1"]);
  assert.match(store.getSnapshot().issue, /Không đọc được/);
  store.toggle("v2");
  assert.deepEqual(store.getSnapshot().ids, ["v1", "v2"]);
  assert.equal(notifications, 4);
});

test("new read failure during toggle preserves previously persisted cached IDs", () => {
  const storage = memory();
  const store = createFavoritesStore(storage);
  store.toggle("v1");
  storage.getItem = () => { throw new Error("blocked"); };
  storage.setItem = () => { throw new Error("quota"); };
  store.toggle("v2");
  assert.deepEqual(store.getSnapshot().ids, ["v1", "v2"]);
  store.refresh();
  assert.deepEqual(store.getSnapshot().ids, ["v1", "v2"]);
  assert.ok(store.getSnapshot().issue);
});

test("corrupt external data cannot erase cached IDs and is not overwritten by refresh", () => {
  const storage = memory();
  const store = createFavoritesStore(storage);
  store.toggle("v1");
  for (const raw of ["broken", "{}", '["../bad"]']) {
    storage.values.set(storage.key, raw);
    store.refresh();
    assert.deepEqual(store.getSnapshot().ids, ["v1"]);
    assert.ok(store.getSnapshot().issue);
    assert.equal(storage.values.get(storage.key), raw);
  }
});

test("recovery honors readable external replacement and clear, without writing RAM automatically", () => {
  const storage = memory(), get = storage.getItem, set = storage.setItem;
  const store = createFavoritesStore(storage);
  storage.getItem = () => { throw new Error("blocked"); };
  storage.setItem = () => { throw new Error("quota"); };
  store.toggle("v1"); store.refresh();
  assert.deepEqual(store.getSnapshot().ids, ["v1"]);
  storage.getItem = get; storage.setItem = set;
  storage.values.set(storage.key, '["v3"]');
  store.refresh();
  assert.deepEqual(store.getSnapshot(), { ids: ["v3"], issue: "" });
  assert.equal(storage.values.get(storage.key), '["v3"]');
  storage.values.delete(storage.key); store.refresh();
  assert.deepEqual(store.getSnapshot(), { ids: [], issue: "" });
  store.toggle("v2");
  assert.equal(storage.values.get(storage.key), '["v2"]');
  storage.values.set(storage.key, '[]'); store.refresh();
  assert.deepEqual(store.getSnapshot(), { ids: [], issue: "" });
});

test("successful clear replaces volatile RAM, while failure in one scope cannot affect another", () => {
  const a = memory(), b = memory();
  const first = createFavoritesStore(a), second = createFavoritesStore(b);
  second.toggle("v2");
  a.setItem = () => { throw new Error("quota"); };
  first.toggle("v1");
  a.getItem = () => { throw new Error("blocked"); };
  first.refresh();
  assert.deepEqual(first.getSnapshot().ids, ["v1"]);
  assert.deepEqual(second.getSnapshot(), { ids: ["v2"], issue: "" });
  a.getItem = () => null; first.refresh();
  assert.deepEqual(first.getSnapshot(), { ids: [], issue: "" });
  assert.deepEqual(second.getSnapshot().ids, ["v2"]);
});
