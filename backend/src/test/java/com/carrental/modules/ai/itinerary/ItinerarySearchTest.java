package com.carrental.modules.ai.itinerary;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.carrental.exception.DomainException;
import com.carrental.modules.ai.AiRequestDeadline;
import com.carrental.modules.ai.itinerary.dto.ItineraryPreviewRequest;
import com.carrental.modules.ai.itinerary.dto.ItineraryPreviewResponse;
import com.carrental.modules.ai.itinerary.dto.ItineraryPreviewResponse.Status;
import com.carrental.modules.ai.itinerary.service.ItineraryPreviewService;
import com.carrental.modules.ai.itinerary.service.QwenItinerarySequencePlanner;
import com.carrental.modules.ai.routing.OsrmRoutingClient;
import com.carrental.modules.location.service.LocationService;
import com.carrental.modules.location.service.LocationService.PublicView;
import com.carrental.modules.location.service.VisitMetadata;
import com.carrental.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * G6.3: when Qwen's order does not fit, the backend examines the finite candidate set. Results are judged
 * against the independent ItineraryOracle (G6.1) on the synthetic scenario facts, plus targeted cases for
 * tie-breaking, directional leg memo and provider failures. Providers are test doubles answering from facts.
 */
class ItinerarySearchTest {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final Actor USER = new Actor("u1", "USER", "Khách");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2027-02-01T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    static JsonNode scenarios;
    static List<JsonNode> cases;

    @BeforeAll static void load() throws Exception {
        Path data = Path.of("..", "scripts", "ai", "evaluation", "scenarios-v1").toAbsolutePath().normalize();
        scenarios = JSON.readTree(data.resolve("facts.json").toFile()).path("scenarios");
        cases = new ArrayList<>();
        for (String line : Files.readAllLines(data.resolve("itinerary.jsonl"))) if (!line.isBlank()) cases.add(JSON.readTree(line));
    }

    /** Routing double: answers 2-point and multi-point requests from a directed leg table; records every call. */
    static final class Facts {
        final Map<String, PublicView> views = new LinkedHashMap<>();
        final Map<String, String> byPoint = new HashMap<>();
        final Map<String, double[]> legs = new HashMap<>(); // from->to : {distance, seconds}; absent/NO_ROUTE = missing
        final List<List<String>> calls = new ArrayList<>();
        /** After this many calls every further call is UNAVAILABLE (models OSRM or the 60s budget failing mid-request). */
        int unavailableAfterCalls = Integer.MAX_VALUE;
        /** Like real OSRM through intermediate waypoints: a multi-point request makes each leg longer than its pair route. */
        double multiPointExtraMeters, multiPointExtraSeconds;

        void add(PublicView v) { views.put(v.id(), v); byPoint.put(v.latitude().doubleValue() + "," + v.longitude().doubleValue(), v.id()); }
        void leg(String from, String to, double meters, double seconds) { legs.put(from + "->" + to, new double[]{meters, seconds}); }

        OsrmRoutingClient.Estimate route(List<OsrmRoutingClient.Point> points) {
            var ids = points.stream().map(p -> byPoint.get(p.latitude() + "," + p.longitude())).toList();
            calls.add(ids);
            if (calls.size() > unavailableAfterCalls) return new OsrmRoutingClient.Estimate(OsrmRoutingClient.Status.UNAVAILABLE, null, null, null, List.of());
            boolean multi = ids.size() > 2;
            var out = new ArrayList<OsrmRoutingClient.Leg>();
            double meters = 0, seconds = 0;
            for (int i = 0; i + 1 < ids.size(); i++) {
                double[] leg = legs.get(ids.get(i) + "->" + ids.get(i + 1));
                if (leg == null) return new OsrmRoutingClient.Estimate(OsrmRoutingClient.Status.NO_ROUTE, null, null, null, List.of());
                double m = leg[0] + (multi ? multiPointExtraMeters : 0), s = leg[1] + (multi ? multiPointExtraSeconds : 0);
                out.add(new OsrmRoutingClient.Leg(m, s));
                meters += m; seconds += s;
            }
            return new OsrmRoutingClient.Estimate(OsrmRoutingClient.Status.VERIFIED, meters, seconds, "OSM", out);
        }
        long pairCalls(String from, String to) { return calls.stream().filter(c -> c.equals(List.of(from, to))).count(); }
    }

    static ItineraryPreviewService service(Facts facts, LocationService locations, QwenItinerarySequencePlanner planner) {
        var routing = mock(OsrmRoutingClient.class);
        when(routing.route(anyList(), any(AiRequestDeadline.class))).thenAnswer(a -> facts.route(a.getArgument(0)));
        when(locations.activeCatalog(USER)).thenAnswer(a -> List.copyOf(facts.views.values()));
        return new ItineraryPreviewService(locations, routing, planner, CLOCK);
    }

    static Facts fromScenario(JsonNode scenario) throws Exception {
        var facts = new Facts();
        int n = 0;
        for (JsonNode l : scenario.path("locations")) {
            if (!"ACTIVE".equals(l.path("status").asText())) continue;
            VisitMetadata meta = l.path("metadata").isNull() ? null : JSON.treeToValue(l.path("metadata"), VisitMetadata.class);
            n++;
            facts.add(new PublicView(l.path("id").asText(), l.path("categoryId").asText(), l.path("categoryId").asText(),
                "Điểm " + l.path("id").asText(), new BigDecimal("16." + (1000 + n)), new BigDecimal("108." + (2000 + n)), meta));
        }
        for (JsonNode leg : scenario.path("legs"))
            if (!"NO_ROUTE".equals(leg.path("status").asText()))
                facts.leg(leg.path("source").asText(), leg.path("target").asText(), leg.path("distanceMeters").asDouble(), leg.path("durationSeconds").asDouble());
        return facts;
    }

    static QwenItinerarySequencePlanner proposing(List<String> order) {
        var planner = mock(QwenItinerarySequencePlanner.class);
        when(planner.planSequence(anyString(), anyList(), anyInt(), any(AiRequestDeadline.class)))
            .thenReturn(new QwenItinerarySequencePlanner.SequenceResult(order, "QWEN_OPTIMIZED"));
        return planner;
    }

    static List<String> strings(JsonNode a) { var out = new ArrayList<String>(); a.forEach(n -> out.add(n.asText())); return out; }

    /** Independent ranking over oracle-feasible orders: preferred count desc, size desc, travel+wait asc, IDs asc. */
    static List<String> bestByRule(ItineraryOracle oracle, JsonNode scenario, String preferred) {
        Map<String, String> category = new HashMap<>();
        scenario.path("locations").forEach(l -> category.put(l.path("id").asText(), l.path("categoryId").asText()));
        Comparator<ItineraryOracle.Outcome> rule = Comparator
            .comparingInt((ItineraryOracle.Outcome o) -> -(int) o.order().stream().filter(id -> preferred != null && preferred.equals(category.get(id))).count())
            .thenComparingInt(o -> -o.order().size())
            .thenComparingInt(o -> o.items().stream().mapToInt(i -> i.waitMinutes() + (i.travelMinutes() == null ? 0 : i.travelMinutes())).sum())
            .thenComparing(o -> String.join("\u0000", o.order()));
        return oracle.enumerate().stream().filter(ItineraryOracle.Outcome::feasible).min(rule).map(ItineraryOracle.Outcome::order).orElse(null);
    }

    @Test void everyScenarioMatchesTheOracleAndTieBreakRule() throws Exception {
        int checked = 0, searched = 0;
        for (JsonNode c : cases) {
            String category = c.path("category").asText();
            if (!Set.of("FEASIBLE", "INFEASIBLE", "NEEDS_DATA").contains(category)) continue;
            JsonNode scenario = scenarios.path(c.path("fixtureRef").asText());
            JsonNode input = c.path("input");
            var facts = fromScenario(scenario);
            var proposed = strings(scenario.path("proposedOrder"));
            var service = service(facts, mock(LocationService.class), proposing(proposed));
            String preferred = input.path("preferredCategoryId").isNull() ? null : input.path("preferredCategoryId").asText();
            var response = service.preview(USER, new ItineraryPreviewRequest(LocalDate.parse(input.path("tripDate").asText()),
                input.path("startTime").asText(), input.path("endTime").asText(), input.path("startLocationId").asText(), preferred));
            String id = c.path("caseId").asText();
            assertEquals(c.path("expected").path("status").asText(), response.status().name(), id + " " + response.notices());
            var oracle = ItineraryOracle.forCase(scenario, input);
            if (response.status() == Status.VALIDATED) {
                var order = response.orderedLocationIds();
                var outcome = oracle.schedule(order);
                assertTrue(outcome.feasible(), id + " backend order rejected by oracle: " + outcome.cause());
                boolean qwenFits = oracle.schedule(proposed).feasible();
                assertEquals(qwenFits ? proposed : bestByRule(oracle, scenario, preferred), order, id);
                if (!qwenFits) searched++;
                for (int i = 0; i < order.size(); i++) {
                    var expected = outcome.items().get(i);
                    var actual = response.items().get(i);
                    assertEquals(expected.arrival(), actual.arrival(), id + " arrival " + i);
                    assertEquals(expected.departure(), actual.departure(), id + " departure " + i);
                    assertEquals(expected.waitMinutes(), actual.waitMinutes(), id + " wait " + i);
                    assertEquals(expected.travelMinutes(), actual.travelToNextMinutes(), id + " travel " + i);
                }
            }
            if (response.status() == Status.NO_FEASIBLE_PLAN) {
                assertTrue(oracle.feasibleOrders().isEmpty(), id + " backend gave up although the oracle has a plan");
                assertTrue(response.notices().stream().anyMatch(n -> n.contains("Đã xét toàn bộ")), id + " exhaustive notice");
                assertEquals(ItineraryPreviewResponse.PLAN_SOURCE_BACKEND_SEARCH, response.planSource(), id + " infeasible only after the search");
            }
            if (response.status() == Status.VALIDATED) {
                boolean qwenFits = oracle.schedule(proposed).feasible();
                assertEquals(qwenFits ? ItineraryPreviewResponse.PLAN_SOURCE_QWEN : ItineraryPreviewResponse.PLAN_SOURCE_BACKEND_SEARCH,
                    response.planSource(), id);
            }
            if (response.status() == Status.NEEDS_DATA) assertNull(response.planSource(), id);
            if (response.candidateSet() != null) {
                assertEquals(new TreeSet<>(strings(scenario.path("proof").path("candidateIds"))),
                    new TreeSet<>(response.candidateSet().candidateIds()), id + " candidate set = proof pool");
                assertEquals(input.path("startLocationId").asText(), response.candidateSet().candidateIds().get(0), id);
                assertTrue(response.candidateSet().candidateIds().containsAll(response.orderedLocationIds()), id);
            }
            checked++;
        }
        assertEquals(20, checked);
        assertTrue(searched >= 1, "iti-09 must exercise the backend search");
    }

    @Test void iti09ReturnsAcAfterQwenProposesAb() throws Exception {
        JsonNode c = cases.stream().filter(x -> "iti-09".equals(x.path("caseId").asText())).findFirst().orElseThrow();
        var facts = fromScenario(scenarios.path("iti-09"));
        var response = service(facts, mock(LocationService.class), proposing(List.of("a", "b")))
            .preview(USER, new ItineraryPreviewRequest(LocalDate.parse("2027-02-10"), "08:00", "09:20", "a", null));
        assertEquals(Status.VALIDATED, response.status(), response.notices().toString());
        assertEquals(List.of("a", "c"), response.orderedLocationIds());
        assertEquals("09:10", response.items().get(1).departure());
        assertNotNull(response.fingerprint());
        assertEquals(c.path("expected").path("status").asText(), response.status().name());
        assertEquals(ItineraryPreviewResponse.PLAN_SOURCE_BACKEND_SEARCH, response.planSource());
        assertEquals(List.of("a", "b", "c"), response.candidateSet().candidateIds());
        assertEquals(3, response.candidateSet().eligibleCount());
        assertTrue(response.candidateSet().excluded().isEmpty());
        assertTrue(response.candidateSet().truncatedLocationIds().isEmpty());
    }

    @Test void planContextExplainsSourceExclusionsAndCap() {
        var closed = new VisitMetadata("Asia/Ho_Chi_Minh", Map.of(DayOfWeek.WEDNESDAY, new VisitMetadata.Hours(VisitMetadata.State.CLOSED, List.of())),
            Map.of(), 30, null, null);
        var views = new ArrayList<PublicView>(List.of(open("s", "x", 1), open("a", "x", 2),
            new PublicView("nometa", "x", "x", "Thiếu", new BigDecimal("16.1099"), new BigDecimal("108.2099"), null),
            new PublicView("shut", "x", "x", "Đóng", new BigDecimal("16.1098"), new BigDecimal("108.2098"), closed)));
        for (int i = 0; i < 8; i++) views.add(open("p" + i, "x", 10 + i));
        var facts = complete(views, 600);
        var qwen = run(facts, List.of("s", "a"), "12:00", null);
        assertEquals(Status.VALIDATED, qwen.status());
        assertEquals(ItineraryPreviewResponse.PLAN_SOURCE_QWEN, qwen.planSource());
        var set = qwen.candidateSet();
        assertEquals(10, set.eligibleCount());
        assertEquals(List.of("s", "a", "p0", "p1", "p2", "p3", "p4", "p5"), set.candidateIds());
        assertEquals(List.of("p6", "p7"), set.truncatedLocationIds());
        assertEquals(List.of(new ItineraryPreviewResponse.ExcludedLocation("nometa", "MISSING_METADATA"),
            new ItineraryPreviewResponse.ExcludedLocation("shut", "NOT_OPEN_ON_DATE")), set.excluded());

        var lonely = new Facts();
        lonely.add(open("s", "x", 1));
        lonely.add(new PublicView("nometa", "x", "x", "Thiếu", new BigDecimal("16.1099"), new BigDecimal("108.2099"), null));
        var needs = run(lonely, List.of("s", "nometa"), "12:00", null);
        assertEquals(Status.NEEDS_DATA, needs.status());
        assertNull(needs.planSource());
        assertEquals(1, needs.candidateSet().eligibleCount());
        assertEquals("MISSING_METADATA", needs.candidateSet().excluded().get(0).reason());

        var qwenDown = mock(QwenItinerarySequencePlanner.class);
        when(qwenDown.planSequence(anyString(), anyList(), anyInt(), any(AiRequestDeadline.class)))
            .thenThrow(new QwenItinerarySequencePlanner.QwenPlanningException(503, "Qwen tắt"));
        var down = service(facts, mock(LocationService.class), qwenDown)
            .preview(USER, new ItineraryPreviewRequest(LocalDate.parse("2027-02-10"), "08:00", "12:00", "s", null));
        assertEquals(Status.UNAVAILABLE, down.status());
        assertNull(down.planSource(), "no plan source when the planner did not answer");
        assertNotNull(down.candidateSet());
    }

    static PublicView open(String id, String category, int n) {
        var ev = new VisitMetadata.Evidence("https://example.invalid/x", Instant.parse("2027-01-01T00:00:00Z"));
        var meta = new VisitMetadata("Asia/Ho_Chi_Minh", Map.of(DayOfWeek.WEDNESDAY, new VisitMetadata.Hours(VisitMetadata.State.OPEN_24H, List.of())),
            Map.of(), 30, ev, ev);
        return new PublicView(id, category, category, "Điểm " + id, new BigDecimal("16." + (1000 + n)), new BigDecimal("108." + (2000 + n)), meta);
    }

    static Facts complete(List<PublicView> views, int seconds) {
        var facts = new Facts();
        views.forEach(facts::add);
        for (var a : views) for (var b : views) if (!a.equals(b)) facts.leg(a.id(), b.id(), 1000, seconds);
        return facts;
    }

    static ItineraryPreviewResponse run(Facts facts, List<String> proposed, String end, String preferred) {
        return service(facts, mock(LocationService.class), proposing(proposed))
            .preview(USER, new ItineraryPreviewRequest(LocalDate.parse("2027-02-10"), "08:00", end, "s", preferred));
    }

    @Test void tieBreakPreferredThenSizeThenTravelWaitThenId() {
        // 30-min visits, 10-min legs: 08:00-10:00 fits three stops, not four.
        var views = List.of(open("s", "x", 1), open("a", "x", 2), open("b", "y", 3), open("c", "y", 4), open("z", "x", 5));
        var facts = complete(views, 600);
        facts.legs.remove("s->z"); // Qwen's proposal s->z has no route, forcing the search
        assertEquals(List.of("s", "a", "b"), run(facts, List.of("s", "z"), "10:00", null).orderedLocationIds(), "size first, then IDs");
        assertEquals(List.of("s", "b", "c"), run(facts, List.of("s", "z"), "10:00", "y").orderedLocationIds(), "two preferred stops beat ID order");

        facts.leg("a", "b", 1000, 1200); // a->b now 20 minutes: same size, more travel than a->c
        assertEquals(List.of("s", "a", "c"), run(facts, List.of("s", "z"), "10:00", null).orderedLocationIds(), "travel+wait before IDs");

        var two = run(facts, List.of("s", "z"), "09:20", "y");
        assertEquals(List.of("s", "b"), two.orderedLocationIds(), "only two stops fit; preferred y, then ID");
    }

    @Test void legsAreRoutedAtMostOncePerDirectionAndTheSetIsBounded() {
        var views = new ArrayList<PublicView>();
        views.add(open("s", "x", 1));
        for (int i = 0; i < 7; i++) views.add(open("p" + i, "x", i + 2));
        var facts = complete(views, 60);
        facts.legs.remove("p0->p6");
        var proposed = List.of("s", "p0", "p6"); // leg p0->p6 has no route: Qwen's order fails, search begins
        var response = run(facts, proposed, "23:00", null);
        assertEquals(Status.VALIDATED, response.status());
        assertEquals(List.of("s", "p0", "p1", "p2"), response.orderedLocationIds());
        assertTrue(facts.calls.stream().allMatch(c -> c.size() == 2), "every leg is a 2-point route (G6.4b-F1)");
        var pairs = facts.calls;
        assertEquals(pairs.size(), new HashSet<>(pairs).size(), "each directed pair requested once per preview, Qwen order included");
        assertTrue(pairs.size() <= 7 * 8, "at most 56 directed pairs for 8 candidates");
        assertEquals(1, facts.pairCalls("p0", "p6"), "the failed Qwen leg is cached, not re-requested by the search");
        assertEquals(1, facts.pairCalls("s", "p0"));
        assertEquals(0, facts.pairCalls("p1", "s"), "no leg back to the start is ever needed");
    }

    /** G6.4b-F1 regression: displayed legs equal the pair legs used to choose, never a multi-waypoint variant. */
    @Test void displayedLegsAreThePairLegsUsedForTheDecision() {
        var views = List.of(open("s", "x", 1), open("a", "x", 2), open("b", "x", 3), open("z", "x", 4));
        var facts = complete(views, 600);
        facts.legs.remove("s->z");
        facts.multiPointExtraMeters = 360;
        facts.multiPointExtraSeconds = 180; // through-waypoint variant: +3 minutes per leg
        for (var proposed : List.of(List.of("s", "a", "b"), List.of("s", "z"))) { // Qwen order kept / backend search
            facts.calls.clear();
            var response = run(facts, proposed, "10:00", null);
            assertEquals(Status.VALIDATED, response.status(), proposed.toString());
            var order = response.orderedLocationIds();
            assertEquals(List.of("s", "a", "b"), order, "08:00-10:00 fits three stops with 10-minute pair legs only");
            for (int i = 0; i + 1 < order.size(); i++) {
                assertEquals(1000.0, response.items().get(i).distanceToNextMeters(), "pair distance, not the multi-waypoint one");
                assertEquals(10, response.items().get(i).travelToNextMinutes());
            }
            assertEquals("09:50", response.items().get(2).departure());
            assertTrue(facts.calls.stream().allMatch(c -> c.size() == 2), "no multi-waypoint request");
        }
    }

    @Test void exhaustedSetReportsNoFeasiblePlanWithTheExaminedCount() {
        var views = List.of(open("s", "x", 1), open("a", "x", 2), open("b", "x", 3));
        var facts = complete(views, 3600); // every leg 60 min: nothing fits 08:00-09:00 after a 30-min visit
        var response = run(facts, List.of("s", "a"), "09:00", null);
        assertEquals(Status.NO_FEASIBLE_PLAN, response.status());
        assertTrue(response.notices().stream().anyMatch(n -> n.contains("Đã xét toàn bộ 2 thứ tự")), response.notices().toString());
        assertEquals(List.of("s", "a"), response.orderedLocationIds(), "Qwen's order kept for explanation");
    }

    @Test void providerFailuresStayUnavailableAndAreNotSearchedAround() {
        var views = List.of(open("s", "x", 1), open("a", "x", 2), open("b", "x", 3));
        var facts = complete(views, 600);
        var qwenDown = mock(QwenItinerarySequencePlanner.class);
        when(qwenDown.planSequence(anyString(), anyList(), anyInt(), any(AiRequestDeadline.class)))
            .thenThrow(new QwenItinerarySequencePlanner.QwenPlanningException(503, "Qwen tắt"));
        var down = service(facts, mock(LocationService.class), qwenDown)
            .preview(USER, new ItineraryPreviewRequest(LocalDate.parse("2027-02-10"), "08:00", "12:00", "s", null));
        assertEquals(Status.UNAVAILABLE, down.status());
        assertTrue(facts.calls.isEmpty(), "no backend search when the planner is unavailable");

        var busy = mock(QwenItinerarySequencePlanner.class);
        when(busy.planSequence(anyString(), anyList(), anyInt(), any(AiRequestDeadline.class)))
            .thenThrow(new QwenItinerarySequencePlanner.QwenPlanningException(429, "bận"));
        var ex = assertThrows(DomainException.class, () -> service(facts, mock(LocationService.class), busy)
            .preview(USER, new ItineraryPreviewRequest(LocalDate.parse("2027-02-10"), "08:00", "12:00", "s", null)));
        assertEquals(429, ex.status.value());

        facts.legs.remove("s->a");
        facts.calls.clear();
        facts.unavailableAfterCalls = 1; // Qwen's s->a gets NO_ROUTE; the next leg the search needs is UNAVAILABLE
        var midSearch = run(facts, List.of("s", "a", "b"), "12:00", null);
        assertEquals(Status.UNAVAILABLE, midSearch.status(), "an unavailable leg is never read as infeasible");
        assertTrue(facts.calls.size() >= 2, "the search started before OSRM failed");
    }
}
