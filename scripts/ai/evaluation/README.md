# Hợp đồng đánh giá AI KDrive

Phiên bản định dạng: `kdrive-ai-eval-v1`.

Tài liệu quy định dữ liệu đầu vào, nhãn, cách tính điểm và kết quả của công cụ đánh giá. Đây không phải API nghiệp vụ mới hoặc chứng nhận chất lượng. [Bộ parser tổng hợp](parser-v1/README.md) và [bộ tình huống xe/hành trình](scenarios-v1/README.md) có dữ liệu DRAFT cùng validator offline; bộ tình huống kèm facts và oracle hữu hạn. [Runner chấm API](RUNNER.md) hỗ trợ capture từ fixture backend và HTTP parser trên catalog đã chuẩn bị; nhãn DRAFT chỉ dùng exploratory, không chứng nhận chất lượng. Ví dụ/đường dẫn đầu ra dưới đây là định dạng quy ước, không phải tài nguyên runtime có sẵn.

Contract nghiệp vụ giữ theo [parser/chatbox](../../../docs/modules/ai-assistant.md), [gợi ý xe](../../../docs/modules/ai-recommendation.md), [hành trình](../../../docs/modules/ai-itinerary.md) và [metadata tham quan](../../../docs/modules/location-visit-metadata.md). Khi source và contract mâu thuẫn, ghi finding trước khi chấm; không tự đổi nhãn để giống implementation.

## 1. Phạm vi và điểm chấm

| Suite | Điểm chấm chính | Không được suy ra |
|---|---|---|
| `parser` | POST `/api/v1/ai/criteria/parse`, sau validator và ánh xạ branch name→ID; status/draft/unresolvedFields và HTTP | JSON model đúng cú pháp không chứng minh API hiểu đúng |
| `recommendation` | POST `/api/v1/ai/recommendations`, top3 theo thứ tự server, hard-filter/total/reasonCodes đối chiếu fixture | Score không phải xác suất; thứ tự quy tắc không là nhãn người dùng |
| `itinerary` | POST `/api/v1/ai/itineraries/preview`, status và lịch kiểm độc lập giờ/facts/từng chặng | Một thứ tự thất bại không chứng minh không có phương án khác |

Quyền USER và actor âm là kiểm tra hợp đồng riêng, không trộn vào semantic accuracy của câu USER hợp lệ. Save/list/detail và rollback là kiểm tra integration bổ sung theo contract, không thay điểm chất lượng preview. Đo adapter/model thô nếu cần phải có series riêng, không gộp với kết quả API.

## 2. Bộ mẫu và tách tập

| Suite | Số mẫu | Nhóm chính |
|---|---|---|
| parser | 200 | `dev`100 và `heldout`100; mỗi tập50 CLEAR,20 CLARIFY,10 CONFLICT,10 OUT_OF_SCOPE,10 PRIVACY_INJECTION |
| recommendation | 30 dev | 20 MATCH,5 NO_MATCH,5 INVALID_INPUT; MATCH phải có xe phù hợp theo fixture/nhãn |
| itinerary | 30 dev | 10 FEASIBLE,5 INFEASIBLE,5 NEEDS_DATA,5 PROVIDER_ADVERSE,5 INVALID_OR_ACCESS |

Mỗi mẫu có đúng một `category` chính để đếm số lượng; `tags` có thể nhiều, không dùng tags để nhân mẫu. Nhóm PRIVACY_INJECTION gồm5 yêu cầu có định danh tổng hợp phải chặn trước inference và5 prompt injection không chứa định danh, xử lý theo ý định thuê xe/ngoài phạm vi, không nhất thiết trả400. Nhóm INVALID_OR_ACCESS itinerary gồm2 validation và3 quyền guest/STAFF/ADMIN.

Các tỷ lệ trên là quy mô bộ đánh giá, không là kết quả đã có. Các test concurrency/rollback/provider adverse khác có thể bổ sung vào regression suite nhưng không âm thầm tăng mẫu số các nhóm cố định.

`groupId` gom cùng ý nghĩa, template, nguồn hoặc các diễn đạt gần nhau. Toàn bộ group chỉ thuộc một split. Chuẩn hóa để dò trùng: Unicode NFC, strip và gộp khoảng trắng; giữ dấu tiếng Việt, phủ định, số tiền/ngày. Dò trùng exact sau chuẩn hóa và rà paraphrase thủ công. Không cố ý sửa dấu/punctuation để lách group split.

Chỉ dùng câu/dữ liệu tổng hợp, không sao chép hồ sơ, email/số điện thoại/số giấy tờ, token hoặc hội thoại thật. Ca privacy dùng định danh giả được đánh dấu synthetic; không dùng credential có hiệu lực. Nội dung injection là dữ liệu test, không là chỉ dẫn vận hành.

### Freeze và người rà nhãn

- Tác giả mẫu ghi rationale trước khi nhìn output baseline. Người dùng hoặc reviewer khác tác giả rà tính đúng và các biến thể được chấp nhận; không tự ký reviewer thứ hai.
- Nhãn chưa rà có `reviewState=DRAFT`, không đủ điều kiện chứng nhận chất lượng. REVIEWED yêu cầu reviewer không null và khác author. Thiếu reviewer là giới hạn công khai, vẫn có thể đo exploratory trên dev với nhãn DRAFT được tách riêng; exploratory không được đổi gate thành đạt.
- Đóng băng manifest/dataset/fixture hashes và split trước tuning. Baseline phát triển chỉ chạy dev; heldout không gửi model, không đọc kết quả để sửa prompt.
- Gán nhãn heldout được phép đọc câu để xây gold, nhưng không dùng các câu đó làm ví dụ prompt hoặc nhóm sửa lỗi. Rà rubric bằng ví dụ dev trước khi chốt heldout.
- Chỉ chạy heldout cuối sau khi đóng băng source/model/prompt. Đã dùng kết quả heldout để tối ưu thì lần sau không còn độc lập; phải lập tập kiểm chứng mới được rà và freeze. Không chuyển ca khó sang dev để tăng điểm.

## 3. Định dạng dữ liệu

Quy ước UTF-8 JSONL, một object mỗi dòng, không comment/NaN/Infinity/duplicate keys. Đây là định dạng cho bộ mẫu, không thêm trường vào body API. Dữ liệu và mã runner tái sử dụng thuộc Git; báo cáo/log/hash mỗi lượt thuộc runtime ignored.

### Phần chung của mỗi mẫu

| Field | Kiểu / ràng buộc |
|---|---|
| `schemaVersion` | Chuỗi đúng `kdrive-ai-eval-v1` |
| `caseId` | ID ASCII `[a-z0-9][a-z0-9_-]{0,63}`, duy nhất toàn bộ dataset |
| `suite` | parser / recommendation / itinerary |
| `split` | dev / heldout; recommendation và itinerary trong bộ này dùng dev |
| `category` | Một giá trị trong bảng nhóm của suite |
| `groupId` | ID ổn định, cùng ràng buộc ký tự như caseId |
| `tags` | Mảng chuỗi không trùng, có thể rỗng |
| `fixtureRef` | Profile fixture được khai báo trong manifest, không path/env/credentials tùy ý |
| `actor` | USER / GUEST / STAFF / ADMIN; actor ngoài USER chỉ trong ca quyền |
| `input` | Object đúng body API tương ứng sau render placeholder; không chứa token/owner tùy ý |
| `expected` | Nhãn typed theo suite bên dưới, không output tự lấy từ hệ thống cần chấm |
| `label` | `{rationale, author, reviewer, reviewState}`; author/reviewer là mã người rà, reviewer null khi DRAFT; reviewState DRAFT / REVIEWED |

Reject unknown fields tại mỗi cấu trúc quy định. Không đủ nhãn/profile/placeholder là lỗi dữ liệu trước khi gọi API, không là model fail. Một payload cố ý sai vẫn nằm trong `input` của mẫu INVALID_INPUT hợp lệ; validator dataset không sửa nó thành payload đúng.

### `expected` của parser

`expected` có `{kind, variants}`; kind=`PARSER`, variants là mảng1–3 kết quả chấp nhận được đã gán trước. Không thêm biến thể sau khi xem output.

Mỗi variant có đủ `{httpStatus, status, draft, unresolvedFields}`. `status` chỉ READY_FOR_REVIEW / NEEDS_CLARIFICATION / OUT_OF_SCOPE hoặc null khi HTTP error; `draft` là null hoặc đủ7keys: pickupDate, returnDate, pickupBranchId, returnBranchId, passengers, budgetVndTotal, transmission. Giá trị thiếu là null, không tự thêm mặc định. Ngày ISO đủ năm; tiền nguyên VND; transmission AUTO/MANUAL/null. Không có amenity trong parser DTO hiện hành.

Với response200, so khớp toàn bộ variant: draft null khác object toàn null; strings ID/enum/ngày exact, số exact không chấp nhận bool/string/float thay integer. `unresolvedFields` so như tập không trùng; allowlist API là pickupDate, returnDate, pickupBranchName, returnBranchName, passengers, budgetVndTotal, transmission và conflicts. Không đổi pickupBranchName thành pickupBranchId trong danh sách thiếu chỉ vì draft dùng ID. Field thiếu không tự coi như null. Với HTTP error, status/draft/unresolvedFields trong nhãn đều null và không so text lỗi tiếng Việt.

CONFLICT yêu cầu NEEDS_CLARIFICATION, draft null, unresolvedFields chứa đúng conflicts theo API hiện hành. CLARIFY mô tả rõ trường nào còn thiếu, cho phép draft một phần chỉ khi được nhãn ghi trước. OUT_OF_SCOPE không được tạo draft. Privacy400 và actor401/403 là hợp đồng guard, không cộng vào CLEAR exact match. Nội dung lỗi/conflict tự do không được so văn bản nguyên câu hoặc coi là xác suất.

Ví dụ minh họa cấu trúc, không phải một mẫu thuộc bộ200:

```json
{
  "schemaVersion": "kdrive-ai-eval-v1",
  "caseId": "example-parser-clear",
  "suite": "parser",
  "split": "dev",
  "category": "CLEAR",
  "groupId": "example-dn-four-passengers",
  "tags": [],
  "fixtureRef": "synthetic-branches-v1",
  "actor": "USER",
  "input": {"text": "Thuê xe 4 người từ {{pickupDate}} đến {{returnDate}}, nhận và trả tại KDrive Đà Nẵng, tổng ngân sách 3000000 đồng, số tự động."},
  "expected": {
    "kind": "PARSER",
    "variants": [{"httpStatus": 200, "status": "READY_FOR_REVIEW", "draft": {"pickupDate": "{{pickupDate}}", "returnDate": "{{returnDate}}", "pickupBranchId": "{{dnBranchId}}", "returnBranchId": "{{dnBranchId}}", "passengers": 4, "budgetVndTotal": 3000000, "transmission": "AUTO"}, "unresolvedFields": []}]
  },
  "label": {"rationale": "Các trường được nêu rõ; branch ID lấy từ fixture tổng hợp.", "author": "author-a", "reviewer": null, "reviewState": "DRAFT"}
}
```

### `expected` của recommendation

Đủ keys `{kind, httpStatus, status, acceptableVehicleIds, eligibleVehicleIds, requiredReasonCodes, forbiddenReasonCodes}`, kind=`RECOMMENDATION`. Status OK/NO_MATCH/NEEDS_CLARIFICATION hoặc null khi HTTP error. Các danh sách ID/mã không trùng. Reason code lists là map vehicleId→array; không kiểm exact thứ tự reason codes nếu không là contract.

- MATCH: expected200/OK; acceptableVehicleIds là tập xe người rà chấp nhận, không rỗng và là tập con eligibleVehicleIds. Eligible được xác lập thủ công từ facts/booking/tuyến/giá fixture, không gọi VehicleSearchService/RecommendationService làm oracle.
- Tất cả kết quả trả về phải thuộc eligible, không trùng, total/fees/rentalDays/reasons đúng facts; kiểm cả xe ngoài top3. Bất cứ vi phạm hard-filter/facts nào đều fail dù top3 có xe chấp nhận được.
- NO_MATCH: expected200/NO_MATCH và results rỗng, tập eligible/acceptable rỗng. INVALID_INPUT: expected400, status null, các tập/map rỗng. Không trộn các ca này vào mẫu số top3.
- Giữ rankingVersion hiện hành làm baseline. Thêm ranking learned hoặc đổi trọng số cần quyết định riêng, không sửa nhãn theo thứ tự server.

### `expected` của itinerary

Đủ keys `{kind, httpStatus, status, oracle, requiredLocationIds, forbiddenLocationIds}`, kind=`ITINERARY`; status VALIDATED/NEEDS_DATA/NO_FEASIBLE_PLAN/UNAVAILABLE hoặc null khi HTTP error. Oracle là object `{type, witnessOrders, evidenceRef}`; type=`KNOWN_FEASIBLE` / `EXHAUSTIVE_INFEASIBLE` / `MISSING_FACTS` / `EXPECTED_PROVIDER_FAILURE` / `INVALID_OR_ACCESS`.

- FEASIBLE:200/VALIDATED, có ít nhất một witnessOrder2–4ID không trùng và bắt đầu đúng điểm. Không bắt output giống witness nếu một lịch khác cũng kiểm độc lập đạt.
- INFEASIBLE:200/NO_FEASIBLE_PLAN; oracle duyệt hết phạm vi fixture nhỏ, không lấy một lần Qwen thất bại làm căn cứ. Ghi rõ tập ứng viên/thời gian/phạm vi xét; không tuyên bố toàn vùng không khả thi.
- NEEDS_DATA:200/NEEDS_DATA; chỉ dùng tình huống contract rõ như thiếu duration/metadata hoặc không đủ ứng viên. Các ca đóng cửa/UNKNOWN của điểm đầu phải đối chiếu source và contract, ghi finding nếu chưa thống nhất trước gán nhãn.
- PROVIDER_ADVERSE: Qwen tắt/lỗi hoặc OSRM unavailable dự kiến200/UNAVAILABLE; ca capacity dự kiến429/status null. Cause/state phải được tạo và kiểm chứng trên fixture, không coi lỗi kết nối ngẫu nhiên là ca âm đạt.
- INVALID_OR_ACCESS: validation400, guest401, STAFF403, ADMIN403; status null. Nhóm có2validation và3role như bảng số lượng.
- required/forbiddenLocationIds là các tập không giao nhau, dùng khi scenario có ràng buộc tương ứng. WitnessOrders rỗng cho type khác KNOWN_FEASIBLE.

Oracle tính độc lập từ metadata/ngoại lệ/qua đêm, thời lượng quản trị ước tính, timezone Việt Nam và chặng có hướng. Không import computeSchedule hoặc hàm xếp hạng để tạo expected. Chặng fixture tổng hợp chỉ chứng minh lịch trên fixture; tuyến thực cần series OSRM thật và graph hash riêng. Không suy A→B bằng B→A.

Khi chấm VALIDATED: số điểm2–4, ACTIVE/đủ facts, start đúng, không trùng, từng chặng hợp lệ, arrival/departure/wait/travel/totals đúng nguồn; không cần exact fingerprint/UUID/timestamp. Với số học tổng hợp, phút/số nguyên exact; khoảng cách double sai số tuyệt đối tối đa0,01m. So OSRM thật chỉ dùng cùng graph/version; không áp sai số nới rộng để che graph drift.

## 4. Manifest, thời gian và fixture

Manifest evaluation có đủ `{schemaVersion, datasetVersion, datasets, profiles, reviewState}`; reviewState DRAFT/REVIEWED theo nhãn. datasets gồm các entry `{path, sha256, suite, split, count}`; path tương đối trong thư mục dataset đã chọn, không traversal/URL. Profiles map fixtureRef→`{clockInstant, timezone, bindings, factsPath, factsSha256, providerMode}`; providerMode=`synthetic` / `live` / `adverse`. Manifest không chứa credentials/actor token hoặc identity runtime máy khác.

- clockInstant cố định UTC, timezone Asia/Ho_Chi_Minh. Case sử dụng ngày tương lai so với Clock fixture; không thay giờ hệ thống/DB chính. Luồng live không override Clock được thì compile ngày từ một reference instant đã đóng băng cho run, ghi lại manifest run và tất cả ngày render; ngày không còn hợp lệ thì không so trực tiếp hai run đó.
- Chỉ placeholder nguyên token `{{bindingName}}` từ bindings allowlist đã khai báo; render input/expected một lần trước validation. Placeholder là toàn giá trị JSON giữ kiểu bindings, hoặc nằm trong text dùng string; không eval expression/shell. Thiếu binding là DATASET_INVALID. Ngày sau render là ISO đủ năm; nhãn không tự suy năm theo ngày máy.
- Mapping logical ID→fixture ID cố định trong run, giữ dữ liệu synthetic cùng version/hash. Seed fixtures qua nguồn test được quản lý, không gọi công cụ đánh giá ghi vào DB người dùng.
- Live series ghi model ID/digest có sẵn nếu lấy được, runtime/version, settings/prompt/source hashes, graph manifest/hash, fixture facts hash; không tự pull model/extract graph. Thiếu model digest phải ghi unknown, không suy bản model từ tên tag.
- MySQL/OSRM chỉ dùng pool đã xác minh và schema/marker riêng theo [fixture policy](../../../skills/kltn-docker-fixtures/SKILL.md). Không33385/3306, không start/stop provider của người khác; series synthetic không thay bằng chứng live.

## 5. Phân loại kết quả từng ca

Mỗi ca có đúng một outcome chính; `violations` ghi thêm các lỗi độc lập. HTTP status một mình không xác định nguyên nhân lỗi503.

| Outcome | Ý nghĩa | Có được tính đúng semantic? |
|---|---|---|
| `PASS` | Response khớp nhãn và mọi assertion bắt buộc | Có, chỉ nếu thuộc nhóm semantic tương ứng |
| `SEMANTIC_FAIL` | Response đúng cấu trúc nhưng sai status/giá trị/khả thi | Không |
| `CONTRACT_FAIL` | JSON/schema API sai, sai quyền, hard-filter/facts hoặc mutation không phép | Không; vi phạm bắt buộc chặn nghiệm thu |
| `MODEL_OUTPUT_INVALID` | Có bằng chứng output model bị validator từ chối | Không; ghi riêng, không gán timeout vào nhóm này |
| `PROVIDER_ERROR` | Provider tắt/bận/lỗi ngoài tình huống adverse đã định | Không |
| `TIMEOUT` | Hết deadline có nguyên nhân xác định | Không |
| `API_ERROR` | HTTP/network lỗi chưa xác định đúng provider/model cause | Không; không đoán nguyên nhân |
| `ENV_BLOCKED` | Thiếu môi trường hoặc preflight không đủ điều kiện | Không; không gọi là semantic fail |
| `DATASET_INVALID` | Sai nhãn/schema/profile/split/hash trước khi gọi hệ thống | Không; từ chối whole run, không tiếp tục chọn ca dễ |
| `SKIPPED` | Ca chưa thực thi, có lý do cụ thể | Không |

Thứ tự quyết định: preflight/data → ENV_BLOCKED/DATASET_INVALID; không gọi→SKIPPED; vi phạm quyền/facts/schema→CONTRACT_FAIL; expected adverse đúng cause/state→PASS; unexpected timeout/provider/model/API error→nhãn tương ứng; response còn lại→PASS hoặc SEMANTIC_FAIL. Adverse phải chứng minh cause đúng và invariants; sai cause vẫn fail. Không tự retry/best-of để chọn lần đúng.

Đầu ra từng ca đủ `{caseId, suite, split, category, attempt, outcome, httpStatus, responseStatus, causeCode, assertionResults, violations, durationMs, expectedRef}`; assertionResults là mảng `{name, passed}` và violations là mảng mã lỗi. causeCode nullable, durationMs đo monotonic từ dispatch đến nhận/parse response hoặc kết thúc lỗi. Không lưu token, nguyên hội thoại hay nội dung người dùng thật trong report.

## 6. Công thức và gate

N là toàn bộ ca dự kiến thuộc nhóm, cố định trước chạy. C là số ca có response so được semantic; F là số đúng trong C. Response hợp lệ sai nghĩa vẫn thuộc C. Unexpected model/provider/timeout/API/env/skip không thuộc C nhưng vẫn thuộc N.

- `coverage = C/N`; `semanticAccuracy = F/C`; `conservativeSuccess = F/N`. Mẫu số0 trả null/N/A, không trả100%.
- Công bố semanticAccuracy cùng coverage và conservativeSuccess. Không chứng nhận đạt ngưỡng khi coverage<100%, nhãn chưa REVIEWED hoặc có vi phạm bắt buộc; baseline vẫn được báo có finding.
- Parser CLEAR: exact match variant toàn bộ field/status; mục tiêu ít nhất95%, tức48/50. CLARIFY+CONFLICT: status/field thiếu/draft đúng toàn variant; mục tiêu ít nhất95%, tức29/30. OUT_OF_SCOPE và PRIVACY_INJECTION báo riêng từng nhóm, không làm loãng mẫu số CLEAR.
- Recommendation: Hit@3 = số ca MATCH có ít nhất một acceptable ID trong3 kết quả đầu /20. Đúng ít nhất90% cần18/20; mọi hard-filter/facts/reasons sai vẫn fail hợp đồng. NO_MATCH5 và INVALID_INPUT5 báo riêng, không cộng vào Hit@3.
- Itinerary: báo số lịch hợp lệ/FEASIBLE10, số NO_FEASIBLE đúng/INFEASIBLE5, NEEDS_DATA5, adverse5, validation/access5 riêng. Mục tiêu đúng toàn bộ oracle fixture và không vi phạm bắt buộc; không lấy VALIDATED rate cao làm chứng cứ mọi lịch hấp dẫn/tối ưu.
- Mọi tỷ lệ so trên số nguyên trước làm tròn; hiển thị2 chữ số thập phân. Lỗi contract/quyền/facts không được bù bằng điểm semantic cao.

Ví dụ tính tay: CLEAR có50ca,47đúng,1sai,2timeout → C48, F47, coverage96%, semanticAccuracy97,92%, conservativeSuccess94%; chưa đạt gate dù accuracy trên95%. Nhóm cần làm rõ29/30 đạt96,67%. Hit@3 đạt18/20=90%;17/20=85% không đạt. Không ca nào chạy thì coverage0 và accuracyN/A, không PASS.

## 7. Độ trễ, lặp lại và báo cáo

- Một attempt chính mỗi ca cho quality. Không tự retry POST/save hoặc tăng inference concurrency để hoàn tất nhanh; kết quả lần đầu luôn giữ.
- Báo `durationMs` riêng cho success, semantic/contract failure và timeout/provider error; timeout là quan sát censored, không bỏ chúng rồi gọi p95 success là p95 toàn dịch vụ. p50/p95 dùng nearest-rank trên dãy tăng dần: phần tử ceil(p*n), đánh số từ1; n0=N/A.
- Chạy performance lặp riêng trên20parser dev chọn trước theo tỷ lệ10/4/2/2/2 của5nhóm;3lần/mẫu, tuần tự. Không gộp60attempt này vào mẫu số quality100; báo tỷ lệ nhất quán và tất cả attempt, không best-of.
- Chỉ ghi warm/cold khi có bằng chứng trạng thái load; không gọi request đầu là cold theo suy đoán. Nếu cold cần unload/start lại dịch vụ không do lượt sở hữu, ghi NOT_MEASURED và chờ phạm vi được phép, không tự dừng dịch vụ.
- Result manifest runtime ghi run ID, command, source revision/dirty hashes, dataset/fixture/source/prompt/model/graph identifiers, reference clock/render bindings không secrets, settings/flags không credentials, hardware khi biết, series type và attempt policy. Thiếu thông tin ghi unknown và giới hạn so sánh.
- Report tổng hợp có planned/executed/outcome counts theo suite/split/category, metric numerator/denominator/coverage, latency groups và skipped/blocked reasons. Mọi caseId xuất hiện đúng một lần trong quality series; không mất mẫu hoặc trùng mẫu để đạt coverage.
- Exit0: cả dữ liệu/runner hợp lệ và mọi assertion/gate được yêu cầu của chế độ đó đạt. Exit1: run thực thi có assertion/gate fail. Exit2: dataset/preflight invalid hoặc môi trường blocked nên chưa đủ thực thi. Quality chưa đạt ở baseline là exit1 kèm report, không sửa exit thành0 để gọi AI PASS. Việc hoàn thành đo baseline được xác nhận bằng runner kiểm chứng + report đầy đủ, không bằng AI đạt điểm cao.

Cách chạy và giới hạn runner tại [RUNNER.md](RUNNER.md). Tài liệu đặc tả này không tuyên bố chất lượng model hoặc nghiệm thu độc lập.
