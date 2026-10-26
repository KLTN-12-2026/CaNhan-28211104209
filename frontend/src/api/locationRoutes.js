import apiClient from './client';

export async function previewLocationRoute(locationIds, signal) {
  if(locationIds.length<2||locationIds.length>8||new Set(locationIds).size!==locationIds.length||locationIds.some(id=>!id)) throw new Error('Chọn từ 2 đến 8 địa điểm khác nhau.');
  const {data}=await apiClient.post('/ai/routes/preview',{locationIds},{signal});
  if(!['VERIFIED','NO_ROUTE','UNAVAILABLE'].includes(data?.status)||typeof data.attribution!=='string') throw new Error('Phản hồi tuyến đường không hợp lệ.');
  if(data.status==='VERIFIED') {
    if(!Number.isFinite(data.distanceMeters)||data.distanceMeters<0||!Number.isFinite(data.durationSeconds)||data.durationSeconds<0) throw new Error('Ước tính tuyến đường không hợp lệ.');
  } else if(data.distanceMeters!==null||data.durationSeconds!==null) throw new Error('Tuyến chưa kiểm chứng không được có số liệu ước tính.');
  return data;
}
