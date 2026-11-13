package com.carrental.modules.booking.service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Read-only classification of ONE booking; caller supplies authorized projections and backend time. */
public final class QrReconciliationPolicy {
    private QrReconciliationPolicy() {}

    public enum Stage { CLOSED, EXPIRED, APPLIED, AWAITING_APPROVAL, AWAITING_APPLICATION,
        RECEIVED_INCOMPLETE, REPORTED_NO_RECEIPT, AWAITING_TRANSFER }
    public enum Urgency { NOT_APPLICABLE, NORMAL, DUE_SOON, EXPIRED }
    public enum ProcessingIssue { NONE, RETRY_REQUIRED }
    public enum TimingIssue { TRANSFER_LATE, VERIFICATION_LATE }

    public record Booking(boolean required, String method, String status, long amount,
                          Instant expiresAt, Instant approvedAt, Instant paymentDueAt,
                          Instant reviewDueAt, Instant reportedAt) {}
    /** hasRefund means ANY refund row, including DECLINED, matching settleQr's current predicate. */
    public record Receipt(String status, long amount, long appliedAmount,
                          Instant transferTime, Instant verifiedAt, boolean hasRefund) {}
    public record Summary(Instant checkedAt, Instant reviewDueAt, Stage stage, Urgency urgency,
                          long receivedAmount, long appliedAmount, long eligibleAmount,
                          long shortfallAmount, ProcessingIssue processingIssue, List<TimingIssue> timingIssues) {
        public Summary { timingIssues = List.copyOf(timingIssues); }
    }

    /** Null means not applicable; API adapters must omit the field rather than invent a QR state. */
    public static Summary classify(Booking b, List<Receipt> receipts, boolean hasRetryEvent, Instant now) {
        Objects.requireNonNull(b); Objects.requireNonNull(receipts); Objects.requireNonNull(now);
        if (!b.required() || !DepositPolicy.isDemoQrMethod(b.method())) return null;
        if (b.amount() <= 0) throw new IllegalArgumentException("Required QR deposit must be positive");
        String status = Objects.requireNonNull(b.status());
        if ("PENDING".equals(status) && !Objects.requireNonNull(b.expiresAt()).isAfter(now)) status = "EXPIRED";
        long received = 0, applied = 0, eligible = 0;
        var timing = EnumSet.noneOf(TimingIssue.class);
        for (var p : receipts) {
            if (!"SUCCEEDED".equals(p.status())) continue;
            if (p.amount() <= 0 || p.appliedAmount() < 0 || p.appliedAmount() > p.amount())
                throw new IllegalArgumentException("Invalid receipt amounts");
            received = Math.addExact(received, p.amount());
            applied = Math.addExact(applied, p.appliedAmount());
            if (p.transferTime() != null && b.paymentDueAt() != null && !p.transferTime().isBefore(b.paymentDueAt()))
                timing.add(TimingIssue.TRANSFER_LATE);
            if (p.verifiedAt() != null && b.reviewDueAt() != null && !p.verifiedAt().isBefore(b.reviewDueAt()))
                timing.add(TimingIssue.VERIFICATION_LATE);
            if (b.approvedAt() != null && b.paymentDueAt() != null && b.reviewDueAt() != null
                    && p.transferTime() != null && p.verifiedAt() != null
                    && p.transferTime().isBefore(b.paymentDueAt()) && p.verifiedAt().isBefore(b.reviewDueAt()) && !p.hasRefund())
                eligible = Math.addExact(eligible, p.amount());
        }
        Stage stage;
        if (Set.of("CANCELLED", "REJECTED", "RETURNED", "COMPLETED").contains(status)) stage = Stage.CLOSED;
        else if ("EXPIRED".equals(status)) stage = Stage.EXPIRED;
        else if (applied >= b.amount()) stage = Stage.APPLIED;
        else if (b.approvedAt() == null) stage = Stage.AWAITING_APPROVAL;
        else if (eligible >= b.amount()) stage = Stage.AWAITING_APPLICATION;
        else if (received > 0) stage = Stage.RECEIVED_INCOMPLETE;
        else if (b.reportedAt() != null) stage = Stage.REPORTED_NO_RECEIPT;
        else stage = Stage.AWAITING_TRANSFER;

        Urgency urgency = Urgency.NOT_APPLICABLE;
        if ("PENDING".equals(b.status()) && b.approvedAt() != null && applied < b.amount() && b.reviewDueAt() != null) {
            if (!now.isBefore(b.reviewDueAt())) urgency = Urgency.EXPIRED;
            else if (Duration.between(now, b.reviewDueAt()).compareTo(Duration.ofMinutes(10)) <= 0) urgency = Urgency.DUE_SOON;
            else urgency = Urgency.NORMAL;
        }
        var issue = "PENDING".equals(status) && stage == Stage.AWAITING_APPLICATION && hasRetryEvent
                ? ProcessingIssue.RETRY_REQUIRED : ProcessingIssue.NONE;
        return new Summary(now, b.reviewDueAt(), stage, urgency, received, applied, eligible,
                Math.max(0, b.amount() - eligible), issue, List.copyOf(timing));
    }
}
