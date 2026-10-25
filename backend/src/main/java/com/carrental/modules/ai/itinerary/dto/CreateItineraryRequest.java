package com.carrental.modules.ai.itinerary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record CreateItineraryRequest(
    @NotBlank @Size(min = 1, max = 200) String title,
    @NotBlank String fingerprint,
    @NotNull LocalDate tripDate,
    @NotBlank String startTime,
    @NotBlank String endTime,
    @NotBlank String startLocationId,
    String preferredCategoryId,
    @NotEmpty @Size(min = 2, max = 4) List<String> orderedLocationIds
) {}
