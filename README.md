# KDrive — Hệ thống quản lý cho thuê xe tự lái

**Đề tài:** Xây dựng hệ thống quản lý cho thuê xe tự lái tích hợp AI tư vấn phương tiện và hành trình.

**Sinh viên:** Nguyễn Văn Thanh — **MSSV:** 28211104209.

**Giảng viên hướng dẫn:** ThS. Nguyễn Thanh Trung.

**Hình thức:** Khóa luận tốt nghiệp cá nhân.

## Giới thiệu

Tôi phát triển KDrive để giải quyết bài toán quản lý thuê xe tự lái theo mô hình doanh nghiệp có nhiều chi nhánh. Phạm vi bao gồm tìm và đặt xe, xử lý giao nhận, quản lý hồ sơ thuê và hỗ trợ khách hàng lựa chọn phương tiện, hành trình.

Tôi sử dụng AI hỗ trợ phân tích yêu cầu, đề xuất giải pháp, viết và rà soát mã nguồn, xây dựng kiểm thử và biên soạn tài liệu. Tôi chịu trách nhiệm lựa chọn giải pháp, kiểm tra và giải thích kết quả sử dụng trong đồ án. AI hỗ trợ phát triển được phân biệt với tính năng AI phục vụ khách hàng của hệ thống.

## Mốc A — Phạm vi và kiến trúc

Repository của khoa được tạo sau khi đề tài đã được phát triển tại môi trường cá nhân. Nội dung được đồng bộ theo từng mốc chức năng; Mốc A giới thiệu phạm vi và kiến trúc hệ thống, không phải thời điểm bắt đầu lập trình.

Bản cập nhật này chỉ gồm tài liệu nền tảng và quy tắc loại trừ file. Mô tả chức năng là phạm vi thiết kế, không phải tuyên bố mọi chức năng đã hoàn tất hoặc đã được kiểm thử. Repository tại Mốc A chưa chứa mã nguồn ứng dụng, vì vậy chưa có lệnh cài đặt, chạy hay tài khoản demo. Hướng dẫn tương ứng sẽ được bổ sung cùng mã nguồn ở các mốc tiếp theo.

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

Backend quyết định giá, quyền truy cập, lịch xe và trạng thái giao dịch. Phạm vi đồ án không bao gồm thanh toán trực tuyến hoặc kiến trúc microservices.

## Tài liệu

| Tài liệu | Nội dung |
|---|---|
| [Phạm vi hệ thống](docs/requirements/system-scope.md) | Bài toán, actor, nhóm nghiệp vụ và giới hạn |
| [Kiến trúc hệ thống](docs/architecture/system-architecture.md) | Các thành phần, phân tầng và nguyên tắc toàn vẹn dữ liệu |

## Các mốc đồng bộ dự kiến

Sau Mốc A, các mốc tiếp theo sẽ bổ sung database và xác thực; quản lý xe và chi nhánh; đặt xe và giao nhận; chứng từ và sự cố; tính năng tư vấn và kiểm thử. Mỗi mốc ghi rõ phạm vi đã đưa lên và phần còn thiếu. Lịch sử công bố trên repository khoa được phân biệt với lịch sử phát triển trước đó tại môi trường cá nhân.
