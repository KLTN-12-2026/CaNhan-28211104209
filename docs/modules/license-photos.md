# Hồ sơ ảnh GPLX

Khách khai báo GPLX và tải đủ hai mặt JPEG/PNG trước khi gửi yêu cầu thuê. Báo giá vẫn kiểm tra tương thích và hiệu lực GPLX nhưng chưa bắt buộc ảnh. Mỗi mặt tối đa 5 MB và 20 triệu điểm ảnh; backend giải mã và ghi lại PNG để loại bỏ metadata và dữ liệu đính kèm. Không OCR, nhận diện khuôn mặt, tra cứu quốc gia hoặc gửi ảnh cho AI.

## Hai bước kiểm tra riêng

- Hồ sơ ảnh: `MISSING` (chưa đủ ảnh), `PENDING` (chờ kiểm tra), `APPROVED` (đạt kiểm tra hồ sơ), `NEEDS_CHANGES` (yêu cầu bổ sung). Staff phải ghi nhận xét, người và thời điểm kiểm tra. Đạt hồ sơ không chứng minh giấy tờ thật.
- Đối chiếu bản gốc: tiếp tục dùng trạng thái GPLX hiện có và checkbox thực tế khi giao xe. Duyệt ảnh không tự chuyển GPLX sang `VERIFIED`.

Staff tại chi nhánh nhận xem và kiểm tra ảnh qua đơn `PENDING` còn hiệu lực hoặc `CONFIRMED`. Quyền đọc đơn của chi nhánh trả/Admin không tự cấp quyền xem ảnh. Khách chỉ xem ảnh của mình. Hồ sơ ảnh phải `APPROVED` trước xác nhận và bàn giao; tương thích xe/hạn bằng vẫn được backend kiểm tra riêng.

Sửa thông tin hoặc thay ảnh đặt lại kết quả kiểm tra hồ sơ và đối chiếu bản gốc. Thay ảnh cần gửi đủ hai mặt với version hiện tại; sau khi giao dịch lưu thành công, hai tệp cũ được xóa. Nếu giao dịch thất bại, metadata cũ được giữ và tệp mới được dọn. Lỗi dọn tệp được báo qua log vận hành, cần xử lý riêng; không tự xóa backup hoặc dữ liệu khác.

## API

| Phương thức | Đường dẫn dưới `/api/v1` | Quyền / dữ liệu |
|---|---|---|
| PUT | `/me/license/photos` | USER; multipart `front`, `back`, `version`; cần lưu thông tin GPLX trước |
| GET | `/me/license/photos/{front\|back}` | USER sở hữu hồ sơ; PNG, `Cache-Control: no-store, private` |
| GET | `/bookings/{id}/license-photos/{front\|back}` | Staff chi nhánh nhận, đơn còn trong giai đoạn kiểm tra |
| POST | `/bookings/{id}/license-photo-review` | Staff như trên; JSON `version`, `approve`, `reason` |

DTO GPLX bổ sung `photos` gồm cờ có ảnh hai mặt, trạng thái, nhận xét, người/thời điểm kiểm tra; không trả tên tệp hoặc đường dẫn lưu trữ. API cũ lưu thông tin GPLX không cho client tự duyệt hồ sơ.

## Lưu trữ và nâng cấp

Migration V5 chỉ thêm cột và một FK trên `driver_licenses`, không sửa migration đã áp dụng hoặc thêm bảng. Hồ sơ cũ giữ thông tin/đối chiếu đã có, nhận trạng thái ảnh `MISSING`; không tự tạo ảnh hay đánh dấu đạt. Đơn đã thuê/trả vẫn tiếp tục nhận trả/hoàn tất; đơn chờ xác nhận/giao phải bổ sung ảnh theo quy tắc mới.

`LICENSE_PHOTO_DIRECTORY` trỏ đến thư mục riêng tư ngoài webroot và Git, mặc định `../.runtime/private-license-photos` khi chạy tại `backend/`. Thư mục này phải được giới hạn quyền truy cập ở hệ điều hành và sao lưu cùng metadata DB. Không cấu hình static-resource/public URL cho thư mục. Không lưu ảnh thật trong fixture/test/evidence. Sao lưu DB và ảnh trước nâng cấp môi trường dùng chung; giữ Flyway validation và Hibernate validate.

Ảnh được giữ đến khi khách thay ảnh, không có tác vụ xóa theo hạn. Việc xóa tài khoản, backup và chính sách lưu trữ sau khi dừng hệ thống nằm ngoài tính năng này.
