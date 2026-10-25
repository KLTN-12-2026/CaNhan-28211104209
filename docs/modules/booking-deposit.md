# Cọc giữ chỗ — QR DEMO thủ công và VNPAY Sandbox

Chỉ thử nghiệm, không thu tiền thật. Mặc định `DEPOSIT_ENABLED=false`. QR DEMO không cần merchant; VNPAY Sandbox cần merchant riêng. URL VNPAY cố định ở sandbox, không tự chuyển production.

## Quy tắc

Đơn mới khi cờ bật lưu snapshot `sandbox-deposit-30-v1`: cọc 30% tiền thuê làm tròn lên VND nguyên, không tính phí tuyến. Cọc tính vào tiền thuê, không là cọc thiệt hại. Đơn cũ không yêu cầu cọc. Hóa đơn vẫn thể hiện giá trị dịch vụ, không chứng minh đã thu đủ tiền.

PENDING gồm chờ kiểm tra hồ sơ/chờ đóng cọc. Staff chi nhánh nhận duyệt ảnh GPLX và yêu cầu cọc. Hạn là mốc sớm hơn giữa duyệt +30 phút và hạn giữ lịch hiện có; duyệt lại không kéo dài. Backend kiểm lại GPLX, lịch, xe và hạn trước khi tự CONFIRMED, phát hành một hợp đồng. Không xác nhận thủ công đơn yêu cầu cọc. Đối chiếu bản gốc và bàn giao giữ nguyên.

Return URL chỉ hiển thị; IPN hoặc truy vấn có chữ ký mới ghi nhận tiền. Mỗi giao dịch có tham chiếu riêng. Kết quả chưa rõ phải đối soát trước khi thay thế. Ghi nhận tiền tách khỏi xác nhận đơn để tiền đến muộn/không đủ điều kiện vẫn được đưa vào hoàn, không giành lại lịch.

## Hủy và hoàn

- Chưa thu tiền: không hoàn.
- Doanh nghiệp từ chối/không cung cấp xe, khách hủy trước mốc nhận ít nhất 24 giờ: hoàn toàn bộ. Đơn theo ngày dùng đầu ngày nhận.
- Hủy sát giờ hoặc không đến nhận: Admin quyết định 0–100%, bắt buộc lý do.
- Tiền đến muộn, đơn không xác nhận được hoặc thu thừa: hoàn toàn bộ khoản không áp dụng.
- No-show: sau hết ngày nhận, Staff chi nhánh nhận xác nhận đã liên hệ và chưa giao xe, ghi lý do, kiểm tra chuỗi lịch trước hủy. Không áp dụng cho đơn đã giao/có biên bản giao.

Hoàn có vòng đời REQUESTED → APPROVED → PROCESSING → SUCCEEDED/FAILED/UNKNOWN; quyết định 0 đồng là DECLINED. Gửi yêu cầu thành công chưa là đã hoàn. Timeout giữ UNKNOWN, không gửi lại mù quáng. Tổng hoàn thành công và đang xử lý không vượt tiền thu. Không tự trừ thiệt hại/phụ phí.

## Cấu hình

V6 thêm payment_transactions, payment_refunds, payment_events; tổng 29 bảng gồm Flyway history. Không sửa migration cũ. Biến môi trường backend: DEPOSIT_ENABLED, VNPAY_TMN_CODE, VNPAY_HASH_SECRET, VNPAY_RETURN_URL. Spring không tự đọc .env.local. Không đưa secret vào Git/log/frontend.

Tắt nhận thanh toán mới vẫn xử lý IPN/đối soát/hoàn cũ. HTTPS tunnel chỉ chuyển endpoint IPN, không mở DB/Admin. Chưa có merchant không nghiệm thu tích hợp sandbox thật; kiểm thử giao thức nội bộ không thay thế kiểm thử nhà cung cấp.

Nguồn: [thanh toán](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html), [truy vấn/hoàn](https://sandbox.vnpayment.vn/apis/docs/truy-van-hoan-tien/querydr%26refund.html), [FAQ](https://sandbox.vnpayment.vn/apis/docs/faqs/).

## QR DEMO thủ công

Cấu hình backend `DEPOSIT_METHOD=DEMO_QR_MANUAL`, `DEPOSIT_ENABLED=true` chỉ trong môi trường thử được phép. Mã QR cục bộ có payload `KDRIVE_DEMO|mã yêu cầu|số tiền`, không phải QR chuyển khoản ngân hàng. Bên nhận là dữ liệu giả lập chung; không chuyển tiền thật. Phương thức/giá/chính sách lưu theo đơn; đổi cấu hình không đổi đơn cũ.

Staff pickup duyệt hồ sơ → USER xem QR và báo đã chuyển → Staff đối chiếu đúng mã yêu cầu/bên nhận/số tiền/nguồn tiền giả lập, ghi từng khoản. Báo của USER không phải tiền đã nhận. Hạn đóng cọc là min(duyệt+30 phút, hạn giữ gốc). Báo lần đầu trước hạn cho thêm tối đa30 phút đối soát, vẫn cap hạn giữ gốc; báo/duyệt lặp không gia hạn. Khoản chuyển tại/sau hạn không áp dụng, kể cả còn thời gian đối soát.

Cho cộng dồn khoản thiếu; backend áp dụng FIFO theo thời điểm chuyển/ID khi đủ, xác nhận đơn và phát hành đúng một hợp đồng. Lưu khoản nhận bền trước bước này. Lỗi hợp đồng/hạ tầng để lại khoản cần đối soát; GPLX/lịch không còn hợp lệ hoặc quá hạn không giành lại lịch, tạo hoàn phần không áp dụng. Tiền dư và khoản thiếu khi hết giữ lịch được đưa vào hoàn. Tham chiếu giao dịch và khóa yêu cầu chống ghi trùng; không sửa/xóa khoản đã ghi nhận qua thao tác thường.

Admin bắt đầu xử lý khoản đã duyệt rồi ghi kết quả hoàn DEMO ở bước riêng: tài khoản nhận phải khớp nguồn tiền, có tham chiếu, thời điểm và version. Không coi bắt đầu xử lý là đã hoàn. Một khoản nhận có thể hoàn nhiều phần theo nguyên nhân; tổng đã hoàn/đang xử lý không vượt tiền thực nhận. Hủy muộn/no-show luôn cần quyết định Admin, kể cả quyết định0đ. Tắt cờ nhận mới không chặn xử lý khoản đã có.

API bổ sung: GET `/bookings/{id}/deposit/qr`, `/qr-payload`; POST `/deposit/report`, `/deposit/verify`; Admin `/refunds/{id}/process-manual`, `/complete-manual`. verify yêu cầu `verified=true`, tham chiếu, VND, Instant UTC, mã yêu cầu đúng, tài khoản nguồn giả lập, expectedVersion/idempotencyKey. complete-manual yêu cầu recipientAccount/reference/transferTime/expectedVersion. UI nhập giờ Việt Nam và chuyển +07:00 tường minh; ảnh QR tải có Bearer, không đưa token vào URL.

Ghi nhận cọc QR yêu cầu `amountVnd` là số JSON nguyên dương; không nhận chuỗi, số thập phân hoặc số vượt giới hạn Long. POST verify trả trực tiếp thông tin đơn, không bao gồm ledger. GET `/bookings/{id}/deposit` trả `deposit`, `payments`, `refunds`, `events`; quyền đọc theo chủ đơn hoặc chi nhánh được phân công, Admin có quyền tra cứu.

Staff chủ động điền thời điểm hiện tại theo giờ Việt Nam hoặc nhập thời điểm trên chứng từ, kiểm tra bản xác nhận trước khi gửi. Trong lúc gửi hoặc chưa rõ kết quả, khóa nội dung khoản nhận. Lỗi mạng không đồng nghĩa thất bại: tải lại ledger và đơn, đối chiếu booking, khóa yêu cầu, tham chiếu, số tiền, nguồn, nội dung và thời điểm. Nếu cần gửi lại thì giữ nguyên khóa và payload; không tạo khoản thay thế. Nếu đã nhận phản hồi thành công hoặc tìm thấy khoản khớp nhưng tải lại đơn thất bại, chỉ kiểm tra kết quả, không ghi nhận lại. Xung đột phiên bản/tham chiếu cần đối soát.

## Tra cứu Admin và hoàn DEMO

GET `/admin/payments` chỉ dành cho Admin. `unresolved` là hàng cần đối soát (tối đa 50); `payments` là 200 giao dịch gần nhất, gồm cả khoản đã áp dụng; `refunds` là 200 yêu cầu hoàn gần nhất. Tìm kiếm/lọc trên màn này áp dụng trong danh sách đã tải, không phải toàn bộ DB; chi tiết đơn có lịch sử riêng. `refundPayments` chứa khoản nhận liên quan các yêu cầu hoàn đã tải, kể cả khoản nhận cũ ngoài giới hạn lịch sử. `historyLimit` công bố giới hạn này.

`refundableAmounts` là số có thể yêu cầu hoàn thủ công cho phần chưa áp dụng của từng giao dịch trong lịch sử: tiền nhận trừ tiền đã áp dụng và khoản dành cho APPROVED/PROCESSING/UNKNOWN/SUCCEEDED, chặn dưới ở 0. Máy chủ vẫn khóa/kiểm tra lại khi mutation. `manualRefundPaymentIds` giúp không mở lại form tạo yêu cầu thủ công đã có, kể cả yêu cầu đó ngoài danh sách gần nhất. Không suy phần chưa áp dụng là tiền dư đã được phép hoàn, hoặc số tiền yêu cầu là số đã hoàn.

Admin xem lại khoản/đơn/số tiền/lý do trước khi gửi; hoàn DEMO cần đối chiếu nguồn tiền, tham chiếu, thời điểm và xác nhận rõ. Duyệt và bắt đầu xử lý không đồng nghĩa đã hoàn; chỉ SUCCEEDED hiển thị đã hoàn. Số tiền JSON khi tạo/duyệt yêu cầu phải được cung cấp tường minh và là số nguyên; thiếu/null bị từ chối, không ép số thập phân hoặc chuỗi. Quyết định không hoàn phải truyền rõ số 0 cùng lý do. Phát lại hoàn thành đã thành công chỉ được chấp nhận nếu cùng tham chiếu, tài khoản nguồn và thời điểm theo độ chính xác giây của `requestDate` hiện có; khác payload trả 409, không ghi thêm sự kiện.

Khi POST lỗi mạng hoặc tải lại sau POST thành công bị lỗi, giao diện khóa thao tác và chỉ kiểm tra GET, không tự POST lại hay tạo yêu cầu thay thế. Đối chiếu theo ID yêu cầu, khoản nhận, version và dữ liệu thao tác; hoàn thành còn phải khớp tham chiếu/thời điểm/nguồn tiền. Dữ liệu chưa khớp cần đối soát, không báo hoàn thành. Không lưu thông tin nguồn tiền hoặc hàng đợi retry trong localStorage.

Migration V7/V8 bổ sung trường và guard trong ba bảng payment hiện có. V9 là migration Java Flyway nhỏ trong `backend/src/main/java/db/migration/`, chỉ xử lý tên UNIQUE(payment_id) do từng DB tự sinh, giữ FK và index tra cứu; tất cả dùng chung namespace `classpath:db/migration` và history Flyway, không có DDL chạy riêng. Tổng vẫn29 bảng gồm history; không đổi V1–V6. Không dùng V7 thử nghiệm cũ để nâng DB đã áp dụng checksum khác; cần kiểm kê history trước. Java migration có checksum tường minh và được kiểm thử MySQL/H2.
