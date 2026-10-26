import { Link } from "react-router-dom";
import { ShieldOff } from "lucide-react";

export default function UnauthorizedPage() {
  return (
    <div className="flex min-h-[70vh] flex-col items-center justify-center px-4 text-center">
      <ShieldOff className="h-12 w-12 text-amber-500" />
      <h1 className="mt-4 text-3xl font-bold text-slate-900">
        Không có quyền truy cập
      </h1>
      <p className="mt-2 max-w-md text-slate-600">
        Tài khoản hiện tại không được phép mở trang này. Hãy quay lại khu vực
        phù hợp với vai trò của bạn.
      </p>
      <Link
        to="/"
        className="mt-6 rounded-lg bg-teal-700 px-4 py-2 text-sm font-semibold text-white hover:bg-teal-800"
      >
        Về trang chủ
      </Link>
    </div>
  );
}
