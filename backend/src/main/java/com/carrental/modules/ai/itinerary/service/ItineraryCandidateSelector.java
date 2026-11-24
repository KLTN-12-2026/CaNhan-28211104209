package com.carrental.modules.ai.itinerary.service;

import com.carrental.modules.location.service.LocationService.PublicView;
import java.util.*;
import java.util.function.Predicate;

/**
 * Chooses the finite candidate set for one-day itinerary planning. Input is the ACTIVE catalog.
 * The start location is kept first; other locations with complete facts follow, preferred category
 * first, each group by ascending ID, capped at {@link #MAX_CANDIDATES}. The result does not depend on
 * catalog order or names. Excluded and truncated locations are reported, never silently dropped.
 */
public final class ItineraryCandidateSelector {
    public static final int MAX_CANDIDATES = 8;

    public enum Reason { MISSING_METADATA, MISSING_DURATION, NOT_OPEN_ON_DATE }
    public record Excluded(String locationId, Reason reason) {}
    /**
     * @param candidates start first, then at most {@code MAX_CANDIDATES - 1} others
     * @param eligibleCount locations with complete facts, start included when eligible
     * @param truncatedIds eligible locations left out by the cap, in selection order
     */
    public record Selection(List<PublicView> candidates, List<Excluded> excluded, int eligibleCount, List<String> truncatedIds) {
        public boolean truncated() { return !truncatedIds.isEmpty(); }
    }

    private ItineraryCandidateSelector() {}

    public static Selection select(List<PublicView> activeCatalog, PublicView start, String preferredCategoryId,
                                   Predicate<PublicView> openOnTripDate) {
        Objects.requireNonNull(start, "start");
        boolean preference = preferredCategoryId != null && !preferredCategoryId.isBlank();
        var excluded = new ArrayList<Excluded>();
        var eligibleOthers = new ArrayList<PublicView>();
        boolean startEligible = false;
        for (PublicView view : activeCatalog) {
            Reason reason = reason(view, openOnTripDate);
            if (reason != null) { excluded.add(new Excluded(view.id(), reason)); continue; }
            if (view.id().equals(start.id())) startEligible = true;
            else eligibleOthers.add(view);
        }
        excluded.sort(Comparator.comparing(Excluded::locationId));
        eligibleOthers.sort(Comparator
            .comparing((PublicView v) -> preference && preferredCategoryId.equals(v.categoryId()) ? 0 : 1)
            .thenComparing(PublicView::id));
        var candidates = new ArrayList<PublicView>();
        candidates.add(start);
        int room = MAX_CANDIDATES - 1;
        candidates.addAll(eligibleOthers.subList(0, Math.min(room, eligibleOthers.size())));
        var truncated = eligibleOthers.size() > room
            ? eligibleOthers.subList(room, eligibleOthers.size()).stream().map(PublicView::id).toList()
            : List.<String>of();
        return new Selection(List.copyOf(candidates), List.copyOf(excluded),
            eligibleOthers.size() + (startEligible ? 1 : 0), truncated);
    }

    private static Reason reason(PublicView view, Predicate<PublicView> openOnTripDate) {
        if (view.metadata() == null) return Reason.MISSING_METADATA;
        Integer duration = view.metadata().durationMinutes();
        if (duration == null || duration <= 0) return Reason.MISSING_DURATION;
        if (!openOnTripDate.test(view)) return Reason.NOT_OPEN_ON_DATE;
        return null;
    }
}
