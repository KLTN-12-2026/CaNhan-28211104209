import { Link } from "react-router-dom";
import { Compass } from "lucide-react";

export default function NotFoundPage() {
  return (
    <div className="flex min-h-[70vh] flex-col items-center justify-center px-4 text-center">
      <Compass className="h-12 w-12 text-teal-600" />
      <p className="mt-4 text-sm font-semibold text-teal-700">404</p>
      <h1 className="mt-1 text-3xl font-bold text-slate-900">
        Không tìm thấy trang
      </h1>
      <p className="mt-2 max-w-md text-slate-600">
        Đường dẫn không tồn tại hoặc đã được chuyển sang vị trí khác.
      </p>
      <Link
        to="/"
        className="mt-6 rounded-lg bg-teal-700 px-4 py-2 text-sm font-semibold text-white hover:bg-teal-800"
      >
        Quay lại trang chủ
      </Link>
    </div>
  );
}
