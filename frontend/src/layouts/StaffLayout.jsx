import { Outlet } from "react-router-dom";
import { Car, ClipboardList, Handshake, Inbox } from "lucide-react";
import Sidebar from "../components/common/Sidebar";
import WorkspaceTopbar from "./WorkspaceTopbar";

const staffNav = [
  { to: "/staff/queue", label: "Hàng đợi đơn duyệt", icon: Inbox },
  { to: "/staff/handover", label: "Bàn giao xe", icon: Handshake },
  { to: "/staff/return", label: "Nhận trả xe", icon: ClipboardList },
  { to: "/staff/fleet", label: "Trạng thái đội xe", icon: Car },
];

export default function StaffLayout() {
  return (
    <div className="flex min-h-screen flex-col bg-slate-100">
      <WorkspaceTopbar areaLabel="Nhân viên" />
      <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
        <Sidebar title="Vận hành" items={staffNav} />
        <main className="min-w-0 flex-1 p-4 lg:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
