# Giao diện khách hàng và tích hợp API

React/Vite SPA dùng React Router, API client tập trung và layout Guest/User/Staff/Admin. Xem [frontend](../../frontend/README.md), [phạm vi](../requirements/system-scope.md) và [ranh giới module](../architecture/module-boundaries.md).

## Dữ liệu và thao tác

Đơn API mới chọn ngày nhận và ngày trả, không chọn giờ. Số ngày tính bằng chênh lệch hai ngày tại Việt Nam: nhận 17/10, trả 21/10 là 4 ngày thuê. Có thể chọn ngày nhận hôm nay; ngày trả phải sau ngày nhận, tối đa 90 ngày thuê. Khách được trả trong cả ngày trả; xe chỉ nhận lịch mới từ ngày kế tiếp, kể cả khách trả sớm. Staff xác nhận bàn giao trong ngày nhận; biên bản vẫn lưu giờ giao/nhận thực tế. Đơn cũ tiếp tục hiển thị giờ và giữ giá, hợp đồng/hóa đơn đã lưu theo chính sách cũ.

API mode lấy dữ liệu backend/MySQL, demo có model/session riêng và được nhận diện rõ. Lỗi API phải hiển thị lỗi/thử lại, không thay bằng dữ liệu mẫu. Backend kiểm tra quyền, chủ sở hữu, chi nhánh, trạng thái, lịch và giá. Admin không kế thừa giao nhận của Staff. [Trợ lý khách hàng](../modules/ai-assistant.md) tích hợp API gợi ý xe, phân tích nhu cầu và xem trước tuyến, phụ thuộc cấu hình và dịch vụ cục bộ; không tự đặt xe hoặc lưu hành trình.

Giao diện có loading/error/empty/pending, chặn gửi lặp và phản hồi cũ. Stale version/revision cần tải lại trước khi gửi tiếp. Quote gắn với input; đổi xe, thời gian hoặc chi nhánh làm quote cũ hết hiệu lực. VND nguyên đồng, thời gian Asia/Ho_Chi_Minh; hóa đơn không chứng minh đã thanh toán. Thông tin [so sánh](vehicle-comparison.md), [yêu thích](vehicle-favorites.md) và [hướng dẫn thuê](rental-guide.md) có contract riêng.

USER có thể mở [hành trình một ngày](../modules/ai-itinerary.md) từ khối khám phá trong Trợ lý, xem preview rồi chủ động lưu. Đổi tiêu chí hủy preview cũ; khi đang lưu, đầu vào bị khóa. Kết quả lưu đọc từ snapshot của chính tài khoản, không phụ thuộc provider còn chạy. Guest được chuyển đến đăng nhập; Staff/Admin không dùng trang planner dành cho khách.

## Tài khoản và hồ sơ

Đăng ký gồm họ tên, email, mật khẩu và tạo role USER. Email chuẩn hóa/kiểm trùng tại backend. Mật khẩu mới ít nhất 8 ký tự, có hoa/thường/số/ký tự đặc biệt, tối đa 72 byte UTF-8 và không thuộc danh sách phổ biến; tài khoản cũ giữ mật khẩu hiện có. Form có autocomplete, hiện/ẩn mật khẩu và giới hạn gửi request.

Hồ sơ sửa họ tên, số điện thoại và GPLX; email chỉ đọc. Điện thoại 7–15 chữ số, có thể bắt đầu bằng +; đây là kiểm tra định dạng, không xác minh sở hữu số. GPLX theo hệ/hạng/hộp số, còn hạn đến hết thuê; Staff đối chiếu bản gốc trước giao.

Hồ sơ API có khu vực tải hai mặt ảnh GPLX sau khi lưu thông tin bằng. Thiếu ảnh không gửi được yêu cầu thuê; báo giá vẫn kiểm tra tương thích như trước. Khách xem lại ảnh riêng tư và nhận xét yêu cầu bổ sung. Staff chi nhánh nhận kiểm tra ảnh qua chi tiết đơn trước xác nhận; kết quả này không thay đối chiếu bản gốc. Thay ảnh/thông tin đặt lại kết quả kiểm tra. Xem [quy tắc ảnh GPLX](../modules/license-photos.md).

Đổi mật khẩu cần mật khẩu hiện tại, mật khẩu mới và xác nhận. Thành công mới xóa phiên local, thu hồi token cũ và yêu cầu đăng nhập lại. Đăng xuất xóa phiên thiết bị ngay, đồng thời gọi API thu hồi; lỗi mạng phải cảnh báo chưa xác nhận thu hồi phía server. Đăng nhập tiếp theo chờ request đăng xuất cũ kết thúc. Không tự gửi lại thao tác ghi khi timeout; tải lại thông tin để kiểm tra kết quả. Email verification/OTP/SMS/Google OAuth chưa tích hợp.

## Danh mục và thương hiệu

Trang chủ/danh sách API có thanh hãng lấy từ xe thật và catalog, chỉ hiện hãng có xe; tối đa 7 hãng và phần còn lại trong Khác. Tất cả xe bỏ lọc hãng; chọn hãng dùng `/vehicles?brand=<brandId>` và kết hợp tên/loại/chỗ/hộp số/chuyến. Trạng thái xe không còn là bộ lọc công khai. Thiếu dữ liệu hoặc lỗi chỉ có Tất cả xe, không tạo hãng giả. Màn hình hẹp cuộn ngang; thanh hãng có tên truy cập, aria-current và focus rõ.

## Độ rõ và khả năng truy cập

Token màu/shadow nằm tại `frontend/src/index.css`. Nền trang `--color-page-background`, thẻ trắng `--color-surface`, nút chính cam/chữ tối, nút phụ trắng/viền rõ trong nội dung khách hàng. Chữ phụ dùng `--color-readable-muted`, viền điều khiển dùng `--color-secondary-border`; không thêm màu cứng trong component. Footer nền tối giữ màu chữ riêng.

Form có label/focus rõ, thao tác bàn phím và thông báo dễ hiểu. Chữ thông thường đặt mục tiêu tương phản tối thiểu 4.5:1, biên điều khiển 3:1 so với nền liền kề; đây là tiêu chí từng cặp màu, không tự chứng nhận toàn site đạt WCAG. Responsive web, không phát triển app mobile riêng. Kiểm tra bằng npm test/lint, build API/demo và browser với DB test cô lập; không test ghi trên dữ liệu vận hành.

## Hàng đợi đơn thuê trên website

Danh sách đơn sắp theo thời điểm tạo mới nhất trước; API trả `createdAt` theo UTC, giao diện hiển thị giờ Việt Nam. Bộ lọc trạng thái dùng nhãn tiếng Việt và giữ nguyên mã gửi/nhận qua API.

Bộ lọc **Hàng đợi** mặc định **Tất cả** để không bỏ sót đơn cũ chưa xử lý. **Hôm nay** tính theo ngày tạo đơn ở hàng chờ duyệt, ngày nhận xe ở trang bàn giao và ngày trả xe ở trang nhận trả, trong múi giờ `Asia/Ho_Chi_Minh`. **Tất cả** chỉ bỏ giới hạn ngày; trạng thái và từ khóa vẫn được áp dụng. Dùng **Tải lại danh sách** để lấy dữ liệu mới, không cam kết cập nhật thời gian thực. Bộ lọc không thay đổi quyền xem hoặc thao tác theo chi nhánh.

## Báo giá và cọc giữ chỗ trước gửi yêu cầu

Ở chế độ API, form đặt xe chưa có lựa chọn chuyến mặc định dùng chi nhánh hiện tại đang hoạt động của xe. Điểm trả theo điểm nhận khi có tuyến cùng chi nhánh đang mở. Nếu không có tuyến phù hợp, khách phải chọn điểm trả; hệ thống không tự chọn tuyến khác có phí. Lựa chọn trả khác trong chuyến tìm kiếm được giữ nếu còn hợp lệ; khi đổi điểm nhận làm tuyến đó không hợp lệ, form yêu cầu chọn lại.

Báo giá hiển thị tiền thuê, phí tuyến và tổng. Nếu backend yêu cầu cọc, form hiển thị số cọc từ báo giá và số còn lại bằng tổng trừ cọc; cọc đã nằm trong tổng, không cộng thêm lần nữa. Cọc chỉ được yêu cầu thanh toán sau khi nhân viên kiểm tra hồ sơ. QR DEMO ghi rõ không chuyển tiền thật. Khi báo giá không yêu cầu cọc, giao diện nói rõ điều đó. Báo giá không giữ chỗ hoặc chứng minh đã thanh toán; thay ngày/xe/nhánh làm mất hiệu lực báo giá và xác nhận trước đó.
