import { roleLabels } from "../utils/uiLabels";
import { Link, useNavigate } from "react-router-dom";
import { Car, LogOut } from "lucide-react";
import { useAuth } from "../context/AuthContext";

export default function WorkspaceTopbar({ areaLabel }) {
  const { user, logout, logoutWarning } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    const request = logout();
    navigate("/login", { replace: true });
    await request;
  };

  return (
    <header className="flex min-h-14 flex-wrap items-center justify-between gap-2 border-b border-slate-200 bg-white px-4 py-2">
      {logoutWarning ? (
        <p role="alert" className="w-full text-sm text-rose-800">
          {logoutWarning}
        </p>
      ) : null}
      <Link
        to="/"
        className="flex items-center gap-3 text-xl font-bold text-teal-800"
      >
        <Car className="h-7 w-7" />
        KDrive
        <span className="rounded bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">
          {areaLabel}
        </span>
      </Link>
      <div className="flex items-center gap-3">
        <div className="hidden text-right text-sm sm:block">
          <p className="font-medium text-slate-800">{user?.fullName}</p>
          <p className="text-xs text-slate-500">{roleLabels[user?.role] || "Chưa xác định"}</p>
        </div>
        <button
          type="button"
          onClick={handleLogout}
          className="inline-flex items-center gap-1 rounded-lg border border-slate-200 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
        >
          <LogOut className="h-4 w-4" />
          Đăng xuất
        </button>
      </div>
    </header>
  );
}
