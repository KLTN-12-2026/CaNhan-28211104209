# Kiến trúc hệ thống

Tài liệu mô tả kiến trúc lựa chọn cho KDrive trong [phạm vi đề tài](../requirements/system-scope.md). Mốc B công bố backend và database; frontend được bổ sung ở mốc sau. Tài liệu kiến trúc không thay thế bằng chứng nghiệm thu ứng dụng.

## Thành phần

Trình duyệt chạy React/Vite SPA, gọi REST API của Spring Boot. Backend được chia module nghiệp vụ trong cùng một ứng dụng và sử dụng một MySQL database. AI và dịch vụ tuyến đường được truy cập qua adapter phía backend.

Tôi chọn modular monolith để giữ giao dịch nghiệp vụ trong một hệ thống, giảm số dịch vụ phải vận hành và phù hợp phạm vi đồ án cá nhân. Đổi lại, cần kiểm soát phụ thuộc module và kiểm thử hồi quy để tránh thay đổi một nghiệp vụ làm hỏng nghiệp vụ khác.

## Phân tầng

| Thành phần | Trách nhiệm |
|---|---|
| Frontend | Hiển thị dữ liệu, thu nhận thao tác, kiểm tra đầu vào hỗ trợ trải nghiệm |
| Controller | Nhận request, kiểm tra hình thức, gọi nghiệp vụ và trả response/DTO |
| Service | Quy tắc nghiệp vụ, quyền theo đối tượng, trạng thái và ranh giới transaction |
| Repository/adapter | Truy cập persistence hoặc dịch vụ ngoài theo trách nhiệm module |
| Database | Dữ liệu, khóa, ràng buộc tham chiếu và giao dịch |

Backend gồm các nhóm auth, user, vehicle, branch, booking, operation, contract, invoice, review, location và ai. Staff/Admin là role, không tách thành kho dữ liệu riêng. Module trao đổi qua API nội bộ/DTO có kiểu, tránh truy cập repository hoặc entity mutable xuyên module.

## Toàn vẹn giao dịch

Luồng thuê xe phải kiểm tra tài khoản, quyền, lịch xe, tuyến nhận–trả và giá trước khi lưu. Kiểm tra lịch và ghi đơn cần nằm trong transaction với cơ chế khóa thống nhất; khóa ngoại không chống đặt trùng thời gian. Cơ chế này phải được kiểm thử đồng thời trên MySQL khi công bố module đặt xe.

Chuyển trạng thái đơn và ghi hồ sơ giao nhận/chứng từ liên quan cần nhất quán. Các thao tác cập nhật phải xử lý dữ liệu cũ, yêu cầu đồng thời và lỗi giữa chừng; không coi giao diện cập nhật thành công là bằng chứng dữ liệu đã được lưu.

## Schema và cấu hình

Flyway là nguồn migration duy nhất: SQL tại `backend/src/main/resources/db/migration/` và Java migration V9 tại `backend/src/main/java/db/migration/`, cùng namespace và history Flyway. Hibernate dùng validate, không tự sinh schema song song. Không sửa migration đã áp dụng trong môi trường dùng chung; nâng cấp cần kiểm tra lịch sử và dữ liệu.

Mật khẩu được băm; cấu hình nhạy cảm nằm ngoài Git. Frontend không giữ khóa dịch vụ AI/Map. Các biến môi trường và hướng dẫn khởi động nằm trong [README](../../README.md) và [hướng dẫn database](../../database/README.md).

## Ranh giới AI và dữ liệu ngoài

AI hỗ trợ hiểu nhu cầu hoặc đề xuất phương án trên dữ liệu được cấp. Backend kiểm tra định dạng, ID, lịch, giá, giờ mở cửa và tuyến đường theo nghiệp vụ. Kết quả không đủ căn cứ phải được báo rõ, không tự thay bằng dữ liệu giả để tiếp tục lưu.

Dữ liệu demo và dữ liệu API tách biệt. Thông báo lỗi dịch vụ không được thay bằng kết quả demo mà không thông báo cho người dùng.

## Nguyên tắc kiểm thử

Kiểm thử gồm unit, integration, quyền truy cập, trạng thái, transaction/concurrency MySQL và tương tác trình duyệt cho các luồng chính/ca âm. Build thành công, health endpoint hoặc kiểm thử H2 không thay thế kiểm chứng toàn luồng trên MySQL. Phạm vi kiểm thử và các điều kiện chạy được mô tả tại [hướng dẫn kiểm thử backend](../testing/backend.md). Các ca chuyên biệt chưa chạy hoặc chưa tương thích không được tính là đã đạt.
