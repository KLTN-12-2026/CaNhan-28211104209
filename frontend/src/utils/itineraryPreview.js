// Presentation of itinerary preview outcomes: who chose the order, which candidates were used and why
// others were left out. Pure functions; the backend remains the authority for every value shown.

const STATUS_HEADINGS = {
  VALIDATED: 'HÀNH TRÌNH KHẢ THI',
  NO_FEASIBLE_PLAN: 'KHÔNG CÓ THỨ TỰ KHẢ THI',
  NEEDS_DATA: 'THIẾU DỮ LIỆU KIỂM CHỨNG',
  UNAVAILABLE: 'DỊCH VỤ TẠM THỜI GIÁN ĐOẠN',
};

const STATUS_EXPLANATIONS = {
  NO_FEASIBLE_PLAN: 'Đã xét mọi thứ tự 2–4 điểm trong tập ứng viên; không thứ tự nào vừa giờ mở cửa, thời lượng và tuyến đường trong khung giờ đã chọn.',
  NEEDS_DATA: 'Chưa đủ địa điểm có giờ mở cửa và thời lượng được kiểm chứng để lập hành trình. Đây không phải kết luận rằng không có lịch trình.',
  UNAVAILABLE: 'Trợ lý hoặc dịch vụ định tuyến chưa phản hồi kịp, nên hệ thống chưa thể kết luận. Bạn có thể thử lại sau.',
};

const EXCLUSION_LABELS = {
  MISSING_METADATA: 'chưa có thông tin tham quan được kiểm chứng',
  MISSING_DURATION: 'thiếu thời lượng tham quan',
  NOT_OPEN_ON_DATE: 'không mở cửa hoặc chưa rõ giờ mở cửa ngày này',
};

export function previewHeading(status) {
  return STATUS_HEADINGS[status] || 'KHÔNG XÁC ĐỊNH ĐƯỢC KẾT QUẢ';
}

export function previewExplanation(status) {
  return STATUS_EXPLANATIONS[status] || null;
}

export function planSourceLabel(planSource) {
  if (planSource === 'QWEN') return 'Thứ tự do trợ lý AI (Qwen) đề xuất và đã được hệ thống kiểm tra.';
  if (planSource === 'BACKEND_SEARCH') {
    return 'Thứ tự trợ lý AI đề xuất không vừa khung giờ; hệ thống đã tự xét các thứ tự khác trong tập ứng viên.';
  }
  return null;
}

function nameOf(id, namesById) {
  return (namesById && namesById[id]) || id;
}

/** Lines describing the finite candidate set, or [] when the backend sent none. */
export function candidateSetLines(candidateSet, namesById) {
  if (!candidateSet) return [];
  const lines = [`Đã xét ${candidateSet.candidateIds.length} địa điểm ứng viên đủ dữ liệu.`];
  const truncated = candidateSet.truncatedLocationIds || [];
  if (truncated.length > 0) {
    lines.push(`Giới hạn tối đa ${candidateSet.candidateIds.length} điểm: chưa xét ${truncated.length} điểm khác (${truncated.map((id) => nameOf(id, namesById)).join(', ')}).`);
  }
  for (const item of candidateSet.excluded || []) {
    lines.push(`Không dùng ${nameOf(item.locationId, namesById)}: ${EXCLUSION_LABELS[item.reason] || 'thiếu dữ liệu kiểm chứng'}.`);
  }
  return lines;
}
