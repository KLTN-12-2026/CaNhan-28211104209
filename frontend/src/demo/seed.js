export const demoActors = {
  USER: {
    id: "u1",
    role: "USER",
    fullName: "Minh Anh · Demo",
    email: "minhanh@example.test",
  },
  STAFF: {
    id: "s1",
    role: "STAFF",
    fullName: "Hoàng Nam · Demo",
    email: "staff@example.test",
  },
  ADMIN: {
    id: "a1",
    role: "ADMIN",
    fullName: "Quản trị · Demo",
    email: "admin@example.test",
  },
};

export function createSeed(now = Date.now()) {
  const at = (days) => new Date(now + days * 86400000).toISOString();
  const vehicles = [
    {
      id: "v1",
      name: "Toyota Vios",
      brand: "Toyota",
      category: "Sedan",
      seats: 5,
      transmission: "Tự động",
      fuel: "Xăng",
      price: 650000,
      plate: "DEMO-001",
      status: "ACTIVE",
      amenities: ["Camera lùi", "Bluetooth"],
      color: "#0f766e",
      image: "/images/vehicles/toyota-vios.jpg",
      gallery: [
        { url: "/images/vehicles/toyota-vios.jpg", label: "Ngoại thất góc trước" },
        { url: "/images/vehicles/toyota-vios-interior.jpg", label: "Khoang lái & nội thất" },
      ],
      attribution: {
        author: "EurovisionNim",
        source: "Wikimedia Commons",
        sourceUrl: "https://commons.wikimedia.org/wiki/File:Toyota_Vios_(XP150)_sedan_front_view.jpg",
        license: "CC BY-SA 4.0",
        licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0/",
      },
      description:
        "Gọn gàng cho chuyến đi trong thành phố, khoang hành lý phù hợp một gia đình nhỏ.",
    },
    {
      id: "v2",
      name: "Mitsubishi Xpander",
      brand: "Mitsubishi",
      category: "MPV",
      seats: 7,
      transmission: "Tự động",
      fuel: "Xăng",
      price: 850000,
      plate: "DEMO-002",
      status: "ACTIVE",
      amenities: ["Camera lùi", "Bluetooth", "Điều hòa sau"],
      color: "#334155",
      image: "/images/vehicles/mitsubishi-xpander.jpg",
      gallery: [
        { url: "/images/vehicles/mitsubishi-xpander.jpg", label: "Ngoại thất góc trước" },
        { url: "/images/vehicles/mitsubishi-xpander-interior.jpg", label: "Khoang lái & nội thất" },
      ],
      attribution: {
        author: "Ethan Llamas",
        source: "Wikimedia Commons",
        sourceUrl: "https://commons.wikimedia.org/wiki/File:2022_Mitsubishi_Xpander_GLS_(20221009)_01.jpg",
        license: "CC BY-SA 4.0",
        licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0/",
      },
      description:
        "Không gian linh hoạt cho nhóm bạn và những chuyến đi cùng gia đình.",
    },
    {
      id: "v3",
      name: "Hyundai Accent",
      brand: "Hyundai",
      category: "Sedan",
      seats: 5,
      transmission: "Tự động",
      fuel: "Xăng",
      price: 600000,
      plate: "DEMO-003",
      status: "ACTIVE",
      amenities: ["Bluetooth", "Camera lùi"],
      color: "#b45309",
      image: "/images/vehicles/hyundai-accent.jpg",
      gallery: [
        { url: "/images/vehicles/hyundai-accent.jpg", label: "Ngoại thất góc trước" },
        { url: "/images/vehicles/hyundai-accent-interior.jpg", label: "Khoang lái & nội thất" },
      ],
      attribution: {
        author: "EurovisionNim",
        source: "Wikimedia Commons",
        sourceUrl: "https://commons.wikimedia.org/wiki/File:2012_Hyundai_Accent_(RB)_Premium_sedan_(2018-10-01)_02.jpg",
        license: "CC BY-SA 4.0",
        licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0/",
      },
      description: "Lựa chọn dễ tiếp cận cho những chuyến đi ngắn cuối tuần.",
    },
    {
      id: "v4",
      name: "Toyota Corolla Cross",
      brand: "Toyota",
      category: "SUV",
      seats: 5,
      transmission: "Tự động",
      fuel: "Xăng",
      price: 1100000,
      plate: "DEMO-004",
      status: "MAINTENANCE",
      amenities: ["Camera lùi", "Cảm biến", "Bluetooth"],
      color: "#475569",
      image: "/images/vehicles/toyota-corolla-cross.jpg",
      gallery: [
        { url: "/images/vehicles/toyota-corolla-cross.jpg", label: "Ngoại thất góc trước" },
        { url: "/images/vehicles/toyota-corolla-cross-interior.jpg", label: "Khoang lái & nội thất" },
      ],
      attribution: {
        author: "Alexander Migl",
        source: "Wikimedia Commons",
        sourceUrl: "https://commons.wikimedia.org/wiki/File:Toyota_Corolla_Cross_Hybrid_1X7A6349.jpg",
        license: "CC BY-SA 4.0",
        licenseUrl: "https://creativecommons.org/licenses/by-sa/4.0/",
      },
      description:
        "Khoang ngồi thoải mái, gầm cao. Xe hiện đang bảo trì trong dữ liệu mẫu.",
    },
  ];
  const booking = (id, vehicleId, status, start, end) => {
    const vehicle = vehicles.find((v) => v.id === vehicleId);
    const days = Math.ceil(end - start);
    const row = {
      id,
      userId: "u1",
      customer: demoActors.USER.fullName,
      vehicleId,
      vehicleName: vehicle.name,
      branch: "KDrive Đà Nẵng · Chi nhánh demo",
      status,
      startAt: at(start),
      endAt: at(end),
      createdAt: at(-0.01),
      expiresAt: at(0.07),
      price: vehicle.price,
      days,
      total: days * vehicle.price,
      version: 1,
      history: [{ status, at: at(-0.01), actor: "Dữ liệu mẫu", reason: "" }],
    };
    const snapshot = {
      vehicleName: row.vehicleName,
      customer: row.customer,
      branch: row.branch,
      startAt: row.startAt,
      endAt: row.endAt,
      price: row.price,
      days: row.days,
      total: row.total,
    };
    if (["CONFIRMED", "IN_PROGRESS", "RETURNED", "COMPLETED"].includes(status))
      row.contract = { ...snapshot, id: `HD-${id}`, issuedAt: at(-1) };
    if (status === "COMPLETED")
      row.invoice = {
        ...snapshot,
        id: `HĐ-${id}`,
        issuedAt: at(-1),
        items: [
          {
            name: "Tiền thuê xe mẫu",
            quantity: days,
            unitPrice: row.price,
            amount: row.total,
          },
        ],
      };
    return row;
  };
  return {
    vehicles,
    bookings: [
      booking("KD-1001", "v1", "PENDING", 3, 5),
      booking("KD-1002", "v2", "CONFIRMED", 0.02, 2),
      booking("KD-1003", "v3", "IN_PROGRESS", -2, 0.1),
      booking("KD-1004", "v1", "COMPLETED", -6, -4),
    ],
    accounts: [
      ...Object.values(demoActors).map((u) => ({ ...u, status: "ACTIVE" })),
      {
        id: "u2",
        role: "USER",
        fullName: "Khách hàng mẫu 02",
        email: "user2@example.test",
        status: "ACTIVE",
      },
    ],
    catalog: [
      { id: "c1", type: "Hãng xe", name: "Toyota" },
      { id: "c2", type: "Hãng xe", name: "Mitsubishi" },
      { id: "c3", type: "Hãng xe", name: "Hyundai" },
      { id: "c4", type: "Loại xe", name: "Sedan" },
      { id: "c5", type: "Loại xe", name: "MPV" },
      { id: "c6", type: "Loại xe", name: "SUV" },
      { id: "c7", type: "Tiện nghi", name: "Camera lùi" },
      { id: "c8", type: "Chi nhánh", name: "KDrive Đà Nẵng · Chi nhánh demo" },
    ],
    locations: [
      {
        id: "l1",
        name: "Bảo tàng Điêu khắc Chăm",
        category: "Văn hóa",
        lat: 16.0606,
        lng: 108.2232,
        status: "ACTIVE",
      },
      {
        id: "l2",
        name: "Biển Mỹ Khê",
        category: "Thiên nhiên",
        lat: 16.055,
        lng: 108.247,
        status: "ACTIVE",
      },
      {
        id: "l3",
        name: "Phố cổ Hội An",
        category: "Văn hóa",
        lat: 15.877,
        lng: 108.326,
        status: "ACTIVE",
      },
    ],
    reviews: [],
    itineraries: [],
  };
}
