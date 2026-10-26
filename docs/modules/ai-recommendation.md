# Hợp đồng API gợi ý xe AI1

API gợi ý xe xếp hạng bằng quy tắc, không gọi mô hình ngôn ngữ hoặc dịch vụ bản đồ trong bước xếp hạng. Phân tích văn bản và xem trước tuyến là các API riêng, được kết nối qua [trợ lý khách hàng](ai-assistant.md). Chỉ USER được dùng; Guest nhận401, Staff/Admin nhận403. Biến `AI_RECOMMENDATION_ENABLED` mặc định false; cần chủ động bật trên môi trường đã chuẩn bị. Không thêm migration hay lưu lịch sử gợi ý.

POST `/api/v1/ai/recommendations`:
```json
{"requestId":"example-1","criteria":{"pickupDate":"2026-10-20","returnDate":"2026-10-24","pickupBranchId":"dn","returnBranchId":"dn","passengers":4,"budgetVndTotal":3000000,"preferences":{"transmission":"AUTO","amenityIds":[]}}}
```

`requestId` chỉ đối chiếu yêu cầu/phản hồi, dài1–64 ký tự ASCII chữ/số/gạch ngang/gạch dưới; không có cơ chế lưu idempotency. Ngày thuê theo Việt Nam, ngày trả sau ngày nhận, tối đa90 ngày tính tiền. Số người2–9 gồm tài xế. Tổng ngân sách là số nguyên0–9007199254740991 VND, gồm phí tuyến. Thiếu tiêu chí bắt buộc trả NEEDS_CLARIFICATION và chưa tìm lịch; giá trị sai trả400. Trường không thuộc hợp đồng và số thập phân cho trường số nguyên bị từ chối. Lỗi chi nhánh/tuyến hoặc dependency được trả về, không thay bằng dữ liệu demo.

Dùng Input/Result có kiểu của VehicleSearchService: xe ACTIVE, đủ chỗ, tổng tiền trong ngân sách, giữ lịch hết ngày trả, đúng chuỗi chi nhánh và không trùng lịch. Kết quả không khóa hoặc giữ xe; quote/create kiểm tra lại, gồm GPLX theo quy tắc đặt xe. `factsAsOf` là thời điểm kiểm tra tìm kiếm, không phải snapshot khóa database hoặc bảo đảm còn xe trong tương lai.

Phản hồi gồm requestId/status/rankingVersion/criteria/unresolvedFields/results/factsAsOf/warnings. Trạng thái OK, NO_MATCH hoặc NEEDS_CLARIFICATION. Chưa bật tính năng trả503. Mỗi kết quả có vehicleId,vehicleName,seats,transmission,rentalDays,rentalTotal,oneWayFee,total,score,reasonCodes. Trả tối đa20 xe; NO_MATCH không nới điều kiện bắt buộc.

Phiên bản `rules-2026-10-18-v1`: điểm = round(40×budgetFit +25×seatFit +35×preferenceFit), từ0–100. budgetFit=1-total/budget; nếu ngân sách và tổng tiền đều0 thì bằng1. seatFit=passengers/seats. preferenceFit=số đặc tính khớp/số đặc tính yêu cầu; hộp số tính một đặc tính, mỗi tiện nghi khác nhau tính một đặc tính. Không chọn sở thích thì mọi xe cùng nhận1 cho thành phần đó. Xe có tổng giá thấp hơn hoặc ít dư chỗ hơn nhận điểm tương ứng cao hơn. Sắp điểm giảm dần rồi vehicleId tăng dần. Điểm không phải xác suất.

Sở thích nhận hộp số AUTO/MANUAL và tối đa20 mã tiện nghi khác nhau, mỗi mã dài1–36. Mã tiện nghi chưa tồn tại được xem là ưu tiên không khớp, không bịa ra tiện nghi có trên xe. Reason codes chỉ dựa trên dữ liệu trả về; không phải lời giải thích từ LLM. Không trả dữ liệu khách hàng, GPLX hay hồ sơ đơn thuê.

Kết quả có thể hết hiệu lực ngay sau phản hồi. Phân tích ngôn ngữ tự nhiên và xem trước tuyến dùng dịch vụ riêng; API này không sinh lời giải thích từ mô hình ngôn ngữ hoặc tạo hành trình. Trợ lý trong chế độ API gọi endpoint này; trang AI demo độc lập không phải bằng chứng tích hợp.
