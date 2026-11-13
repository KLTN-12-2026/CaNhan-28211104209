package com.carrental;

import com.carrental.modules.booking.service.QrReconciliationPolicy.*;
import com.carrental.modules.booking.service.QrReconciliationPolicy;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QrReconciliationPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-30T23:50:00+07:00");
    private static final Instant PAY = NOW.plusSeconds(300), REVIEW = NOW.plusSeconds(600);
    private static Booking booking(String status, boolean approved, boolean reported) {
        return new Booking(true, "DEMO_QR_MANUAL", status, 100, REVIEW,
                approved ? NOW.minusSeconds(60) : null, approved ? PAY : null,
                approved ? REVIEW : null, reported ? NOW : null);
    }
    private static Receipt receipt(long amount, long applied, boolean refund) {
        return new Receipt("SUCCEEDED", amount, applied, NOW, NOW, refund);
    }
    private static Summary classify(Booking b, List<Receipt> p, boolean retry, Instant at) {
        return QrReconciliationPolicy.classify(b, p, retry, at);
    }
    @Test void unrelatedDepositsAreNotInvented() {
        for (var b : List.of(new Booking(false,"DEMO_QR_MANUAL","PENDING",0,null,null,null,null,null),
                new Booking(true,"VNPAY_SANDBOX","PENDING",100,REVIEW,null,null,null,null)))
            assertNull(classify(b,List.of(),false,NOW));
    }
    @Test void stagesRespectReportAndMoneyNotReportAsPayment() {
        assertEquals(Stage.AWAITING_APPROVAL,classify(booking("PENDING",false,true),List.of(),false,NOW).stage());
        assertEquals(Stage.AWAITING_TRANSFER,classify(booking("PENDING",true,false),List.of(),false,NOW).stage());
        var reported=classify(booking("PENDING",true,true),List.of(),false,NOW);
        assertEquals(Stage.REPORTED_NO_RECEIPT,reported.stage()); assertEquals(0,reported.receivedAmount());
        var partial=classify(booking("PENDING",true,false),List.of(receipt(40,0,false)),false,NOW);
        assertEquals(Stage.RECEIVED_INCOMPLETE,partial.stage()); assertEquals(60,partial.shortfallAmount());
        var enough=classify(booking("PENDING",true,false),List.of(receipt(40,0,false),receipt(80,0,false)),false,NOW);
        assertEquals(Stage.AWAITING_APPLICATION,enough.stage()); assertEquals(120,enough.eligibleAmount()); assertEquals(0,enough.shortfallAmount());
    }
    @Test void terminalAndExpiryPrecedeAppliedWhileConfirmedDoesNotInventApplied() {
        for (String status : List.of("CANCELLED","REJECTED","RETURNED","COMPLETED")) {
            var s=classify(booking(status,true,true),List.of(receipt(100,100,false)),true,NOW);
            assertEquals(Stage.CLOSED,s.stage()); assertEquals(Urgency.NOT_APPLICABLE,s.urgency()); assertEquals(100,s.appliedAmount());
        }
        assertEquals(Stage.EXPIRED,classify(booking("PENDING",true,true),List.of(receipt(100,100,false)),true,REVIEW).stage());
        for(String status:List.of("CONFIRMED","IN_PROGRESS")) {
            assertEquals(Stage.APPLIED,classify(booking(status,true,true),List.of(receipt(100,100,false)),true,NOW).stage());
            var s=classify(booking(status,true,true),List.of(receipt(100,0,false)),true,NOW);
            assertEquals(Stage.AWAITING_APPLICATION,s.stage()); assertEquals(ProcessingIssue.NONE,s.processingIssue()); assertEquals(Urgency.NOT_APPLICABLE,s.urgency());
        }
    }
    @Test void exactTenMinuteAndVietnamMidnightBoundaries() {
        var b=booking("PENDING",true,false);
        assertEquals(Urgency.NORMAL,classify(b,List.of(),false,NOW.minusNanos(1)).urgency());
        assertEquals(Urgency.DUE_SOON,classify(b,List.of(),false,NOW).urgency());
        assertEquals(Urgency.DUE_SOON,classify(b,List.of(),false,REVIEW.minusNanos(1)).urgency());
        for(var t:List.of(REVIEW,REVIEW.plusNanos(1))) {
            var s=classify(b,List.of(),false,t); assertEquals(Urgency.EXPIRED,s.urgency()); assertEquals(Stage.EXPIRED,s.stage()); assertEquals(t,s.checkedAt());
        }
        assertEquals(Urgency.NOT_APPLICABLE,classify(booking("EXPIRED",true,false),List.of(),false,REVIEW).urgency());
    }
    @Test void deadlinesStrictAndIssuesUniqueOrderedEvenAfterRefund() {
        var rows=List.of(new Receipt("SUCCEEDED",20,0,PAY,REVIEW.minusNanos(1),true),
                new Receipt("SUCCEEDED",30,0,PAY.minusNanos(1),REVIEW,false),
                new Receipt("SUCCEEDED",40,0,PAY.plusNanos(1),REVIEW.plusNanos(1),false));
        var s=classify(booking("COMPLETED",true,true),rows,false,REVIEW);
        assertEquals(List.of(TimingIssue.TRANSFER_LATE,TimingIssue.VERIFICATION_LATE),s.timingIssues());
        assertEquals(90,s.receivedAmount()); assertEquals(0,s.eligibleAmount());
        var timely=classify(booking("PENDING",true,true),List.of(new Receipt("SUCCEEDED",100,0,PAY.minusNanos(1),REVIEW.minusNanos(1),false)),false,NOW);
        assertEquals(100,timely.eligibleAmount()); assertTrue(timely.timingIssues().isEmpty());
    }
    @Test void refundPresenceExcludesReceiptWithoutSubtractingHistoricalMoney() {
        // Projection must set hasRefund for every refund state, including DECLINED.
        var s=classify(booking("PENDING",true,true),List.of(receipt(100,40,true)),false,NOW);
        assertEquals(100,s.receivedAmount()); assertEquals(40,s.appliedAmount()); assertEquals(0,s.eligibleAmount()); assertEquals(100,s.shortfallAmount());
        assertEquals(100,classify(booking("PENDING",true,true),List.of(receipt(100,40,false)),false,NOW).eligibleAmount());
    }
    @Test void missingTimestampsAndNonSuccessCannotProveEligibilityOrLateness() {
        var rows=List.of(new Receipt("SUCCEEDED",10,0,null,NOW,false),new Receipt("SUCCEEDED",20,0,NOW,null,false),
                new Receipt("FAILED",999,0,REVIEW,REVIEW,false),new Receipt("PENDING",999,0,REVIEW,REVIEW,false));
        var s=classify(booking("PENDING",true,true),rows,false,NOW);
        assertEquals(30,s.receivedAmount());assertEquals(0,s.eligibleAmount());assertTrue(s.timingIssues().isEmpty());
        s=classify(booking("PENDING",false,true),List.of(receipt(100,0,false)),false,NOW);
        assertEquals(0,s.eligibleAmount());assertNull(s.reviewDueAt());assertEquals(Urgency.NOT_APPLICABLE,s.urgency());
    }
    @Test void retryClearsAfterRecoveryExpiryClosureOrIneligibleMoney() {
        var b=booking("PENDING",true,true);
        assertEquals(ProcessingIssue.RETRY_REQUIRED,classify(b,List.of(receipt(100,0,false)),true,NOW).processingIssue());
        assertEquals(ProcessingIssue.NONE,classify(b,List.of(receipt(100,0,false)),false,NOW).processingIssue());
        for(var rows:List.of(List.of(receipt(100,100,false)),List.of(receipt(100,0,true)),List.of(receipt(40,0,false))))
            assertEquals(ProcessingIssue.NONE,classify(b,rows,true,NOW).processingIssue());
        assertEquals(ProcessingIssue.NONE,classify(b,List.of(receipt(100,0,false)),true,REVIEW).processingIssue());
    }
    @Test void immutableOutputAndNoInputMutation() {
        var rows=new ArrayList<>(List.of(receipt(100,0,false))); var before=List.copyOf(rows);
        var s=classify(booking("PENDING",true,true),rows,true,NOW);
        assertEquals(before,rows); rows.clear(); assertEquals(100,s.receivedAmount());
        assertThrows(UnsupportedOperationException.class,()->s.timingIssues().add(TimingIssue.TRANSFER_LATE));
    }
    @Test void overflowAndInvalidSuccessfulAmountsFailRatherThanInventZero() {
        assertThrows(ArithmeticException.class,()->classify(booking("PENDING",true,true),List.of(receipt(Long.MAX_VALUE,0,false),receipt(1,0,false)),false,NOW));
        assertThrows(IllegalArgumentException.class,()->classify(booking("PENDING",true,true),List.of(receipt(10,11,false)),false,NOW));
        assertThrows(IllegalArgumentException.class,()->classify(booking("PENDING",true,true),List.of(receipt(-1,0,false)),false,NOW));
    }
}
