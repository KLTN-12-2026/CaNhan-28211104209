package com.carrental.modules.ai.recommendation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class RecommendationModels {
    private RecommendationModels() {}
    public record Preferences(String transmission, List<String> amenityIds) {}
    public record Criteria(LocalDate pickupDate, LocalDate returnDate, String pickupBranchId,
                           String returnBranchId, Integer passengers, Long budgetVndTotal,
                           Preferences preferences) {}
    public record Request(String requestId, Criteria criteria) {}
    public record Item(String vehicleId, String vehicleName, int seats, String transmission,
                       int rentalDays, long rentalTotal, long oneWayFee, long total,
                       int score, List<String> reasonCodes) {}
    public record Response(String requestId, String status, String rankingVersion, Criteria criteria,
                           List<String> unresolvedFields, List<Item> results, Instant factsAsOf,
                           List<String> warnings) {}
}
