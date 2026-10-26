import { money, dateTime } from '../common/PageKit';

export default function ReturnPolicySummary({ policy }) {
  if (!policy) return null;
  return <section className="mt-4 space-y-2 text-sm" aria-label="Chính sách trả xe đã lưu">
    <h3 className="font-semibold">Chính sách trả xe sớm/muộn</h3>
    <p>{policy.terms.text}</p>
    <p>Giá/ngày dùng tính vượt hạn: <strong>{money(policy.terms.dailyPriceVnd)}</strong> · Theo ngày Việt Nam.</p>
    {policy.acceptedAt && <p>Đã xác nhận đã đọc lúc {dateTime(policy.acceptedAt)}. Xác nhận này không phải chữ ký hợp đồng.</p>}
  </section>;
}
