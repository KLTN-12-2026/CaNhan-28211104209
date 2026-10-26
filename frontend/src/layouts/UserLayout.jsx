import { Outlet } from "react-router-dom";
import Footer from "../components/common/Footer";
import Navbar from "../components/common/Navbar";

export default function UserLayout() {
  return (
    <div className="urban-shell flex min-h-screen flex-col">
      <Navbar />
      <main className="mx-auto w-full max-w-7xl flex-1">
        <Outlet />
      </main>
      <Footer />
    </div>
  );
}
