package com.carrental.modules.ai.itinerary.dto;

import java.util.List;

public record ItineraryPreviewResponse(
    Status status,
    CriteriaInfo criteria,
    List<String> orderedLocationIds,
    List<ScheduleItem> items,
    Totals totals,
    String fingerprint,
    List<String> notices,
    String planSource,
    CandidateSet candidateSet
) {
    /** Response without plan context: no plan attempted yet. */
    public ItineraryPreviewResponse(Status status, CriteriaInfo criteria, List<String> orderedLocationIds,
            List<ScheduleItem> items, Totals totals, String fingerprint, List<String> notices) {
        this(status, criteria, orderedLocationIds, items, totals, fingerprint, notices, null, null);
    }

    public ItineraryPreviewResponse withPlanContext(String planSource, CandidateSet candidateSet) {
        return new ItineraryPreviewResponse(status, criteria, orderedLocationIds, items, totals, fingerprint, notices,
            planSource, candidateSet);
    }

    /** QWEN: the planner's order fits. BACKEND_SEARCH: the backend examined the candidate set after it did not. */
    public static final String PLAN_SOURCE_QWEN = "QWEN";
    public static final String PLAN_SOURCE_BACKEND_SEARCH = "BACKEND_SEARCH";

    /** Finite set used for planning; excluded locations carry the missing or closed fact. */
    public record CandidateSet(int eligibleCount, List<String> candidateIds, List<ExcludedLocation> excluded,
                               List<String> truncatedLocationIds) {}
    public record ExcludedLocation(String locationId, String reason) {}

    public enum Status {
        VALIDATED,
        NEEDS_DATA,
        NO_FEASIBLE_PLAN,
        UNAVAILABLE
    }

    public record CriteriaInfo(
        String contractVersion,
        int planVersion,
        String tripDate,
        String startTime,
        String endTime,
        String startLocationId,
        String startLocationName,
        String preferredCategoryId,
        String fingerprint
    ) {
        public CriteriaInfo(
            String contractVersion,
            int planVersion,
            String tripDate,
            String startTime,
            String endTime,
            String startLocationId,
            String startLocationName,
            String preferredCategoryId
        ) {
            this(contractVersion, planVersion, tripDate, startTime, endTime, startLocationId, startLocationName, preferredCategoryId, null);
        }
    }

    public record ScheduleItem(
        int sortOrder,
        String locationId,
        String locationName,
        String categoryName,
        double latitude,
        double longitude,
        String arrival,
        String departure,
        Integer dwellMinutes,
        Integer waitMinutes,
        Integer travelToNextMinutes,
        Double distanceToNextMeters,
        String hoursState,
        String fullHoursDescription,
        String hoursEvidenceUrl,
        String hoursVerifiedAt,
        String durationEvidenceUrl,
        String durationVerifiedAt,
        String osmAttribution,
        String contractVersion,
        com.carrental.modules.location.service.VisitMetadata visitMetadata
    ) {
        public ScheduleItem(
            int sortOrder,
            String locationId,
            String locationName,
            String categoryName,
            double latitude,
            double longitude,
            String arrival,
            String departure,
            Integer dwellMinutes,
            Integer waitMinutes,
            Integer travelToNextMinutes,
            Double distanceToNextMeters,
            String hoursState,
            String fullHoursDescription,
            String hoursEvidenceUrl,
            String hoursVerifiedAt,
            String durationEvidenceUrl,
            String durationVerifiedAt,
            String osmAttribution,
            String contractVersion
        ) {
            this(sortOrder, locationId, locationName, categoryName, latitude, longitude, arrival, departure,
                 dwellMinutes, waitMinutes, travelToNextMinutes, distanceToNextMeters, hoursState,
                 fullHoursDescription, hoursEvidenceUrl, hoursVerifiedAt, durationEvidenceUrl, durationVerifiedAt,
                 osmAttribution, contractVersion, null);
        }
    }

    public record Totals(
        int totalDwellMinutes,
        int totalWaitMinutes,
        int totalTravelMinutes,
        double totalDistanceMeters,
        int locationCount
    ) {}
}
