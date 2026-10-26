import { Link } from "react-router-dom";
import { Page } from "../../components/common/PageKit";
import { DEMO_MODE } from "../../demo/config";

const steps = [
  ["Chọn xe và chuyến đi", "Chọn điểm nhận, thời gian nhận/trả và điểm trả nếu khác. Tìm xe để kiểm tra lịch; so sánh hoặc lưu yêu thích trước khi quyết định."],
  ["Kiểm tra GPLX và báo giá", "Đăng nhập tài khoản khách hàng, khai báo GPLX tương thích xe và còn hiệu lực đến hết thuê. Kiểm tra lịch & báo giá trước khi gửi đơn."],
  ["Gửi yêu cầu thuê", "Xác nhận báo giá hiện tại rồi gửi đơn. Đơn chờ xác nhận giữ lịch tối đa 2 giờ và không vượt hết ngày nhận đối với đơn theo ngày; đơn cũ không vượt giờ nhận; xem thời hạn trên đơn."],
  ["Chờ chi nhánh xác nhận", "Nhân viên chi nhánh nhận kiểm tra yêu cầu. Hợp đồng được phát hành khi xác nhận; checkbox báo giá không phải ký hợp đồng."],
  ["Chuẩn bị và nhận xe", "Mang GPLX bản gốc đến chi nhánh nhận theo đơn. Nhân viên đối chiếu GPLX, ghi nhận tình trạng và xác nhận đã giao xe trong ngày nhận đối với đơn theo ngày."],
  ["Trả xe", "Trả trong ngày trả đã đặt, tại chi nhánh trả ghi trên đơn. Nhân viên chi nhánh trả ghi nhận tình trạng nhận xe; không tự đổi điểm trả giữa chuyến."],
  ["Hoàn tất và xem chứng từ", "Theo dõi đến khi nhân viên hoàn tất đơn. Xem hóa đơn và gửi đánh giá cho đơn hoàn tất. Hóa đơn không chứng minh tiền đã thu."],
];
const questions = [
  ["Giá thuê được tính thế nào?", "Đơn mới tính số ngày giữa ngày nhận và ngày trả: 17/10 đến 21/10 là 4 ngày thuê, VND nguyên đồng. Khách được trả trong ngày trả; xe chỉ nhận lịch mới từ ngày tiếp theo. Đơn cũ giữ giá và quy tắc block 24 giờ đã lưu. Báo giá gồm tiền thuê và phí tuyến theo hệ thống; giá và lịch được kiểm tra lại khi gửi đơn."],
  ["Có thể nhận và trả khác chi nhánh không?", "Có nếu tuyến được mở trong hệ thống. Chọn đúng điểm nhận/trả và xem phí tuyến trong báo giá. Chi nhánh là điểm vận hành, không phải địa điểm tham quan."],
  ["GPLX cần đáp ứng điều kiện gì?", "Hệ thống xét hệ bằng cũ hoặc mới, số chỗ gồm người lái, hộp số/hạn chế và hiệu lực đến hết thuê. Khai báo trên hồ sơ không thay việc nhân viên đối chiếu GPLX thực tế trước giao xe."],
  ["Tôi có thể hủy đơn không?", "Khách có thể tự hủy đơn chờ xác nhận hoặc đã xác nhận khi còn ít nhất 24 giờ trước mốc nhận, kèm lý do (đơn theo ngày lấy đầu ngày nhận làm mốc). Nếu dưới 24 giờ, liên hệ nhân viên; thao tác thực tế phụ thuộc trạng thái và quyền trên đơn."],
  ["Vì sao trạng thái chưa thay đổi?", "Mở chi tiết đơn và bấm Tải lại đơn để lấy dữ liệu mới. Lịch sử hiển thị các sự kiện hệ thống đã ghi nhận theo giờ Việt Nam; trang không tự xác nhận, giao xe hoặc hoàn tất đơn."],
  ["Hợp đồng và hóa đơn xuất hiện lúc nào?", "Hợp đồng được phát hành khi xác nhận, hóa đơn khi hoàn tất và giữ thông tin tại thời điểm phát hành. Hóa đơn không xác nhận đã thanh toán; các hồ sơ sự cố được theo dõi riêng."],
  ["Ảnh xe có phải ảnh chiếc xe sẽ nhận?", "Ảnh có thể là ảnh minh họa cho mẫu xe. Xem thông tin xe và đối chiếu xe thực tế khi nhận; nguồn ảnh và giấy phép được công bố ở cuối trang."],
];
export default function RentalGuidePage() {
  return <Page title="Hướng dẫn thuê xe" description="Từ chọn chuyến đến nhận xe, trả xe và theo dõi đơn.">
    {DEMO_MODE ? <p role="status" className="panel mb-5">Bạn đang xem chế độ demo; dữ liệu và thao tác mẫu tách biệt với hệ thống API.</p> : null}
    <section className="panel"><h2 className="text-xl font-semibold">Các bước thuê xe</h2><ol className="mt-5 grid gap-5 md:grid-cols-2">{steps.map(([title, body], index) => <li key={title}><h3 className="font-semibold">{index + 1}. {title}</h3><p className="mt-2 text-sm leading-6">{body}</p></li>)}</ol></section>
    <section className="panel mt-6"><h2 className="text-xl font-semibold">Câu hỏi thường gặp</h2><div className="mt-4 space-y-3">{questions.map(([title, body]) => <details className="border-b border-slate-200 pb-3" key={title}><summary className="cursor-pointer font-semibold">{title}</summary><p className="mt-3 text-sm leading-6">{body}</p></details>)}</div></section>
    <div className="mt-6 flex flex-wrap gap-3"><Link className="btn" to="/vehicles">Tìm xe cho chuyến đi</Link><a className="btn-secondary" href="/media/vehicles/licenses.html">Nguồn ảnh &amp; giấy phép</a></div>
  </Page>;
}
