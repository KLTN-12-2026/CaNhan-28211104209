import assert from 'node:assert/strict';
import test from 'node:test';
import { bookingStatusLabels,roleLabels,accountStatusLabels,vehicleStatusLabels,licenseStatusLabels,licenseSchemeLabels,incidentKindLabels,incidentStatusLabels } from './uiLabels.js';
test('Vietnamese display labels preserve separate account, vehicle, license and booking meanings',()=>{
 assert.equal(roleLabels.STAFF,'Nhân viên');assert.equal(roleLabels.ADMIN,'Quản trị viên');assert.equal(roleLabels.USER,'Khách hàng');
 assert.equal(bookingStatusLabels.PENDING,'Chờ xác nhận');assert.equal(bookingStatusLabels.IN_PROGRESS,'Đang thuê');
 assert.equal(accountStatusLabels.ACTIVE,'Đang hoạt động');assert.equal(vehicleStatusLabels.ACTIVE,'Đang khai thác');assert.notEqual(bookingStatusLabels.PENDING,licenseStatusLabels.PENDING);
 assert.equal(licenseStatusLabels.VERIFIED,'Đã đối chiếu');assert.equal(licenseSchemeLabels.VN_2025,'Bằng cấp từ năm 2025');
 assert.equal(incidentKindLabels.DAMAGE,'Hư hỏng');assert.equal(incidentStatusLabels.CLAIMED,'Đã gửi yêu cầu');
});
