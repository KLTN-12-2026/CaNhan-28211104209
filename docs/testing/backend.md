# Kiểm thử backend

## Build và kiểm thử mặc định

Tại thư mục gốc, dùng Git Bash với JDK 21:

```bash
(cd backend && bash ./mvnw -B -ntp verify)
```

Lệnh cần mạng để tải dependency khi cache trống. Không dùng `-DskipTests` để xác nhận bản build đạt kiểm thử. Surefire XML nằm trong `backend/target/surefire-reports/`; đọc cả failures, errors và skipped. Không cộng số lượt chạy lại thành số test duy nhất.

Các test thông thường dùng H2 hoặc stub dịch vụ ngoài. Khi chưa cấu hình fixture, các test MySQL/native bị skip. H2 không chứng minh khóa, concurrency hay migration MySQL.

## Chuẩn bị MySQL test trên máy khác

Cần một MySQL 8 riêng cho kiểm thử, chỉ bind loopback ở cổng riêng (ví dụ 33676), không dùng volume/database website hoặc cổng 3306/33385. Quản trị viên môi trường phải xác nhận instance, volume và không có lượt test khác đang sử dụng trước khi cấp tài khoản.

Repo không mang registry Docker, mật khẩu hoặc identity container của máy phát triển. Không copy credential hay giả định một container cùng tên là an toàn. Không có lệnh tự tạo/dọn container trong hướng dẫn này.

Mỗi suite dùng schema mới riêng, tiền tố `kdrive_test_`, và token marker ngẫu nhiên riêng. Trên đúng instance test, quản trị viên tạo schema nghiệp vụ rỗng và một schema marker kỹ thuật riêng. Ví dụ cấu trúc marker (thay tên schema, token trước khi chạy bằng client MySQL):

```sql
CREATE DATABASE kdrive_test_example;
CREATE DATABASE kdrive_marker_example;
CREATE TABLE kdrive_marker_example.__kdrive_test_fixture_marker (
  id INT PRIMARY KEY,
  target_schema VARCHAR(255),
  marker_token VARCHAR(255)
);
INSERT INTO kdrive_marker_example.__kdrive_test_fixture_marker
VALUES (1, 'kdrive_test_example', '<token_marker_ngau_nhien>');
```

Đây là metadata của test harness, không phải schema ứng dụng thứ hai. Các bảng nghiệp vụ vẫn chỉ được tạo bằng Flyway. Không tạo marker trên database vận hành để vượt guard.

Đặt các biến dưới đây trong shell riêng; cung cấp giá trị bí mật trên máy, không đưa vào Git/log:

| Biến | Giá trị ví dụ/ý nghĩa |
|---|---|
| KDRIVE_TEST_DB_URL | jdbc:mysql://127.0.0.1:33676/kdrive_test_example?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC |
| KDRIVE_TEST_DB_USER / KDRIVE_TEST_DB_PASSWORD | Tài khoản chỉ dành cho instance test |
| KDRIVE_TEST_EXPECTED_HOST | 127.0.0.1 |
| KDRIVE_TEST_EXPECTED_PORT | 33676 |
| KDRIVE_TEST_EXPECTED_SCHEMA | kdrive_test_example |
| KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX | kdrive_test_ |
| KDRIVE_TEST_MARKER_SCHEMA | kdrive_marker_example |
| KDRIVE_TEST_FIXTURE_MARKER_TOKEN | Khớp token ngẫu nhiên trong marker |

Ví dụ chạy một suite sau khi đã chuẩn bị đúng fixture:

```bash
(cd backend && bash ./mvnw -B -ntp -Dtest=ConcurrencyAndRaceTest test)
```

Không chạy toàn bộ suites trên cùng schema bẩn. Chuẩn bị schema/marker mới và đổi biến tương ứng cho mỗi suite. Không tắt guard, Flyway hoặc Hibernate validate để vượt lỗi. Sau test giữ XML và dữ liệu cần điều tra; chỉ dừng instance do lượt đó khởi động, không xóa volume tự động.

## Tái chạy migration và lịch sử V1–V10

Repo có runner `scripts/testing/run_mysql_migrations.py` và snapshot lịch sử trong `backend/src/test/resources/fixtures/historical-v2/`. Snapshot cũ chỉ phục vụ ca lỗi nâng cấp, không nằm trong namespace Flyway hiện hành và không được dùng để khởi tạo ứng dụng.

Cài thêm Python 3.12 trở lên để dùng runner (chỉ dùng thư viện chuẩn, không cần pip). Chuẩn bị instance MySQL, schema và marker như phần trên; tài khoản test cần quyền tạo schema trên **instance test riêng** vì migration suite tạo các schema nâng cấp độc lập. Không cấp tài khoản này cho database website.

Sau khi export đủ biến fixture và chọn JDK 21, chạy từ thư mục gốc:

```bash
python scripts/testing/test_mysql_runner.py
python scripts/testing/run_mysql_migrations.py --evidence .runtime/mysql-migrations-01
```

Mỗi lần chạy dùng thư mục evidence mới. Runner kiểm tra endpoint, các biến khớp nhau, hash snapshot lịch sử và Java 21 trước khi ghi evidence/chạy Maven. Guard Java tiếp tục kiểm tra marker bằng kết nối chỉ đọc trước thao tác migration. Script không tạo marker để vượt guard.

Runner chạy tuần tự `MySqlMigrationTest` rồi `CancelBoundaryFlywayHistoryTest`, kiểm exit code và XML phải đủ ca, không failure/error/skip; lưu hash source, log và XML trong evidence local. Test lịch sử migrate trên schema fixture đã được kiểm tra, validate đủ V1–V10 gồm Java V9 và kiểm migrate lặp không thay đổi.

Runner không khởi động/dừng MySQL, không tự xóa schema hay volume. Người vận hành quản lý vòng đời instance test, giữ dữ liệu ca lỗi khi cần điều tra. Không đưa evidence chứa cấu hình môi trường hoặc secrets lên Git. Có thể truyền `--maven-repo <duong_dan_cache>` nếu muốn dùng cache Maven riêng; không có đường dẫn máy phát triển được ghi cứng trong runner.

`CancelBoundaryGuardUnitTest` có các probe live cần fixture thiếu marker/sai token riêng; `CancelBoundaryBootstrapNegativeTest` cần chọn trường hợp bằng `KDRIVE_TEST_BOOTSTRAP_CASE` (missing-marker, wrong-token, target-mismatch). Các ca phá cấu hình này không thuộc runner migration/history. Không phá marker của pool dùng chung để tạo ca âm và không coi chúng đạt khi bị skip.

## AI và định tuyến

Các test parser bằng stub không chứng minh kết nối Qwen/Gemini thật. `LocationRouteHttpServerTest` có ca yêu cầu OSRM đang chạy và ca yêu cầu OSRM dừng; hai trạng thái phải kiểm riêng trên dịch vụ test được quản lý. Không dừng OSRM đang phục vụ ứng dụng của người dùng chỉ để tạo ca âm.

Bản backend có thể build khi dịch vụ tùy chọn chưa bật. Điều đó không chứng minh chất lượng gợi ý, chất lượng hành trình, thanh toán sandbox của nhà cung cấp, hoặc trải nghiệm frontend.

## Kiểm tra khởi động

Theo [hướng dẫn database](../../database/README.md), chạy backend với database phát triển riêng và cổng còn trống. Xác nhận log Flyway/Hibernate và GET `/api/v1/health`; health chỉ chứng minh dịch vụ phản hồi, không thay thế test nghiệp vụ.

Trong mốc B chưa có frontend. Không coi browser E2E đã được nghiệm thu từ test backend.
