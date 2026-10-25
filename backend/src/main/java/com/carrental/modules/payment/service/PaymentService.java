package com.carrental.modules.payment.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.booking.service.DepositPolicy;
import com.carrental.modules.payment.repository.PaymentRepository;
import com.carrental.modules.payment.repository.PaymentRepository.*;
import com.carrental.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class PaymentService {
    private final PaymentRepository repo;
    private final BookingService bookings;
    private final PaymentSettings settings;
    private final VnpayGateway gateway;
    private final Clock clock;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public PaymentService(PaymentRepository repo, BookingService bookings, PaymentSettings settings, VnpayGateway gateway, Clock clock, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repo = repo; this.bookings = bookings; this.settings = settings; this.gateway = gateway; this.clock = clock; this.jdbc=jdbc;
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static class AmountMismatch extends DomainException {
        public AmountMismatch() { super(org.springframework.http.HttpStatus.BAD_REQUEST, "Số tiền VNPAY không khớp giao dịch."); }
    }

    public record Session(String transactionId, String url, java.time.Instant expiresAt) {}

    @Transactional
    public Session start(Actor actor, String bookingId, String ip) {
        var b = bookings.view(bookings.require(actor, bookingId));
        if (DepositPolicy.isVnpayMethod(b.deposit().method())) {
            settings.requireNew();
            // For VNPAY, require deposit to be approved first (validates GPLX, schedule, etc.)
            bookings.requirePayable(actor, bookingId);
            for (var p : repo.forBooking(bookingId)) {
                if (p.applied() || "SUCCEEDED".equals(p.status())) throw DomainException.conflict("Đang ghi nhận khoản cọc; vui lòng tải lại đơn.");
                if ("UNKNOWN".equals(p.status()) || ("PENDING".equals(p.status()) && !p.expiresAt().isAfter(clock.instant())))
                    throw DomainException.conflict("Cần đối soát giao dịch trước trước khi thanh toán lại.");
                if ("PENDING".equals(p.status())) return new Session(p.id(), gateway.checkout(p, ip), p.expiresAt());
            }
            String ref = id();
            repo.createPayment(ref, bookingId, b.deposit().amount(), clock.instant(), b.expiresAt());
            var p = repo.payment(ref).orElseThrow();
            repo.event(bookingId, ref, "PAYMENT_CREATED", "Tạo phiên VNPAY Sandbox", actor.id(), clock.instant(), "created:" + ref);
            return new Session(ref, gateway.checkout(p, ip), p.expiresAt());
        }
        if (DepositPolicy.isDemoQrMethod(b.deposit().method())) {
            throw DomainException.conflict("Phương thức QR DEMO không tạo phiên thanh toán trực tuyến; hãy dùng mã QR và báo chuyển khoản.");
        }
        throw DomainException.conflict("Phương thức cọc không hỗ trợ tạo phiên thanh toán trực tuyến.");
    }

    public Object summary(Actor actor, String bookingId) {
        var b = bookings.view(bookings.require(actor, bookingId));
        return Map.of(
            "sandbox", true,
            "acceptingNew", settings.acceptingNew(),
            "deposit", b.deposit(),
            "payments", repo.forBooking(bookingId),
            "refunds", repo.refunds(bookingId),
            "events", repo.events(bookingId)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment receive(VnpayGateway.Receipt r) {
        var found = repo.payment(r.ref()).orElseThrow(DomainException::missing);
        bookings.depositLock(found.bookingId());
        var p = repo.lockPayment(r.ref());
        if(!DepositPolicy.isVnpayMethod(p.depositMethod())) throw DomainException.conflict("Khoản QR không nhận thông báo VNPAY.");
        if (p.amount() != r.amount()) throw new AmountMismatch();
        if ("SUCCEEDED".equals(p.status())) {
            if (r.success() && !Objects.equals(p.providerTransaction(), r.transaction()))
                throw DomainException.conflict("Tham chiếu thanh toán không nhất quán.");
            return p;
        }
        if (!r.success() && !r.definitiveFailure()) {
            repo.unknown(p.id());
            repo.event(p.bookingId(), p.id(), "PAYMENT_UNKNOWN", "VNPAY chưa trả kết quả kết thúc; cần đối soát trước khi tạo giao dịch thay thế", null, clock.instant(), null);
            return repo.payment(p.id()).orElseThrow();
        }
        repo.receipt(p.id(), r.success() ? "SUCCEEDED" : "FAILED", r.transaction(), r.payDate());
        repo.event(p.bookingId(), p.id(), r.success() ? "PAYMENT_RECEIVED" : "PAYMENT_FAILED",
            r.success() ? "VNPAY Sandbox xác minh đã nhận tiền" : "VNPAY Sandbox trả kết quả thất bại", null, clock.instant(), "receipt:" + p.id() + ":" + r.success());
        return repo.payment(p.id()).orElseThrow();
    }

    @Transactional
    public void queueUnappliedRefund(String paymentId) {
        var found = repo.payment(paymentId).orElseThrow(DomainException::missing);
        bookings.depositLock(found.bookingId());
        var p = repo.lockPayment(paymentId);
        if (p.appliedAmount() >= p.amount() || !"SUCCEEDED".equals(p.status())) return;
        queue(p, true, p.amount(), "UNAPPLIED", "APPROVED", "Tiền đến sau hạn/hủy hoặc đơn không thể xác nhận; hoàn toàn bộ.");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancellation(BookingService.View b) {
        String kind = Objects.toString(b.deposit().cancellationKind(), "CANCELLED");
        boolean discretionary = Set.of("NO_SHOW", "LATE_CUSTOMER").contains(kind);
        for (var found : repo.forBooking(b.id())) if ("SUCCEEDED".equals(found.status())) {
            var p = repo.lockPayment(found.id());
            queue(p, true, Math.max(0, p.amount() - repo.reserved(p.id(), null)), kind,
                discretionary ? "REQUESTED" : "APPROVED", discretionary ? "Admin quyết định 0–100%; cần lý do" : "Hoàn theo chính sách hủy");
        }
    }

    private void queue(Payment p, boolean full, long amount, String kind, String status, String reason) {
        if (amount <= 0 || repo.refunds(p.bookingId()).stream().anyMatch(r -> r.paymentId().equals(p.id()) && r.kind().equals(kind))) return;
        amount = Math.min(amount, Math.max(0, p.amount() - repo.reserved(p.id(), null)));
        if (amount <= 0) return;
        repo.createRefund(id(), p, amount, status, kind, reason, clock.instant());
        repo.event(p.bookingId(), p.id(), "REFUND_REQUESTED", reason, null, clock.instant(), "refund:" + p.id() + ":" + kind);
    }

    @Transactional
    public void qrRefund(String bookingId, String kind, boolean all) {
        bookings.depositLock(bookingId);
        for (var found : repo.forBooking(bookingId)) if ("SUCCEEDED".equals(found.status())) {
            var p = repo.lockPayment(found.id());
            if ("LATE".equals(kind)) {
                var b=bookings.view(bookings.require(new Actor("system","ADMIN","workflow"),bookingId));
                if(p.transferTime()!=null && p.transferTime().isBefore(b.deposit().paymentDueAt()) && p.verifiedAt().isBefore(b.deposit().reviewDueAt())) continue;
            }
            queue(p, true, all ? p.amount() : p.amount()-p.appliedAmount(), kind, "APPROVED", "Hoàn khoản QR DEMO không áp dụng vào cọc");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment recordQr(Actor staff, String bookingId, BookingService.VerifyInput input) {
        var b = bookings.requireDepositStaff(staff,bookingId);
        if (!DepositPolicy.isDemoQrMethod(b.deposit().method()) || b.deposit().approvedAt() == null)
            throw DomainException.conflict("Đơn chưa được duyệt cọc QR.");
        if (input.transferTime()==null || input.transferTime().isAfter(clock.instant()) || input.transferTime().isBefore(b.deposit().approvedAt()))
            throw DomainException.bad("Thời điểm chuyển không hợp lệ.");
        if (input.amountVnd()<=0 || !input.verified()) throw DomainException.bad("Cần số tiền dương và xác nhận đã đối chiếu.");
        checkText(input.transactionRef(),30); checkText(input.idempotencyKey(),64); checkText(input.sourceAccount(),100);
        if (!BookingService.requestCode(b.id()).equals(input.transferNote())) throw DomainException.bad("Nội dung chuyển chưa khớp mã yêu cầu; cần đối soát ngoại lệ.");
        for(var previous:repo.forBooking(bookingId)) if(input.idempotencyKey().equals(previous.idempotencyKey())) {
            if(previous.amount()!=input.amountVnd() || !Objects.equals(previous.providerTransaction(),input.transactionRef()) || !Objects.equals(previous.transferTime(),input.transferTime()) || !Objects.equals(previous.sourceAccount(),input.sourceAccount()))
                throw DomainException.conflict("Khóa yêu cầu đã dùng với dữ liệu khác.");
            return previous;
        }
        if (b.version()!=input.expectedVersion()) throw DomainException.conflict("Đơn đã thay đổi; tải lại.");
        if (jdbc.queryForObject("select count(*) from payment_transactions where provider_transaction=?",Long.class,input.transactionRef())>0)
            throw DomainException.conflict("Tham chiếu đã được ghi nhận, không cộng lại.");
        String paymentId=id(); Instant now=clock.instant();
        jdbc.update("""
            insert into payment_transactions(id,booking_id,amount,status,created_at,expires_at,provider_transaction,provider_pay_date,applied,version,verifier_id,verified_at,transfer_time,transfer_note,source_account,applied_amount,idempotency_key)
            values(?,?,?,'SUCCEEDED',?,?,?,?,false,0,?,?,?,?,?,0,?)
            """,paymentId,b.id(),input.amountVnd(),java.sql.Timestamp.from(now),java.sql.Timestamp.from(b.deposit().paymentDueAt()),input.transactionRef(),VnpayGateway.date(input.transferTime()),staff.id(),java.sql.Timestamp.from(now),java.sql.Timestamp.from(input.transferTime()),input.transferNote(),input.sourceAccount(),input.idempotencyKey());
        repo.event(b.id(),paymentId,"PAYMENT_RECEIVED","Staff đối chiếu khoản nhận QR DEMO",staff.id(),now,"receipt:"+paymentId);
        return repo.payment(paymentId).orElseThrow();
    }

    private static void checkText(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max) throw DomainException.bad("Thiếu thông tin đối soát hoặc vượt độ dài cho phép.");
    }

    @Transactional
    public Object decide(Actor admin, String refundId, long version, long amount, String reason) {
        admin.require("ADMIN");
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw DomainException.bad("Cần lý do hoàn tiền, tối đa 1000 ký tự.");
        var first = repo.refund(refundId);
        bookings.depositLock(first.bookingId());
        var firstRefund = repo.refund(refundId);
        bookings.depositLock(firstRefund.bookingId());
        var r = repo.lockRefund(refundId);
        var p = repo.lockPayment(r.paymentId());
        if (!"SUCCEEDED".equals(p.status()) || amount < 0 || amount > p.amount() - repo.reserved(p.id(), r.id()))
            throw DomainException.bad("Số tiền hoàn vượt tiền đã nhận.");
        repo.decide(r.id(), version, amount, reason, admin.id(), clock.instant());
        repo.event(p.bookingId(), p.id(), "REFUND_DECIDED", reason, admin.id(), clock.instant(), null);
        return repo.refund(r.id());
    }

    @Transactional(readOnly = true)
    public Object admin(Actor actor) {
        actor.require("ADMIN");
        var history = repo.adminPayments();
        var reserved = repo.reservedAmounts();
        var refunds = repo.adminRefunds();
        Map<String, Long> refundable = new HashMap<>();
        for (var p : history) refundable.put(p.id(), "SUCCEEDED".equals(p.status())
            ? Math.max(0, p.amount() - p.appliedAmount() - reserved.getOrDefault(p.id(), 0L)) : 0L);
        return Map.of("refunds", refunds, "refundPayments", repo.paymentsForRefunds(refunds),
            "unresolved", repo.unresolvedPayments(), "payments", history,
            "refundableAmounts", refundable, "manualRefundPaymentIds", repo.manualRefundPaymentIds(),
            "historyLimit", 200, "sandbox", true);
    }

    @Transactional
    public Object createManualRefund(Actor admin, String paymentId, long amount, String reason) {
        admin.require("ADMIN");
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw DomainException.bad("Cần lý do hoàn tiền, tối đa 1000 ký tự.");
        var first = repo.payment(paymentId).orElseThrow(DomainException::missing);
        bookings.depositLock(first.bookingId());
        var p = repo.lockPayment(paymentId);
        var b = bookings.view(bookings.require(admin, p.bookingId()));
        if (!DepositPolicy.isDemoQrMethod(b.deposit().method())) throw DomainException.conflict("Chỉ áp dụng cho QR DEMO.");
        if (!"SUCCEEDED".equals(p.status())) throw DomainException.conflict("Chỉ tạo hoàn cho giao dịch đã nhận tiền.");
        long available = p.amount() - p.appliedAmount() - repo.reserved(p.id(), null);
        if (amount <= 0 || amount > available)
            throw DomainException.bad("Số tiền hoàn vượt tiền có sẵn.");
        String refundId = id();
        repo.createRefund(refundId, p, amount, "REQUESTED", "MANUAL_EXCESS", reason, clock.instant());
        repo.event(p.bookingId(), p.id(), "REFUND_REQUESTED", reason, admin.id(), clock.instant(), "refund:" + refundId);
        return repo.refund(refundId);
    }

    public Object decideManualRefund(Actor admin, String refundId, long version, long amount, String reason) {
        return decide(admin, refundId, version, amount, reason);
    }

    @Transactional
    public void processManualRefund(Actor admin, String refundId) {
        admin.require("ADMIN");
        var firstRefund = repo.refund(refundId);
        bookings.depositLock(firstRefund.bookingId());
        var r = repo.lockRefund(refundId);
        var p = repo.lockPayment(r.paymentId());
        var b = bookings.view(bookings.require(admin, p.bookingId()));
        if (!DepositPolicy.isDemoQrMethod(b.deposit().method())) throw DomainException.conflict("Chỉ áp dụng cho QR DEMO.");
        if (!"APPROVED".equals(r.status())) throw DomainException.conflict("Chỉ xử lý khoản đã duyệt.");
        repo.refundStatus(refundId, "PROCESSING", null, VnpayGateway.date(clock.instant()));
        repo.event(p.bookingId(), p.id(), "REFUND_PROCESSING", "Admin đang xử lý hoàn tiền thủ công (QR DEMO)", admin.id(), clock.instant(), null);
    }

    @Transactional
    public void completeManualRefund(Actor admin, String refundId, String recipientAccount, String reference, Instant transferTime, long expectedVersion) {
        admin.require("ADMIN");
        var firstRefund = repo.refund(refundId);
        bookings.depositLock(firstRefund.bookingId());
        var r = repo.lockRefund(refundId);
        var p = repo.lockPayment(r.paymentId());
        var b = bookings.view(bookings.require(admin, p.bookingId()));
        if (!DepositPolicy.isDemoQrMethod(b.deposit().method())) throw DomainException.conflict("Chỉ áp dụng cho QR DEMO.");
        if ("SUCCEEDED".equals(r.status())) {
            if (Objects.equals(r.providerTransaction(), reference) && Objects.equals(recipientAccount, p.sourceAccount())
                    && transferTime != null && Objects.equals(r.requestDate(), VnpayGateway.date(transferTime))) return;
            throw DomainException.conflict("Khoản hoàn đã hoàn thành với dữ liệu khác; cần đối soát.");
        }
        if (r.version()!=expectedVersion) throw DomainException.conflict("Khoản hoàn đã thay đổi; tải lại.");
        if (!Objects.equals(recipientAccount,p.sourceAccount())) throw DomainException.bad("Tài khoản nhận phải khớp nguồn tiền đã xác minh.");
        if(r.amount()>p.amount()-repo.reserved(p.id(),r.id())) throw DomainException.conflict("Vượt tiền còn được hoàn.");
        if (!"PROCESSING".equals(r.status())) throw DomainException.conflict("Chỉ hoàn thành khoản đang xử lý.");
        if (recipientAccount == null || recipientAccount.isBlank()) throw DomainException.bad("Thiếu tài khoản nhận.");
        if (reference == null || reference.isBlank() || reference.length()>30) throw DomainException.bad("Thiếu mã tham chiếu hoàn tiền.");
        if (transferTime == null || transferTime.isAfter(clock.instant())) throw DomainException.bad("Thời điểm hoàn không hợp lệ.");

        repo.refundStatus(refundId, "SUCCEEDED", reference, VnpayGateway.date(transferTime));
        repo.event(p.bookingId(), p.id(), "REFUND_SUCCEEDED", "Admin ghi nhận đã hoàn tiền thủ công (QR DEMO) - Tài khoản: " + recipientAccount + ", Tham chiếu: " + reference, admin.id(), clock.instant(), null);
    }
}
