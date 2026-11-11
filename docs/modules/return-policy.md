# Chính sách trả xe — snapshot và xác nhận lúc đặt

## Bộ phân loại rủi ro lịch xe nội bộ

`ScheduleRiskPolicy` tính thuần từ danh sách lịch của **một xe** và thời điểm do bên gọi lấy từ Clock backend. Chỉ xét đơn nhận/trả cùng một chi nhánh; các đơn liên quan phải cùng chi nhánh đó. Không cần snapshot chính sách tính phí để nhận biết rủi ro, không tính tiền, không ghi DB hoặc đổi trạng thái. Đây là thành phần nội bộ chưa nối vào API, UI hay các điểm chặn lịch; không đồng nghĩa cảnh báo đã xuất hiện trên website.

- `RETURN_DUE_NEXT_DAY_BOOKING`: đơn A còn IN_PROGRESS, hôm nay là ngày trả và có đơn B nhận ngày kế tiếp. Gắn với A, tham chiếu B; chưa gọi A là quá hạn. Không cảnh báo sớm cho B nhận cách hai ngày hoặc xa hơn.
- `OVERDUE`: A chưa trả và đã vượt hạn. Đơn theo ngày bắt đầu quá hạn từ 00:00 ngày kế tiếp theo Asia/Ho_Chi_Minh; thời điểm cuối ngày lưu ở độ chính xác microsecond vẫn chưa quá hạn. Đơn legacy dùng đúng thời điểm kết thúc cũ (từ thời điểm đó), không chuyển sang thuê theo ngày.
- `PREDECESSOR_NOT_RETURNED`: gắn với từng B kế tiếp còn CONFIRMED hoặc PENDING chưa hết hạn, tham chiếu A đang quá hạn. Kể cả ngày nhận B đã đến hoặc đã qua, B vẫn cần được kiểm tra nếu chưa xử lý; không khẳng định chắc chắn trùng lịch chỉ từ dự đoán ngày trả. Không xét B hủy, từ chối, hết hạn, đã trả hoặc hoàn tất.

Kết quả gồm loại, mã đơn, mã đơn liên quan nếu có, ngày trả dự kiến của A và số ngày Việt Nam đã vượt. Legacy quá hạn trong cùng ngày có số ngày bằng 0; trạng thái quá hạn không được suy từ số ngày này hoặc dùng làm phí. Kết quả không trùng, thứ tự theo thời gian bắt đầu rồi mã đơn. Đầu vào phải là lịch đầy đủ của một xe, không trộn xe hoặc lọc chỉ còn trang hiện tại; bên tích hợp chịu trách nhiệm kiểm tra quyền trước khi trả mã đơn liên quan.

A được nhận trả thì rủi ro do A chưa về biến mất; điều này không tự mở lịch, bỏ bảo dưỡng hoặc rút ngắn thời gian giữ xe của đơn trả sớm. Không có job, tự hủy, đổi xe, gia hạn, hoàn tiền hay ngày dự kiến do Staff nhập. Các quy tắc chặn lịch hiện có giữ nguyên.

## Kiểm tra lịch tại thao tác nghiệp vụ

Tìm xe, báo giá, tạo đơn, xác nhận thường và xác nhận từ cọc dùng kiểm tra lịch chung. Bộ phân loại và kiểm tra lịch dùng cùng mốc quá hạn: đơn theo ngày từ đầu ngày kế tiếp tại Việt Nam; legacy từ thời điểm kết thúc. Cảnh báo sớm không tự chặn lịch hợp lệ. Xe quá hạn chưa trả không được bảo đảm cho đơn kế tiếp, kể cả ngày nhận còn xa; báo giá cũ không bỏ qua kiểm tra lại khi tạo đơn.

Duyệt yêu cầu đóng cọc kiểm tra xe ACTIVE trước khi lưu duyệt, đồng thời kiểm tra lại lịch và GPLX. Nếu khoản cọc đã được ghi nhận nhưng không thể xác nhận do lịch xe, giữ quy trình hoàn khoản chưa áp dụng hiện có; không tự phát hành hợp đồng hay giành lại lịch. Bàn giao tiếp tục yêu cầu xe sẵn sàng tại đúng chi nhánh và không còn đơn khác IN_PROGRESS. Nhận trả không bỏ qua trạng thái bảo dưỡng. Các khóa xe/đơn, version và isolation của workflow không thay đổi; chưa bổ sung cảnh báo API/UI.

`RETURN_POLICY_ENABLED` mặc định `false` (`app.return-policy-enabled`). Chỉ bật trên fixture thử; giữ tắt ở môi trường sử dụng cho đến khi nhận trả, công nợ và bảo vệ lịch hoàn chỉnh. Cờ điều khiển áp chính sách khi tạo đơn mới; đơn đã chấp nhận chính sách vẫn sử dụng snapshot nếu tắt cờ. Ghi thu thủ công khoản vượt hạn có nhật ký riêng; chưa thay lịch.

Khi bật, chỉ đơn theo ngày, nhận/trả cùng chi nhánh nhận chính sách `same-branch-return-v1`. Không thay `policyVersion` tính ngày thuê. Trả sớm không tự giảm giá/hoàn cọc và giữ lịch gốc; trả muộn theo số ngày Việt Nam vượt ngày trả, nhân giá/ngày đã lưu, không thêm tiền phạt. Cọc giữ chỗ thuộc tiền thuê, không phải cọc bảo đảm; khoản vượt hạn không tự được coi là đã thu.

## API

Quote thêm `returnPolicy`, hoặc `null` nếu không áp dụng:

```json
{
  "version": "same-branch-return-v1",
  "terms": {
    "dailyPriceVnd": 600000,
    "timeZone": "Asia/Ho_Chi_Minh",
    "lateDayRule": "VIETNAM_CALENDAR_DAYS_AFTER_RETURN_DATE",
    "earlyReturnRule": "KEEP_BOOKED_PRICE_AND_RESERVED_END",
    "text": "Nội dung chính sách do backend cung cấp"
  },
  "acceptedAt": null
}
```

Tạo đơn thuộc chính sách yêu cầu `returnPolicyVersion` đúng báo giá và `returnPolicyAccepted: true`. Quote không yêu cầu xác nhận. Backend quyết định giá/tham số/nội dung và thời điểm xác nhận UTC; không tin snapshot hoặc timestamp client truyền. Sai/mất phiên bản hoặc cờ thay đổi trả409 yêu cầu báo giá lại; chưa đồng ý trả400. Đơn không áp dụng tiếp tục dùng payload cũ; payload nhận phiên bản đã hết áp dụng bị từ chối, không âm thầm bỏ chính sách.

UI có checkbox riêng với xác nhận báo giá; đổi điều kiện hoặc báo giá lại phải xác nhận lại. Xác nhận đã đọc không phải chữ ký hợp đồng.

## Lưu và tương thích

V10 thêm ba cột nullable trong `bookings`: `return_policy_version`, `return_policy_snapshot` (JSON tham số/nội dung), `return_policy_accepted_at`. Cả ba cùng rỗng hoặc cùng có dữ liệu. Không thêm bảng, không backfill hoặc truy thu đơn cũ; không sửa V1–V9.

Chi tiết đơn đọc snapshot đã lưu. Cả xác nhận thường và xác nhận từ cọc sao chép cùng `returnPolicy` vào snapshot hợp đồng; thay giá/cờ/nội dung về sau không sửa đơn hoặc hợp đồng cũ. Không phát hành lại chứng từ.

## Ghi nhận trả xe cùng chi nhánh

Staff chi nhánh trả gọi `GET /api/v1/bookings/{id}/return-preview` cho đơn `IN_PROGRESS` có snapshot chính sách hỗ trợ. Kết quả `{version, assessment}` là xem trước, không ghi biên bản hoặc khoản tiền. `assessment` gồm `bookedReturnDate`, `actualReturnDate`, `actualReturnedAt`, `timing` (`EARLY/ON_TIME/LATE`), `lateDays`, `dailyPriceVnd`, `amountVnd`, `collectionStatus`. Ngày Việt Nam và thời điểm dự kiến lấy từ Clock backend; Staff không tự chọn ngày để tránh phí. Số ngày vượt hạn là chênh lệch dương giữa ngày ghi nhận và ngày trả đăng ký, không phải số block24giờ; giá lấy từ snapshot chính sách.

POST `/{id}/actions` với `next: RETURNED` tiếp tục nhận `version`, `condition`, `maintenance`; đơn áp chính sách phải thêm `returnPreviewDate` và `expectedLateAmount` từ preview. Đây là dữ liệu đối chiếu, không có thẩm quyền quyết định tiền. Backend khóa xe/đơn, tính lại, từ chối409 nếu version/ngày/số tiền không khớp, kể cả qua nửa đêm. Lưu kết quả và RETURN record trong cùng transaction; rollback nếu ghi biên bản lỗi. Thời điểm thực tế là thời điểm backend xác nhận, lưu UTC; chưa hỗ trợ ghi nhận lùi ngày.

V11 thêm `bookings.return_assessment` TEXT nullable, không thêm bảng/backfill. Chi tiết/list đơn trả `returnAssessment` đã lưu. RETURN record `occurred_at` trùng thời điểm trong assessment. Legacy/khác chi nhánh/đơn không có policy giữ quy trình cũ, không tự tính phí mới.

Trả sớm/đúng ngày: `amountVnd=0`, `collectionStatus=NOT_DUE`; tiền thuê gốc và lịch giữ đến hết ngày trả không đổi. Trả muộn: `collectionStatus=UNPAID` (Chưa thu), không tự trừ cọc, không đánh dấu đã thanh toán. Ví dụ trả đăng ký28/10, ghi nhận31/10, giá650.000đ/ngày →3ngày,1.950.000đ. Đơn có khoản còn phải thu không được COMPLETED hoặc phát hành hóa đơn. Thu/miễn/đảo và số dư hiện tại theo nhật ký mô tả bên dưới; không sửa kết quả tính phí gốc. Đơn không phát sinh khoản vượt hạn vẫn có thể hoàn tất theo luồng hóa đơn cũ.

Desktop tách xem trước và xác nhận nhận xe; đổi ghi chú/tình trạng bảo trì bỏ preview. Gửi lặp bị chặn tại UI và kiểm soát version/unique record backend; khi kết quả POST không rõ, chỉ đọc lại trước thao tác tiếp. User/Staff/Admin được đọc kết quả theo quyền đọc đơn hiện có; Admin không được nhận trả thay Staff. Chưa giải quyết cảnh báo/đổi xe hoặc hủy đơn kế tiếp khi xe trả muộn; không được suy đây là luồng trả muộn hoàn chỉnh để bật trên môi trường sử dụng.


## Xử lý khoản vượt hạn
V12 thêm booking_return_settlements (tổng 30 bảng kể cả Flyway). Booking sở hữu khoản vượt hạn và nhật ký xử lý; không đưa khoản này vào payment_transactions của cọc. Nhật ký bất biến gồm RECEIPT, WAIVER, REVERSAL; đảo toàn bộ một bản ghi thu/miễn trước đó một lần, không đảo bản ghi đảo. Đảo sai ghi nhận không xác nhận hoàn tiền thực tế. Không sửa ngày, giá, khoản vượt hạn gốc, hợp đồng hoặc hóa đơn cũ.

POST /api/v1/bookings/{id}/return-settlements nhận kind, amountVnd (VND nguyên dương cho thu/miễn), method (CASH/BANK_TRANSFER cho thu), reference (tham chiếu thu), reason, reversesId (chỉ đảo), idempotencyKey, expectedVersion, confirmed=true. Staff đúng chi nhánh trả chỉ ghi thu đã đối chiếu; Admin chỉ miễn/giảm/đảo. USER chỉ đọc. Phải RETURNED có khoản vượt hạn dương cùng chi nhánh. Đảo không nhận số tiền client. Transaction ghi công nợ và action hoàn tất dùng READ_COMMITTED để đọc được nhật ký vừa commit sau khi chờ khóa. Khóa xe rồi đơn cùng thứ tự hoàn tất, ghi nhật ký và tăng version trong cùng transaction; lỗi rollback cả hai. Cùng khóa idempotency/nội dung/người gửi trả readback không ghi trùng; đổi nội dung cùng khóa trả409. Từ chối số tiền vượt số dư, tiền lẻ/âm/0, tham chiếu thu đang dùng trong cùng đơn, sai quyền/version; không tự retry mutation sau timeout.

returnSettlement trên đơn gồm originalAmount, receivedAmount, waivedAmount, outstandingAmount, status, demo, entries. Số dư = gốc - thu hợp lệ - miễn hợp lệ; bản bị đảo không tính. Trạng thái UNPAID/PARTIAL/PAID/WAIVED/SETTLED/NOT_DUE phân biệt miễn và thu. returnAssessment.collectionStatus là kết quả ban đầu khi nhận xe; số dư/trạng thái hiện tại lấy returnSettlement. Khi số dư0 Staff được hoàn tất riêng; Admin không có quyền này. Chỉ khoản vượt hạn đã xử lý, không xác nhận phần tiền thuê còn lại.

RETURN_SETTLEMENT_DEMO mặc định true: nhật ký và giao diện gắn nhãn mô phỏng; QA không chuyển tiền thật. Chế độ lưu trên từng bản ghi, không trộn chế độ trong một đơn. Không bật chính sách hoặc chế độ thu thật trên môi trường sử dụng khi chưa có nghiệm thu vận hành đầy đủ.

Hóa đơn mới lưu snapshot assessment và settlement; tổng = tiền gốc + vượt hạn gốc - miễn/giảm. Invoice item vượt hạn lưu số ròng không âm để giữ ràng buộc schema cũ; khối giải thích riêng hiển thị khoản gốc, thu, miễn và còn thiếu. Hóa đơn không chứng minh toàn bộ tiền thuê đã thanh toán. Sau COMPLETED không ghi/đảo mới; retry cùng thao tác đã có chỉ đọc lại.
