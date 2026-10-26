import { useState } from "react";
import { useDemo } from "../../context/DemoContext";
import { useAuth } from "../../context/AuthContext";
import { Page, Field, Notice } from "../../components/common/PageKit";
export default function ProfileScreen() {
  const { user } = useAuth();
  const { data, update } = useDemo();
  const profile = data.accounts.find((a) => a.id === user.id) || user;
  const [name, setName] = useState(profile.fullName),
    [phone, setPhone] = useState(profile.phone || ""),
    [message, setMessage] = useState("");
  const [passwords, setPasswords] = useState({
    current: "",
    next: "",
    confirm: "",
  });
  const [passwordMessage, setPasswordMessage] = useState("");
  const [passwordError, setPasswordError] = useState(false);
  function checkPasswordForm(event) {
    event.preventDefault();
    if (
      passwords.next !== passwords.confirm ||
      passwords.next === passwords.current
    ) {
      setPasswordError(true);
      setPasswordMessage(
        "Mật khẩu mới cần khác mật khẩu cũ và khớp với phần nhập lại.",
      );
      return;
    }
    setPasswords({ current: "", next: "", confirm: "" });
    setPasswordError(false);
    setPasswordMessage(
      "Biểu mẫu hợp lệ trong demo. Không đổi mật khẩu thật và không lưu các giá trị đã nhập.",
    );
  }
  function save(e) {
    e.preventDefault();
    if (!name.trim()) {
      setMessage("Vui lòng nhập họ tên hợp lệ.");
      return;
    }
    update((state) => ({
      ...state,
      accounts: state.accounts.map((a) =>
        a.id === user.id ? { ...a, fullName: name.trim(), phone } : a,
      ),
    }));
    setMessage("Đã lưu hồ sơ mẫu trong phiên demo.");
  }
  return (
    <Page
      title="Thông tin cá nhân"
      description="Quản lý thông tin liên hệ dùng cho chuyến thuê."
    >
      <div className="grid gap-6 md:grid-cols-2">
        <form className="panel space-y-5" onSubmit={save}>
          {message ? <Notice>{message}</Notice> : null}
          <Field label="Họ và tên">
            <input
              required
              maxLength={100}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </Field>
          <Field label="Email">
            <input readOnly value={profile.email} />
          </Field>
          <Field label="Số điện thoại (mẫu)">
            <input
              type="tel"
              maxLength={20}
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder="Không nhập thông tin cá nhân thật"
            />
          </Field>
          <button className="btn">Lưu hồ sơ mẫu</button>
        </form>
        <section className="panel h-fit">
          <h2 className="mb-4 text-lg font-semibold">
            Mật khẩu & phiên đăng nhập
          </h2>
          <Notice>
            Chỉ nhập giá trị thử để kiểm tra biểu mẫu. Demo không gửi hoặc lưu
            mật khẩu; đổi mật khẩu thật cần kết nối API xác thực.
          </Notice>
          <form
            className="mb-5 space-y-4"
            onSubmit={checkPasswordForm}
            autoComplete="off"
          >
            {passwordMessage ? (
              <Notice error={passwordError}>{passwordMessage}</Notice>
            ) : null}
            {[
              ["current", "Mật khẩu hiện tại (mẫu)"],
              ["next", "Mật khẩu mới (mẫu)"],
              ["confirm", "Nhập lại mật khẩu mới (mẫu)"],
            ].map(([key, label]) => (
              <Field
                key={key}
                label={label}
                hint={
                  key === "next"
                    ? "Kiểm tra form demo: tối thiểu 8 ký tự; chính sách thật chờ API xác nhận."
                    : undefined
                }
              >
                <input
                  type="password"
                  required
                  minLength={key === "current" ? 1 : 8}
                  maxLength={128}
                  autoComplete="off"
                  value={passwords[key]}
                  onChange={(event) =>
                    setPasswords({ ...passwords, [key]: event.target.value })
                  }
                />
              </Field>
            ))}
            <button className="btn-secondary">Kiểm tra đổi mật khẩu mẫu</button>
          </form>
          <p className="text-sm leading-6 text-slate-600">
            Chính sách đã duyệt: phiên ngắn hạn, hết phiên đăng nhập lại; đăng
            xuất thu hồi mọi phiên tại server. Bản demo chỉ kết thúc vai trò mẫu
            trên trình duyệt.
          </p>
        </section>
      </div>
    </Page>
  );
}
