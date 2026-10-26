import { useSyncExternalStore } from "react";
import { useAuth } from "../context/AuthContext";
import { createFavoritesStore, favoriteScope, FAVORITES_PREFIX } from "../utils/vehicleFavorites";

const stores = new Map();
const empty = { ids: [], issue: "" };
const unavailable = { getSnapshot: () => empty, subscribe: () => () => {}, toggle: () => {} };
function storeFor(key) {
  if (!key) return unavailable;
  if (!stores.has(key)) {
    const store = createFavoritesStore({ key, getItem: name => window.localStorage.getItem(name), setItem: (name, value) => window.localStorage.setItem(name, value) });
    stores.set(key, store);
  }
  return stores.get(key);
}
if (typeof window !== "undefined") window.addEventListener("storage", event => {
  if (event.key === null) stores.forEach(store => store.refresh());
  else if (event.key.startsWith(FAVORITES_PREFIX)) stores.get(event.key)?.refresh();
});
export function useVehicleFavorites() {
  const key = favoriteScope(useAuth());
  const store = storeFor(key);
  const snapshot = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot);
  return { ...snapshot, ready: Boolean(key), toggle: store.toggle };
}
