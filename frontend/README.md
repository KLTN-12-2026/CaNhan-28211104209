# KDrive frontend

Frontend React/Vite có hai chế độ dữ liệu: demo để duyệt UI và API để dùng các endpoint backend đã được triển khai. Layout Guest/User/Staff/Admin, route guard và giao diện Urban với nền be ấm và điểm nhấn cam phục vụ các trang người dùng; các màn hình vận hành dùng bố cục theo vai trò.

Các màn hình API chỉ dùng dữ liệu do backend trả về; backend vẫn là nguồn có thẩm quyền cho quyền truy cập, trạng thái, khả dụng và giá.

## Chạy local

Môi trường kiểm tra bản công bố: Node.js 24.11.0, npm 11.6.1, JDK 21 và MySQL 8. Từ thư mục repository, vào `frontend`, chạy `npm ci`, rồi sao chép `.env.example` thành `.env.local` để chọn API mode. Nếu không có cấu hình, source vẫn mặc định demo. Backend phải được khởi động riêng theo [hướng dẫn database](../database/README.md). Không cần cài Maven riêng khi dùng Maven Wrapper.


Trong thư mục `frontend`, chạy `npm ci` nếu chưa có dependency, sau đó `npm run dev -- --host 127.0.0.1`. Mở địa chỉ Vite thông báo (mặc định http://127.0.0.1:5173). `npm run build` kiểm tra bản build; `npm test` chạy các test frontend bằng Node test runner; `npm run lint` chạy ESLint với ngưỡng 0 warning.

PowerShell:

```powershell
cd frontend
$env:VITE_DATA_MODE = "demo"
npm run dev -- --host 127.0.0.1
```

Git Bash (API mode): open two terminals at the repository root. The scripts locate the repository from their own path, so they can also be called from another working directory. For a clean checkout, install dependencies once from the `frontend` directory with `npm ci`; the lockfile pins ESLint 9, which satisfies `eslint-plugin-react@7.37.5`'s declared ESLint peer range while retaining the existing flat config and lint rules. Do not use `--force` or `--legacy-peer-deps`.

Use JDK 21 consistently for the preflight, Maven Wrapper, and running JAR. In Git Bash, set it in the same terminal before starting the backend:

```bash
export JAVA_HOME='/path/to/your/jdk-21'
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

The last command must report Java 21. Maven Wrapper uses `JAVA_HOME`; the backend script resolves and invokes that same JDK for both version check and JAR startup.

Terminal 1 — backend (requires Java 21, Maven Wrapper, and a reachable MySQL):

```bash
bash scripts/run-backend-gitbash.sh
```

By default, unset backend variables are read from `backend/.env.local`; variables already exported in the terminal take precedence. To select a fixture or other environment file explicitly, pass its path (relative paths are from repository root):

```bash
bash scripts/run-backend-gitbash.sh --env-file .runtime/my-isolated-db/backend.env
```

The selected file uses simple `KEY=value` lines (optional `export`, single/double quotes, UTF-8 BOM, and CRLF are supported); it is parsed as data and never evaluated. Only recognized backend keys are loaded. Do not paste secrets into terminal commands or logs. For database verification, configure the fixture file for a separate MySQL instance/schema, never the shared port 33385.

Terminal 2 — frontend (requires `frontend/node_modules`; install with `cd frontend && npm ci` once if dependencies are missing):

```bash
bash scripts/run-frontend-gitbash.sh
```

The backend script loads the ignored `backend/.env.local`, runs `./mvnw package`, and starts the packaged JAR only if the build succeeds. The frontend script builds with `VITE_DATA_MODE=api` and starts Vite at `http://127.0.0.1:5173`; `/api/v1` stays a relative URL and Vite proxies `/api` to the backend at port 8080. It sets `MSYS2_ENV_CONV_EXCL` for `VITE_API_BASE_URL` so Git Bash does not rewrite `/api/v1` into a Windows drive path. Check the proxied backend health at `http://127.0.0.1:5173/api/v1/health` (or `curl -fsS http://127.0.0.1:5173/api/v1/health`). Keep both terminals open and press Ctrl+C in each to stop only the process started there. The scripts do not start, stop, or reset Docker/MySQL. If using the existing local MySQL container, confirm Docker Desktop and that container are already running; do not point the application at another project's database. Use the configured backend port and Vite's configured proxy when testing locally; do not change production CORS settings to work around Git Bash path conversion.

Giữ terminal mở; Ctrl+C để dừng. Khi dùng script Git Bash, cổng bận hoặc backend health chưa sẵn sàng sẽ bị từ chối trước build; khởi động backend trước hoặc chọn `FRONTEND_PORT` khác. Script dùng `VITE_PROXY_TARGET` (mặc định `http://127.0.0.1:8080`); khi đổi cổng phải đồng bộ proxy và CORS backend. Khi chạy `npm run dev` trực tiếp, dùng URL Vite thực tế thông báo. Khi đổi biến môi trường, dừng và chạy lại server. Build thành công không bảo đảm không có lỗi lúc render: cần mở các route và thử luồng demo bên dưới.

## Hai chế độ dữ liệu

- `VITE_DATA_MODE=demo` là mặc định của bản UI này. Thanh **DEMO UI** luôn hiển thị; chọn Guest/User/Staff/Admin để kiểm tra từng khu vực. Không nhập tài khoản thật hoặc mật khẩu trong demo. Không gọi API backend, Gemini hoặc Map.
- Dữ liệu demo lưu trong `sessionStorage` với khóa `kdrive.demo.data.v1`, vai trò lưu riêng tại `kdrive.demo.role`. Reload cùng tab giữ dữ liệu; nút **Khôi phục mẫu** đặt lại tập dữ liệu demo. Không đọc/ghi token thật để mô phỏng đăng nhập. Các vai trò mẫu độc lập với tài khoản thêm/sửa trong màn hình Admin.
- `VITE_DATA_MODE=api` tắt toàn bộ nguồn demo và bộ chọn vai trò. Đăng nhập/đăng ký, duyệt xe, tạo báo giá/đơn đa chi nhánh, danh sách/chi tiết đơn, hợp đồng/hóa đơn, biên bản giao nhận, hồ sơ/GPLX, sự cố, quản lý tài khoản/xe/chi nhánh/tuyến, kiểm duyệt đánh giá và tình trạng đội xe gọi API. Không tự thay lỗi API bằng dữ liệu giả. Báo cáo vận hành Admin và quản lý danh mục địa điểm cũng sử dụng API. Trợ lý khách hàng gọi API gợi ý xe, phân tích nhu cầu và xem trước tuyến khi backend được cấu hình tương ứng; xem [tài liệu trợ lý](../docs/modules/ai-assistant.md). Các trang độc lập còn thông báo chờ kết nối không đại diện cho trạng thái tích hợp của trợ lý. USER có thể lập preview, lưu và xem lại hành trình một ngày qua API; xem [contract hành trình](../docs/modules/ai-itinerary.md). Sau khi đổi biến môi trường cần khởi động lại Vite.

### Luồng API

1. Tạo tài khoản User hoặc đăng nhập bằng API; token ngắn hạn được lưu để gửi các yêu cầu xác thực.
2. Duyệt xe và xem trạng thái/giá từ API. Card và gallery đọc ảnh, thứ tự và thông tin ghi công từ API; ảnh thiếu hoặc không tải được dùng icon dự phòng.
3. Chọn thời gian, chi nhánh nhận/trả và kiểm tra báo giá. Backend xác thực tuyến, lịch xe, GPLX và giá; gửi đơn kèm tổng báo giá để backend kiểm tra lại.
4. Theo dõi đơn, gửi yêu cầu hủy theo chính sách, xem snapshot hợp đồng/hóa đơn; Staff xử lý trạng thái và biên bản trong phạm vi chi nhánh được gán.
5. User khai báo hồ sơ/GPLX, đánh giá booking hoàn tất và ghi nhận sự cố; Admin quản lý tài khoản, xe, chi nhánh, tuyến và kiểm duyệt đánh giá.

API mode phụ thuộc backend đang chạy và cấu hình database/seed phù hợp. Trên PowerShell, có thể đặt `$env:VITE_API_BASE_URL = "/api/v1"` trước khi khởi động Vite để dùng proxy cùng origin trong `vite.config.js`; trên Git Bash dùng script ở trên để tránh MSYS chuyển đổi giá trị này. Nếu gọi URL backend trực tiếp, backend phải cho phép CORS từ origin frontend. Khi API lỗi, giao diện hiển thị lỗi và thao tác thử lại; không dùng dữ liệu mẫu thay thế. Build/test frontend không thay thế kiểm thử API, phân quyền backend, MySQL hoặc concurrency.
- `.env.example` chỉ là mẫu cấu hình. Vite dùng `.env.local` khi bạn tạo file đó; tuyệt đối không đặt Gemini/Map secret trong biến `VITE_*`.

### Kiểm tra frontend

```powershell
cd frontend
npm run lint
npm test
$env:VITE_DATA_MODE = "demo"; npm run build
$env:VITE_DATA_MODE = "api"; npm run build
Remove-Item Env:VITE_DATA_MODE
```

Test bao gồm mô hình demo và regression cho snapshot quote, thứ tự response/session, phân công chi nhánh, cùng việc không hiển thị lịch sử incident cũ dưới incident mới đang tải. Đây không phải toàn bộ test browser/API integration. Trong API mode, `GET /me/branches` là nguồn phân công Staff; giao nhận dùng chi nhánh nhận/trả tương ứng. Admin không nhận quyền giao nhận. GPLX hiện được hiển thị riêng trong chi tiết đơn Staff; khả năng tương thích và xác minh có thẩm quyền vẫn do API báo giá/giao xe kiểm tra, frontend không tự suy hạng bằng từ tên hạng GPLX. Quote gắn với đúng xe, tuyến và thời gian; đổi input hoặc nhận response cũ sẽ vô hiệu quote. `useApiData` chỉ trả data/error của request key hiện tại, và reload chuyển UI về loading cho key mới. Audit sự cố phân biệt thời điểm xảy ra với thời điểm báo cáo; khung nhập xảy ra kết thúc ở thời điểm biên bản trả thực tế, còn báo cáo muộn không đổi thời điểm xảy ra. Lịch sử sự cố chỉ hiển thị events thuộc incident đang chọn; khi tải có loading, khi lỗi hiển thị lỗi thay vì dữ liệu cũ.

Kiểm thử luồng API bằng trình duyệt cần chạy backend trên MySQL thật cùng seed/tài khoản/xe/chi nhánh đã chuẩn bị; test demo hay API mode khi backend không sẵn sàng không thay thế kiểm thử tích hợp thực tế. Ca đặt xe cần gồm đúng/sai chi nhánh Staff, Admin không được giao/nhận, User đánh giá đơn hoàn tất và Admin kiểm duyệt.

## Luồng demo

1. Guest mở Duyệt xe, tìm/lọc và xem chi tiết. Chọn đặt xe sẽ sang đăng nhập demo, chọn User rồi quay về xe/thời gian đang chọn.
2. Nhập khoảng thuê, kiểm tra lịch/giá mẫu, xác nhận rồi gửi đơn. Đơn dùng block 24 giờ làm tròn lên và hạn chờ nhỏ hơn giữa 2 giờ và giờ nhận. Cọc/phụ phí không được suy diễn thành giá trị 0 hay cộng vào tiền thuê.
3. Đổi Staff, mở mã đơn vừa tạo, xác nhận, xem hợp đồng mẫu, lập biên bản giao (có kiểm tra GPLX), nhận trả, rồi hoàn tất và xem hóa đơn. Demo cho thử các bước liên tiếp để review UI; điều kiện giao sớm, trả muộn, gia hạn, no-show chưa được triển khai.
4. Đổi User, mở đơn hoàn tất, gửi một đánh giá. Đổi Admin để thử ẩn/hiện; xem lại trang xe để kiểm tra đánh giá công khai.
5. Recommendation lọc/chấm điểm minh họa từ tiêu chí có cấu trúc; không xử lý free text bằng AI. Thử lỗi giải thích: giữ danh sách; lỗi hiểu yêu cầu: yêu cầu thử lại.
6. Itinerary dùng tập địa điểm Đà Nẵng/Hội An và số liệu tuyến gắn nhãn **demo**. Số người/ngân sách lưu làm tiêu chí, chưa tối ưu; các ngày lặp lại điểm để minh họa layout. Đây không phải kết quả đã kiểm chứng bằng Map. Thử lỗi thiếu tuyến/AI: không có kết quả để lưu. Chỉ User lưu bản mẫu và xem lại trong hành trình của mình.

## Routing

| Khu vực        | URL                                                                                                                                                        |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Công khai      | `/`, `/vehicles`, `/vehicles/:vehicleId`, `/login`, `/register`                                                                                            |
| AI preview     | `/ai/recommend` — mô phỏng; `/ai/itinerary` — API chỉ USER, demo giữ mô phỏng                                                                    |
| User           | `/user/bookings`, `/user/bookings/new?vehicleId=…`, `/user/bookings/:bookingId`, `/user/profile`                                                           |
| User itinerary | `/user/itineraries`, `/user/itineraries/:itineraryId`                                                                                                      |
| Staff          | `/staff/queue`, `/staff/bookings/:bookingId`, `/staff/handover`, `/staff/handover/:bookingId`, `/staff/return`, `/staff/return/:bookingId`, `/staff/fleet` |
| Admin          | `/admin`, `/admin/accounts`, `/admin/vehicles`, `/admin/catalog`, `/admin/locations`, `/admin/reviews`, `/admin/bookings`, `/admin/bookings/:bookingId`    |
| Hồ sơ          | `/{user,staff,admin}/bookings/:bookingId/contract` hoặc `/invoice`                                                                                         |
| Lỗi            | `/unauthorized`, route không tồn tại và tài nguyên không tìm thấy                                                                                          |

Guard tách role; Admin không vào Staff/User. Route guard và mô phỏng quyền chỉ phục vụ UI, không là cơ chế bảo mật backend. Với triển khai SPA, host phải phục vụ `index.html` cho các URL route khi reload.

## Cấu trúc và giới hạn

- `src/demo`: seed và mô phỏng chuyển trạng thái/khả dụng/giá, chỉ phục vụ review UI. Không copy làm nguồn nghiệp vụ có thẩm quyền.
- `src/context/DemoContext.jsx`: dữ liệu phiên demo; `AuthContext.jsx`: tách phiên demo và phiên API.
- `src/components/common/PageKit.jsx`: bố cục, field, thông báo, loading/empty/error gate; toolbar chọn tình huống demo. Đổi tình huống sẽ đặt lại biểu mẫu chưa lưu, dữ liệu đã lưu vẫn giữ.
- Màn hình demo theo nhóm `VehicleScreens`, `BookingScreens`, `AiScreens`, `ManagementScreens`; `src/pages/api` chứa các màn hình gọi API. Router chọn đúng bộ màn hình theo `VITE_DATA_MODE`.
- Có 8 ảnh minh họa cho 4 mẫu xe demo (ngoại thất/nội thất), gallery và nguồn/giấy phép theo ảnh đang chọn; API quản lý URL ảnh, thứ tự và ghi công; bộ tài nguyên gồm 17 ảnh minh họa có hồ sơ giấy phép tại `/media/vehicles/licenses.html`. Database mới cần gắn ảnh qua giao diện Admin; ảnh thiếu dùng icon dự phòng. Ảnh minh họa demo có thể khác đời/phiên bản xe cho thuê, không phải ảnh kiểm định xe thực tế. Nguồn nằm trong `src/assets/images/vehicles/attributions.json`; `public/images/vehicles` phục vụ URL tĩnh của seed/hero. API profile hỗ trợ đổi mật khẩu thật và khai báo GPLX, khả năng ảnh GPLX và ảnh xe phụ thuộc cấu hình backend và màn hình tương ứng; xem tài liệu module trước khi bật. Map tương tác và thao tác phát hành lại chưa được triển khai; API gợi ý xe và hành trình một ngày được mô tả trong tài liệu module AI.
- Chưa bổ sung xóa cứng dữ liệu giao dịch, thanh toán online, trình chỉnh sửa itinerary hoặc quyền Admin giao nhận.
- Quyết định nghiệp vụ tại `docs/architecture/decisions.md`, schema tại `docs/database/database-design.md`; không dùng demo model làm baseline backend.

## Kiểm tra thủ công sau khi thay đổi

- Guest vào route bảo vệ → login và giữ query; khi đổi tài khoản, đường dẫn chờ chỉ được giữ nếu thuộc role vừa đăng nhập, nếu không sẽ chuyển về trang chính role đó; sai role → unauthorized; đường dẫn sai → 404.
- Tạo đơn trùng lịch → thông báo lỗi; đơn hết hạn không xác nhận được; thao tác dùng version cũ bị từ chối trong demo.
- Hợp đồng xuất hiện sau xác nhận, hóa đơn sau hoàn tất; giá catalog sửa không làm đổi snapshot đơn/hồ sơ cũ.
- Modal giữ focus, Escape đóng và trả focus về nút mở. Giao diện ưu tiên web desktop 1366px và 1440px; không phát triển ứng dụng hoặc bộ thiết kế mobile riêng.
- Loading/empty/error qua toolbar, trạng thái rỗng do bộ lọc, lỗi AI, tài nguyên không tồn tại; kiểm tra console không có lỗi.
- Kết quả demo không chứng minh transaction chống đặt trùng, phân quyền server hoặc AI/Map đã hoạt động. Các luồng nghiệp vụ cần kiểm thử tích hợp riêng với backend/MySQL; AI/Map cần triển khai dịch vụ tương ứng trước khi kiểm thử tích hợp.


## Báo cáo vận hành Admin

Trong chế độ API, Admin mở `/admin` để xem đơn tạo trong khoảng, đơn hoàn tất, số hóa đơn và tổng giá trị hóa đơn; bảng phân rã trạng thái hiện tại của các đơn tạo trong khoảng. Chọn Từ ngày/Đến ngày theo giờ Việt Nam (bao gồm hai ngày, tối đa 366 ngày), mặc định tháng hiện tại; dùng Xem báo cáo/Làm mới để tải dữ liệu. Khoảng rỗng hiển thị 0, lỗi tải có nút Thử tải lại. Tổng hóa đơn là giá trị phát hành, không xác nhận tiền đã thu. USER/STAFF/Guest không có quyền báo cáo.

Backend: GET `/api/v1/admin/reports/operations?from=YYYY-MM-DD&to=YYYY-MM-DD`. Khởi động lại backend/frontend bằng script dev hiện có sau khi cập nhật source; không cần migration/bảng/phụ thuộc mới cho báo cáo. DemoDashboard vẫn là dữ liệu demo riêng.

## Quy tắc giao diện khách hàng

Xem [giao diện khách hàng và tích hợp API](../docs/ui/customer-experience.md) cho đăng nhập/hồ sơ, thanh hãng, trạng thái request và token độ tương phản. Các chức năng [so sánh](../docs/ui/vehicle-comparison.md), [yêu thích](../docs/ui/vehicle-favorites.md), [hướng dẫn thuê](../docs/ui/rental-guide.md) có contract riêng.

### Giao diện quản trị

Admin dùng shell cao theo viewport với header luôn hiển thị, sidebar và nội dung cuộn độc lập. Token `--admin-*` trong `src/index.css` tách khỏi giao diện khách hàng. Tổng quan hiển thị bốn số liệu hiện có; tổng giá trị hóa đơn không xác nhận tiền đã thanh toán. Tra cứu đơn dùng bảng, giữ bộ lọc và phân trang. Ô ngày báo cáo hiển thị dd/mm/yyyy và gửi ngày ISO qua API. Các thao tác nguy hiểm yêu cầu xác nhận trước khi gửi; kết quả được thông báo bằng toast có nút đóng.
