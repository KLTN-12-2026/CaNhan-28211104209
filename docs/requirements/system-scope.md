# Phạm vi hệ thống

Tài liệu xác định phạm vi thiết kế của KDrive. Mốc B công bố backend và database; frontend được đồng bộ ở mốc tiếp theo. Các yêu cầu dưới đây không thay thế bằng chứng triển khai hoặc kiểm thử. Xem [giới thiệu đề tài](../../README.md) và [kiến trúc](../architecture/system-architecture.md).

## Bài toán

Doanh nghiệp cần quản lý xe và lịch thuê tại nhiều chi nhánh, phân công nhân viên, lưu hồ sơ giao nhận và truy vết thay đổi trong quá trình thuê. Khách hàng cần tìm xe phù hợp, theo dõi đơn và nhận thông tin rõ ràng về giá, điểm nhận/trả, chứng từ và sự cố.

## Actor và quyền

- Guest sử dụng thông tin công khai, đăng ký hoặc đăng nhập; không có role riêng trong database.
- USER quản lý dữ liệu và giao dịch thuộc tài khoản của mình.
- STAFF xử lý nghiệp vụ theo chi nhánh được phân công; quyền đọc đơn không tự cấp quyền tác động xe ở chi nhánh khác.
- ADMIN quản trị dữ liệu và tài khoản, không mặc định có quyền giao nhận của STAFF.

Backend kiểm tra role, chủ sở hữu và chi nhánh; việc ẩn nút trên giao diện không thay thế phân quyền.

## Các nhóm chức năng

| Nhóm | Phạm vi |
|---|---|
| Tài khoản | Đăng ký, đăng nhập, hồ sơ cá nhân, quản lý tài khoản và quyền |
| Xe | Hãng, loại xe, số chỗ, hộp số, tiện nghi, ảnh, giá và trạng thái khai thác |
| Chi nhánh | Điểm nhận/trả, phân công nhân viên và tuyến nhận–trả được mở |
| Thuê xe | Tìm kiếm, kiểm tra lịch, báo giá, tạo/hủy đơn theo điều kiện và lịch sử trạng thái |
| Giao nhận | Xác minh GPLX, bàn giao, tiếp nhận xe và ghi tình trạng thực tế |
| Chứng từ | Hợp đồng, hóa đơn và snapshot của giao dịch; không đồng nghĩa đã thanh toán hoặc đã ký điện tử |
| Sự cố | Báo cáo, thẩm định, chứng cứ, căn cứ trách nhiệm và yêu cầu bồi thường |
| Đánh giá | Đánh giá gắn với đơn thuê đủ điều kiện và kiểm duyệt |
| Tư vấn | Hỗ trợ lựa chọn xe và hành trình từ dữ liệu hợp lệ; không tự thực hiện giao dịch thuê |
| Quản trị | Quản lý dữ liệu và báo cáo vận hành theo quyền |

## Quy tắc thiết kế trọng yếu

- Trả khác chi nhánh chỉ theo tuyến được mở. Phân biệt chi nhánh gốc, hiện tại, điểm nhận, điểm trả dự kiến và thực tế; không giả định xe tự quay về hoặc tự điều chuyển.
- GPLX xét hệ bằng, loại xe, số chỗ, hộp số và hiệu lực đến hết chuyến; không chỉ so sánh tên hạng. Staff đối chiếu trước giao xe.
- Đơn mới chọn ngày nhận/trả theo giờ Việt Nam; đơn cũ giữ chính sách và giá đã lưu. Chi tiết công thức, giới hạn và chuyển đổi dữ liệu được công bố cùng module thuê xe.
- Thời điểm xảy ra sự cố khác thời điểm báo cáo. Trách nhiệm và bồi thường dựa trên chứng cứ, không mặc định quy toàn bộ thiệt hại cho khách hoặc tự trừ cọc.
- Chứng từ giữ thông tin tại thời điểm phát hành; thay đổi danh mục không tự sửa dữ liệu lịch sử.
- Giá, quyền, lịch và trạng thái do backend quyết định. Kết quả tư vấn phải được kiểm tra lại trước khi tạo giao dịch.

## Giới hạn

Không thu tiền thật, không dùng microservices hoặc tra cứu GPLX từ cơ sở dữ liệu quốc gia. Source có cọc thử nghiệm và xử lý hủy/no-show theo [chính sách cọc](../modules/booking-deposit.md), mặc định tắt. [Chính sách trả xe](../modules/return-policy.md) mới lưu snapshot và xác nhận theo phạm vi mô tả, không tự hoàn thiện thu phí trả muộn. Gia hạn, phụ phí khác, phát hành lại chứng từ và lưu trữ/xóa dữ liệu không được coi là đã giải quyết chỉ vì có trường dữ liệu.

Lựa chọn mô hình, phạm vi dữ liệu và khả năng kiểm chứng tuyến đường phải được mô tả trong tài liệu chức năng tương ứng. Việc gọi mô hình có sẵn không được trình bày như tự huấn luyện mô hình.
