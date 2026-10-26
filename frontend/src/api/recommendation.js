import apiClient from './client';

export async function recommendVehicles(criteria, signal) {
  const requestId = globalThis.crypto.randomUUID();
  const { data } = await apiClient.post('/ai/recommendations', { requestId, criteria }, { signal });
  if (data?.requestId !== requestId || !['OK', 'NO_MATCH', 'NEEDS_CLARIFICATION'].includes(data.status) || !Array.isArray(data.results)) throw new Error('Phản hồi gợi ý không hợp lệ. Hãy thử lại.');
  if (data.status === 'OK' && (data.results.length === 0 || data.results.length > 20 || new Set(data.results.map(item => item.vehicleId)).size !== data.results.length)) throw new Error('Danh sách xe gợi ý không hợp lệ.');
  for (const item of data.results) {
    if ((typeof item.vehicleId !== 'string' || !item.vehicleId || item.vehicleId.length > 36) || typeof item.vehicleName !== 'string' || !Number.isSafeInteger(item.total) || item.total < 0 || item.total > criteria.budgetVndTotal || !Array.isArray(item.reasonCodes) || item.reasonCodes.some(code => typeof code !== 'string')) throw new Error('Dữ liệu xe gợi ý không hợp lệ.');
  }
  if (data.status !== 'OK' && data.results.length) throw new Error('Phản hồi gợi ý không nhất quán.');
  return data;
}

export async function parseRentalCriteria(text, signal) {
  const { data } = await apiClient.post('/ai/criteria/parse', { text }, { signal, timeout: 65000 });
  if (!['READY_FOR_REVIEW', 'NEEDS_CLARIFICATION', 'OUT_OF_SCOPE'].includes(data?.status) || !Array.isArray(data.unresolvedFields)) throw new Error('Phản hồi đọc nhu cầu không hợp lệ.');
  return data;
}
