import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import LoadingSkeleton from "../components/common/LoadingSkeleton";

export default function ProtectedRoute({ children, roles }) {
  const { isAuthenticated, user, loading, sessionError, retrySession } =
    useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-10">
        <LoadingSkeleton rows={3} />
      </div>
    );
  }

  if (sessionError)
    return (
      <div className="page-shell">
        <p role="alert" className="notice notice-error">
          {sessionError}
        </p>
        <button className="btn" onClick={retrySession}>
          Thử lại
        </button>
      </div>
    );
  if (!isAuthenticated || !user) {
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: location.pathname + location.search + location.hash }}
      />
    );
  }

  if (roles?.length && !roles.includes(user.role)) {
    return <Navigate to="/unauthorized" replace />;
  }

  return children;
}
