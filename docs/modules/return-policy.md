# Chính sách trả xe — snapshot và xác nhận lúc đặt

`RETURN_POLICY_ENABLED` mặc định `false` (`app.return-policy-enabled`). Chỉ bật trên fixture thử; giữ tắt ở môi trường sử dụng cho đến khi xử lý nhận trả, khoản vượt hạn và ghi thu đã hoàn chỉnh. Việc lưu chính sách không tự bật tính phí, thu tiền hoặc thay lịch/state machine hiện hành.

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

Chi tiết đơn đọc snapshot đã lưu. Cả xác nhận thường và xác nhận từ cọc sao chép cùng `returnPolicy` vào snapshot hợp đồng; thay giá/cờ/nội dung về sau không sửa đơn hoặc hợp đồng cũ. Không phát hành lại chứng từ. Chưa thêm trường ghi thu/phí thực tế cho tính năng chưa triển khai.
