import AdminAreaContext from "../context/AdminAreaContext";
import { Outlet } from "react-router-dom";
import {
  Building2,
  Car,
  LayoutDashboard,
  MapPin,
  Star,
  Users,
} from "lucide-react";
import Sidebar from "../components/common/Sidebar";
import WorkspaceTopbar from "./WorkspaceTopbar";
import { DEMO_MODE } from "../demo/config";

const adminNav = [
  { to: "/admin", label: "Tổng quan", icon: LayoutDashboard, end: true },
  { to: "/admin/bookings", label: "Tra cứu đơn thuê", icon: Car },
  ...(!DEMO_MODE ? [{ to: "/admin/payments", label: "Cọc / Hoàn tiền", icon: Building2 }] : []),
  { to: "/admin/accounts", label: "Quản lý tài khoản", icon: Users },
  { to: "/admin/vehicles", label: "Quản lý xe", icon: Car },
  { to: "/admin/catalog", label: "Danh mục xe", icon: Building2 },
  ...(!DEMO_MODE ? [{ to: "/admin/branches", label: "Chi nhánh / Tuyến", icon: Building2 }] : []),
  { to: "/admin/locations", label: "Điểm du lịch", icon: MapPin },
  { to: "/admin/reviews", label: "Kiểm duyệt đánh giá", icon: Star },
];

export default function AdminLayout() {
  return (
    <AdminAreaContext.Provider value={true}><div className="admin-shell">
      <WorkspaceTopbar areaLabel="Quản trị" />
      <div className="admin-workspace">
        <Sidebar title="Quản trị hệ thống" items={adminNav} />
        <main className="admin-main" data-workspace-scroll>
          <Outlet />
        </main>
      </div>
    </div></AdminAreaContext.Provider>
  );
}
