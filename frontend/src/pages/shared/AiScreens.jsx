import { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useDemo } from "../../context/DemoContext";
import { useAuth } from "../../context/AuthContext";
import { available } from "../../demo/model";
import {
  Page,
  Field,
  Notice,
  Missing,
  money,
  toUTC,
} from "../../components/common/PageKit";
import VehicleCard from "../../components/vehicle/VehicleCard";
import EmptyState from "../../components/common/EmptyState";
import LoadingSkeleton from "../../components/common/LoadingSkeleton";

function useDemoDelay() {
  const timer = useRef(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => () => clearTimeout(timer.current), []);
  function run(callback) {
    clearTimeout(timer.current);
    setBusy(true);
    timer.current = setTimeout(() => {
      callback();
      setBusy(false);
    }, 450);
  }
  return [busy, run];
}
function AiDisclaimer() {
  return (
    <Notice>
      Kết quả minh họa bằng dữ liệu mẫu, không gọi Gemini hoặc Map. Quyền AI của
      Guest chưa chốt; quyền xem bản demo không xác định chính sách sản phẩm.
    </Notice>
  );
}

export function RecommendScreen() {
  const { data } = useDemo();
  const [people, setPeople] = useState(4),
    [budget, setBudget] = useState(1000000),
    [request, setRequest] = useState("");
  const [start, setStart] = useState(""),
    [end, setEnd] = useState(""),
    [failure, setFailure] = useState("none");
  const [result, setResult] = useState(null),
    [error, setError] = useState("");
  const [busy, run] = useDemoDelay();
  function submit(e) {
    e.preventDefault();
    setError("");
    setResult(null);
    if ((start || end) && (!start || !end || start >= end)) {
      setError("Nhập đủ khoảng thuê hợp lệ hoặc để trống cả hai mốc.");
      return;
    }
    if (start && Date.parse(`${start}:00+07:00`) <= Date.now()) {
      setError("Giờ nhận phải nằm trong tương lai.");
      return;
    }
    run(() => {
      if (failure === "parse") {
        setError(
          "Mô phỏng lỗi hiểu yêu cầu tự nhiên. Hãy kiểm tra tiêu chí có cấu trúc và thử lại.",
        );
        return;
      }
      const items = data.vehicles
        .filter(
          (v) =>
            v.status === "ACTIVE" &&
            v.seats >= Number(people) &&
            v.price <= Number(budget) &&
            (!start ||
              available(
                data.vehicles,
                data.bookings,
                v.id,
                toUTC(start),
                toUTC(end),
              )),
        )
        .map((v) => {
          const seatsScore = Math.max(10, 40 - (v.seats - Number(people)) * 5);
          const budgetScore = Math.round(
            60 * (1 - v.price / (Number(budget) * 2)),
          );
          return {
            vehicle: v,
            seatsScore,
            budgetScore,
            score: seatsScore + budgetScore,
          };
        })
        .sort((a, b) => b.score - a.score);
      setResult({
        items,
        start,
        end,
        request,
        explanationMissing: failure === "explain",
      });
    });
  }
  const resultParams = new URLSearchParams();
  if (result?.start) {
    resultParams.set("start", result.start);
    resultParams.set("end", result.end);
  }
  return (
    <Page
      title="Tìm xe hợp với bạn"
      description="Cho KDrive biết bạn đi cùng ai và cần gì cho chuyến đi."
    >
      <AiDisclaimer />
      <div className="grid gap-6 lg:grid-cols-[330px_1fr]">
        <form className="panel h-fit space-y-4" onSubmit={submit}>
          <Field label="Số người">
            <input
              type="number"
              min={1}
              max={9}
              required
              value={people}
              onChange={(e) => setPeople(e.target.value)}
            />
          </Field>
          <Field label="Ngân sách thuê / 24 giờ (VND)">
            <input
              type="number"
              min={1}
              step={1}
              required
              value={budget}
              onChange={(e) => setBudget(e.target.value)}
            />
          </Field>
          <Field label="Nhận xe (tùy chọn, giờ Việt Nam)">
            <input
              type="datetime-local"
              value={start}
              onChange={(e) => setStart(e.target.value)}
            />
          </Field>
          <Field label="Trả xe (tùy chọn, giờ Việt Nam)">
            <input
              type="datetime-local"
              value={end}
              onChange={(e) => setEnd(e.target.value)}
            />
          </Field>
          <Field
            label="Mong muốn cho chuyến đi"
            hint="Demo chỉ sử dụng số người, ngân sách và thời gian để lọc; chưa phân tích văn bản."
          >
            <textarea
              rows={3}
              maxLength={1000}
              value={request}
              onChange={(e) => setRequest(e.target.value)}
              placeholder="Ví dụ: đi cùng gia đình, muốn xe rộng rãi..."
            />
          </Field>
          <Field label="Tình huống demo">
            <select
              value={failure}
              onChange={(e) => setFailure(e.target.value)}
            >
              <option value="none">Kết quả bình thường</option>
              <option value="explain">Lỗi giải thích AI</option>
              <option value="parse">Lỗi hiểu yêu cầu</option>
            </select>
          </Field>
          <button className="btn w-full" disabled={busy}>
            {busy ? "Đang chuẩn bị kết quả mẫu..." : "Xem gợi ý mẫu"}
          </button>
        </form>
        <div>
          {error ? <Notice error>{error}</Notice> : null}
          {busy ? (
            <LoadingSkeleton rows={3} />
          ) : result ? (
            <>
              <Notice>
                {result.start
                  ? "Đã kiểm tra theo lịch demo; cần kiểm tra lại trước khi đặt."
                  : "Chưa có ngày thuê: kết quả chỉ thể hiện độ phù hợp, chưa xác nhận khả dụng."}
              </Notice>
              {result.explanationMissing ? (
                <Notice error>
                  Giải thích AI tạm thời không khả dụng (mô phỏng). Danh sách và
                  điểm mẫu vẫn được giữ.
                </Notice>
              ) : null}
              {result.items.length ? (
                <div className="grid gap-5 sm:grid-cols-2">
                  {result.items.map((item, index) => (
                    <div key={item.vehicle.id}>
                      <p className="mb-2 text-sm font-semibold text-teal-800">
                        #{index + 1} · {item.score}/100 điểm minh họa
                      </p>
                      <VehicleCard
                        vehicle={item.vehicle}
                        search={`?${resultParams}`}
                      />
                      <div className="px-2 py-3 text-xs leading-6 text-slate-600">
                        <p>
                          Chỗ ngồi: {item.seatsScore}/40 · Ngân sách:{" "}
                          {item.budgetScore}/60
                        </p>
                        {!result.explanationMissing ? (
                          <p>
                            Đủ chỗ cho nhóm, giá mẫu {money(item.vehicle.price)}
                            /ngày trong ngân sách đã chọn.
                          </p>
                        ) : null}
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <EmptyState
                  title="Không có xe phù hợp"
                  description="Thử tăng ngân sách, đổi thời gian hoặc giảm yêu cầu số chỗ."
                />
              )}
            </>
          ) : (
            <EmptyState
              title="Chuyến đi của bạn bắt đầu từ đây"
              description="Nhập nhu cầu bên trái để xem gợi ý xe mẫu và lý do phù hợp."
            />
          )}
        </div>
      </div>
    </Page>
  );
}

function Timeline({ plan }) {
  return (
    <div className="space-y-5">
      <Notice>
        Ước tính tuyến minh họa, không phải dữ liệu Map đã kiểm chứng. Không
        dùng bản demo để dẫn đường.
      </Notice>
      {plan.days.map((day) => (
        <section className="panel" key={day.date}>
          <h2 className="mb-5 text-lg font-semibold">
            Ngày {day.number} · {day.date.split("-").reverse().join("/")}
          </h2>
          <ol className="space-y-5">
            {day.items.map((item, index) => (
              <li
                className="border-l-2 border-teal-200 pl-5"
                key={`${item.locationId}-${index}`}
              >
                <div className="flex flex-wrap justify-between gap-3">
                  <h3 className="font-semibold">{item.name}</h3>
                  <span className="text-sm text-teal-700">{item.time}</span>
                </div>
                <p className="mt-1 text-sm text-slate-500">
                  {item.category} · {item.lat}, {item.lng}
                </p>
                {item.route ? (
                  <p className="mt-2 text-xs text-slate-500">
                    Chặng trước → điểm này: {item.route.distanceKm} km /{" "}
                    {item.route.minutes} phút · Nguồn: bộ dữ liệu demo
                  </p>
                ) : (
                  <p className="mt-2 text-xs text-slate-500">
                    Điểm bắt đầu ngày — chưa có chặng di chuyển trước đó
                  </p>
                )}
              </li>
            ))}
          </ol>
        </section>
      ))}
    </div>
  );
}

export function ItineraryScreen() {
  const { data, update } = useDemo();
  const { user } = useAuth();
  const [start, setStart] = useState(() =>
    new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10),
  );
  const [days, setDays] = useState(2),
    [people, setPeople] = useState(4),
    [budget, setBudget] = useState(3000000),
    [interest, setInterest] = useState("Tất cả"),
    [failure, setFailure] = useState("none");
  const [plan, setPlan] = useState(null),
    [error, setError] = useState(""),
    [savedId, setSavedId] = useState("");
  const [busy, run] = useDemoDelay();
  function submit(e) {
    e.preventDefault();
    setError("");
    setPlan(null);
    setSavedId("");
    run(() => {
      if (failure !== "none") {
        setError(
          failure === "map"
            ? "Thiếu dữ liệu tuyến kiểm chứng (mô phỏng Map lỗi). Không lưu hành trình. Hãy chọn tình huống bình thường rồi thử lại."
            : "Kết quả lịch trình không hợp lệ (mô phỏng Gemini lỗi). Không lưu hành trình. Hãy thử lại.",
        );
        return;
      }
      const candidates = data.locations.filter(
        (l) =>
          l.status === "ACTIVE" &&
          (interest === "Tất cả" || l.category === interest),
      );
      if (!candidates.length) {
        setError("Không có địa điểm phù hợp. Hãy đổi sở thích.");
        return;
      }
      const itineraryDays = Array.from(
        { length: Number(days) },
        (_, index) => ({
          number: index + 1,
          date: new Date(Date.parse(`${start}T00:00:00Z`) + index * 86400000)
            .toISOString()
            .slice(0, 10),
          items: candidates.slice(0, 3).map((l, i) => ({
            locationId: l.id,
            name: l.name,
            category: l.category,
            lat: l.lat,
            lng: l.lng,
            time: ["09:00 – 11:00", "13:00 – 15:00", "16:00 – 18:00"][i],
            route:
              i === 0
                ? null
                : {
                    distanceKm: i === 1 ? 5 : 28,
                    minutes: i === 1 ? 15 : 45,
                    source: "DEMO_FIXTURE",
                  },
          })),
        }),
      );
      setPlan({
        title: `Khám phá Đà Nẵng · ${days} ngày`,
        start,
        people: Number(people),
        budget: Number(budget),
        interest,
        days: itineraryDays,
        demoOnly: true,
        routeSource: "DEMO_FIXTURE",
        generatedAt: new Date().toISOString(),
      });
    });
  }
  function save() {
    if (user?.role !== "USER" || !plan || savedId) return;
    const id = `trip-${Date.now()}`;
    try {
      update((state) => {
        if (
          plan.days.some((day) =>
            day.items.some(
              (item, i) =>
                !state.locations.some(
                  (l) => l.id === item.locationId && l.status === "ACTIVE",
                ) ||
                (i > 0 && !item.route),
            ),
          )
        )
          throw new Error(
            "Địa điểm hoặc tuyến mẫu đã thay đổi. Vui lòng tạo lại hành trình.",
          );
        return {
          ...state,
          itineraries: [{ ...plan, id, userId: user.id }, ...state.itineraries],
        };
      });
      setSavedId(id);
    } catch (err) {
      setError(err.message);
    }
  }
  return (
    <Page
      title="Một hành trình, đúng sở thích"
      description="Thử bố cục kế hoạch theo ngày với bộ địa điểm Đà Nẵng và Hội An mẫu."
    >
      <AiDisclaimer />
      <div className="grid gap-6 lg:grid-cols-[330px_1fr]">
        <form className="panel h-fit space-y-4" onSubmit={submit}>
          <Field label="Khu vực demo">
            <input readOnly value="Đà Nẵng – Hội An" />
          </Field>
          <Field label="Ngày bắt đầu (giờ Việt Nam)">
            <input
              type="date"
              required
              value={start}
              onChange={(e) => setStart(e.target.value)}
            />
          </Field>
          <Field label="Số ngày (demo 1–3)">
            <input
              type="number"
              required
              min={1}
              max={3}
              value={days}
              onChange={(e) => setDays(e.target.value)}
            />
          </Field>
          <Field label="Số người">
            <input
              type="number"
              required
              min={1}
              max={9}
              value={people}
              onChange={(e) => setPeople(e.target.value)}
            />
          </Field>
          <Field label="Ngân sách chuyến đi (VND)">
            <input
              type="number"
              required
              min={1}
              step={1}
              value={budget}
              onChange={(e) => setBudget(e.target.value)}
            />
          </Field>
          <Field label="Sở thích">
            <select
              value={interest}
              onChange={(e) => setInterest(e.target.value)}
            >
              {["Tất cả", "Văn hóa", "Thiên nhiên", "Ẩm thực"].map((v) => (
                <option key={v}>{v}</option>
              ))}
            </select>
          </Field>
          <Field label="Tình huống demo">
            <select
              value={failure}
              onChange={(e) => setFailure(e.target.value)}
            >
              <option value="none">Kết quả mẫu đầy đủ</option>
              <option value="map">Thiếu dữ liệu tuyến</option>
              <option value="gemini">Kết quả AI không hợp lệ</option>
            </select>
          </Field>
          <p className="text-xs leading-5 text-slate-500">
            Ngân sách và số người được lưu làm tiêu chí mẫu; chưa có tối ưu chi
            phí hay lịch thực tế. Các ngày lặp lại bộ điểm để minh họa bố cục.
          </p>
          <button className="btn w-full" disabled={busy}>
            {busy ? "Đang dựng kế hoạch mẫu..." : "Tạo hành trình mẫu"}
          </button>
        </form>
        <div>
          {error ? <Notice error>{error}</Notice> : null}
          {busy ? (
            <LoadingSkeleton rows={4} />
          ) : plan ? (
            <>
              <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
                <h2 className="text-xl font-bold">{plan.title}</h2>
                {savedId ? (
                  <Link
                    className="btn-secondary"
                    to={`/user/itineraries/${savedId}`}
                  >
                    Xem bản đã lưu
                  </Link>
                ) : user?.role === "USER" ? (
                  <button className="btn" onClick={save}>
                    Lưu hành trình demo
                  </button>
                ) : (
                  <p className="text-sm text-slate-500">
                    Chọn vai trò User để lưu bản mẫu.
                  </p>
                )}
              </div>
              {savedId ? (
                <Notice>Đã lưu bản mẫu trong phiên trình duyệt.</Notice>
              ) : null}
              <Timeline plan={plan} />
            </>
          ) : (
            <EmptyState
              title="Dành chỗ cho những trải nghiệm mới"
              description="Điền thông tin chuyến đi để xem lịch trình mẫu theo ngày."
            />
          )}
        </div>
      </div>
    </Page>
  );
}

export function SavedItinerariesScreen() {
  const { data } = useDemo();
  const { user } = useAuth();
  const rows = data.itineraries.filter((p) => p.userId === user.id);
  return (
    <Page
      title="Hành trình của tôi"
      description="Các kế hoạch demo bạn đã lưu trong phiên trình duyệt."
      actions={
        <Link className="btn" to="/ai/itinerary">
          Tạo hành trình
        </Link>
      }
    >
      {rows.length ? (
        <div className="grid gap-5 sm:grid-cols-2">
          {rows.map((p) => (
            <article className="panel" key={p.id}>
              <p className="eyebrow">HÀNH TRÌNH DEMO</p>
              <h2 className="mt-3 text-xl font-bold">{p.title}</h2>
              <p className="my-4 text-sm text-slate-500">
                {p.start} · {p.people} người · Ngân sách nhập {money(p.budget)}
              </p>
              <Link className="btn-secondary" to={`/user/itineraries/${p.id}`}>
                Xem kế hoạch →
              </Link>
            </article>
          ))}
        </div>
      ) : (
        <EmptyState
          title="Chưa có hành trình đã lưu"
          description="Tạo một kế hoạch mẫu và lưu để xem lại tại đây."
          action={
            <Link className="btn" to="/ai/itinerary">
              Lên kế hoạch chuyến đi
            </Link>
          }
        />
      )}
    </Page>
  );
}
export function ItineraryDetailScreen() {
  const { itineraryId } = useParams();
  const { data } = useDemo();
  const { user } = useAuth();
  const plan = data.itineraries.find(
    (p) => p.id === itineraryId && p.userId === user.id,
  );
  return (
    <Page
      title={plan?.title || "Chi tiết hành trình"}
      actions={
        <Link className="btn-secondary" to="/user/itineraries">
          ← Hành trình đã lưu
        </Link>
      }
    >
      {plan ? <Timeline plan={plan} /> : <Missing back="/user/itineraries" />}
    </Page>
  );
}
