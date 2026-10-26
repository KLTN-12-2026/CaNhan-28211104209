import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useDemo } from "../../context/DemoContext";
import { useAuth } from "../../context/AuthContext";
import {
  Field,
  Page,
  Notice,
  Missing,
  money,
  localInput,
  toUTC,
} from "../../components/common/PageKit";
import { VehicleVisual } from "../../components/vehicle/VehicleCard";
import { simulateBooking } from "../../demo/model";
export default function NewBookingPage() {
  const [params] = useSearchParams();
  const { data, createBooking } = useDemo();
  const { user } = useAuth();
  const navigate = useNavigate();
  const vehicleId = params.get("vehicleId");
  const vehicle = data.vehicles.find((v) => v.id === vehicleId);
  const [start, setStart] = useState(params.get("start") || localInput(3));
  const [end, setEnd] = useState(params.get("end") || localInput(4));
  const [quote, setQuote] = useState(null);
  const [error, setError] = useState("");
  const [accepted, setAccepted] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  if (!vehicle)
    return (
      <Page title="Tạo đơn thuê">
        <Missing />
      </Page>
    );
  function check(event) {
    event.preventDefault();
    setError("");
    setQuote(null);
    try {
      setQuote(
        simulateBooking(data, user, {
          vehicleId,
          startAt: toUTC(start),
          endAt: toUTC(end),
        }),
      );
    } catch (err) {
      setError(err.message);
    }
  }
  function submit() {
    if (submitting || !accepted || !quote) return;
    setSubmitting(true);
    setError("");
    try {
      if (vehicle.price !== quote.price) {
        setQuote(null);
        throw new Error("Giá mẫu đã thay đổi. Vui lòng kiểm tra lại.");
      }
      const created = createBooking(user, {
        vehicleId,
        startAt: toUTC(start),
        endAt: toUTC(end),
      });
      navigate(`/user/bookings/${created.id}`, {
        replace: true,
        state: { created: true },
      });
    } catch (err) {
      setError(err.message);
      setQuote(null);
      setSubmitting(false);
    }
  }
  return (
    <Page
      title="Lên kế hoạch thuê xe"
      description="Chọn thời gian, kiểm tra thông tin rồi gửi yêu cầu thuê mẫu."
      actions={
        <Link className="btn-secondary" to={`/vehicles/${vehicleId}`}>
          ← Chi tiết xe
        </Link>
      }
    >
      <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
        <div className="space-y-6">
          <form className="panel space-y-5" onSubmit={check}>
            <h2 className="text-lg font-semibold">
              01 · Thời gian & điểm nhận
            </h2>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Nhận xe (giờ Việt Nam)">
                <input
                  type="datetime-local"
                  required
                  value={start}
                  onChange={(e) => {
                    setStart(e.target.value);
                    setQuote(null);
                    setAccepted(false);
                  }}
                />
              </Field>
              <Field label="Trả xe (giờ Việt Nam)">
                <input
                  type="datetime-local"
                  required
                  value={end}
                  onChange={(e) => {
                    setEnd(e.target.value);
                    setQuote(null);
                    setAccepted(false);
                  }}
                />
              </Field>
            </div>
            <Field label="Nhận / trả cùng chi nhánh">
              <input
                readOnly
                value={
                  data.catalog.find((item) => item.type === "Chi nhánh")
                    ?.name || "Chi nhánh demo"
                }
              />
            </Field>
            <p className="text-sm text-slate-500">
              Mỗi 24 giờ là một ngày thuê, phần lẻ làm tròn lên.
            </p>
            {error ? <Notice error>{error}</Notice> : null}
            <button className="btn" type="submit">
              Kiểm tra lịch & giá mẫu
            </button>
          </form>
          {quote ? (
            <section className="panel space-y-4">
              <h2 className="text-lg font-semibold">
                02 · Kiểm tra & gửi yêu cầu
              </h2>
              <dl className="space-y-3 text-sm">
                <div className="flex justify-between gap-3">
                  <dt>
                    {quote.days} ngày × {money(quote.price)}
                  </dt>
                  <dd className="font-bold">{money(quote.total)}</dd>
                </div>
              </dl>
              <Notice>
                Đây là tiền thuê cơ bản mẫu. Cọc, phụ phí và tổng thanh toán
                cuối cùng chưa được xác định; không thu tiền trong demo.
              </Notice>
              <p className="text-sm leading-6 text-slate-600">
                Yêu cầu chờ xác nhận giữ lịch tối đa 2 giờ, không vượt giờ nhận
                xe. Khả dụng sẽ được kiểm tra lại khi gửi.
              </p>
              <label className="flex items-start gap-3 text-sm">
                <input
                  className="mt-1 h-4 w-4"
                  type="checkbox"
                  checked={accepted}
                  onChange={(e) => setAccepted(e.target.checked)}
                />
                Tôi đã kiểm tra thời gian thuê và hiểu đây là yêu cầu mẫu.
              </label>
              <button
                className="btn"
                disabled={!accepted || submitting}
                onClick={submit}
              >
                {submitting ? "Đang tạo..." : "Gửi yêu cầu thuê mẫu"}
              </button>
            </section>
          ) : null}
        </div>
        <aside className="panel h-fit">
          <VehicleVisual vehicle={vehicle} />
          <h2 className="mt-4 text-xl font-bold">{vehicle.name}</h2>
          <p className="mt-2 text-sm text-slate-500">
            {vehicle.seats} chỗ · {vehicle.transmission}
          </p>
          <p className="mt-4 text-xl font-bold text-teal-800">
            {money(vehicle.price)}{" "}
            <span className="text-xs font-normal text-slate-500">/ 24 giờ</span>
          </p>
        </aside>
      </div>
    </Page>
  );
}
