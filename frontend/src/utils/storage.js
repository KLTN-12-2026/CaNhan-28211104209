const TOKEN_KEY = "kdrive.jwt";
const USER_KEY = "kdrive.user";
export const STORAGE_ERROR = "Không thể đọc hoặc lưu phiên trên thiết bị. Vui lòng cho phép lưu trữ rồi đăng nhập lại.";
export const STORAGE_CLEAR_WARNING = "Đã kết thúc phiên trong trang này nhưng không xóa được phiên đã lưu. Hãy cho phép lưu trữ và đăng xuất lại trước khi đóng trang.";

// A failed removal must never let a leftover credential authenticate this page.
let sessionBlocked = false;

export function readStoredSession() {
  if (sessionBlocked) return { token: null, issue: STORAGE_ERROR };
  try {
    return { token: localStorage.getItem(TOKEN_KEY), issue: "" };
  } catch {
    return { token: null, issue: STORAGE_ERROR };
  }
}
export function getStoredToken() { return readStoredSession().token; }
export function setStoredToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
    sessionBlocked = false;
    return { issue: "" };
  } catch {
    sessionBlocked = true;
    return { issue: STORAGE_ERROR };
  }
}
export function getStoredUser() {
  if (sessionBlocked) return null;
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch { return null; }
}
export function setStoredUser(user) {
  try {
    if (user) localStorage.setItem(USER_KEY, JSON.stringify(user));
    else localStorage.removeItem(USER_KEY);
    return { issue: "" };
  } catch { return { issue: STORAGE_ERROR }; }
}
export function clearSession() {
  sessionBlocked = true;
  let issue = "";
  for (const key of [TOKEN_KEY, USER_KEY]) {
    try { localStorage.removeItem(key); }
    catch { issue = STORAGE_CLEAR_WARNING; }
  }
  sessionBlocked = Boolean(issue);
  return { issue };
}
export function storeSession(token, user) {
  // Commit the credential last; never expose a partially persisted session.
  const result = setStoredUser(user);
  const issue = result.issue || setStoredToken(token).issue;
  if (issue) {
    clearSession();
    sessionBlocked = true;
  }
  return { issue };
}
