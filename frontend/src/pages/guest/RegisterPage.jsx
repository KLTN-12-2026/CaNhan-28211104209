import { useRef, useState } from "react";
import PasswordField from "../../components/common/PasswordField";
import { passwordError, passwordHint } from "../../utils/passwordPolicy";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import { getErrorMessage, homePathForRole } from "../../utils/roles";

export default function RegisterPage() {
  const { register, isAuthenticated, user, loading } = useAuth();
  const navigate = useNavigate();

  const [form, setForm] = useState({ fullName: "", email: "", password: "" });
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const inFlight = useRef(false);

  if (!loading && isAuthenticated && user) {
    return <Navigate to={homePathForRole(user.role)} replace />;
  }

  const onChange = (event) => {
    setForm((prev) => ({ ...prev, [event.target.name]: event.target.value }));
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    if (inFlight.current) return;
    const invalid = passwordError(form.password);
    if (invalid) {
      setError(invalid);
      return;
    }
    inFlight.current = true;
    setError("");
    setSubmitting(true);
    try {
      const result = await register(form);
      if (result.token && result.user?.role === "USER") {
        navigate(homePathForRole(result.user.role), { replace: true });
      } else {
        navigate("/login", { replace: true, state: { registered: true } });
      }
    } catch (err) {
      setError(
        getErrorMessage(err, "Không thể đăng ký. Vui lòng kiểm tra thông tin."),
      );
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  };

  return (
    <div className="mx-auto max-w-md px-4 py-16">
      <h1 className="text-2xl font-bold text-slate-900">Đăng ký tài khoản</h1>
      <p className="mt-2 text-sm text-slate-500">
        Tạo tài khoản để đặt xe và theo dõi đơn thuê.
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
          Họ và tên
          <input
            name="fullName"
            autoComplete="name"
            required
            value={form.fullName}
            onChange={onChange}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-teal-600 focus:ring-2 focus:ring-teal-100"
          />
        </label>
        <label className="block text-sm font-medium text-slate-700">
          Email
          <input
            name="email"
            autoComplete="email"
            type="email"
            required
            value={form.email}
            onChange={onChange}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-teal-600 focus:ring-2 focus:ring-teal-100"
          />
        </label>
        <PasswordField label="Mật khẩu" name="password" required minLength={8} maxLength={72} autoComplete="new-password" value={form.password} onChange={onChange} />
        <p className="text-sm text-slate-600">{passwordHint}</p>
        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded-lg bg-teal-700 py-2.5 text-sm font-semibold text-white hover:bg-teal-800 disabled:opacity-60"
        >
          {submitting ? "Đang tạo tài khoản..." : "Đăng ký"}
        </button>
      </form>
      <p className="mt-4 text-center text-sm text-slate-600">
        Đã có tài khoản?{" "}
        <Link
          to="/login"
          className="font-semibold text-teal-700 hover:underline"
        >
          Đăng nhập
        </Link>
      </p>
    </div>
  );
}
