import { Link } from "react-router-dom";
import { DEMO_MODE } from "../../demo/config";

export default function Footer() {
  return (
    <footer className="urban-footer border-t border-slate-200 bg-slate-900 text-slate-300">
      <div className="mx-auto grid max-w-7xl gap-8 px-4 py-10 sm:grid-cols-3">
        <div>
          <p className="text-lg font-semibold text-white">KDrive</p>
          <p className="mt-2 text-sm leading-6">
            {DEMO_MODE
              ? "Hệ thống cho thuê xe tự lái với bản xem trước gợi ý xe và lập kế hoạch hành trình AI."
              : "Thuê xe tự lái theo hành trình của bạn. Xem xe, kiểm tra giá và theo dõi đơn thuê tại KDrive."}
          </p>
        </div>
        <div>
          <p className="text-sm font-semibold text-white">Khám phá</p>
          <ul className="mt-3 space-y-2 text-sm">
            <li><Link to="/rental-guide" className="hover:text-white">Hướng dẫn thuê xe</Link></li>
            <li>
              <Link to="/vehicles" className="hover:text-white">
                Duyệt xe
              </Link>
            </li>
            <li><a href="/media/vehicles/licenses.html" className="hover:text-white">Nguồn ảnh &amp; giấy phép</a></li>
            {DEMO_MODE ? (
              <>
                <li>
                  <Link to="/ai/recommend" className="hover:text-white">
                    Gợi ý AI
                  </Link>
                </li>
                <li>
                  <Link to="/ai/itinerary" className="hover:text-white">
                    Lập lịch trình AI
                  </Link>
                </li>
              </>
            ) : null}
          </ul>
        </div>
        <div>
          <p className="text-sm font-semibold text-white">Lưu ý</p>
          <p className="mt-3 text-sm leading-6">
            {DEMO_MODE
              ? "Bản demo giữ các nghiệp vụ mẫu tách biệt với dữ liệu API."
              : "Chọn chi nhánh nhận/trả theo tuyến được cấu hình. Kiểm tra báo giá và thông tin đơn trước khi gửi yêu cầu."}
          </p>
        </div>
      </div>
      <div className="border-t border-slate-800 py-4 text-center text-xs text-slate-500">
        Đồ án tốt nghiệp · KDrive · {new Date().getFullYear()}
      </div>
    </footer>
  );
}
