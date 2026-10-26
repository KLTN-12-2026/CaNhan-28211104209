export function vehicleBrandOptions(vehicles = [], catalog = {}) {
  const names = new Map((catalog?.brands || []).map(brand => [String(brand.id), brand.name]));
  const counts = new Map();
  for (const vehicle of Array.isArray(vehicles) ? vehicles : []) {
    const id = vehicle.brandId == null ? "" : String(vehicle.brandId).trim();
    if (!id) continue;
    const brand = counts.get(id) || { id, name: names.get(id) || id, count: 0 };
    brand.count++;
    counts.set(id, brand);
  }
  return [...counts.values()].sort((a,b) => b.count-a.count || a.name.localeCompare(b.name,"vi") || a.id.localeCompare(b.id));
}
