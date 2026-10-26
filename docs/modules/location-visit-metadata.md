# Thông tin tham quan và phạm vi AI3

AI3 bản đầu hỗ trợ graph ô tô vùng Đà Nẵng–Hội An. Quãng đường/thời gian chỉ được hiển thị khi OSRM trả tuyến hợp lệ; không có dữ liệu giao thông trực tiếp. Ngoài vùng hoặc không có đường vào trong giới hạn snapping có thể trả NO_ROUTE. Không suy độ nổi tiếng hoặc tự tạo lịch trình từ tọa độ.

Admin quản lý thông tin tham quan ở trang Địa điểm. USER đọc thông tin của địa điểm ACTIVE trong mục “Khám phá địa điểm và tuyến đường” của Trợ lý KDrive. Guest/STAFF/ADMIN không dùng API catalog/routing dành riêng USER. Quản trị catalog vẫn giữ quyền ADMIN.

## Contract metadata

`POST/PUT /api/v1/admin/locations` nhận `metadata` có kiểu; response Admin và `GET /api/v1/ai/locations` trả cùng nhóm facts đã lưu. Metadata gồm:

- `timezone`: cố định `Asia/Ho_Chi_Minh`.
- `weekly`: map MONDAY…SUNDAY, ngày không khai báo giữ UNKNOWN.
- `exceptions`: tối đa60 ngày ISO; thay lịch bắt đầu của ngày tương ứng, không là lịch tuần mới.
- Mỗi ngày có `state`: UNKNOWN, CLOSED, OPEN_24H hoặc INTERVALS. INTERVALS có1–4 khoảng `{start:"HH:mm",end:"HH:mm",nextDay:false}`; trạng thái khác có intervals rỗng.
- Khoảng giờ thuộc ngày bắt đầu. `nextDay:true` cho phép đóng vào ngày kế tiếp, dài tối đa24giờ; các khoảng tăng dần, không trùng nhau, kể cả biên ngày/ngoại lệ. CLOSED nghĩa là không có lượt mở bắt đầu vào ngày đó; không tự cắt một khoảng qua đêm đã bắt đầu hôm trước. AI4 phải xét cả khoảng từ ngày trước khi kiểm một thời điểm.
- `durationMinutes`: null hoặc số nguyên1–1440; đây là ước lượng của quản trị, không phải thời gian bắt buộc.
- `hoursEvidence` và `durationEvidence`: `{url,verifiedAt}`. Lịch có facts khác UNKNOWN cần hoursEvidence; duration có giá trị cần durationEvidence. Nguồnhttp/https không có userinfo, tối đa500ký tự; verifiedAt từ năm2000 và không ở tương lai. Backend lưu nguồn, không tự fetch URL hoặc xác minh nội dung website.

Thông tin cũ `metadata:null` nghĩa là chưa xác minh, không mặc định mở24giờ. Khi update, metadata bị bỏ qua/null giữ thông tin đã có để tương thích client cũ; muốn xóa facts, gửi lịch UNKNOWN, exceptions rỗng và duration/evidence null. Metadata tham gia revision; cập nhật từ expectedRevision cũ trả409.

Nguồn và ngày kiểm chứng được hiển thị cho khách. Chúng không bảo đảm facts còn đúng ở ngày đi; không có chính sách tự coi dữ liệu mới/cũ là đủ điều kiện lập lịch. Cần kiểm tra nguồn trước chuyến đi; AI4 còn phải kiểm thời gian cụ thể và khả thi trước khi lưu itinerary. Không có thu thập xu hướng tuần hoặc tự cập nhật giờ mở cửa từ Qwen.

## Persistence

Flyway V4 thêm `locations.visit_metadata TEXT NULL`, chứa JSON canonical sau validation service; không thêm bảng, không đổi migration đã áp dụng và không seed facts giả cho địa điểm cũ. Hibernate giữ validate. Upgrade phải inventory history/checksum, thử trên schema riêng có dữ liệu, giữ rows/audit/V1–V3 checksum. Nguồn migration duy nhất ở backend/src/main/resources/db/migration.

Định tuyến và cách vận hành graph cục bộ: [local-ai-routing.md](local-ai-routing.md). Khối xem tuyến chỉ preview, không ghi booking hoặc itinerary; không thay đổi quyền và state machine thuê xe.
