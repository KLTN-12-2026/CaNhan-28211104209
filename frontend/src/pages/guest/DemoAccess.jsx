import { Link, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import { homePathForRole } from "../../utils/roles";
import { Page, Notice } from "../../components/common/PageKit";
export default function DemoAccess({ register = false }) {
  const { selectDemoRole } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  return (
    <Page
      title={register ? "Đăng ký tài khoản" : "Trải nghiệm KDrive"}
      description="Chọn vai trò để xem các màn hình và thử quy trình bằng dữ liệu mẫu."
    >
      <div className="panel mx-auto max-w-xl">
        <Notice>
          Bản demo không thu thập mật khẩu và không tạo tài khoản thật. Phiên
          mẫu tách biệt với đăng nhập API.
        </Notice>
        <div className="grid gap-3">
          {["USER", "STAFF", "ADMIN"].map((role) => (
            <button
              className="btn-secondary justify-between"
              key={role}
              onClick={() => {
                selectDemoRole(role);
                const from = location.state?.from;
                navigate(
                  role === "USER" &&
                    typeof from === "string" &&
                    from.startsWith("/user/")
                    ? from
                    : homePathForRole(role),
                  { replace: true },
                );
              }}
            >
              {role === "USER"
                ? "Khách hàng"
                : role === "STAFF"
                  ? "Nhân viên vận hành"
                  : "Quản trị viên"}{" "}
              <span aria-hidden="true">→</span>
            </button>
          ))}
        </div>
        <Link
          className="mt-5 inline-block text-sm text-teal-700 underline"
          to="/vehicles"
        >
          Tiếp tục xem xe với tư cách khách
        </Link>
      </div>
    </Page>
  );
}
