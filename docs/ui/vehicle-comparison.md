# So sánh xe trong chế độ API

Chọn/bỏ xe từ danh sách hoặc chi tiết; tối đa ba xe. Trang `/vehicles/compare?compare=id1,id2,id3` đọc lựa chọn từ URL, không lưu giá hay dữ liệu tài khoản trong trình duyệt và không thêm bảng DB. Có thể xem một xe rồi chọn thêm; ID sai/trùng/quá giới hạn được thông báo, xe không tải được có lỗi và nút thử lại riêng. Chế độ demo không gọi API so sánh; đường dẫn này quay về danh sách demo.

Các cột gồm ảnh, hãng/loại, số chỗ gồm người lái, hộp số, tiện nghi, chi nhánh hiện tại và giá block 24 giờ. Với chuyến đã chọn, mọi cột dùng chung thời gian và điểm nhận/trả từ URL. Trang gọi API tìm chuyến công khai để nhận tiền thuê, phí tuyến và tổng chuyến; không tự tính lại giá. Ngân sách chỉ cảnh báo vượt mức trong bảng so sánh, không làm mất giá của xe đã chọn. Xe không xuất hiện trong kết quả khả dụng không được hiển thị tổng giá hoặc nút đặt cho chuyến đó.

Đổi chuyến qua liên kết về form tìm kiếm; lựa chọn xe đi theo URL. Khi điều kiện thay đổi hoặc kiểm tra lại, giá cũ bị ẩn trong lúc chờ phản hồi mới. Nút kiểm tra lại cũng tải lại thông số xe. Không gắn nhãn rẻ nhất khi dữ liệu thiếu. Kết quả không giữ chỗ; backend tiếp tục kiểm quyền, GPLX, giá và lịch khi báo giá/tạo đơn.

Chọn xe từ cột đưa đúng `vehicleId`, thời gian và điểm nhận/trả sang đặt thuê, bao gồm qua đăng nhập. Đổi hãng, quay lại danh sách và chi tiết giữ điều kiện chuyến cùng lựa chọn. Xóa bộ lọc tìm kiếm giữ `brand` và lựa chọn so sánh; “Bỏ tất cả xe” chỉ xóa lựa chọn so sánh. Bảng có vùng cuộn ngang riêng trên màn hình hẹp và có thể nhận focus bàn phím.
