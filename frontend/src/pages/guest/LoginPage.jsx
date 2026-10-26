import { useState } from "react";
import { Link, Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import {
  destinationForRole,
  getErrorMessage,
} from "../../utils/roles";

export default function LoginPage() {
  const { login, isAuthenticated, user, loading } = useAuth();
  const location = useLocation();
  const from = location.state?.from;

  const [form, setForm] = useState({ email: "", password: "" });
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [authenticatedUserId, setAuthenticatedUserId] = useState(null);

  if (!loading && isAuthenticated && user) {
    if (!authenticatedUserId || authenticatedUserId === user.id) {
      return (
        <Navigate
          to={destinationForRole(user.role, from)}
          replace
        />
      );
    }
  }

  const onChange = (event) => {
    setForm((prev) => ({ ...prev, [event.target.name]: event.target.value }));
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      const user = await login(form);
      setAuthenticatedUserId(user.id);
    } catch (err) {
      setError(getErrorMessage(err, "Email hoặc mật khẩu không đúng."));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="mx-auto max-w-md px-4 py-16">
      <h1 className="text-2xl font-bold text-slate-900">Đăng nhập</h1>
      {location.state?.registered ? (
        <p role="status" className="notice mt-4">
          Đã đăng ký tài khoản. Vui lòng đăng nhập.
        </p>
      ) : null}
      <p className="mt-2 text-sm text-slate-500">
        Sử dụng tài khoản đã đăng ký trên hệ thống.
      </p>
      <form
        onSubmit={onSubmit}
        className="mt-8 space-y-4 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm"
      >
        {error ? (
          <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">
            {error}
          </p>
        ) : null}
        <label className="block text-sm font-medium text-slate-700">
          Email
          <input
            name="email"
            type="email"
            required
            value={form.email}
            onChange={onChange}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-teal-600 focus:ring-2 focus:ring-teal-100"
          />
        </label>
        <label className="block text-sm font-medium text-slate-700">
          Mật khẩu
          <input
            name="password"
            type="password"
            required
            value={form.password}
            onChange={onChange}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-teal-600 focus:ring-2 focus:ring-teal-100"
          />
        </label>
        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded-lg bg-teal-700 py-2.5 text-sm font-semibold text-white hover:bg-teal-800 disabled:opacity-60"
        >
          {submitting ? "Đang đăng nhập..." : "Đăng nhập"}
        </button>
      </form>
      <p className="mt-4 text-center text-sm text-slate-600">
        Chưa có tài khoản?{" "}
        <Link
          to="/register"
          className="font-semibold text-teal-700 hover:underline"
        >
          Đăng ký
        </Link>
      </p>
    </div>
  );
}
