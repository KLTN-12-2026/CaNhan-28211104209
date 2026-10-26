# Ranh giới module và quyền truy cập

## Backend

Package gốc: `com.carrental`. Một module là một phần mã nghiệp vụ, không tương ứng bắt buộc với một bảng hoặc một role.


| Module | Sở hữu/trách nhiệm | Phụ thuộc được phép ở mức service/adapter |
|---|---|---|
| `auth` | Đăng nhập, JWT ngắn hạn, thu hồi token_version, không refresh | `user`; hạ tầng security |
| `user` | Tài khoản, hồ sơ, role USER/STAFF/ADMIN, GPLX/tương thích và kết quả xác minh | Không phụ thuộc booking/AI |
| `vehicle` | Xe, hãng, loại, tiện nghi, ảnh, giá hiện hành, trạng thái khai thác | `branch` để kiểm tra chi nhánh |
| `branch` | Chi nhánh, tuyến/phí, phân công Staff | `user` service kiểm tra Staff; không đọc users trực tiếp, không gộp catalog tham quan |
| `location` | Catalog tham quan, tọa độ, phân loại, tham chiếu nhà cung cấp nếu có | Map adapter khi cần xác thực tọa độ |
| `booking` | Đơn, khoảng thuê, availability, giá snapshot, hủy và state transition | `user`, `vehicle`, `branch` |
| `operation` | Biên bản, sự cố/vi phạm, điều phối giao nhận/phát hành và cọc | `booking`, `vehicle`, `user`, `contract`, `invoice`, `branch`, `payment` qua API nội bộ có kiểu |
| `payment` | Giao dịch, hoàn tiền, chữ ký VNPAY Sandbox và audit tiền | `booking` qua DTO/service, không phụ thuộc operation |
| `contract` | Hồ sơ hợp đồng và snapshot | `booking` để kiểm tra/trích xuất dữ liệu cần thiết |
| `invoice` | Hồ sơ hóa đơn và snapshot, không xác nhận tiền đã thu | `booking` để kiểm tra/trích xuất dữ liệu cần thiết |
| `review` | Đánh giá và trạng thái kiểm duyệt | `booking` kiểm tra chủ đơn, xe và điều kiện hoàn tất |
| `ai/recommendation` | Criteria, hard-filter, điểm và giải thích | `vehicle`, `booking`, Qwen adapter cục bộ |
| `ai/itinerary` | Kế hoạch cá nhân và các mục lịch trình | `location`, Qwen adapter cục bộ, Map adapter |

`security`, `config`, `exception` là hạ tầng chung. Kiểm tra chủ sở hữu tài nguyên vẫn nằm ở service nghiệp vụ. Không thêm repository/entity cho recommendation nếu chưa có yêu cầu lưu lịch sử.

Quy ước chống phụ thuộc vòng: `vehicle` không gọi `booking` để tính availability; `booking` kết hợp xe/lịch. `booking` không gọi ngược `operation`; operation điều phối qua service. Entry point cho các action và phát hành chứng từ (`/api/v1/bookings/{id}/actions`, `/contract`, `/invoice`) đã được chuyển hoàn toàn về `RentalWorkflowController` thuộc module `operation`, loại bỏ hoàn toàn phụ thuộc từ `booking` sang `operation` trong khi bảo toàn 100% URL REST và quyền hạn. Đồng thời `ReviewService` sử dụng API có kiểu `bookingService.bookingIdsForVehicle`, và `BranchService` xác thực nhân viên qua `AccountService.requireStaff(userId)`.

## Staff/Admin là quyền, không là module dữ liệu

Không có module backend `staff` hoặc `admin`. Controller cho thao tác quản trị/nhân viên đặt trong module sở hữu nghiệp vụ: quản lý Staff ở `user`, xác nhận đơn ở `booking`, biên bản ở `operation`, kiểm duyệt ở `review`. Role vẫn giữ nguyên; bỏ thư mục không làm mất chức năng.

| Nhóm hành động | Guest | User | Staff | Admin |
|---|---|---|---|---|
| Catalog công khai | Xem | Xem | Xem | Xem |
| Tài khoản/hồ sơ | Đăng ký/đăng nhập | Hồ sơ mình | Hồ sơ mình theo quyền | Quản lý User/Staff, không xem mật khẩu rõ |
| Đặt xe | Không | Cho mình | Không đặt thay mặc định | Không đặt thay mặc định |
| Đơn/hợp đồng/hóa đơn | Không | Của mình | Dữ liệu cần cho vận hành | Tra cứu toàn hệ thống |
| Hủy đơn | Không | Của mình theo chính sách | Hỗ trợ theo quyền | Can thiệp theo quyền |
| Xác nhận/giao/nhận | Không | Không | Nghiệp vụ chính | Ngoại lệ được quy định rõ |
| Trạng thái xe | Không | Không | Cập nhật vận hành | Quản lý khai thác/catalog |
| Catalog/chi nhánh/địa điểm | Không sửa | Không sửa | Không quản lý danh mục | Quản lý |
| Đánh giá | Xem công khai | Gửi khi đủ điều kiện | Không kiểm duyệt mặc định | Kiểm duyệt |
| AI | Preview còn mở | Dùng và lưu hành trình mình | Không phải nghiệp vụ chính | Kiểm tra theo quyền được cấp |

Quyền Admin không bỏ qua state machine: hiện được hủy PENDING/CONFIRMED có lý do và quản lý sự cố, không xác nhận/giao/nhận thay Staff. Staff xác nhận/giao đúng chi nhánh nhận, trả/hoàn tất đúng chi nhánh trả; quyền đọc đơn áp dụng cả hai chi nhánh. Biết URL không cấp quyền. Sự cố phải kiểm tra thêm phạm vi tác vụ khóa xe, không chỉ quyền đọc booking cũ.

## Frontend

- `pages/guest`: đăng nhập, đăng ký; `pages/shared`: trang công khai dùng chung như danh sách/chi tiết xe.
- `pages/user`: hồ sơ, đặt xe, đơn của mình, hành trình; `pages/staff`: hàng đợi, xử lý đơn, giao nhận; `pages/admin`: tài khoản và catalog/kiểm duyệt.
- `components`: khối giao diện tái sử dụng; `inspection` phục vụ giao nhận, không là dịch vụ nghiệp vụ riêng.
- `api`: HTTP client chung và hàm gọi API theo nghiệp vụ; không gọi Gemini/Map trực tiếp, không tính giá có thẩm quyền.
- `hooks`: tải dữ liệu và logic giao diện dùng lại; `context`: phiên đăng nhập/trạng thái dùng chung nhỏ, không chứa bản sao toàn bộ database.
- `routes`: cấu hình route và guard; `layouts`: bố cục từng khu vực; `utils`: tiện ích thuần. Các trang thể hiện rõ loading, empty, lỗi, thành công.

Hiện không dùng Redux; AuthContext quản lý phiên, DemoContext chỉ mô phỏng bằng sessionStorage và không cấp dữ liệu trong API mode. src/pages/api hiện là bước tích hợp tạm, chứa nhiều trang/hook/HTTP trực tiếp; cần phân rã theo quy tắc ở trên. Quyết định chọn lọc UI Redesign nằm tại decisions.md, không nhập mock store làm nguồn nghiệp vụ.


## Ranh giới nghiệp vụ và điều phối

1. **Giao thức khóa tài nguyên (Lock Protocol - L03):**
   - Khi thực hiện thay đổi trạng thái thuê hoặc kiểm tra lịch xe: Luôn khóa tài nguyên Vehicle trước thông qua VehicleRepository.lock(vehicleId), sau đó mới khóa Booking thông qua BookingRepository.lock(bookingId). Tránh tình trạng deadlock và bảo đảm snapshot lịch hiện hành (current-read) trên MySQL.
2. **Quyền hạn tác động xe theo chi nhánh hiện tại (L02):**
   - Việc chuyển trạng thái xe sang MAINTENANCE từ bất kỳ luồng nào (bao gồm báo cáo sự cố IncidentService.create) phải tuân thủ quyền hạn chi nhánh của xe tại thời điểm thực tế (
ehicle.currentBranchId), ngăn chặn Staff ở chi nhánh cũ tác động lên xe đã bàn giao hoặc di chuyển tới chi nhánh khác.
3. **Quản trị phiên bản tuyến chi nhánh (L06):**
   - API PUT /api/v1/admin/branch-routes bắt buộc truyền expectedVersion để đối soát với version của bản ghi tuyến trước khi áp dụng thay đổi phí hoặc trạng thái hoạt động.
4. **Nhất quán thời gian nghiệp vụ (L10):**
   - Toàn bộ các module phụ thuộc thời gian (tạo biên bản giao nhận, phát hành hợp đồng, hóa đơn, xác minh bằng lái, đánh giá và sự cố) đều nhận bean Clock được quản trị tập trung tại ClockConfig.


## Catalog xe

Module vehicle quản lý đọc/tạo/cập nhật hãng, loại xe và tiện nghi. Controller HTTP gọi VehicleCatalogService; service kiểm tra ADMIN, input và transaction; VehicleCatalogRepository giữ SQL với bảng whitelist và parameter binding. Không có entity mutable xuyên module.

- Admin: GET/POST `/api/v1/admin/{vehicle-brands|vehicle-categories|amenities}`; PUT cùng đường dẫn `/{id}`. Read DTO `{id,name,revision}`, create `{name}`, update `{name,expectedRevision}`. Tên trim, 1–100 ký tự; unique theo DB. Create201, read/update200; invalid400, missing404, duplicate/stale409, anonymous401, USER/STAFF403. Không có DELETE; method không hỗ trợ405.
- Revision là SHA256 của kind/ID/name hiện tại. PUT khóa hàng trong transaction rồi đối chiếu revision; hai cập nhật làm thay đổi tên từ cùng revision có một200, một409. Digest không là số phiên bản tăng dần/audit và không phát hiện ABA khi tên đổi rồi trở lại.
- Public GET `/api/v1/vehicles/catalog` giữ brands/categories `{id,name}` và thêm amenities `{id,name}`. Form xe dùng ID hiện tại. `/admin/catalog` là màn hình danh mục; `/admin/branches` giữ màn hình chi nhánh riêng trong API mode. Demo giữ màn hình demo.


## Liên kết tiện nghi xe

Public GET /api/v1/vehicles và /vehicles/{id} thêm amenities:[{id,name}], giữ trường xe cũ. List dùng bulk links và bulk names; không có links trả[]. Admin GET/PUT /api/v1/admin/vehicles/{id}/amenities, GET/PUT DTO {amenities,revision}; PUT input {amenityIds,expectedRevision}. ADMIN200,anonymous401,USER/STAFF403;unknownvehicle404;missing/null/badrevision hoặc IDs null/blank/duplicate/unknown400;stale409/no-write. [] xóa links riêng xe, không xóa master amenities. Hai state-changing updates cùng revision có một200/một409.

Service khóa Vehicle trong transaction, validate tất cả IDs trước mutation và so current revision. Revision SHA256 vehicleID + NUL + sorted IDs, không phụ thuộc name; label catalog đổi thì link/revision giữ nguyên. Same-ID-set kể cả đảo thứ tự là no-op200/0DML; digest không là audit/version tăng dần hoặc phát hiện ABA. Replace links atomic; vehicle fields/version và historical snapshots không đổi do thao tác tiện nghi. PUT thông tin xe cũ thiếu amenities không xóa links; không thêm migration/bảng. Controller/service/repository tách HTTP/business/SQL.

## Cọc sandbox

`payment` giữ giao dịch, yêu cầu hoàn, chữ ký VNPAY và nhật ký tiền. `operation` điều phối payment → booking → contract trong transaction nội bộ, gọi mạng ngoài transaction. booking không phụ thuộc payment service; công thức cọc thuần và snapshot được ghi lúc tạo. Không dùng entity mutable mới để điều phối cọc. Xem [contract cọc](../modules/booking-deposit.md).
