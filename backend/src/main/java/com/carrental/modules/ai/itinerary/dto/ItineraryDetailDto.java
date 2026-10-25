package com.carrental.modules.ai.itinerary.dto;

import java.time.Instant;
import java.util.List;

public record ItineraryDetailDto(
    String id,
    String title,
    Instant createdAt,
    ItineraryPreviewResponse.CriteriaInfo criteria,
    String tripDate,
    List<ItineraryPreviewResponse.ScheduleItem> items,
    ItineraryPreviewResponse.Totals totals,
    String fingerprint
) {}
