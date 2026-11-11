package com.carrental.modules.booking.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Read-only classification of a complete schedule for ONE vehicle, using the caller's backend clock. */
public final class ScheduleRiskPolicy {
    private ScheduleRiskPolicy() {}

    public enum Kind { RETURN_DUE_NEXT_DAY_BOOKING, OVERDUE, PREDECESSOR_NOT_RETURNED }

    public record Risk(Kind kind, String bookingId, String relatedBookingId,
                       LocalDate bookedReturnDate, long lateDays) {}

    public static List<Risk> classify(List<SchedulePolicy.Slot> schedule, Instant now) {
        var rows = schedule.stream().filter(s -> s.pickup().equals(s.destination()))
                .sorted(Comparator.comparing(SchedulePolicy.Slot::start)
                        .thenComparing(SchedulePolicy.Slot::id)).toList();
        var risks = new LinkedHashSet<Risk>();
        LocalDate today = now.atZone(RentalTerms.VIETNAM).toLocalDate();
        for (var active : rows) {
            if (!"IN_PROGRESS".equals(active.status())) continue;
            LocalDate returnDate = active.end().atZone(RentalTerms.VIETNAM).toLocalDate();
            boolean overdue = SchedulePolicy.isOverdue(active, now);
            long lateDays = Math.max(0, ChronoUnit.DAYS.between(returnDate, today));
            if (overdue) risks.add(new Risk(Kind.OVERDUE, active.id(), null, returnDate, lateDays));
            for (var next : rows) {
                if (next.id().equals(active.id()) || !next.pickup().equals(active.destination())
                        || next.start().isBefore(active.end()) || !awaitingPickup(next, now)) continue;
                if (overdue) {
                    risks.add(new Risk(Kind.PREDECESSOR_NOT_RETURNED, next.id(), active.id(), returnDate, lateDays));
                } else if (today.equals(returnDate)
                        && next.start().atZone(RentalTerms.VIETNAM).toLocalDate().equals(returnDate.plusDays(1))) {
                    risks.add(new Risk(Kind.RETURN_DUE_NEXT_DAY_BOOKING, active.id(), next.id(), returnDate, 0));
                }
            }
        }
        return List.copyOf(risks);
    }

    private static boolean awaitingPickup(SchedulePolicy.Slot slot, Instant now) {
        return "CONFIRMED".equals(slot.status()) || ("PENDING".equals(slot.status())
                && slot.expires() != null && slot.expires().isAfter(now));
    }
}
