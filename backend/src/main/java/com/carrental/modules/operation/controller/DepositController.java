package com.carrental.modules.operation.controller;

import com.carrental.exception.DomainException;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.booking.service.DepositPolicy;
import com.carrental.modules.operation.service.DepositWorkflow;
import com.carrental.modules.payment.service.PaymentService;
import com.carrental.modules.payment.service.QrGenerator;
import com.carrental.security.Actor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class DepositController {
    private final PaymentService payments;
    private final DepositWorkflow workflow;
    private final BookingService bookings;
    private final QrGenerator qrGenerator;

    public DepositController(PaymentService payments, DepositWorkflow workflow, BookingService bookings, QrGenerator qrGenerator) {
        this.payments = payments;
        this.workflow = workflow;
        this.bookings = bookings;
        this.qrGenerator = qrGenerator;
    }

    public record Approval(long expectedVersion) {}
    public record NoShow(long expectedVersion, boolean contacted, boolean notDelivered, String reason) {}
    public record Decision(long expectedVersion,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = WholeVnd.class) long amount, String reason) {}
    public record ReportInput(String transactionRef, java.time.Instant transferTime, String transferNote, String sourceAccount, String idempotencyKey) {}
    public record VerifyInput(String transactionRef,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = WholeVnd.class) long amountVnd,
        java.time.Instant transferTime, String transferNote, String sourceAccount, String idempotencyKey, long expectedVersion, boolean verified) {}
    public static class WholeVnd extends com.fasterxml.jackson.databind.JsonDeserializer<Long> {
        @Override public Long getNullValue(com.fasterxml.jackson.databind.DeserializationContext context)
                throws com.fasterxml.jackson.databind.JsonMappingException {
            throw com.fasterxml.jackson.databind.exc.InvalidFormatException.from(context.getParser(),
                "Cần cung cấp số tiền VND nguyên.", null, Long.class);
        }
        @Override public Object getAbsentValue(com.fasterxml.jackson.databind.DeserializationContext context)
                throws com.fasterxml.jackson.databind.JsonMappingException {
            return getNullValue(context);
        }
        @Override public Long deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))
                throw com.fasterxml.jackson.databind.exc.InvalidFormatException.from(parser,
                    "Số tiền VND phải là số nguyên JSON.", parser.getText(), Long.class);
            return parser.getLongValue();
        }
    }
    public record ManualRefundInput(String paymentId,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = WholeVnd.class) long amount, String reason) {}
    public record ManualRefundDecideInput(long expectedVersion,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = WholeVnd.class) long amount, String reason) {}
    public record ManualRefundCompleteInput(String recipientAccount, String reference, java.time.Instant transferTime, long expectedVersion) {}

    @GetMapping("/bookings/{id}/deposit")
    public Object summary(@AuthenticationPrincipal Actor actor, @PathVariable String id) {
        return payments.summary(actor, id);
    }

    @GetMapping("/bookings/{id}/deposit/qr")
    public ResponseEntity<Resource> qrImage(@AuthenticationPrincipal Actor actor, @PathVariable String id) {
        var b = bookings.view(bookings.require(actor, id));
        if (!b.deposit().required()) throw DomainException.conflict("Đơn không yêu cầu cọc.");
        if (!DepositPolicy.isDemoQrMethod(b.deposit().method())) throw DomainException.conflict("Đơn không dùng phương thức QR giả lập.");
        if (b.deposit().approvedAt() == null) throw DomainException.conflict("Đơn chưa được duyệt hồ sơ.");

        String payload = QrGenerator.buildDemoPayload(BookingService.requestCode(b.id()), b.deposit().amount());
        byte[] png = qrGenerator.generatePng(payload, 300);
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .body(new ByteArrayResource(png));
    }

    @GetMapping("/bookings/{id}/deposit/qr-payload")
    public Object qrPayload(@AuthenticationPrincipal Actor actor, @PathVariable String id) {
        var b = bookings.view(bookings.require(actor, id));
        if (!b.deposit().required()) throw DomainException.conflict("Đơn không yêu cầu cọc.");
        if (!DepositPolicy.isDemoQrMethod(b.deposit().method())) throw DomainException.conflict("Đơn không dùng phương thức QR giả lập.");
        if (b.deposit().approvedAt() == null) throw DomainException.conflict("Đơn chưa được duyệt hồ sơ.");

        return Map.of(
            "payload", QrGenerator.buildDemoPayload(BookingService.requestCode(b.id()), b.deposit().amount()),
            "requestCode", BookingService.requestCode(b.id()),
            "amount", b.deposit().amount(),
            "receiverSnapshot", b.deposit().receiverSnapshot(),
            "paymentDueAt", b.deposit().paymentDueAt(),
            "reviewDueAt", b.deposit().reviewDueAt(),
            "originalHoldUntil", b.deposit().originalHoldUntil(),
            "warning", "Mô phỏng — Không chuyển tiền thật"
        );
    }

    @PostMapping("/bookings/{id}/deposit/approve")
    public Object approve(@AuthenticationPrincipal Actor actor, @PathVariable String id, @RequestBody Approval input) {
        return workflow.approve(actor, id, input.expectedVersion());
    }

    @PostMapping("/bookings/{id}/deposit/session")
    public Object session(@AuthenticationPrincipal Actor actor, @PathVariable String id, HttpServletRequest request) {
        return payments.start(actor, id, request.getRemoteAddr());
    }

    @PostMapping("/bookings/{id}/deposit/report")
    public Object reportDeposit(@AuthenticationPrincipal Actor actor, @PathVariable String id, @RequestBody ReportInput input) {
        return bookings.reportDeposit(actor, id, new BookingService.ReportInput(
            input.transactionRef(), input.transferTime(), input.transferNote(), input.sourceAccount(), input.idempotencyKey()
        ));
    }

    @PostMapping("/bookings/{id}/deposit/verify")
    public Object verifyDeposit(@AuthenticationPrincipal Actor actor, @PathVariable String id, @RequestBody VerifyInput input) {
        return workflow.verifyQrDeposit(actor, id, new BookingService.VerifyInput(
            input.transactionRef(), input.amountVnd(), input.transferTime(), input.transferNote(),
            input.sourceAccount(), input.idempotencyKey(), input.expectedVersion(), input.verified()
        ));
    }

    @PostMapping("/bookings/{id}/no-show")
    public Object noShow(@AuthenticationPrincipal Actor actor, @PathVariable String id, @RequestBody NoShow input) {
        return workflow.noShow(actor, id, input.expectedVersion(), input.contacted(), input.notDelivered(), input.reason());
    }

    @GetMapping("/admin/payments")
    public Object admin(@AuthenticationPrincipal Actor actor) {
        return payments.admin(actor);
    }

    @PostMapping("/admin/payments/{id}/reconcile")
    public void reconcile(@AuthenticationPrincipal Actor actor, @PathVariable String id) {
        workflow.reconcile(actor, id);
    }

    @PostMapping("/admin/refunds/{id}/decide")
    public Object decide(@AuthenticationPrincipal Actor actor, @PathVariable String id, @RequestBody Decision input) {
        return payments.decide(actor, id, input.expectedVersion(), input.amount(), input.reason());
    }

    @PostMapping("/admin/refunds/{id}/process")
    public void process(@AuthenticationPrincipal Actor actor, @PathVariable String id) {
        workflow.processRefund(actor, id);
    }

    @PostMapping("/admin/refunds/{id}/reconcile")
    public void reconcileRefund(@AuthenticationPrincipal Actor actor, @PathVariable String id) {
        workflow.reconcileRefund(actor, id);
    }

    @PostMapping("/admin/refunds/create-manual")
    public Object createManualRefund(@AuthenticationPrincipal Actor actor, @RequestBody ManualRefundInput input) {
        return payments.createManualRefund(actor, input.paymentId(), input.amount(), input.reason());
    }

    @PostMapping("/admin/refunds/{id}/decide-manual")
    public Object decideManualRefund(@AuthenticationPrincipal Actor actor, @PathVariable String id, @RequestBody ManualRefundDecideInput input) {
        return payments.decideManualRefund(actor, id, input.expectedVersion(), input.amount(), input.reason());
    }

    @PostMapping("/admin/refunds/{id}/process-manual")
    public void processManualRefund(@AuthenticationPrincipal Actor actor, @PathVariable String id) {
        payments.processManualRefund(actor, id);
    }

    @PostMapping("/admin/refunds/{id}/complete-manual")
    public void completeManualRefund(@AuthenticationPrincipal Actor actor, @PathVariable String id, @RequestBody ManualRefundCompleteInput input) {
        payments.completeManualRefund(actor, id, input.recipientAccount(), input.reference(), input.transferTime(), input.expectedVersion());
    }

    @GetMapping("/payments/vnpay/ipn")
    public Object ipn(@RequestParam MultiValueMap<String, String> query) {
        try {
            if (query.values().stream().anyMatch(v -> v.size() != 1)) throw DomainException.bad("Tham số bị lặp.");
            workflow.notification(query.toSingleValueMap());
            return Map.of("RspCode", "00", "Message", "Confirmed");
        } catch (PaymentService.AmountMismatch e) {
            return Map.of("RspCode", "04", "Message", "Invalid amount");
        } catch (DomainException e) {
            return Map.of("RspCode", e.status.value() == 404 ? "01" : "97", "Message", "Invalid notification");
        } catch (RuntimeException e) {
            return Map.of("RspCode", "99", "Message", "Retry reconciliation");
        }
    }
}
