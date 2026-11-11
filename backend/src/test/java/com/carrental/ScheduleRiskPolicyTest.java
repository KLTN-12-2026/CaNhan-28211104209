package com.carrental;

import com.carrental.modules.booking.service.SchedulePolicy.Slot;
import com.carrental.modules.booking.service.SchedulePolicy;
import com.carrental.modules.booking.service.ScheduleRiskPolicy;
import com.carrental.modules.booking.service.ScheduleRiskPolicy.Risk;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static com.carrental.modules.booking.service.ScheduleRiskPolicy.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleRiskPolicyTest {
    private static Instant time(String local) { return Instant.parse(local + "+07:00"); }
    private static final LocalDate RETURN_DATE = LocalDate.of(2026, 10, 27);
    private static final Slot A = slot("A", "IN_PROGRESS", "2026-10-25T00:00:00", "2026-10-27T23:59:59.999999", true);
    private static final Slot B = slot("B", "CONFIRMED", "2026-10-28T00:00:00", "2026-10-29T23:59:59.999999", true);
    private static Slot slot(String id, String status, String start, String end, boolean calendar) {
        return new Slot(id, "DN", "DN", time(start), time(end), status, null, calendar);
    }
    private static Risk risk(ScheduleRiskPolicy.Kind kind, String id, String related, long days) {
        return new Risk(kind, id, related, RETURN_DATE, days);
    }
    @Test void noWarningBeforeReturnDay() {
        assertEquals(List.of(), ScheduleRiskPolicy.classify(List.of(A, B), time("2026-10-26T23:59:59.999999")));
    }
    @Test void earlyWarningStartsAtVietnamMidnightAndDoesNotMeanOverdue() {
        assertEquals(List.of(risk(RETURN_DUE_NEXT_DAY_BOOKING, "A", "B", 0)),
                ScheduleRiskPolicy.classify(List.of(B, A), time("2026-10-27T00:00:00")));
    }
    @Test void calendarLastMicrosecondAndNanosecondAreNotOverdue() {
        for (String value : List.of("2026-10-27T23:59:59.999999", "2026-10-27T23:59:59.999999999")) {
            assertEquals(List.of(risk(RETURN_DUE_NEXT_DAY_BOOKING, "A", "B", 0)),
                    ScheduleRiskPolicy.classify(List.of(A, B), time(value)));
        }
    }
    @Test void overdueStartsNextDayEvenWhenUtcDateIsStillPreviousDay() {
        assertEquals(List.of(risk(OVERDUE, "A", null, 1), risk(PREDECESSOR_NOT_RETURNED, "B", "A", 1)),
                ScheduleRiskPolicy.classify(List.of(A, B), Instant.parse("2026-10-27T17:00:00Z")));
    }
    @Test void onlyAdjacentPickupGetsEarlyWarningButAllLaterLiveBookingsGetOverdueRisk() {
        var c = slot("C", "CONFIRMED", "2026-10-30T00:00:00", "2026-11-01T23:59:59.999999", true);
        assertTrue(ScheduleRiskPolicy.classify(List.of(A, c), time("2026-10-27T12:00:00")).isEmpty());
        assertEquals(List.of(risk(OVERDUE, "A", null, 3), risk(PREDECESSOR_NOT_RETURNED, "B", "A", 3),
                        risk(PREDECESSOR_NOT_RETURNED, "C", "A", 3)),
                ScheduleRiskPolicy.classify(List.of(c, B, A, B), time("2026-10-30T12:00:00")));
    }
    @Test void pendingExpiresAtExactBoundary() {
        var now = time("2026-10-27T12:00:00");
        for (Instant expiry : List.of(now.minusSeconds(1), now, now.plusSeconds(1))) {
            var pending = new Slot("B", "DN", "DN", B.start(), B.end(), "PENDING", expiry, true);
            assertEquals(expiry.isAfter(now) ? List.of(risk(RETURN_DUE_NEXT_DAY_BOOKING, "A", "B", 0)) : List.of(),
                    ScheduleRiskPolicy.classify(List.of(A, pending), now));
        }
        var missingExpiry = new Slot("B", "DN", "DN", B.start(), B.end(), "PENDING", null, true);
        assertTrue(ScheduleRiskPolicy.classify(List.of(A, missingExpiry), now).isEmpty());
    }
    @Test void terminalBookingsAreNotAffectedSuccessors() {
        for (String status : List.of("CANCELLED", "REJECTED", "EXPIRED", "RETURNED", "COMPLETED")) {
            var closed = new Slot("B", "DN", "DN", B.start(), B.end(), status, null, true);
            assertEquals(List.of(risk(OVERDUE, "A", null, 1)),
                    ScheduleRiskPolicy.classify(List.of(A, closed), time("2026-10-28T10:00:00")));
        }
    }
    @Test void returnedOrCompletedPredecessorClearsRiskWithoutChangingReservation() {
        for (String status : List.of("RETURNED", "COMPLETED")) {
            var returned = new Slot("A", "DN", "DN", A.start(), A.end(), status, null, true);
            assertTrue(ScheduleRiskPolicy.classify(List.of(returned, B), time("2026-10-27T10:00:00")).isEmpty());
            assertTrue(SchedulePolicy.occupies(returned, time("2026-10-27T10:00:00")));
            assertTrue(ScheduleRiskPolicy.classify(List.of(returned, B), time("2026-10-28T10:00:00")).isEmpty());
        }
    }
    @Test void legacyUsesExactEndNotEndOfDayAndDoesNotChargeMoney() {
        var legacy = slot("A", "IN_PROGRESS", "2026-10-25T10:00:00", "2026-10-27T10:00:00", false);
        assertTrue(ScheduleRiskPolicy.classify(List.of(legacy), time("2026-10-27T09:59:59.999999")).isEmpty());
        assertEquals(List.of(risk(OVERDUE, "A", null, 0)),
                ScheduleRiskPolicy.classify(List.of(legacy), time("2026-10-27T10:00:00")));
    }
    @Test void differentBranchAndOneWayTripsAreOutsideClassifierScope() {
        var elsewhere = new Slot("B", "HA", "HA", B.start(), B.end(), "CONFIRMED", null, true);
        var oneWay = new Slot("A", "DN", "HA", A.start(), A.end(), "IN_PROGRESS", null, true);
        assertEquals(List.of(risk(OVERDUE, "A", null, 1)),
                ScheduleRiskPolicy.classify(List.of(A, elsewhere), time("2026-10-28T10:00:00")));
        assertTrue(ScheduleRiskPolicy.classify(List.of(oneWay, elsewhere), time("2026-10-28T10:00:00")).isEmpty());
    }
    @Test void historicalBookingsAndSelfAreNotSuccessorsAndResultIsImmutable() {
        var old = slot("OLD", "CONFIRMED", "2026-10-20T00:00:00", "2026-10-22T23:59:59.999999", true);
        var input = List.of(old, A, A);
        var result = ScheduleRiskPolicy.classify(input, time("2026-10-28T10:00:00"));
        assertEquals(List.of(risk(OVERDUE, "A", null, 1)), result);
        assertEquals(List.of(old, A, A), input);
        assertThrows(UnsupportedOperationException.class, result::clear);
    }
    @Test void emptyScheduleHasNoRisk() {
        assertTrue(ScheduleRiskPolicy.classify(List.of(), time("2026-10-28T10:00:00")).isEmpty());
    }
}
