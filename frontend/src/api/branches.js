import apiClient from "./client";

export async function changeBranchStatus(branch, status) {
  const { data } = await apiClient.put(
    `/admin/branches/${encodeURIComponent(branch.id)}/status`,
    { status, expectedVersion: branch.version },
  );
  return data;
}
