package com.carrental.modules.ai.itinerary.service;

import com.carrental.modules.location.service.CatalogRevision;
import java.util.List;

public final class ItineraryFingerprint {
    public static final String CONTRACT_VERSION = "kdrive-itinerary-v1";
    public static final int PLAN_VERSION = 1;

    private ItineraryFingerprint() {}

    public static String compute(
            String tripDate,
            String startTime,
            String endTime,
            String startLocationId,
            String preferredCategoryId,
            List<String> orderedLocationIds,
            List<String> orderedRevisions,
            List<Integer> dwellMinutes,
            List<Integer> waitMinutes,
            List<Integer> travelMinutes,
            List<Double> distanceMeters) {
        return CatalogRevision.of(
            CONTRACT_VERSION,
            Integer.toString(PLAN_VERSION),
            tripDate != null ? tripDate : "",
            startTime != null ? startTime : "",
            endTime != null ? endTime : "",
            startLocationId != null ? startLocationId : "",
            preferredCategoryId != null ? preferredCategoryId : "",
            String.join(",", orderedLocationIds),
            String.join(",", orderedRevisions),
            dwellMinutes.stream().map(Object::toString).reduce("", (a, b) -> a + ";" + b),
            waitMinutes.stream().map(Object::toString).reduce("", (a, b) -> a + ";" + b),
            travelMinutes.stream().map(m -> m == null ? "null" : m.toString()).reduce("", (a, b) -> a + ";" + b),
            distanceMeters.stream().map(d -> d == null ? "null" : d.toString()).reduce("", (a, b) -> a + ";" + b)
        );
    }
}
