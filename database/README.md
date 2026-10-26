# Database và cấu hình backend

## Môi trường

Dùng MySQL 8 với database riêng cho KDrive và tài khoản có quyền áp dụng migration trên database đó. Không trỏ vào database của ứng dụng khác hoặc dùng database đang có dữ liệu để chạy test tự động.

Tạo database rỗng bằng công cụ quản trị MySQL trước khi khởi động ứng dụng. Flyway tạo và nâng cấp các bảng; không nhập DDL thủ công song song.

## Chuẩn bị database mới

Chỉ chạy bước này trên MySQL do bạn quản lý và đã xác nhận là môi trường dành riêng cho KDrive. Nếu đã có database hoặc tài khoản cùng tên, kiểm tra lại quyền và dữ liệu; không drop/reset để chạy lại ví dụ.

Trong MySQL Workbench hoặc MySQL CLI đăng nhập bằng tài khoản quản trị:

```sql
CREATE DATABASE kdrive CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'kdrive'@'localhost' IDENTIFIED BY '<mat_khau_rieng_cua_ban>';
GRANT ALL PRIVILEGES ON kdrive.* TO 'kdrive'@'localhost';
```

Thay mật khẩu mẫu trước khi chạy. Đây là ví dụ kết nối local tới MySQL cài trực tiếp; nếu MySQL nằm trong Docker hoặc máy khác, quản trị viên phải chọn host của tài khoản theo nguồn kết nối thực tế. Không mở quyền từ mọi host chỉ để vượt lỗi đăng nhập. Quyền trên `kdrive.*` phục vụ Flyway trong môi trường đồ án, không cấp quyền quản trị toàn máy chủ.

Kiểm tra bằng đúng host/cổng/tài khoản dùng cho ứng dụng (`-p` hỏi mật khẩu, không ghi mật khẩu vào dòng lệnh):

```bash
mysql --host=127.0.0.1 --port=33385 --user=kdrive -p kdrive
```

Thay cổng ví dụ bằng cổng MySQL của bạn. Nếu chưa có CLI, dùng chức năng Test Connection của Workbench. Chưa tạo bảng thủ công; Flyway sẽ tạo bảng khi backend khởi động.

## Cấu hình

Tạo `backend/.env.local` trên máy, không commit file này:

```dotenv
DB_URL=jdbc:mysql://127.0.0.1:33385/kdrive?useSSL=false&connectionTimeZone=UTC
DB_USER=kdrive
DB_PASSWORD=<mat_khau_database>
JWT_SECRET=<bi_mat_ngau_nhien_it_nhat_32_byte>
PORT=8080
FRONTEND_ORIGIN=http://127.0.0.1:5173
SEED_ENABLED=false
```

Cổng 33385 là ví dụ cấu hình local; thay bằng cổng MySQL riêng thực tế. Các giá trị trong dấu `<...>` phải được thay trên máy. `useSSL=false` chỉ phù hợp ví dụ local; cấu hình triển khai cần được xem xét riêng.

Chạy từ thư mục gốc bằng Git Bash:

```bash
bash scripts/run-backend-gitbash.sh
```

Script nạp các biến được hỗ trợ từ file env, ưu tiên biến đã export. Spring Boot không tự đọc `.env.local` nếu chạy theo cách khác. Script không khởi động MySQL.

## Migration

- SQL V1–V8 và V10: `backend/src/main/resources/db/migration/`.
- Java V9: `backend/src/main/java/db/migration/`.
- Hai loại cùng dùng `classpath:db/migration` và bảng `flyway_schema_history`.
- V1 tạo 25 bảng nghiệp vụ. V6 bổ sung ba bảng giao dịch cọc/hoàn/audit; tổng đến V10 là 29 bảng tính cả history.
- V9 xử lý ràng buộc UNIQUE legacy của hoàn tiền theo metadata.
- V10 thêm ba cột nullable của snapshot chính sách trả xe trong `bookings`, không thêm bảng hoặc truy thu đơn cũ.

Giữ Hibernate `validate`, Flyway validation, `clean-disabled=true` và `baseline-on-migrate=false`. Không sửa migration đã áp dụng, không tự clean/repair. Trước nâng cấp database có dữ liệu, kiểm tra history/checksum và sao lưu; MySQL DDL có thể tồn tại một phần sau lỗi.

## Seed và kiểm thử

Seed chỉ chạy khi bật `SEED_ENABLED=true` và cung cấp `SEED_PASSWORD` ít nhất 12 ký tự. Nếu đã có users, seed bỏ qua; không dùng seed để reset database.

Các tài khoản mẫu gồm `admin@example.test`, `user@example.test`, `staff.dn@example.test`, `staff.hn@example.test`; mật khẩu do người chạy đặt. Seed không đại diện cho dữ liệu kinh doanh thật.

`bash ./mvnw verify` tại backend chạy các test theo cấu hình môi trường. H2 không thay thế kiểm chứng MySQL. Test MySQL phải dùng fixture riêng và guard hợp lệ; không đặt `KDRIVE_TEST_DB_URL` trỏ vào database vận hành.

## Chức năng tùy chọn

Cọc và chính sách trả xe mặc định tắt. Xem [cọc thử nghiệm](../docs/modules/booking-deposit.md) và [chính sách trả xe](../docs/modules/return-policy.md). Không chuyển tiền thật trong môi trường demo.

## Khi khởi động chưa thành công

- `Missing required backend setting`: kiểm tra tên file `backend/.env.local`, các biến bắt buộc và terminal đang dùng. Script chỉ đọc các biến được hỗ trợ; không gửi nội dung file chứa mật khẩu vào báo lỗi công khai.
- `Java 21 is required`: sửa `JAVA_HOME`/`PATH` trong cùng terminal rồi kiểm tra lại phiên bản.
- `Unknown database`: tạo database rỗng trên đúng server/cổng trước, không nhập các file migration bằng tay.
- `Access denied`: kiểm tra mật khẩu, host của tài khoản và quyền trên đúng database.
- Lỗi kết nối: kiểm tra MySQL đã chạy và cổng trong `DB_URL`; không mặc định cổng ví dụ đã có trên máy mới.
- Lỗi checksum/history Flyway: giữ dữ liệu và log, kiểm tra lịch sử migration; không tự `clean`, `repair` hoặc xóa schema để vượt lỗi.

Khi báo lỗi, gửi tên lệnh, phiên bản môi trường và thông báo lỗi đã che thông tin nhạy cảm. Build thành công chưa chứng minh database kết nối hoặc các luồng ứng dụng hoạt động.
