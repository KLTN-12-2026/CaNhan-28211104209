package com.carrental.modules.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import com.carrental.exception.DomainException;
import org.springframework.stereotype.Component;

/** Saved terms and return assessment; collection and schedule changes are separate workflows. */
@Component
public class ReturnPolicy {
    public static final String VERSION = "same-branch-return-v1";
    public static final String TEXT = "Trả sớm không tự giảm tiền thuê hoặc hoàn cọc; lịch giữ đến hết ngày trả đã đặt. "
        + "Trả muộn tính thêm mỗi ngày Việt Nam vượt ngày trả đã đặt theo giá/ngày đã lưu, không thêm tiền phạt. "
        + "Cọc giữ chỗ được tính vào tiền thuê, không phải cọc bảo đảm. Khoản vượt hạn không tự được coi là đã thanh toán.";
    public record Terms(long dailyPriceVnd, String timeZone, String lateDayRule, String earlyReturnRule, String text) {}
    public record View(String version, Terms terms, Instant acceptedAt) {}
    public record Assessment(LocalDate bookedReturnDate, LocalDate actualReturnDate, Instant actualReturnedAt,
                             String timing, int lateDays, long dailyPriceVnd, long amountVnd, String collectionStatus) {}
    private final ObjectMapper json;
    public ReturnPolicy(ObjectMapper json) { this.json = json; }
    public String snapshot(long dailyPrice) {
        try { return json.writeValueAsString(new Terms(dailyPrice, "Asia/Ho_Chi_Minh", "VIETNAM_CALENDAR_DAYS_AFTER_RETURN_DATE", "KEEP_BOOKED_PRICE_AND_RESERVED_END", TEXT)); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public View view(String version, String snapshot, Instant acceptedAt) {
        if (version == null && snapshot == null && acceptedAt == null) return null;
        if (version == null || snapshot == null) throw new IllegalStateException("Incomplete return policy snapshot");
        try { return new View(version, json.readValue(snapshot, Terms.class), acceptedAt); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid return policy snapshot", e); }
    }
    public Assessment assess(View policy, Instant bookedEnd, Instant now) {
        if (!VERSION.equals(policy.version()) || !"Asia/Ho_Chi_Minh".equals(policy.terms().timeZone())
            || !"VIETNAM_CALENDAR_DAYS_AFTER_RETURN_DATE".equals(policy.terms().lateDayRule())
            || !"KEEP_BOOKED_PRICE_AND_RESERVED_END".equals(policy.terms().earlyReturnRule()) || policy.acceptedAt() == null)
            throw DomainException.conflict("Chính sách đã lưu chưa được hỗ trợ để nhận trả. Cần kiểm tra hồ sơ.");
        var booked = bookedEnd.atZone(RentalTerms.VIETNAM).toLocalDate();
        var actual = now.atZone(RentalTerms.VIETNAM).toLocalDate();
        int days = Math.toIntExact(Math.max(0, ChronoUnit.DAYS.between(booked, actual)));
        long amount = Math.multiplyExact(policy.terms().dailyPriceVnd(), days);
        return new Assessment(booked, actual, now.truncatedTo(ChronoUnit.MICROS),
            actual.isBefore(booked) ? "EARLY" : days == 0 ? "ON_TIME" : "LATE", days,
            policy.terms().dailyPriceVnd(), amount, amount > 0 ? "UNPAID" : "NOT_DUE");
    }
    public String snapshot(Assessment assessment) {
        try { return json.writeValueAsString(assessment); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public Assessment assessment(String snapshot) {
        if (snapshot == null) return null;
        try { return json.readValue(snapshot, Assessment.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid return assessment", e); }
    }
}
