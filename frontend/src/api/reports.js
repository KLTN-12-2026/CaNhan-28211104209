export function operationalReportPath(from, to) {
  return `/admin/reports/operations?${new URLSearchParams({ from, to })}`;
}
