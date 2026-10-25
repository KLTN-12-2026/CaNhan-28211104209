package com.carrental.modules.operation.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.booking.service.DepositPolicy;
import com.carrental.modules.contract.service.ContractService;
import com.carrental.modules.payment.repository.PaymentRepository;
import com.carrental.modules.payment.service.*;
import com.carrental.security.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Objects;

/** Network calls deliberately execute outside the transaction template. */
@Service
public class DepositWorkflow {
    private final BookingService bookings;
    private final ContractService contracts;
    private final PaymentRepository repo;
    private final PaymentService payments;
    private final VnpayGateway gateway;
    private final PaymentSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DepositWorkflow(BookingService bookings, ContractService contracts, PaymentRepository repo,
                           PaymentService payments, VnpayGateway gateway, PaymentSettings settings,
                           PlatformTransactionManager manager, Clock clock) {
        this.bookings = bookings; this.contracts = contracts; this.repo = repo;
        this.payments = payments; this.gateway = gateway; this.settings = settings;
        this.tx = new TransactionTemplate(manager); this.clock = clock;
    }

    public Object approve(Actor staff, String id, long version) {
        return tx.execute(s -> {
            var b = bookings.approveDeposit(staff, id, version);
            String eventKind = DepositPolicy.isDemoQrMethod(b.deposit().method()) ? "DEPOSIT_APPROVED_QR" : "DEPOSIT_APPROVED";
            String eventNote = DepositPolicy.isDemoQrMethod(b.deposit().method())
                ? "Duyệt hồ sơ và tạo mã QR cọc giả lập"
                : "Duyệt hồ sơ và yêu cầu đóng cọc VNPAY";
            repo.event(id, null, eventKind, eventNote, staff.id(), clock.instant(), null);
            return b;
        });
    }

    public Object noShow(Actor staff, String id, long version, boolean contacted, boolean notDelivered, String reason) {
        return tx.execute(s -> {
            var b = bookings.noShow(staff, id, version, contacted, notDelivered, reason);
            payments.cancellation(b);
            return b;
        });
    }

    public void notification(Map<String, String> fields) {
        settle(payments.receive(gateway.notification(fields)));
    }

    public void settle(PaymentRepository.Payment payment) {
        if (!"SUCCEEDED".equals(payment.status())) return;
        var b = bookings.view(bookings.require(new Actor("system", "ADMIN", "workflow"), payment.bookingId()));
        if (DepositPolicy.isDemoQrMethod(b.deposit().method())) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> {
                bookings.depositLock(payment.bookingId());
                var p = repo.lockPayment(payment.id());
                if (p.applied() || repo.refundForPayment(p.id()).isPresent()) return;
                var b2 = bookings.confirmPaidDeposit(p.bookingId(), p.id());
                contracts.issueDeposit(b2);
                repo.applied(p.id());
                repo.event(b2.id(), p.id(), "DEPOSIT_APPLIED", "Đã xác nhận đơn và phát hành hợp đồng", null, clock.instant(), "applied:" + p.id());
            });
        } catch (DomainException e) {
            payments.queueUnappliedRefund(payment.id());
        }
    }

    public void reconcile(Actor admin, String paymentId) {
        admin.require("ADMIN");
        reconcile(paymentId);
    }

    public void reconcile(String paymentId) {
        var p = repo.payment(paymentId).orElseThrow(DomainException::missing);
        var b = bookings.view(bookings.require(new Actor("system", "ADMIN", "workflow"), p.bookingId()));
        if (DepositPolicy.isDemoQrMethod(b.deposit().method())) {
            return;
        }
        if ("SUCCEEDED".equals(p.status())) {
            settle(p);
            return;
        }
        settle(payments.receive(gateway.query(p, clock.instant())));
    }

    public void processRefund(Actor admin, String id) {
        admin.require("ADMIN");
        processRefund(id);
    }

    public void reconcileRefund(Actor admin, String id) {
        admin.require("ADMIN");
        settings.requireConfigured();
        var r = repo.refund(id);
        if (!Set.of("UNKNOWN", "PROCESSING").contains(r.status())) throw DomainException.conflict("Khoản hoàn không cần đối soát.");
        var p = repo.payment(r.paymentId()).orElseThrow();
        var b = bookings.view(bookings.require(new Actor("system", "ADMIN", "workflow"), r.bookingId()));
        if (DepositPolicy.isDemoQrMethod(b.deposit().method())) {
            throw DomainException.conflict("Khoản QR giả lập không đối soát qua VNPAY; Admin xử lý thủ công.");
        }
        var result = gateway.queryRefund(p, r.amount(), r.requestDate(), clock.instant());
        tx.executeWithoutResult(s -> {
            bookings.depositLock(r.bookingId());
            var current = repo.lockRefund(id);
            if (current.version() != r.version()) throw DomainException.conflict("Khoản hoàn đã thay đổi; tải lại.");
            String status = result.completed() ? "SUCCEEDED" : result.rejected() ? "FAILED" : "UNKNOWN";
            repo.refundStatus(id, status, result.transaction(), r.requestDate());
            repo.event(r.bookingId(), r.paymentId(), "REFUND_RECONCILED", "Kết quả truy vấn khoản hoàn: " + status, admin.id(), clock.instant(), null);
        });
    }

    public void processRefund(String id) {
        settings.requireConfigured();
        var reserved = tx.execute(s -> {
            var first = repo.refund(id);
            bookings.depositLock(first.bookingId());
            var r = repo.lockRefund(id);
            var p = repo.lockPayment(r.paymentId());
            var b = bookings.view(bookings.require(new Actor("system", "ADMIN", "workflow"), r.bookingId()));
            if (DepositPolicy.isDemoQrMethod(b.deposit().method())) {
                throw DomainException.conflict("Khoản QR giả lập không hoàn qua VNPAY; Admin xử lý thủ công.");
            }
            if (!"APPROVED".equals(r.status())) throw DomainException.conflict("Chỉ gửi yêu cầu đã duyệt; không tự gửi lại giao dịch chưa rõ kết quả.");
            if (!"SUCCEEDED".equals(p.status()) || r.amount() <= 0 || r.amount() > p.amount() - repo.reserved(p.id(), r.id()))
                throw DomainException.conflict("Khoản hoàn không hợp lệ.");
            repo.refundStatus(id, "PROCESSING", null, VnpayGateway.date(clock.instant()));
            return repo.refund(id);
        });
        String status = "UNKNOWN", provider = null;
        try {
            var p = repo.payment(reserved.paymentId()).orElseThrow();
            var result = gateway.refund(p, reserved.id(), reserved.amount(), reserved.requestDate());
            status = result.completed() ? "SUCCEEDED" : result.rejected() ? "FAILED" : "UNKNOWN";
            provider = result.transaction();
        } catch (IllegalStateException e) {
        }
        String finalStatus = status, finalProvider = provider;
        tx.executeWithoutResult(s -> {
            bookings.depositLock(reserved.bookingId());
            var r = repo.lockRefund(id);
            if (!"PROCESSING".equals(r.status())) throw DomainException.conflict("Yêu cầu hoàn đã thay đổi.");
            repo.refundStatus(id, finalStatus, finalProvider, r.requestDate());
            repo.event(r.bookingId(), r.paymentId(), "REFUND_" + finalStatus, "Kết quả hoàn VNPAY Sandbox: " + finalStatus, null, clock.instant(), null);
        });
    }

    public Object verifyQrDeposit(Actor staff, String bookingId, BookingService.VerifyInput input) {
        var payment = payments.recordQr(staff,bookingId,input); // Commits before applying or issuing a contract.
        settleQr(bookingId,payment.id());
        return bookings.view(bookings.require(staff,bookingId));
    }

    public void settleQr(String bookingId,String paymentId) {
        try {
            tx.executeWithoutResult(s -> {
                var b=bookings.depositLock(bookingId);
                if (!DepositPolicy.isDemoQrMethod(b.deposit().method())) return;
                if (!"PENDING".equals(b.status())) {
                    payments.qrRefund(bookingId,"CONFIRMED".equals(b.status()) ? "EXCESS" : "UNAPPLIED",!"CONFIRMED".equals(b.status()));
                    return;
                }
                var eligible=repo.forBooking(bookingId).stream().filter(p -> "SUCCEEDED".equals(p.status()) &&
                    p.transferTime()!=null && p.transferTime().isBefore(b.deposit().paymentDueAt()) &&
                    p.verifiedAt().isBefore(b.deposit().reviewDueAt()) && repo.refunds(bookingId).stream().noneMatch(r -> r.paymentId().equals(p.id())))
                    .sorted(Comparator.comparing(PaymentRepository.Payment::transferTime).thenComparing(PaymentRepository.Payment::id)).toList();
                long received=eligible.stream().mapToLong(PaymentRepository.Payment::amount).reduce(0,Math::addExact);
                if(received < b.deposit().amount()) {
                    // Any ineligible receipt is refundable; partial timely money continues to hold until reviewDueAt.
                    for(var p:repo.forBooking(bookingId)) if(eligible.stream().noneMatch(e -> e.id().equals(p.id()))) payments.qrRefund(bookingId,"LATE",false);
                    return;
                }
                long remaining=b.deposit().amount();
                for(var p:eligible) { long amount=Math.min(p.amount(),remaining);repo.updateAppliedAmount(p.id(),amount);remaining-=amount; }
                var confirmed=bookings.confirmQrDeposit(bookingId,paymentId);
                contracts.issueDeposit(confirmed);
                repo.event(bookingId,paymentId,"DEPOSIT_APPLIED","Đủ cọc, xác nhận đơn và phát hành hợp đồng",null,clock.instant(),"applied-booking:"+bookingId);
                payments.qrRefund(bookingId,"EXCESS",false);
            });
        } catch(DomainException e) {
            payments.qrRefund(bookingId,"UNAPPLIED",true);
        } catch(RuntimeException e) {
            // Persisted receipts stay visible and retryable after infrastructure/contract failure.
            tx.executeWithoutResult(s -> repo.event(bookingId,paymentId,"DEPOSIT_RETRY","Chưa thể áp dụng cọc; cần đối soát trước khi thử lại",null,clock.instant(),null));
        }
    }
}
