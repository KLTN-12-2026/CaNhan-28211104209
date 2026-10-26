package com.carrental.modules.location.service;

import com.carrental.exception.DomainException;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Curated facts, not model output. Intervals belong to their starting local date. */
public record VisitMetadata(String timezone, Map<DayOfWeek, Hours> weekly,
        Map<LocalDate, Hours> exceptions,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=WholeMinutes.class) Integer durationMinutes,
        Evidence hoursEvidence, Evidence durationEvidence) {
    public static class WholeMinutes extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
        @Override public Integer deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Integer.class,"Thời lượng phải là số nguyên.");
            return parser.getIntValue();
        }
    }
    public enum State { UNKNOWN, CLOSED, OPEN_24H, INTERVALS }
    public record Interval(String start, String end, boolean nextDay) {}
    public record Hours(State state, List<Interval> intervals) {}
    public record Evidence(String url, Instant verifiedAt) {}

    public VisitMetadata validated(Clock clock) {
        if (!"Asia/Ho_Chi_Minh".equals(timezone)) bad("Múi giờ phải là Asia/Ho_Chi_Minh.");
        if (weekly == null || exceptions == null || weekly.size() > 7 || exceptions.size() > 60)
            bad("Lịch tuần hoặc ngày ngoại lệ không hợp lệ (tối đa 60 ngày ngoại lệ).");
        var days = new EnumMap<DayOfWeek, Hours>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) days.put(day, validateHours(weekly.getOrDefault(day, new Hours(State.UNKNOWN, List.of()))));
        var special = new TreeMap<LocalDate, Hours>();
        exceptions.forEach((date, hours) -> {
            if (date == null || date.getYear() < 1900 || date.getYear() > 2200) bad("Ngày ngoại lệ không hợp lệ.");
            special.put(date, validateHours(hours));
        });
        for (DayOfWeek day : DayOfWeek.values()) checkBoundary(days.get(day.minus(1)), days.get(day));
        special.forEach((date, hours) -> {
            checkBoundary(special.getOrDefault(date.minusDays(1), days.get(date.minusDays(1).getDayOfWeek())), hours);
            checkBoundary(hours, special.getOrDefault(date.plusDays(1), days.get(date.plusDays(1).getDayOfWeek())));
        });
        boolean known = days.values().stream().anyMatch(h -> h.state() != State.UNKNOWN)
            || special.values().stream().anyMatch(h -> h.state() != State.UNKNOWN);
        if (known && hoursEvidence == null) bad("Cần nguồn kiểm chứng cho lịch mở cửa.");
        validateEvidence(hoursEvidence, clock);
        validateEvidence(durationEvidence, clock);
        if (durationMinutes != null && (durationMinutes < 1 || durationMinutes > 1440 || durationEvidence == null))
            bad("Thời lượng ghé phải từ 1 đến 1440 phút và có nguồn kiểm chứng.");
        if (durationMinutes == null && durationEvidence != null) bad("Nguồn thời lượng phải đi kèm thời lượng ghé.");
        return new VisitMetadata(timezone, Collections.unmodifiableMap(days), Collections.unmodifiableMap(special),
            durationMinutes, hoursEvidence, durationEvidence);
    }

    public Hours hoursOn(LocalDate date) {
        return exceptions.getOrDefault(date, weekly.getOrDefault(date.getDayOfWeek(), new Hours(State.UNKNOWN, List.of())));
    }

    private static Hours validateHours(Hours hours) {
        if (hours == null || hours.state() == null || hours.intervals() == null || hours.intervals().size() > 4)
            bad("Trạng thái hoặc khoảng giờ mở cửa không hợp lệ.");
        if (hours.state() != State.INTERVALS) {
            if (!hours.intervals().isEmpty()) bad("Chỉ lịch theo khung giờ mới có khoảng mở cửa.");
            return new Hours(hours.state(), List.of());
        }
        if (hours.intervals().isEmpty()) bad("Cần ít nhất một khoảng giờ mở cửa.");
        int previousEnd = -1;
        for (Interval interval : hours.intervals()) {
            if (interval == null) bad("Khoảng giờ không hợp lệ.");
            int start = minute(interval.start()), end = minute(interval.end()) + (interval.nextDay() ? 1440 : 0);
            if (end <= start || end - start > 1440 || start < previousEnd)
                bad("Khoảng giờ phải tăng dần, không trùng nhau và dài tối đa 24 giờ.");
            previousEnd = end;
        }
        return new Hours(hours.state(), List.copyOf(hours.intervals()));
    }

    private static int minute(String time) {
        if (time == null || !time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) bad("Giờ mở/đóng phải theo HH:mm.");
        LocalTime parsed = LocalTime.parse(time);
        return parsed.getHour() * 60 + parsed.getMinute();
    }

    private static void checkBoundary(Hours previous, Hours current) {
        if (previous.state() != State.INTERVALS || current.state() == State.UNKNOWN || current.state() == State.CLOSED) return;
        int firstStart = current.state() == State.OPEN_24H ? 0 : minute(current.intervals().get(0).start());
        for (Interval interval : previous.intervals())
            if (interval.nextDay() && minute(interval.end()) > firstStart)
                bad("Khung giờ qua đêm trùng với lịch bắt đầu ngày kế tiếp.");
    }

    private static void validateEvidence(Evidence evidence, Clock clock) {
        if (evidence == null) return;
        try {
            URI uri = URI.create(evidence.url());
            if (evidence.url().length() > 500 || !List.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null || evidence.verifiedAt() == null
                    || evidence.verifiedAt().isAfter(clock.instant()) || evidence.verifiedAt().isBefore(Instant.parse("2000-01-01T00:00:00Z")))
                bad("Nguồn phải là URL http/https và thời điểm kiểm chứng hợp lệ, không ở tương lai.");
        } catch (IllegalArgumentException | NullPointerException error) {
            bad("Nguồn kiểm chứng không hợp lệ.");
        }
    }

    private static void bad(String message) { throw DomainException.bad(message); }
}
