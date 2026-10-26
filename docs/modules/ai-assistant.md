# Trợ lý KDrive — chatbox gợi ý xe

Nút Trợ lý KDrive dành cho USER đã đăng nhập, ở góc dưới bên phải các trang desktop: trang chủ, danh sách/chi tiết xe, danh sách/chi tiết/tạo đơn thuê và hồ sơ. Không hiển thị cho Guest/Staff/Admin hoặc trang đăng nhập, chứng từ. Mobile chưa được triển khai; dưới768px nút được ẩn.

Chatbox mở theo thao tác người dùng, có thu nhỏ/đóng, Escape và trả focus về nút mở. Bắt đầu lại xóa nhu cầu và kết quả. Biểu mẫu/kết quả chỉ giữ trong bộ nhớ khi chuyển trang SPA; tải lại trang, đăng xuất, đổi tài khoản hoặc đổi phiên sẽ xóa. Không lưu hội thoại trong database, localStorage hay sessionStorage. Khi sửa nhu cầu/reset/đổi phiên, phản hồi đang chạy bị hủy và không được gắn vào nhu cầu/phiên mới.

Biểu mẫu kết nối [API AI1](ai-recommendation.md): ngày nhận/trả, chi nhánh, số người gồm tài xế, tổng ngân sách và ưu tiên hộp số/tiện nghi. Ô Mô tả nhu cầu gọi Qwen cục bộ qua backend để điền bản nháp; khách phải kiểm tra rồi chủ động bấm Gợi ý xe phù hợp. Không tự gửi yêu cầu gợi ý sau khi đọc câu. Nếu mở lần đầu từ URL có thông tin chuyến đi, biểu mẫu nhận các ngày và chi nhánh đó; chuyển trang sau đó không ghi đè nhu cầu đang nhập.

API mode tải chi nhánh và tiện nghi từ backend; lỗi danh mục có nút thử lại, không dùng seed/demo thay thế. Kết quả trả tối đa3 xe đầu theo thứ tự server, hiển thị tổng tiền gồm phí tuyến và lý do dựa trên facts. Xem xe chuyển tiếp ngày nhận/trả, chi nhánh và ngân sách vào trang xe. Không áp dụng ưu tiên mềm thành bộ lọc cứng khi chuyển tiếp. Không đặt/hủy/sửa đơn qua chatbox; quote/create vẫn kiểm tra lại GPLX, giá và lịch.

Không có xe thì báo rõ để người dùng tự sửa tiêu chí. Backend chưa bật hoặc lỗi thì giữ biểu mẫu, hiển thị lỗi và đường dẫn Tìm xe. Phản hồi sai requestId/schema bị từ chối. Demo mode chỉ thông báo chưa kết nối, không gọi API hoặc tạo gợi ý giả.

API gợi ý xe mặc định tắt bằng `AI_RECOMMENDATION_ENABLED`. Phân tích văn bản dùng `QWEN_PARSER_ENABLED` và tuyến đường dùng `AI_ROUTING_ENABLED`; các cờ mặc định tắt. Cần chuẩn bị dịch vụ Qwen/OSRM tương ứng theo [hướng dẫn cấu hình cục bộ](local-ai-routing.md). Có source tích hợp không đồng nghĩa tính năng đang bật hoặc đã được kiểm thử trên mọi môi trường.


POST `/api/v1/ai/criteria/parse` chỉ USER, body `{ "text": "..." }` tối đa1000ký tự. Backend từ chối một số mẫu email/số điện thoại/số giấy tờ phổ biến trước khi suy luận; đây không phải bộ phát hiện mọi dữ liệu cá nhân. Không gửi userID/token/hồ sơ hoặc lưu hội thoại cho model. Chỉ đối chiếu tên với điểm nhận/trả ACTIVE qua API nội bộ booking; tên không rõ hoặc nhiều kết quả phải chọn lại. Ngày phải xuất hiện đầy đủ trong câu nhập ở dạng YYYY-MM-DD hoặc ngày/tháng/năm; ngày thiếu năm không tự mặc định. Ưu tiên hộp số chỉ được điền khi câu nhập nêu rõ, không được biến dữ liệu thiếu thành mặc định tự động. READY_FOR_REVIEW trả bản nháp cần kiểm tra, NEEDS_CLARIFICATION yêu cầu bổ sung, OUT_OF_SCOPE không tạo bản nháp. Phản hồi có mâu thuẫn không được dùng để điền. Không hỗ trợ hội thoại nhiều lượt hoặc tự xác nhận nghiệp vụ.
# Địa điểm và tuyến đường AI3

Trong chatbox, mở “Khám phá địa điểm và tuyến đường” để đọc metadata địa điểm và preview tuyến2–8 điểm theo thứ tự. Graph hiện hỗ trợ vùng Đà Nẵng–Hội An; số liệu chỉ xuất hiện khi tuyến VERIFIED. Thông tin chưa xác minh, nguồn và thời điểm kiểm chứng được hiển thị rõ; không tự lưu itinerary. Xem [contract metadata](location-visit-metadata.md).

Khối khám phá có liên kết đến [hành trình một ngày](ai-itinerary.md): USER chủ động preview rồi lưu/xem lại tại trang riêng. Xem tuyến trong chatbox không tự lưu hành trình.
