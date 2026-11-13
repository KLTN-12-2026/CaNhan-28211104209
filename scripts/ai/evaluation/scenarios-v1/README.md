# Bộ tình huống xe và hành trình tổng hợp

Định dạng theo [hợp đồng đánh giá](../README.md). Bộ này có 30 recommendation dev và 30 itinerary dev. Toàn bộ nhãn là **DRAFT**, chưa được người khác tác giả rà. Không dùng số lượng mẫu hoặc validator exit0 làm chứng nhận chất lượng AI. Đây là dữ liệu test tổng hợp, không phải catalog/giá/giờ mở cửa thật và không được import vào dữ liệu người dùng.

## Các file

- [recommendation.jsonl](recommendation.jsonl): 20 MATCH, 5 NO_MATCH, 5 INVALID_INPUT.
- [itinerary.jsonl](itinerary.jsonl): 10 FEASIBLE, 5 INFEASIBLE, 5 NEEDS_DATA, 5 PROVIDER_ADVERSE, 5 INVALID_OR_ACCESS.
- [facts.json](facts.json): catalog tổng hợp, tình trạng xe/booking, metadata, ma trận tuyến có hướng, bảng tính tiền/loại xe và bằng chứng lịch.
- [manifest.json](manifest.json): version, hashes của hai JSONL và facts, 60 profile độc lập theo caseId.
- [validator](../validate_scenarios.py), [tests](../test_validate_scenarios.py): chỉ đọc dữ liệu, kiểm schema/integrity và oracle hữu hạn bằng Python stdlib; không gọi HTTP, Qwen, OSRM hoặc DB, không chấm response API.

Từ root repository, dùng Python 3.12:

```powershell
python -X utf8 scripts/ai/evaluation/validate_scenarios.py
python -X utf8 scripts/ai/evaluation/test_validate_scenarios.py -v
python -X utf8 scripts/ai/evaluation/validate_parser_dataset.py
python -X utf8 scripts/ai/evaluation/test_validate_parser_dataset.py -v
```

Validator dùng helper JSON/path/hash của parser; hai suite test kiểm riêng dependency này. Exit0 nghĩa integrity/oracle synthetic nhất quán; exit2 kèm DATASET_INVALID nghĩa từ chối dataset. Các test mutation chỉ sửa bản copy trong thư mục tạm. Không gọi validator này là runner chấm API; runner đó thuộc mốc tiếp theo.

## Fixture và schema facts

`facts.json` có đúng schemaVersion, synthetic, branches, routes, vehicles, scenarios. `synthetic=true`; branch/vehicle ID là logical ID. `scenarios[fixtureRef]` là trạng thái **riêng** của ca đó, không phải delta cộng dồn từ ca trước. Các profile cùng pin facts hash, UTC clock `2027-02-01T00:00:00Z`, timezone `Asia/Ho_Chi_Minh`, không bindings. Ngày ca chính `2027-02-10` là thứ Tư. Không đổi đồng hồ hệ điều hành.

Khi xây runner, phải inject Clock vào backend fixture hoặc đóng băng một run manifest dịch ngày nhất quán theo hợp đồng đánh giá. Metadata, exceptions, booking, ngày nguồn và ngày request đều phải đi cùng reference clock; không chỉ thay ngày trong payload. Mỗi ca chỉ expose đúng catalog/booking được chỉ định. Logical ID→ID fixture phải được ghi trong run manifest và dùng cho cả input, gold, route stubs. Chưa có loader/seed hoặc fixture adapter trong bộ này; validator không chứng minh API hiện tại đã nhận được các facts này.

Với recommendation, `vehicleIds` chọn catalog chính xác, `bookings` chứa dữ liệu lịch tổng hợp; không có booking ẩn. Fixture này chỉ sử dụng CONFIRMED và PENDING, không chứng minh toàn bộ state machine. Mỗi booking mang ngày theo chính sách calendar; giữ hết ngày trả, số ngày tính phí là returnDate−pickupDate. `audit[vehicleId]` có rentalDays, rentalTotal, oneWayFee, total, excludedBy. Các lý do loại gồm INACTIVE, SEATS, BUDGET, OVERLAP, BRANCH_CHAIN và có thể đồng thời nhiều lý do. Xe free giá0 chỉ có trong rec-20; các ca khác không được seed xe đó. Tuyến dn→ha phí150000 khác ha→dn phí100000; cùng chi nhánh phí0.

Với itinerary, `locations` là catalog riêng có ACTIVE/INACTIVE, tọa độ tổng hợp và metadata theo [contract tham quan](../../../../docs/modules/location-visit-metadata.md). Evidence URL chỉ dùng miền `example.invalid`, không truy cập website. `legs` ghi đầy đủ mọi cặp có hướng trong catalog, không gồm self edge. VERIFIED có durationSeconds/distanceMeters; NO_ROUTE không có số liệu. Tuyến nhiều điểm được fixture adapter ghép từ từng chặng, không tự suy tuyến ngược. Đây là synthetic route series, **không chứng minh OSRM/graph thực**.

`provider` có qwen, osrm, cause, readback. `proposedOrder` là thứ tự model giả lập có kiểm soát trong series synthetic, không phải gold và không dùng làm ví dụ prompt. Với series model thật, không ép model trả proposedOrder; chấm lịch bất kỳ đạt constraints. Các profile adverse cần tạo đúng trạng thái lỗi được ghi, xác nhận readback và giải phóng tài nguyên do fixture sở hữu. Không tự dừng provider người dùng để tạo lỗi.

`proof` khai báo candidateIds, minPoints2/maxPoints4, startFixed, witnessSchedules, rejectedOrders, missingFacts, scope. `oracle.evidenceRef="facts.json#<fixtureRef>"` tham chiếu proof trong scenarios. Manifest pin toàn bộ facts gồm proof. Witness có order và items (locationId, arrival, departure, waitMinutes, travelMinutes, distanceMeters); travel/distance thuộc chặng đến điểm kế tiếp, điểm cuối null. Arrival là trước thời gian chờ; departure gồm thời gian chờ và duration của điểm. Totals có thể đối chiếu bằng tổng các facts này, không gồm tiếp cận điểm đầu hoặc quay về.

## Cách lập và rà gold

Eligible được xác lập từ giá, ghế, trạng thái, chi nhánh và lịch fixture. Acceptable là lựa chọn của tác giả theo nhu cầu trong rationale, **không dùng score hay thứ tự RecommendationService làm nhãn**. Hộp số/amenity là ưu tiên mềm; mọi xe hợp hard-filter vẫn thuộc eligible. requiredReasonCodes/forbiddenReasonCodes có entry cho từng xe eligible. Khi chấm API phải kiểm mọi kết quả, gồm facts/totals/reasons và mã lý do bịa ngoài các yêu cầu, không chỉ xe nằm top3. Acceptable vẫn cần reviewer quyết định độ phù hợp trước khi chứng nhận Hit@3.

Witness lịch được soạn theo phép tính tay. Validator độc lập tìm thời điểm bắt đầu sớm nhất trong các khoảng mở cho **thứ tự cố định**, làm tròn mỗi chặng lên phút và đối chiếu witness. Validator không import Java computeSchedule, search/rank hoặc nhận output baseline làm expected. Khi kiểm INFEASIBLE, duyệt mọi ordered subset dài2–4 bắt đầu ở startFixed trong candidateIds; rejectedOrders phải chứa toàn bộ thứ tự đó với đúng căn cứ. Không có kết luận bất khả thi ngoài tập nhỏ này. Oracle vẫn do cùng tác giả viết và kiểm, chưa là nghiệm thu độc lập.

Các lịch khác witness vẫn có thể đúng: runner API phải kiểm facts/ràng buộc của lịch đó, không bắt exact witness. Ca iti-09 cố ý có proposedOrder a→b thất bại nhưng witness a→c hợp lệ. Nhãn kỳ vọng mô tả sự tồn tại phương án trong tập hữu hạn; source hiện tại chỉ thử một thứ tự có thể bị ghi finding. Không đổi gold sang NO_FEASIBLE_PLAN để làm baseline đẹp.

| Ca | Nội dung cần rà |
|---|---|
| rec-01…08 | Số người2/4/5/7/9, ngân sách và thời lượng1/2/4 ngày, ưu tiên hộp số |
| rec-09…12 | Wifi/GPS/child và mã amenity không tồn tại; không bịa facts |
| rec-13…16 | Phí tuyến có hướng, tổng bằng ngân sách và thiếu một đồng |
| rec-17…20 | Booking trùng, PENDING hết hạn, chuỗi chi nhánh của booking tiếp theo, ngân sách/giá0 |
| rec-21…25 | Không xe vì ngân sách, ghế, nơi nhận, bảo trì, giữ hết ngày trả |
| rec-26…30 | Số người sai, ngân sách âm, thứ tự ngày, amenity lặp, field lạ |
| iti-01…04 | Hai điểm, chờ mở cửa, nghỉ trưa, ngoại lệ thay tuần |
| iti-05…08 | Khoảng hôm trước qua đêm,61giây→2phút, bất đối xứng, bốn điểm |
| iti-09…10 | Có phương án khác; loại INACTIVE và kết thúc đúng biên |
| iti-11…15 | Vượt một phút, đủ mọi thứ tự với3điểm, cửa sổ quá ngắn, NO_ROUTE có hướng, quá nửa đêm |
| iti-16…20 | Metadata/thời lượng thiếu, chỉ một ứng viên, UNKNOWN của điểm thứ hai |
| iti-21…25 | Qwen tắt/timeout/ID sai, OSRM unavailable, capacity429; setup và cause/readback riêng |
| iti-26…30 | Hai validation400, GUEST401, STAFF403, ADMIN403 |

Không gán UNKNOWN của **điểm đầu** thành NEEDS_DATA trong bộ này: source hiện hành gộp nó với không có khoảng mở thành NO_FEASIBLE_PLAN; cần quyết định contract riêng trước khi mở rộng nhãn. Các ca NEEDS_DATA dùng phạm vi rõ hiện tại. Không lấy ca provider failure làm bằng chứng exhaustive infeasible.

Reviewer khác tác giả cần kiểm toàn bộ60nhãn, audit từng xe và phép tính witness/exhaustive, xác nhận lựa chọn acceptable theo rationale; sửa version/hash nếu thay facts/gold. Chỉ đặt REVIEWED khi thực sự có reviewer và ghi reviewer khác author trên mọi nhãn. DRAFT có thể dùng exploratory dev nhưng không chứng nhận gate chất lượng. Bộ parser heldout không được dùng cho tuning hoặc chạy trong mốc này.

Khi cần DB/OSRM thật, tuân thủ [fixture policy](../../../../skills/kltn-docker-fixtures/SKILL.md), pool được xác minh và schema riêng. Không trỏ ghi vào33385/3306 hoặc import facts tổng hợp vào catalog chính. Gate quyền/mutation/latency/model thật phải có bằng chứng integration riêng.
