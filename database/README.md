# Database và cấu hình backend

## Môi trường

Dùng MySQL 8 với database riêng cho KDrive và tài khoản có quyền áp dụng migration trên database đó. Không trỏ vào database của ứng dụng khác hoặc dùng database đang có dữ liệu để chạy test tự động.

Tạo database rỗng bằng công cụ quản trị MySQL trước khi khởi động ứng dụng. Flyway tạo và nâng cấp các bảng; không nhập DDL thủ công song song.

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
