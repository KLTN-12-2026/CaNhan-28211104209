// Explicit UI preview mode. API mode never falls back to demo data.
export const DEMO_MODE = (import.meta.env.VITE_DATA_MODE ?? "demo") === "demo";
