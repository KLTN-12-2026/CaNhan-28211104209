import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { setAuthInterceptors } from "../api/client";
import { useAuth } from "../context/AuthContext";
import { DEMO_MODE } from "../demo/config";

export default function ApiErrorBridge() {
  const navigate = useNavigate();
  const { logout } = useAuth();

  useEffect(() => {
    if (DEMO_MODE) return undefined;
    setAuthInterceptors({
      unauthorized: () => {
        logout({ callApi: false });
        navigate("/login", { replace: true });
      },
      forbidden: () => {
        navigate("/unauthorized", { replace: true });
      },
    });
    return () => setAuthInterceptors();
  }, [logout, navigate]);

  return null;
}
