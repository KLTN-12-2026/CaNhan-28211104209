package com.carrental.modules.ai.itinerary.dto;

import java.time.Instant;

public record ItinerarySummaryDto(
    String id,
    String title,
    String tripDate,
    int locationCount,
    int totalDwellMinutes,
    int totalTravelMinutes,
    double totalDistanceMeters,
    Instant createdAt
) {}
