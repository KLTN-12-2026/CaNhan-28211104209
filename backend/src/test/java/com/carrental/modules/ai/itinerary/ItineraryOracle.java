package com.carrental.modules.ai.itinerary;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Test-only oracle for one-day itineraries on static synthetic facts (scenarios-v1/facts.json shape).
 * Written from the scenario specification (scripts/ai/evaluation/scenarios-v1/README.md), not from
 * ItineraryPreviewService: it never calls production scheduling, search or ranking code.
 *
 * Rules: the first stop is reached at startTime; a stop begins at the earliest time >= arrival such that
 * the whole duration fits inside one opening interval (waiting is allowed); departure = begin + duration;
 * a departure after endTime, at/after midnight, or a missing route fails the order; leg durations are
 * rounded up to whole minutes; overnight intervals carried from the previous day count; a missing day is
 * UNKNOWN (no intervals). No approach or return leg.
 */
public final class ItineraryOracle {
    public static final String OPENING_WINDOW = "OPENING_WINDOW", END_TIME = "END_TIME", DAY_BOUNDARY = "DAY_BOUNDARY", NO_ROUTE = "NO_ROUTE";

    public record Interval(int start, int end) {}
    public record Item(String locationId, String arrival, String departure, int waitMinutes, Integer travelMinutes, Long distanceMeters) {}
    /** cause == null means the order is feasible. */
    public record Outcome(List<String> order, String cause, List<Item> items) {
        public boolean feasible() { return cause == null; }
    }

    private final Map<String, JsonNode> locations = new LinkedHashMap<>();
    private final Map<String, JsonNode> legs = new HashMap<>();
    private final LocalDate tripDate;
    private final int startMinute, endMinute;
    private final String startId;

    public ItineraryOracle(JsonNode scenario, LocalDate tripDate, String startTime, String endTime, String startId) {
        for (JsonNode l : scenario.path("locations")) locations.put(l.path("id").asText(), l);
        for (JsonNode leg : scenario.path("legs")) legs.put(leg.path("source").asText() + "->" + leg.path("target").asText(), leg);
        this.tripDate = tripDate;
        this.startMinute = minutes(startTime);
        this.endMinute = minutes(endTime);
        this.startId = startId;
    }

    public static ItineraryOracle forCase(JsonNode scenario, JsonNode input) {
        return new ItineraryOracle(scenario, LocalDate.parse(input.path("tripDate").asText()),
            input.path("startTime").asText(), input.path("endTime").asText(), input.path("startLocationId").asText());
    }

    static int minutes(String hhmm) {
        LocalTime t = LocalTime.parse(hhmm);
        return t.getHour() * 60 + t.getMinute();
    }

    static String clock(int minute) { return String.format("%02d:%02d", minute / 60, minute % 60); }

    /** Opening intervals in minutes relative to trip-day midnight; previous-day overnight hours included. */
    List<Interval> intervals(String locationId) {
        JsonNode meta = locations.get(locationId).path("metadata");
        if (meta.isMissingNode() || meta.isNull()) return List.of();
        var result = new ArrayList<Interval>();
        for (int delta : new int[]{-1, 0}) {
            LocalDate day = tripDate.plusDays(delta);
            JsonNode hours = meta.path("exceptions").path(day.toString());
            if (hours.isMissingNode() || hours.isNull()) hours = meta.path("weekly").path(day.getDayOfWeek().name());
            String state = hours.isMissingNode() || hours.isNull() ? "UNKNOWN" : hours.path("state").asText();
            if ("OPEN_24H".equals(state) && delta == 0) result.add(new Interval(0, 1440));
            if (!"INTERVALS".equals(state)) continue;
            for (JsonNode iv : hours.path("intervals")) {
                int start = minutes(iv.path("start").asText()) + delta * 1440;
                int end = minutes(iv.path("end").asText()) + delta * 1440 + (iv.path("nextDay").asBoolean() ? 1440 : 0);
                if (end > 0) result.add(new Interval(Math.max(start, 0), end));
            }
        }
        result.sort(Comparator.comparingInt(Interval::start).thenComparingInt(Interval::end));
        return result;
    }

    private Integer duration(String id) {
        JsonNode d = locations.get(id).path("metadata").path("durationMinutes");
        return d.isIntegralNumber() ? d.asInt() : null;
    }

    /** ACTIVE locations with metadata, a duration and at least one opening interval on the trip date. */
    public SortedSet<String> candidatePool() {
        var pool = new TreeSet<String>();
        for (var e : locations.entrySet()) {
            JsonNode l = e.getValue();
            if (!"ACTIVE".equals(l.path("status").asText())) continue;
            if (l.path("metadata").isNull() || l.path("metadata").isMissingNode() || duration(e.getKey()) == null) continue;
            if (!intervals(e.getKey()).isEmpty()) pool.add(e.getKey());
        }
        return pool;
    }

    /** Facts that block planning, in the proof's vocabulary. */
    public SortedSet<String> missingFacts() {
        var missing = new TreeSet<String>();
        for (var e : locations.entrySet()) {
            JsonNode meta = e.getValue().path("metadata");
            if (meta.isNull() || meta.isMissingNode()) missing.add(e.getKey() + ".metadata");
            else if (duration(e.getKey()) == null) missing.add(e.getKey() + ".durationMinutes");
            else if (intervals(e.getKey()).isEmpty()) missing.add(e.getKey() + ".hours");
        }
        var pool = candidatePool();
        if (pool.size() < 2 && pool.contains(startId)) missing.add("minimum_two_candidates");
        return missing;
    }

    public Outcome schedule(List<String> order) {
        int current = startMinute;
        var items = new ArrayList<Item>();
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i);
            int dwell = duration(id);
            Integer begin = null;
            for (Interval iv : intervals(id)) {
                int candidate = Math.max(current, iv.start());
                if (candidate + dwell <= iv.end() && (begin == null || candidate < begin)) begin = candidate;
            }
            if (begin == null) return new Outcome(order, OPENING_WINDOW, List.of());
            int departure = begin + dwell;
            if (departure >= 1440) return new Outcome(order, DAY_BOUNDARY, List.of());
            if (departure > endMinute) return new Outcome(order, END_TIME, List.of());
            Integer travel = null;
            Long distance = null;
            if (i + 1 < order.size()) {
                JsonNode leg = legs.get(id + "->" + order.get(i + 1));
                if (leg == null || "NO_ROUTE".equals(leg.path("status").asText())) return new Outcome(order, NO_ROUTE, List.of());
                travel = (int) ((leg.path("durationSeconds").asLong() + 59) / 60);
                distance = leg.path("distanceMeters").asLong();
            }
            items.add(new Item(id, clock(current), clock(departure), begin - current, travel, distance));
            current = departure + (travel == null ? 0 : travel);
            if (current >= 1440) return new Outcome(order, DAY_BOUNDARY, List.of());
        }
        return new Outcome(order, null, List.copyOf(items));
    }

    /** Every order of 2..4 distinct candidates that begins at the fixed start, in lexicographic order. */
    public List<List<String>> orders() {
        var others = new ArrayList<>(candidatePool());
        if (!others.remove(startId)) return List.of();
        var out = new ArrayList<List<String>>();
        extend(new ArrayList<>(List.of(startId)), others, out);
        return out;
    }

    private static void extend(List<String> prefix, List<String> others, List<List<String>> out) {
        if (prefix.size() >= 4) return;
        for (String next : others) {
            if (prefix.contains(next)) continue;
            prefix.add(next);
            out.add(List.copyOf(prefix));
            extend(prefix, others, out);
            prefix.remove(prefix.size() - 1);
        }
    }

    public List<Outcome> enumerate() { return orders().stream().map(this::schedule).toList(); }

    public List<List<String>> feasibleOrders() { return enumerate().stream().filter(Outcome::feasible).map(Outcome::order).toList(); }
}
