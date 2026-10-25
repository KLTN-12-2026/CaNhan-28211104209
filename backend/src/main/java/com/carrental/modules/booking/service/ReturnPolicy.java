package com.carrental.modules.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Snapshot only. Charging, collection and schedule changes are not enabled by this policy. */
@Component
public class ReturnPolicy {
    public static final String VERSION = "same-branch-return-v1";
    public static final String TEXT = "Trả sớm không tự giảm tiền thuê hoặc hoàn cọc; lịch giữ đến hết ngày trả đã đặt. "
        + "Trả muộn tính thêm mỗi ngày Việt Nam vượt ngày trả đã đặt theo giá/ngày đã lưu, không thêm tiền phạt. "
        + "Cọc giữ chỗ được tính vào tiền thuê, không phải cọc bảo đảm. Khoản vượt hạn không tự được coi là đã thanh toán.";
    public record Terms(long dailyPriceVnd, String timeZone, String lateDayRule, String earlyReturnRule, String text) {}
    public record View(String version, Terms terms, Instant acceptedAt) {}
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
}
