import {
  createContext,
  Fragment,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  useRef,
} from "react";
import {
  fetchCurrentUser,
  loginRequest,
  logoutRequest,
  registerRequest,
} from "../api/auth";
import { DEMO_MODE } from "../demo/config";
import { demoActors } from "../demo/seed";
import {
  clearSession,
  getStoredToken,
  readStoredSession,
  storeSession,
  STORAGE_ERROR,
  setStoredUser,
} from "../utils/storage";
import { createRequestGeneration } from "../utils/requestGeneration";
import { invalidateSessionRequests, setSessionGuard } from "../api/client";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [initialSession] = useState(() =>
    DEMO_MODE ? { token: null, issue: "" } : readStoredSession(),
  );
  const [token, setToken] = useState(initialSession.token);
  const activeToken = useRef(token);
  const [user, setUser] = useState(() => {
    if (!DEMO_MODE) return null;
    try {
      return demoActors[sessionStorage.getItem("kdrive.demo.role")] || null;
    } catch {
      return null;
    }
  });
  const [loading, setLoading] = useState(
    () => !DEMO_MODE && Boolean(initialSession.token),
  );
  const [sessionError, setSessionError] = useState(initialSession.issue);
  const [logoutWarning, setLogoutWarning] = useState("");
  const [retry, setRetry] = useState(0);
  const [sessionRevision, setSessionRevision] = useState(0);
  const [synchronizing, setSynchronizing] = useState(false);
  const identityRevision = useRef(0);
  const observedToken = useRef(initialSession.token);
  const [sessionGeneration] = useState(createRequestGeneration);
  const pendingLogout = useRef(null);
  const selectDemoRole = useCallback((role) => {
    if (!DEMO_MODE) return;
    setUser(demoActors[role] || null);
    try {
      sessionStorage.setItem("kdrive.demo.role", role);
    } catch {
      /* In-memory preview remains usable. */
    }
  }, []);

  const persistSession = useCallback((nextToken, nextUser) => {
    const { issue } = storeSession(nextToken, nextUser);
    if (issue) {
      activeToken.current = null;
      setToken(null);
      setUser(null);
      setSessionError(issue);
      throw new Error(issue);
    }
    activeToken.current = nextToken;
    observedToken.current = nextToken;
    setToken(nextToken);
    setUser(nextUser);
  }, []);
  const refreshUser = useCallback((nextUser) => {
    if (sessionRevision !== identityRevision.current || !token || getStoredToken() !== token || nextUser.id !== user?.id) return;
    const normalizedUser = {
      id: nextUser.id,
      fullName: nextUser.fullName,
      email: nextUser.email,
      role: nextUser.role,
    };
    const { issue } = setStoredUser(normalizedUser);
    if (issue) {
      clearSession();
      activeToken.current = null;
      setToken(null);
      setUser(null);
      setSessionError(issue);
      return;
    }
    setUser(normalizedUser);
  }, [token, user?.id, sessionRevision]);

  const synchronizeSession = useCallback(() => {
    sessionGeneration.invalidate();
    invalidateSessionRequests();
    observedToken.current = getStoredToken();
    activeToken.current = null;
    setToken(null);
    setUser(null);
    setSessionError("");
    setLogoutWarning("");
    setLoading(true);
    setSynchronizing(true);
    identityRevision.current += 1;
    setSessionRevision(identityRevision.current);
  }, [sessionGeneration]);

  useEffect(() => {
    if (DEMO_MODE) return undefined;
    const guard = (storedToken, issue) => {
      if (storedToken !== observedToken.current) synchronizeSession();
      return !issue && storedToken === activeToken.current;
    };
    const onStorage = event => {
      if (event.key !== null && event.key !== "kdrive.jwt") return;
      try {
        if (event.storageArea && event.storageArea !== window.localStorage) return;
      } catch {
        synchronizeSession();
        return;
      }
      synchronizeSession();
    };
    const onFocus = () => guard(getStoredToken(), readStoredSession().issue);
    setSessionGuard(guard);
    window.addEventListener("storage", onStorage);
    window.addEventListener("focus", onFocus);
    return () => {
      setSessionGuard(null);
      window.removeEventListener("storage", onStorage);
      window.removeEventListener("focus", onFocus);
    };
  }, [synchronizeSession]);

  const endSession = useCallback(
    async ({ callApi = true, expectedToken = activeToken.current, expectedRevision = identityRevision.current } = {}) => {
      if (DEMO_MODE) {
        selectDemoRole("GUEST");
        return;
      }
      if (expectedRevision !== identityRevision.current) return;
      if (expectedToken !== activeToken.current && !(activeToken.current === null && expectedToken === observedToken.current)) return;
      const stored = readStoredSession();
      if (!stored.issue && stored.token !== expectedToken) {
        synchronizeSession();
        return;
      }
      const generation = sessionGeneration.begin();
      invalidateSessionRequests();
      const tokenToRevoke = activeToken.current;
      const { issue } = clearSession();
      activeToken.current = null;
      observedToken.current = null;
      setToken(null);
      setUser(null);
      setSessionError("");
      setLogoutWarning(issue);
      setLoading(false);
      setSynchronizing(false);
      if (callApi && tokenToRevoke) {
        const request = logoutRequest(tokenToRevoke);
        pendingLogout.current = request;
        try {
          await request;
        } catch {
          if (sessionGeneration.isCurrent(generation)) {
            setLogoutWarning(
              [issue || "Đã đăng xuất trên thiết bị này.", "Chưa xác nhận được thu hồi phiên ở máy chủ do lỗi kết nối; phiên trên thiết bị khác có thể còn hiệu lực."].join(" "),
            );
          }
        } finally {
          if (pendingLogout.current === request) pendingLogout.current = null;
        }
      }
    },
    [selectDemoRole, sessionGeneration, synchronizeSession],
  );
  const logout = useCallback(options => endSession({ ...options, expectedToken: token, expectedRevision: sessionRevision }), [endSession, token, sessionRevision]);

  useEffect(() => {
    if (DEMO_MODE) return undefined;
    let cancelled = false;
    const generation = sessionGeneration.begin();

    async function hydrate() {
      setSessionError("");
      const { token: storedToken, issue } = readStoredSession();
      if (issue) {
        activeToken.current = null;
        setToken(null);
        setUser(null);
        setSessionError(issue);
        setLoading(false);
        setSynchronizing(false);
        return;
      }
      if (!storedToken) {
        setLoading(false);
        setSynchronizing(false);
        return;
      }
      try {
        const profile = await fetchCurrentUser(storedToken);
        if (readStoredSession().issue) throw new Error(STORAGE_ERROR);
        if (
          !cancelled &&
          sessionGeneration.isCurrent(generation) &&
          getStoredToken() === storedToken &&
          profile
        ) {
          persistSession(storedToken, {
            id: profile.id,
            fullName: profile.fullName,
            email: profile.email,
            role: profile.role,
          });
        }
      } catch (error) {
        if (!cancelled && sessionGeneration.isCurrent(generation) && !readStoredSession().issue && getStoredToken() !== storedToken) {
          synchronizeSession();
        }
        if (!cancelled && sessionGeneration.isCurrent(generation) && readStoredSession().issue) {
          activeToken.current = null;
          setToken(null);
          setUser(null);
          setSessionError(STORAGE_ERROR);
        }
        if (
          !cancelled &&
          sessionGeneration.isCurrent(generation) &&
          getStoredToken() === storedToken
        ) {
          if ([401, 403].includes(error.response?.status))
            await endSession({ callApi: false, expectedToken: storedToken });
          else {
            setUser(null);
            setSessionError(
              "Không thể kiểm tra phiên đăng nhập. Vui lòng thử lại khi kết nối ổn định.",
            );
          }
        }
      } finally {
        if (
          !cancelled &&
          sessionGeneration.isCurrent(generation)
        ) {
          setLoading(false);
          setSynchronizing(false);
        }
      }
    }

    hydrate();
    return () => {
      cancelled = true;
      sessionGeneration.invalidate(generation);
    };
  }, [persistSession, endSession, retry, sessionGeneration, sessionRevision, synchronizeSession]);

  const login = useCallback(
    async ({ email, password }) => {
      if (DEMO_MODE)
        throw new Error("Bản demo không nhận mật khẩu. Hãy chọn vai trò mẫu.");
      const generation = sessionGeneration.begin();
      invalidateSessionRequests();
      setLoading(true);
      setSessionError("");
      try {
        if (pendingLogout.current) await pendingLogout.current.catch(() => {});
        setLogoutWarning("");
        if (!sessionGeneration.isCurrent(generation)) {
          throw new Error("Yêu cầu đăng nhập đã bị thay thế.");
        }
        const startingToken = getStoredToken();
        const { token: nextToken, user: nextUser } = await loginRequest({
          email,
          password,
        });
        if (!nextToken || !nextUser) {
          throw new Error("Phản hồi đăng nhập không hợp lệ.");
        }
        if (sessionGeneration.isCurrent(generation) && getStoredToken() !== startingToken) synchronizeSession();
        if (!sessionGeneration.isCurrent(generation)) {
          throw new Error("Yêu cầu đăng nhập đã bị thay thế.");
        }
        persistSession(nextToken, {
          id: nextUser.id,
          fullName: nextUser.fullName,
          email: nextUser.email,
          role: nextUser.role,
        });
        return nextUser;
      } finally {
        if (sessionGeneration.isCurrent(generation)) {
          setLoading(false);
        }
      }
    },
    [persistSession, sessionGeneration, synchronizeSession],
  );

  const register = useCallback(
    async (payload) => {
      if (DEMO_MODE)
        throw new Error(
          "Bản demo không tạo tài khoản thật. Hãy dùng vai trò User mẫu.",
        );
      const generation = sessionGeneration.begin();
      invalidateSessionRequests();
      setLoading(true);
      setSessionError("");
      try {
        if (pendingLogout.current) await pendingLogout.current.catch(() => {});
        setLogoutWarning("");
        if (!sessionGeneration.isCurrent(generation)) {
          throw new Error("Yêu cầu đăng ký đã bị thay thế.");
        }
        const startingToken = getStoredToken();
        const result = await registerRequest(payload);
        if (sessionGeneration.isCurrent(generation) && getStoredToken() !== startingToken) synchronizeSession();
        if (!sessionGeneration.isCurrent(generation)) {
          throw new Error("Yêu cầu đăng ký đã bị thay thế.");
        }
        if (result.token && result.user) {
          persistSession(result.token, {
            id: result.user.id,
            fullName: result.user.fullName,
            email: result.user.email,
            role: result.user.role,
          });
        }
        return result;
      } finally {
        if (sessionGeneration.isCurrent(generation)) {
          setLoading(false);
        }
      }
    },
    [persistSession, sessionGeneration, synchronizeSession],
  );

  const value = useMemo(
    () => ({
      user,
      token,
      loading,
      isAuthenticated: DEMO_MODE ? Boolean(user) : Boolean(token && user),
      selectDemoRole,
      sessionError,
      logoutWarning,
      retrySession: () => {
        setLoading(true);
        setRetry((value) => value + 1);
      },
      login,
      register,
      logout,
      refreshUser,
    }),
    [
      user,
      token,
      loading,
      login,
      register,
      logout,
      refreshUser,
      selectDemoRole,
      sessionError,
      logoutWarning,
    ],
  );

  return <AuthContext.Provider value={value}>
    {synchronizing ? <p role="status" className="page-shell">Đang xác định phiên truy cập…</p> : <Fragment key={sessionRevision}>{children}</Fragment>}
  </AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth phải được dùng trong AuthProvider.");
  }
  return context;
}

export default AuthContext;
