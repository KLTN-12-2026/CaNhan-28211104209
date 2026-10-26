import { useState } from "react";
import { Link, NavLink, useNavigate } from "react-router-dom";
import { Car, ChevronDown, LogOut, Menu, UserRound, X } from "lucide-react";
import { useAuth } from "../../context/AuthContext";
import { homePathForRole } from "../../utils/roles";
import { DEMO_MODE } from "../../demo/config";

const publicLinks = [
  { to: "/vehicles", label: "Tìm xe" },
  { to: "/rental-guide", label: "Hướng dẫn thuê xe" },
  ...(DEMO_MODE
    ? [
        { to: "/ai/recommend", label: "Gợi ý AI" },
        { to: "/ai/itinerary", label: "Lập lịch trình AI" },
      ]
    : []),
];

const userMenuLinks = [
  { to: "/user/bookings", label: "Đơn thuê của tôi" },
  { to: "/user/itineraries", label: "Lịch trình đã lưu" },
  { to: "/user/profile", label: "Thông tin cá nhân" },
];

function navClass({ isActive }) {
  return `rounded-lg px-3 py-2 text-sm font-medium transition ${
    isActive
      ? "bg-teal-50 text-teal-800"
      : "text-slate-600 hover:bg-slate-100 hover:text-slate-900"
  }`;
}

export default function Navbar() {
  const { user, isAuthenticated, logout, logoutWarning } = useAuth();
  const navigate = useNavigate();
  const [mobileOpen, setMobileOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);

  const handleLogout = async () => {
    setMenuOpen(false);
    setMobileOpen(false);
    const request = logout();
    navigate("/login", { replace: true });
    await request;
  };

  return (
    <header className="urban-navbar sticky top-0 z-40 border-b border-slate-200 bg-white/90 backdrop-blur">
      {logoutWarning ? (
        <p role="alert" className="bg-rose-50 px-4 py-2 text-sm text-rose-800">
          {logoutWarning}
        </p>
      ) : null}
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4">
        <Link
          to="/"
          className="flex items-center gap-3 text-2xl font-bold text-teal-800"
        >
          <span className="flex h-12 w-12 items-center justify-center rounded-xl bg-teal-700 text-white">
            <Car className="h-7 w-7" />
          </span>
          <span>
            KDrive
          </span>
        </Link>

        <nav className="hidden items-center gap-1 md:flex">
          {[...publicLinks, ...(isAuthenticated && user?.role === "USER" ? [
            { to: "/user/bookings", label: "Đơn thuê của tôi" },
            { to: "/user/profile", label: "Hồ sơ & GPLX" },
          ] : [])].map((link) => (
            <NavLink key={link.to} to={link.to} className={navClass}>
              {link.label}
            </NavLink>
          ))}
        </nav>

        <div className="flex items-center gap-2">
          {isAuthenticated && user ? (
            <div className="relative hidden md:block">
              <button
                type="button"
                className="flex items-center gap-2 rounded-full border border-slate-200 py-1 pl-1 pr-3 text-sm font-medium text-slate-700 hover:bg-slate-50"
                aria-expanded={menuOpen}
                onKeyDown={(event) => {
                  if (event.key === "Escape") setMenuOpen(false);
                }}
                onClick={() => setMenuOpen((open) => !open)}
              >
                <span className="flex h-8 w-8 items-center justify-center rounded-full bg-teal-100 text-teal-800">
                  <UserRound className="h-4 w-4" />
                </span>
                {user.fullName}
                <ChevronDown className="h-4 w-4 text-slate-400" />
              </button>
              {menuOpen ? (
                <div className="absolute right-0 mt-2 w-56 overflow-hidden rounded-xl border border-slate-200 bg-white py-1 shadow-lg">
                  <p className="border-b border-slate-100 px-3 py-2 text-xs text-slate-500">
                    {user.email}
                  </p>
                  {user.role === "USER" ? (
                    userMenuLinks.map((link) => (
                      <Link
                        key={link.to}
                        to={link.to}
                        className="block px-3 py-2 text-sm text-slate-700 hover:bg-slate-50"
                        onClick={() => setMenuOpen(false)}
                      >
                        {link.label}
                      </Link>
                    ))
                  ) : (
                    <Link
                      to={homePathForRole(user.role)}
                      className="block px-3 py-2 text-sm text-slate-700 hover:bg-slate-50"
                      onClick={() => setMenuOpen(false)}
                    >
                      Vào khu vực làm việc
                    </Link>
                  )}
                  <button
                    type="button"
                    onClick={handleLogout}
                    className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-rose-600 hover:bg-rose-50"
                  >
                    <LogOut className="h-4 w-4" />
                    Đăng xuất
                  </button>
                </div>
              ) : null}
            </div>
          ) : (
            <div className="hidden items-center gap-2 md:flex">
              <Link
                to="/login"
                className="rounded-lg px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-100"
              >
                Đăng nhập
              </Link>
              <Link
                to="/register"
                className="rounded-lg bg-teal-700 px-3 py-2 text-sm font-semibold text-white hover:bg-teal-800"
              >
                Đăng ký
              </Link>
            </div>
          )}

          <button
            type="button"
            className="rounded-lg p-2 text-slate-700 hover:bg-slate-100 md:hidden"
            onClick={() => setMobileOpen((open) => !open)}
            aria-label="Mở menu"
            aria-expanded={mobileOpen}
          >
            {mobileOpen ? (
              <X className="h-5 w-5" />
            ) : (
              <Menu className="h-5 w-5" />
            )}
          </button>
        </div>
      </div>

      {mobileOpen ? (
        <div className="space-y-1 border-t border-slate-200 px-4 py-3 md:hidden">
          {publicLinks.map((link) => (
            <NavLink
              key={link.to}
              to={link.to}
              className={navClass}
              onClick={() => setMobileOpen(false)}
            >
              {link.label}
            </NavLink>
          ))}
          {isAuthenticated && user?.role === "USER"
            ? userMenuLinks.map((link) => (
                <Link
                  key={link.to}
                  to={link.to}
                  className="block rounded-lg px-3 py-2 text-sm text-slate-700 hover:bg-slate-100"
                  onClick={() => setMobileOpen(false)}
                >
                  {link.label}
                </Link>
              ))
            : null}
          {isAuthenticated && user?.role !== "USER" ? (
            <Link
              className="block rounded-lg px-3 py-2 text-sm font-semibold text-teal-800"
              to={homePathForRole(user?.role)}
              onClick={() => setMobileOpen(false)}
            >
              Vào khu vực làm việc
            </Link>
          ) : null}
          {isAuthenticated ? (
            <button
              type="button"
              onClick={handleLogout}
              className="block w-full rounded-lg px-3 py-2 text-left text-sm text-rose-600 hover:bg-rose-50"
            >
              Đăng xuất
            </button>
          ) : (
            <div className="flex gap-2 pt-2">
              <Link
                to="/login"
                className="flex-1 rounded-lg border border-slate-200 px-3 py-2 text-center text-sm"
              >
                Đăng nhập
              </Link>
              <Link
                to="/register"
                className="flex-1 rounded-lg bg-teal-700 px-3 py-2 text-center text-sm text-white"
              >
                Đăng ký
              </Link>
            </div>
          )}
        </div>
      ) : null}
    </header>
  );
}
