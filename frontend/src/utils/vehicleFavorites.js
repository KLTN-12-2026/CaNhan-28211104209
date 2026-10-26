export const FAVORITES_PREFIX = "kdrive.favorites.v1.";
export function favoriteScope({ user, loading, sessionError }) {
  if (loading || sessionError) return null;
  return user?.id ? `${FAVORITES_PREFIX}user.${encodeURIComponent(user.id)}` : `${FAVORITES_PREFIX}guest`;
}
export function readFavorites(storage, key) {
  try {
    const raw = storage.getItem(key);
    if (raw === null) return { ids: [], issue: "" };
    const ids = JSON.parse(raw);
    if (!Array.isArray(ids) || ids.some(id => typeof id !== "string" || !/^[a-zA-Z0-9_-]+$/.test(id))) throw new Error("Invalid favorites");
    return { ids: [...new Set(ids)], issue: "" };
  } catch {
    return { ids: [], issue: "Không đọc được danh sách đã lưu trên thiết bị." };
  }
}
export function createFavoritesStore(storage) {
  let snapshot = readFavorites(storage, storage.key);
  let volatile = Boolean(snapshot.issue);
  const listeners = new Set();
  function publish(next) { snapshot = next; listeners.forEach(listener => listener()); }
  return {
    getSnapshot: () => snapshot,
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    refresh() {
      const next = readFavorites(storage, storage.key);
      volatile = Boolean(next.issue);
      publish(next.issue ? { ...snapshot, issue: next.issue } : next);
    },
    toggle(id) {
      if (!/^[a-zA-Z0-9_-]+$/.test(id)) return;
      const stored = volatile ? snapshot : readFavorites(storage, storage.key);
      const current = stored.issue ? snapshot : stored;
      const ids = current.ids.includes(id) ? current.ids.filter(value => value !== id) : [...current.ids, id];
      try { storage.setItem(storage.key, JSON.stringify(ids)); volatile = false; publish({ ids, issue: "" }); }
      catch { volatile = true; publish({ ids, issue: "Không lưu được trên thiết bị; lựa chọn chỉ giữ trong phiên hiện tại." }); }
    },
  };
}
