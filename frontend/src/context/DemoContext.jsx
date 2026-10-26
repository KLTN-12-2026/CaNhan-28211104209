import { createContext, useContext, useEffect, useRef, useState } from "react";
import { DEMO_MODE } from "../demo/config";
import { createSeed } from "../demo/seed";
import { simulateBooking, simulateTransition } from "../demo/model";
const DemoContext = createContext(null);
const STORAGE_KEY = "kdrive.demo.data.v1";
function initialData() {
  if (!DEMO_MODE) return null;
  try {
    const saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY));
    if (saved?.version === 1 && saved.data?.vehicles && saved.data?.bookings)
      return saved.data;
  } catch {
    /* Start a fresh preview when storage is unavailable. */
  }
  return createSeed();
}
export function DemoProvider({ children }) {
  const [data, setData] = useState(initialData);
  const ref = useRef(data);
  const [scenario, setScenario] = useState("ready");
  const [storageError, setStorageError] = useState("");
  const storageErrorSet = useRef(false);
  useEffect(() => {
    if (!DEMO_MODE) return;
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ version: 1, data }));
    } catch {
      if (!storageErrorSet.current) {
        storageErrorSet.current = true;
        setStorageError(
          "Trình duyệt không lưu được dữ liệu demo. Thay đổi chỉ giữ trong lần mở này.",
        );
      }
    }
  }, [data]);
  function update(recipe) {
    if (!DEMO_MODE) throw new Error("Chức năng này chưa kết nối API.");
    const next = recipe(ref.current);
    ref.current = next;
    setData(next);
  }
  function createBooking(actor, input) {
    let created;
    update((state) => {
      created = simulateBooking(state, actor, input);
      return { ...state, bookings: [created, ...state.bookings] };
    });
    return created;
  }
  function transition(actor, id, next, details) {
    update((state) => {
      const booking = state.bookings.find((b) => b.id === id);
      if (!booking) throw new Error("Đơn không tồn tại.");
      const changed = simulateTransition(booking, actor, next, details);
      return {
        ...state,
        bookings: state.bookings.map((b) => (b.id === id ? changed : b)),
        vehicles:
          next === "RETURNED" && details.maintenance
            ? state.vehicles.map((v) =>
                v.id === booking.vehicleId
                  ? { ...v, status: "MAINTENANCE" }
                  : v,
              )
            : state.vehicles,
      };
    });
  }
  return (
    <DemoContext.Provider
      value={{
        data,
        update,
        createBooking,
        transition,
        scenario,
        setScenario,
        storageError,
        reset: () => {
          update(() => createSeed());
          setScenario("ready");
        },
      }}
    >
      {children}
    </DemoContext.Provider>
  );
}
export const useDemo = () => useContext(DemoContext);
