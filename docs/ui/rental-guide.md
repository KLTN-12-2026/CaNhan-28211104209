# Hướng dẫn thuê xe và tiến trình đơn

Trang `/rental-guide` truy cập công khai qua liên kết Hướng dẫn thuê xe ở footer, giải thích chọn xe, báo giá, gửi yêu cầu, xác nhận, nhận/trả và hoàn tất. FAQ dùng quy tắc hiện hành về block 24 giờ, tuyến chi nhánh, GPLX, hủy và chứng từ. Chế độ demo có thông báo dữ liệu riêng.

Chi tiết đơn hiển thị trạng thái hiện tại từ API và lịch sử các sự kiện đã ghi nhận theo giờ Việt Nam. Trạng thái hết hạn hiệu lực có thể xuất hiện trước khi lịch sử có sự kiện hết hạn; giao diện không tạo sự kiện hoặc chuyển trạng thái theo đồng hồ. Dùng **Tải lại đơn** để kiểm tra cập nhật; khi tải hoặc lỗi, nội dung cũ được ẩn theo cơ chế request hiện có.

Hướng dẫn bước tiếp theo phân biệt User, Staff và Admin. Các thao tác vẫn dùng chính sách quyền, chi nhánh và khoảng thuê hiện có; backend tiếp tục kiểm tra quyền và trạng thái. Admin không giao/nhận xe. Liên kết GPLX chỉ dành cho User. Hợp đồng hiển thị từ mốc xác nhận, kể cả sau khi hủy nếu lịch sử có xác nhận; hóa đơn từ mốc hoàn tất. Chứng từ thực tế vẫn lấy từ API khi mở, không xác nhận đã thanh toán.

Chạy `npm test`, `npm run lint` và `npm run build` tại `frontend/` để kiểm tra. Kiểm thử toàn hành trình nhiều tài khoản và nhiều tuyến được thực hiện riêng sau khi hoàn thành các phần triển khai.
