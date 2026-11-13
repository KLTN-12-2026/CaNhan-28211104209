# Runner đánh giá AI

Runner Python3.12 stdlib chấm phản hồi API theo [đặc tả](README.md), dùng validators hiện có trước mọi dispatch. Code nghiệp vụ, migration, ranking và prompt không bị thay bằng oracle. Dữ liệu hiện là DRAFT: phải truyền `--allow-draft` và mọi báo cáo giữ `certified=false`. Runner này chỉ chạy **dev**, không có tùy chọn gửi heldout cho model.

## Chạy fixture backend và chấm

[AiEvaluationFixtureTest.java](../../../backend/src/test/java/com/carrental/AiEvaluationFixtureTest.java) dùng Spring context thật, security/JWT/controller/service/repository thật, Flyway và Hibernate validate. Database hardcode H2 in-memory tên `ai_evaluation_g24`, không nhận override URL DB từ môi trường. Đây không phải bằng chứng MySQL, concurrency hay provider thực.

Fixture nạp từng catalog/scenario riêng, Clock2027-02-01 UTC, ghi timestamp dưới dạng UTC và đọc lại qua repository. Preview kiểm snapshot trước/sau các bảng xe/booking/itinerary/location không đổi. Provider beans chuyển lời gọi sang Qwen planner/OSRM client thật nối server HTTP test loopback cổng ngẫu nhiên: Ollama envelope/ID parser, timeout, disabled, semaphore capacity và chặng có hướng. Server/executor và permit do fixture sở hữu được giải phóng trong finally. Không kết nối Ollama/OSRM đang chạy của người dùng, không tải model/graph. Không có endpoint seed trong ứng dụng production.

Từ thư mục `backend/`, đặt JAVA_HOME tới JDK21 đã cài. Dùng một đường dẫn output **mới** dưới `.runtime/`; không tái dùng thư mục cũ, kể cả rỗng. Ví dụ thay phần tên lượt trước mỗi lần chạy:

```powershell
./mvnw.cmd -o "-Dtest=AiEvaluationFixtureTest" "-Dai.eval.output=E:/AI/BERT_Project/codepython/KLTN/.runtime/my-ai-capture" test
```

`-o` chỉ dùng nếu Maven cache đủ. Không bỏ Flyway hoặc Hibernate để vượt lỗi môi trường. Test luôn chạy60ca; bỏ `ai.eval.output` thì vẫn kiểm assertions và không export. Khi export, capture gồm observations.jsonl và capture.json pin SHA256 dữ liệu, toàn backend/src/main, pom và test source. Ghi source revision cùng hashes vì working tree có thể đang sửa. Capture không phụ thuộc gold status/acceptable/witness để sinh response: dữ liệu response đi từ API, model chỉ nhận proposedOrder của fixture.

Từ root repository:

```powershell
python -X utf8 scripts/ai/evaluation/run_evaluation.py --dataset scripts/ai/evaluation/scenarios-v1 --suites recommendation,itinerary --capture .runtime/my-ai-capture/capture.json --output .runtime/my-ai-score --allow-draft
```

Chấm lại cần output mới. Source/dataset/hash khác capture sẽ bị từ chối. Một capture thuộc cả60ca phải chấm cùng `--suites recommendation,itinerary`; không âm thầm bỏ observations ngoài phạm vi. Không dùng test success làm quality success: fixture test xác nhận60response/no mutation/provider evidence; scorer có thể trả exit1 vì không khớp oracle (ví dụ có phương án khác ngoài thứ tự đầu tiên).

## Chạy parser qua HTTP đã chuẩn bị

Chỉ parser hỗ trợ HTTP trực tiếp ở runner này. Catalog phải đúng facts tổng hợp của parser, clock/reference và source đã xác minh trong môi trường đánh giá riêng. Runner **không tự seed hoặc sửa DB**. Operator chuẩn bị preflight.json từ readback của môi trường, không sao chép thông tin máy khác hoặc tự đánh true khi chưa kiểm:

- `datasetHashes`: map manifest.json, dev.jsonl, heldout.jsonl, facts.json→SHA256 thực. Hash heldout chỉ kiểm integrity, không gửi câu heldout.
- `baseUrl`: `http://127.0.0.1:<port>`, không path/userinfo/query/redirect.
- `clockInstant`: giá trị profile parser; `branchFacts`: đúng branches trong facts.json, kể cả inactive. Xác nhận không có branch ACTIVE ngoài fixture gây đổi resolve.
- `sourceHashes`: map đường dẫn repository tương đối→SHA256 source backend đang chạy, tối thiểu controller/parser/service/config liên quan. Readback deployment phải do operator xác minh; Python chỉ đối chiếu file local với attestation này.
- `fixtureVerified=true`, `readOnlyConfirmed=true`: xác nhận fixture và invariants read-only từ kiểm tra bên ngoài. Đây là **attestation**, không tự chứng minh DB không đổi trong quá trình chạy. HTTP exploratory không thay bằng chứng integration có readback.

Token USER đưa qua biến môi trường `KDRIVE_EVAL_TOKEN` trong shell chạy; không truyền token trên command line, không ghi vào manifest hoặc report. Runner không thay biến môi trường caller.

```powershell
python -X utf8 scripts/ai/evaluation/run_evaluation.py --dataset scripts/ai/evaluation/parser-v1 --suites parser --base-url http://127.0.0.1:18080 --preflight .runtime/parser-preflight.json --output .runtime/parser-dev-run --allow-draft
```

Mỗi case chỉ POST một lần đến endpoint parse. Không dùng proxy môi trường, không retry, không follow redirect. Deadline transport tối đa65giây gồm connect/headers/body, giới hạn body1MiB. Lỗi không có bằng chứng nguyên nhân giữ API_ERROR hoặc TIMEOUT, không suy timeout từ text thông báo. Preflight thiếu/sai từ chối trước dispatch/output; operator phải sửa môi trường, không bật mock fallback.

Đo lặp thêm `--series performance`: chọn trước20dev theo thứ tự caseId (10CLEAR/4CLARIFY/2CONFLICT/2OUT_OF_SCOPE/2PRIVACY_INJECTION, gồm1guard và1injection), mỗi mẫu3attempt liên tiếp. IDs được ghi manifest trước dispatch.60attempt nằm series riêng; không gộp mẫu số quality100, không best-of. Báo consistency từ hash response theo từng case và p50/p95 nearest-rank theo outcome; cold/warm giữ UNKNOWN khi không có bằng chứng. Không unload/start provider để tự tạo cold. Capture mode hiện chỉ nhận quality một attempt; không gộp capture cũ thành performance giả.

## Định dạng capture và evidence

capture.json đủ keys: schemaVersion=`kdrive-ai-capture-v1`, captureKind (`spring-h2-synthetic`, `test-double`, `live`), datasetHashes, sourceRevision, sourceHashes, observationsPath, observationsSha256, model, graph, clockInstant. `model`/`graph` trong phiên bản này là synthetic hoặc unknown; không tuyên bố đã pin model thật chỉ từ tên tag. Một capture live chỉ là khai báo nguồn; không tự cấp chứng nhận. Capture path tương đối cùng thư mục, không traversal/URL. Unknown fields/duplicate keys/hash drift bị từ chối.

Mỗi observation có đúng caseId, httpStatus, body, durationMs, error, causeCode, evidence. error là null/TIMEOUT/API_ERROR/ENV_BLOCKED/SKIPPED. evidence có ba boolean fixtureVerified/noMutation/causeConfirmed. Ca uncalled không có status/body/duration. Cause allowlist QWEN_DISABLED/QWEN_TIMEOUT/QWEN_INVALID_IDS/OSRM_UNAVAILABLE/QWEN_CAPACITY/MODEL_OUTPUT_INVALID/PROVIDER_ERROR/TIMEOUT. Không lấy HTTP503 đơn lẻ làm bằng chứng cause. Với adverse, thiếu causeConfirmed→ENV_BLOCKED; sai cause→CONTRACT_FAIL; mapping đúng và payload đúng mới PASS. Capture producer chịu trách nhiệm chứng minh evidence, không sửa boolean để tăng điểm.

Missing observations được giữ thành SKIPPED đúng caseId, không mất mẫu; duplicate/unplanned ID bị từ chối toàn capture. Bộ test-double chỉ để chứng minh scorer bắt output đúng/sai, không phải model baseline. Các export fixtures không ghi Authorization, token hoặc prompt vào report. Capture body là dữ liệu synthetic; không dùng collector này để lưu hội thoại/dữ liệu thật.

## Điểm chấm và kết quả

- Parser: schema/kiểu số nghiêm ngặt, exact variant và unresolvedFields như tập; thiếu field khác null. Sai giá trị hợp schema là SEMANTIC_FAIL.
- Recommendation: kiểm toàn bộ results, kể cả sau top3; duplicate/hard-filter/giá/phí/ghế/tên/hộp số/reasons/score và thứ tự rules-version đều là contract. Hit@3 dựa acceptable độc lập, không dùng score làm gold. Budget/seat/preference score được đối chiếu bằng phân số; giá ngày fixture luôn dương theo schema.
- Itinerary: chấp nhận bất kỳ thứ tự2–4điểm hợp lệ, không bắt exact witness; kiểm từng chặng có hướng, opening intervals, arrival/departure/wait/dwell, totals, catalog/evidence và metadata. Weekly thiếu ngày được normalize UNKNOWN theo contract; tọa độ theo precision7số của DB; distance sai số tuyệt đối≤0,01m. Lịch không VALIDATED không được kèm items/totals/fingerprint.
- Contract/quyền/facts fail chặn gate. C chỉ gồm PASS/SEMANTIC_FAIL có thể chấm; response lỗi schema/facts không được nâng coverage semantic. N vẫn giữ tất cả planned. Unexpected provider/timeout/API/environment/skip không vào C nhưng vẫn vào N. Báo riêng từng suite/category và outcome.

Output mới gồm manifest.json, results.jsonl (flush sau từng ca), summary.json, REPORT.md. Không lưu body/token hoặc nguyên thông báo lỗi vào báo cáo chấm. Runtime manifest pin runner/dependencies, captured source/dataset/fixture, source revision và cấu hình không secrets. JSON report giữ phân số N/C/F và tỷ lệ chưa làm tròn; Markdown hiển thị2chữ số.

- Exit0: đủ dữ liệu/execution, các numeric gates của series đạt; **không đồng nghĩa nhãn đã review hoặc chất lượng đã chứng nhận**.
- Exit1: đã chạy/chấm, có gate fail. Performance trả1 khi có attempt không PASS, không chọn lần tốt nhất.
- Exit2: dataset/preflight invalid hoặc thiếu execution (ENV_BLOCKED/SKIPPED). Preflight invalid không tạo output; run đã có danh sách hợp lệ giữ đủ results kể cả chưa gọi.

## Kiểm tra runner

```powershell
python -X utf8 scripts/ai/evaluation/test_evaluation.py -v
python -X utf8 scripts/ai/evaluation/test_validate_scenarios.py -v
python -X utf8 scripts/ai/evaluation/test_validate_parser_dataset.py -v
```

Test runner gồm vectors đúng/sai, công thức tính tay, response ngoài top3, witness khác, mutation, adverse sai cause, zero denominators, exits0/1/2, duplicate/missing cases, refuse-existing, redaction, và HTTP loopback no redirect/retry/timeout. Các server test dùng cổng0 và dừng đúng instance do test tạo. Trong sandbox chặn loopback, cần quyền chạy test ngoài sandbox; không đổi assertions để PASS. Khi đổi backend test, chạy JDK21 `./mvnw.cmd verify` theo AGENTS.md; H2 và test-double không thay gate MySQL/model/OSRM thực của giai đoạn sau.
