# Chính sách trả xe — snapshot và xác nhận lúc đặt

`RETURN_POLICY_ENABLED` mặc định `false` (`app.return-policy-enabled`). Chỉ bật trên fixture thử; giữ tắt ở môi trường sử dụng cho đến khi nhận trả, công nợ và bảo vệ lịch hoàn chỉnh. Cờ điều khiển áp chính sách khi tạo đơn mới; đơn đã chấp nhận chính sách vẫn sử dụng snapshot nếu tắt cờ. Chưa triển khai thu khoản vượt hạn hoặc thay lịch.

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

Trả sớm/đúng ngày: `amountVnd=0`, `collectionStatus=NOT_DUE`; tiền thuê gốc và lịch giữ đến hết ngày trả không đổi. Trả muộn: `collectionStatus=UNPAID` (Chưa thu), không tự trừ cọc, không đánh dấu đã thanh toán. Ví dụ trả đăng ký28/10, ghi nhận31/10, giá650.000đ/ngày →3ngày,1.950.000đ. Đơn có khoản này chưa thu không được COMPLETED hoặc phát hành hóa đơn. Thu/miễn/điều chỉnh công nợ và dòng hóa đơn vượt hạn thuộc bước tiếp theo; hiện chưa có thao tác đóng khoản này. Đơn không phát sinh khoản vượt hạn vẫn có thể hoàn tất theo luồng hóa đơn cũ.

Desktop tách xem trước và xác nhận nhận xe; đổi ghi chú/tình trạng bảo trì bỏ preview. Gửi lặp bị chặn tại UI và kiểm soát version/unique record backend; khi kết quả POST không rõ, chỉ đọc lại trước thao tác tiếp. User/Staff/Admin được đọc kết quả theo quyền đọc đơn hiện có; Admin không được nhận trả thay Staff. Chưa giải quyết cảnh báo/đổi xe hoặc hủy đơn kế tiếp khi xe trả muộn; không được suy đây là luồng trả muộn hoàn chỉnh để bật trên môi trường sử dụng.
