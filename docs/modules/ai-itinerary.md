# Hành trình một ngày

Khách hàng mở **Lập hành trình một ngày** trong mục Khám phá địa điểm và tuyến đường của Trợ lý KDrive, hoặc vào `/ai/itinerary`. Danh sách và chi tiết đã lưu ở `/user/itineraries`. API mode dùng dữ liệu thật; lỗi API không chuyển sang dữ liệu demo.

Phạm vi pilot là Đà Nẵng–Hội An, một ngày, 2–4 điểm. Khách chọn ngày, giờ và điểm đầu; Qwen cục bộ chỉ chọn thứ tự ID trong catalog. Backend kiểm giờ mở cửa, ngày ngoại lệ, khoảng qua đêm, thời lượng quản trị ước tính và từng chặng OSRM. Đây là ước lượng đường bộ, không giao thông trực tiếp; chưa tính đường đến điểm đầu hoặc quay về. Điểm chưa xác minh không tự coi là mở cả ngày. Không tính giá vé, không cam kết tối ưu.

`POST /api/v1/ai/itineraries/preview` trả VALIDATED, NEEDS_DATA, NO_FEASIBLE_PLAN hoặc UNAVAILABLE. Capacity có thể trả429. Cần bật QWEN_PARSER_ENABLED và có Qwen3 4B/8B, OSRM loopback theo [hướng dẫn vận hành](local-ai-routing.md). Preview có deadline model60giây và frontend65giây. Không cloud fallback hoặc tự tải model.

`POST /api/v1/itineraries` nhận tiêu chí, orderedLocationIds, fingerprint và title; server kiểm lại catalog/giờ/tuyến, không gọi Qwen lần hai. Facts đổi trả409, cần xem trước lại. Fingerprint giữ nhất quán bản đã xem, không là chữ ký hay chứng minh nguồn AI. Chỉ lưu khi khách bấm Lưu; khóa đầu vào khi đang lưu để tránh lệch preview. Mạng gián đoạn có thể đã lưu: kiểm danh sách trước khi thử lại; không tự retry POST.

`GET /api/v1/itineraries` chỉ liệt kê của USER hiện tại, mới trước. `GET /api/v1/itineraries/{id}` đọc snapshot đã lưu, không gọi provider và không đổi theo catalog mới. USER khác nhận404; guest401, STAFF/ADMIN403. Snapshot không bảo đảm giờ mở cửa tương lai, cần đối chiếu nguồn trước chuyến đi. Không thay đổi quyền hoặc trạng thái thuê xe.

Bộ8 địa điểm chuẩn bị tại `scripts/ai/locations-pilot.json`:4 ứng viên có facts,4 UNKNOWN. Thời lượng là ước lượng của quản trị, nguồn mô tả căn cứ chứ không quy định thời gian tham quan. Importer mặc định dry-run; apply chỉ sau khi danh sách được duyệt, không overwrite facts khác và có thể tạo một phần nếu API lỗi. Chạy lại sẽ giữ bản ghi đã khớp. Không dùng catalog này để tự sửa/xóa dữ liệu hiện có.
