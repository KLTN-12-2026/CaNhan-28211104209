# KDrive — Hệ thống quản lý cho thuê xe tự lái

**Đề tài:** Xây dựng hệ thống quản lý cho thuê xe tự lái tích hợp AI tư vấn phương tiện và hành trình.

**Sinh viên:** Nguyễn Văn Thanh — **MSSV:** 28211104209.

**Giảng viên hướng dẫn:** ThS. Nguyễn Thanh Trung.

**Hình thức:** Khóa luận tốt nghiệp cá nhân.

## Giới thiệu

Tôi phát triển KDrive để giải quyết bài toán quản lý thuê xe tự lái theo mô hình doanh nghiệp có nhiều chi nhánh. Phạm vi bao gồm tìm và đặt xe, xử lý giao nhận, quản lý hồ sơ thuê và hỗ trợ khách hàng lựa chọn phương tiện, hành trình.

Tôi sử dụng AI hỗ trợ phân tích yêu cầu, đề xuất giải pháp, viết và rà soát mã nguồn, xây dựng kiểm thử và biên soạn tài liệu. Tôi chịu trách nhiệm lựa chọn giải pháp, kiểm tra và giải thích kết quả sử dụng trong đồ án. AI hỗ trợ phát triển được phân biệt với tính năng AI phục vụ khách hàng của hệ thống.

## Mốc B — Backend và database

Repository khoa được tạo sau khi đề tài đã được phát triển tại môi trường cá nhân. Mốc B đồng bộ backend, migration, kiểm thử và công cụ chạy; đây không phải thời điểm bắt đầu lập trình.

Bản công bố này chưa chứa frontend. Có thể chạy backend và sử dụng API; giao diện tích hợp được bổ sung ở mốc tiếp theo. Mã nguồn có các chức năng tùy chọn, không đồng nghĩa tất cả dịch vụ ngoài đã được cấu hình hoặc nghiệm thu.

## Chạy backend

Yêu cầu JDK 21, Maven Wrapper và MySQL 8. Chuẩn bị database và biến môi trường theo [hướng dẫn database](database/README.md).

Từ thư mục gốc, dùng Git Bash:

```bash
bash scripts/run-backend-gitbash.sh
```

Script kiểm tra Java/cổng, build rồi chạy backend. Nó không tự tạo hoặc khởi động MySQL. Endpoint kiểm tra dịch vụ: `http://127.0.0.1:8080/api/v1/health` khi dùng cổng mặc định.

Chạy kiểm thử và đóng gói:

```bash
cd backend
bash ./mvnw verify
```

Một số test cần fixture MySQL riêng hoặc dịch vụ định tuyến và có thể bị bỏ qua khi thiếu cấu hình. Test H2, build hoặc health thành công không thay thế kiểm chứng MySQL và toàn luồng.

## Nhóm người sử dụng

| Actor | Trách nhiệm |
|---|---|
| Guest | Xem, tìm kiếm xe và thông tin công khai; đăng ký, đăng nhập |
| User | Quản lý hồ sơ, GPLX, đơn thuê và chứng từ của mình; đánh giá, báo sự cố; sử dụng tính năng tư vấn theo phạm vi |
| Staff | Xử lý đơn và giao nhận xe theo chi nhánh được phân công |
| Admin | Quản lý tài khoản, xe, danh mục, chi nhánh, tuyến và báo cáo vận hành |

Guest là người chưa đăng nhập, không phải role lưu trong tài khoản. Admin không mặc định kế thừa quyền giao nhận của Staff.

## Công nghệ và tổ chức

| Thành phần | Lựa chọn |
|---|---|
| Frontend | React/Vite SPA |
| Backend | Java 21, Spring Boot modular monolith, REST API |
| Xác thực | Spring Security và JWT |
| Database | MySQL; Flyway quản lý migration |
| Persistence | Spring Data JPA/Hibernate, adapter truy cập dữ liệu theo module |
| AI và tuyến đường | Gọi qua backend; dữ liệu đầu ra phải được kiểm tra theo nghiệp vụ |

Backend quyết định giá, quyền truy cập, lịch xe và trạng thái giao dịch. Hệ thống không dùng kiến trúc microservices. Source có thử nghiệm cọc VNPAY Sandbox và QR DEMO thủ công, mặc định tắt; không dùng để thu tiền thật. Hóa đơn không chứng minh đã thanh toán.

## Tài liệu

| Tài liệu | Nội dung |
|---|---|
| [Phạm vi hệ thống](docs/requirements/system-scope.md) | Bài toán, actor, nhóm nghiệp vụ và giới hạn |
| [Kiến trúc hệ thống](docs/architecture/system-architecture.md) | Các thành phần, phân tầng và nguyên tắc toàn vẹn dữ liệu |

## Các mốc đồng bộ tiếp theo

Mốc C bổ sung frontend, assets và hướng dẫn chạy tích hợp. Các lượt sau tập trung sửa lỗi, kiểm thử, đánh giá AI và hoàn thiện tài liệu theo góp ý. Những phần đã có được phân biệt với công việc mới.

## Chức năng tùy chọn

- [Cọc sandbox và QR DEMO](docs/modules/booking-deposit.md): chỉ thử nghiệm; không coi QR DEMO hoặc kiểm thử giao thức nội bộ là thanh toán ngân hàng thật.
- [Chính sách trả xe](docs/modules/return-policy.md): snapshot và xác nhận chính sách, mặc định tắt; chưa đồng nghĩa quy trình thu phí trả muộn đã hoàn chỉnh.
- AI và định tuyến cần cấu hình, dữ liệu và dịch vụ tương ứng. Gọi mô hình có sẵn không phải tự huấn luyện mô hình.
- Script khởi động chỉ nạp các biến kết nối, JWT, cổng và seed được hỗ trợ từ `.env.local`. Các cờ tùy chọn phải export trong shell theo tài liệu chức năng; không mặc định mọi biến trong file env đều được nạp.

## Hướng dẫn kiểm thử

Xem [kiểm thử backend](docs/testing/backend.md) để phân biệt H2, MySQL, fixture chuyên biệt và giới hạn tái chạy của bản công bố. Repo cung cấp runner và snapshot cho migration/history; các ca guard hoặc dịch vụ ngoài vẫn cần fixture riêng. Không suy toàn bộ native suite đạt từ build mặc định.
