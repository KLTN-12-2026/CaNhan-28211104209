# Xe yêu thích

Trong chế độ API, chọn Yêu thích ở danh sách hoặc chi tiết xe rồi mở Xe yêu thích. Danh sách chỉ lưu mã xe trên trình duyệt hiện tại. Khách và từng tài khoản có danh sách riêng; đăng nhập, đăng xuất hoặc đổi tài khoản không tự gộp danh sách. Khi phiên chưa xác định hoặc kiểm tra phiên lỗi, danh sách được ẩn và thao tác lưu bị khóa.

Không đồng bộ giữa thiết bị; không lưu token, hồ sơ, giá hoặc dữ liệu đặt xe trong tính năng này. Bộ lọc chuyến đi được giữ trong URL khi quay lại danh sách hoặc mở chi tiết. Trang yêu thích hiển thị giá theo block 24 giờ lấy mới từ API, không xác nhận xe trống theo chuyến; người dùng quay lại tìm xe để kiểm tra lịch và báo giá.

Bộ nhớ bị chặn hoặc hết dung lượng: thông báo rõ lựa chọn chỉ giữ trong phiên hiện tại. Dữ liệu không đọc được được báo lỗi, không làm hỏng trang. Xe không còn trong danh mục hiển thị thông báo và nút bỏ lựa chọn; không thay bằng xe mẫu. Nút Tải lại danh sách lấy lại danh mục, ảnh, giá và trạng thái hiện tại từ API. Các tab cùng phạm vi cập nhật qua sự kiện storage của trình duyệt.

Kiểm thử chuyên biệt dùng fixture nội bộ chưa được đóng gói trong mốc công bố này. Không coi các kiểm thử đó là đã tái chạy từ repository này; các kiểm tra frontend khả dụng là `npm test`, `npm run lint` và `npm run build`.
