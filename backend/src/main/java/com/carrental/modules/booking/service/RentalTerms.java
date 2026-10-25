package com.carrental.modules.booking.service;

import com.carrental.exception.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public final class RentalTerms {
    private RentalTerms() {}
    public static final String CALENDAR_POLICY = "calendar-2026-10-17";
    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    public record CalendarPeriod(Instant start, Instant end, int days, Instant holdUntil) {}
    public static CalendarPeriod calendar(LocalDate pickup, LocalDate returned, Instant now) {
        if (pickup == null || returned == null || pickup.isBefore(now.atZone(VIETNAM).toLocalDate())
                || !returned.isAfter(pickup) || ChronoUnit.DAYS.between(pickup, returned) > 90) {
            throw DomainException.bad("Ngày nhận từ hôm nay; ngày trả sau ngày nhận, tối đa 90 ngày thuê.");
        }
        Instant start = pickup.atStartOfDay(VIETNAM).toInstant();
        // TIMESTAMP(6): reserve the entire return day, without rounding into the next day.
        Instant end = returned.plusDays(1).atStartOfDay(VIETNAM).toInstant().minusNanos(1000);
        Instant pickupDeadline = pickup.plusDays(1).atStartOfDay(VIETNAM).toInstant().minusNanos(1000);
        Instant hold = now.plus(Duration.ofHours(2));
        return new CalendarPeriod(start, end, Math.toIntExact(ChronoUnit.DAYS.between(pickup, returned)),
                hold.isBefore(pickupDeadline) ? hold : pickupDeadline);
    }

    public static int days(Instant start, Instant end, Instant now) {
        if (start == null || end == null || !start.isAfter(now) || !end.isAfter(start)
                || Duration.between(start, end).compareTo(Duration.ofDays(90)) > 0) {
            throw DomainException.bad("Khoảng thuê phải ở tương lai, thời lượng dương và không quá 90 ngày.");
        }
        Duration duration = Duration.between(start, end);
        long seconds = duration.getSeconds();
        return Math.toIntExact(seconds / 86400 + (seconds % 86400 != 0 || duration.getNano() != 0 ? 1 : 0));
    }

    public static long rent(long dailyPrice, int days) { return Math.multiplyExact(dailyPrice, days); }
    public static long total(long rent, long routeFee) { return Math.addExact(rent, routeFee); }
    public static Instant holdUntil(Instant start, Instant now) {
        Instant limit = now.plus(Duration.ofHours(2));
        return limit.isBefore(start) ? limit : start;
    }
}
