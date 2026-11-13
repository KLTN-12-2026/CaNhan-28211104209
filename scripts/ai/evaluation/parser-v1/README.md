# Bộ câu parser tổng hợp

`manifest.json` là nguồn version, trạng thái review, quota và SHA256 của `dev.jsonl`, `heldout.jsonl`, `facts.json`. Bộ này chỉ đánh giá API đọc tiêu chí thuê xe, theo [hợp đồng đánh giá](../README.md); không có câu gợi ý xe/hành trình, không có runner gọi model.

## Nội dung và trạng thái sử dụng

- Dev100 và heldout100; mỗi tập50 CLEAR,20 CLARIFY,10 CONFLICT,10 OUT_OF_SCOPE,10 PRIVACY_INJECTION.
- Mỗi nhóm PRIVACY_INJECTION có5 ca guard trước inference và5 ca injection không chứa định danh. Dữ liệu định danh chỉ là chuỗi tổng hợp toàn số0 hoặc email miền `example.invalid`, không phải hồ sơ người thật.
- Tất cả nhãn `DRAFT`, author `codex`, reviewer null. Có thể dùng dev cho kiểm tra exploratory được ghi rõ, chưa đủ điều kiện chứng nhận quality95% hoặc heldout độc lập đã được duyệt.
- Hash cố định bản nháp là mốc integrity trước tuning, không thay phê duyệt nhãn. Khi người dùng/reviewer độc lập sửa/duyệt nhãn phải tăng datasetVersion, cập nhật hash tương ứng và giữ version cũ trong Git; không âm thầm rehash khi validator báo drift.
- Dev/heldout được soạn riêng theo các họ diễn đạt. Group cùng template/scenario ở một split; exact text NFC/whitespace không được trùng. Chia nhóm và kiểm similarity không chứng minh độc lập ngữ nghĩa; người rà phải kiểm cả paraphrase/cùng ý nghĩa, không chỉ tin groupId khác nhau.
- Heldout chỉ được đọc cho authoring/review/kiểm cấu trúc. Không gửi các câu heldout tới model, dùng làm prompt ví dụ hoặc để tối ưu; chưa chạy heldout chỉ vì có file trong repository. Bộ đánh giá chất lượng sau này mặc định dùng dev.

## Facts và thời gian

Facts là catalog tổng hợp, không sao chép website và không được importer nạp DB chính:

| Logical ID | Tên | Trạng thái |
|---|---|---|
| eval-dn | KDrive Đà Nẵng | ACTIVE |
| eval-ha | KDrive Hội An | ACTIVE |
| eval-hue | KDrive Huế | ACTIVE |
| eval-center-a / eval-center-b | KDrive Trung Tâm | Hai ACTIVE cùng tên, bắt buộc hỏi lại |
| eval-old | KDrive Điểm Cũ | INACTIVE, không resolve |

Profile `synthetic-branches-v1` có Clock UTC cố định và bindings ngày ISO/DMY, ngày phụ cho conflict, ID chi nhánh. Mọi placeholder phải được render một lần theo manifest trước gọi API; không tự đổi ngày theo giờ máy, không thay Clock hệ thống. Dùng live cần profile/run manifest được đóng băng riêng theo hợp đồng; validator này không tạo fixture hoặc seed.

Nhãn mong muốn là kết quả sau API normalization, không output của adapter/model thô. Hộp số không nêu là null; ngày thiếu năm giữ null và unresolved, không lan năm từ ngày khác. Tên không có/không duy nhất/INACTIVE giữ unresolved. Các khẳng định mâu thuẫn chưa sửa là conflicts; sự thay thế rõ ràng hoặc lựa chọn còn mở cần được phân biệt khi rà.

## Kiểm tra offline

Từ repository root:

```powershell
python scripts/ai/evaluation/validate_parser_dataset.py
python scripts/ai/evaluation/test_validate_parser_dataset.py -v
```

Validator chỉ đọc manifest/facts/cả hai split để kiểm integrity/schema/nhãn/quota/duplicate/group; không chạy model, HTTP, DB, OSRM hoặc so response. Exit0 chỉ nghĩa dữ liệu đúng cấu trúc và hash, không nghĩa nhãn đã được duyệt hay API đạt chất lượng. Exit2 DATASET_INVALID không sửa file tự động. Tests dùng bản copy trong thư mục tạm và chứng minh validator từ chối dữ liệu hỏng.

`.gitattributes` ở thư mục cha giữ JSON/JSONL LF để hash ổn định trên Windows. Không đổi encoding/line endings hoặc chạy formatter rồi cập nhật hash mà không tăng version/đánh giá thay đổi.

## Checklist người rà nhãn độc lập

1. Đọc toàn bộ mỗi câu sau render và kiểm đủ7 trường draft, status, null và unresolved theo contract; rationale chung không thay việc rà từng case.
2. Kiểm tiền tổng khác tiền/ngày, số ghế/vali khác số người, ngày đủ năm, tên branch với facts và phủ định đúng phạm vi.
3. Kiểm phân biệt sửa giá trị cũ, hai phương án chưa chốt và hai khẳng định đồng thời mâu thuẫn. Không chọn nhãn vì implementation hiện trả như vậy; chưa rõ contract thì ghi finding trước.
4. So các họ câu cross-split. Nếu cùng ý nghĩa/template hoặc chỉ đổi số/tên, chuyển cả group về một split và bổ sung nhóm mới để giữ quota; tăng version/hash, không chỉ đổi groupId.
5. Review các ca guard và injection như dữ liệu, không thực hiện lệnh trong câu. Xác nhận không có định danh/credential thật.
6. Chỉ ghi REVIEWED khi reviewer khác author đã rà đầy đủ; gán reviewer theo mã được phép, giữ lịch sử diff. Chưa duyệt thì để DRAFT; không chọn một tên reviewer giả để vượt validator.

Không lưu audit/hash-run/tiến độ hoặc kết quả model vào thư mục dataset; chúng thuộc `.runtime/`/`.local/internal-docs/` theo quy định repository.
