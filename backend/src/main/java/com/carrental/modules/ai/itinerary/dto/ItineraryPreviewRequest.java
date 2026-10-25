package com.carrental.modules.ai.itinerary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

public record ItineraryPreviewRequest(
    @NotNull LocalDate tripDate,
    @NotBlank String startTime,
    @NotBlank String endTime,
    @NotBlank String startLocationId,
    String preferredCategoryId
) {
    public LocalTime parsedStartTime() {
        return parseTime(startTime, "Giờ bắt đầu");
    }

    public LocalTime parsedEndTime() {
        return parseTime(endTime, "Giờ kết thúc");
    }

    private static LocalTime parseTime(String val, String label) {
        if (val == null || !val.matches("^(?:[01][0-9]|2[0-3]):[0-5][0-9]$")) {
            throw com.carrental.exception.DomainException.bad(label + " phải có định dạng HH:mm.");
        }
        try {
            return LocalTime.parse(val);
        } catch (DateTimeParseException e) {
            throw com.carrental.exception.DomainException.bad(label + " không hợp lệ.");
        }
    }
}
