package com.carrental.modules.ai.itinerary;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * G6.1: the independent oracle must reproduce every hand-written proof in scenarios-v1/facts.json
 * (witness schedules, exhaustive rejections with causes, missing facts) before it is used to judge
 * backend search in G6.3. No Spring context, provider or database.
 */
class ItineraryOracleTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static JsonNode scenarios;
    static List<JsonNode> cases;

    @BeforeAll static void load() throws Exception {
        Path data = Path.of("..", "scripts", "ai", "evaluation", "scenarios-v1").toAbsolutePath().normalize();
        scenarios = JSON.readTree(data.resolve("facts.json").toFile()).path("scenarios");
        cases = new ArrayList<>();
        for (String line : Files.readAllLines(data.resolve("itinerary.jsonl"))) if (!line.isBlank()) cases.add(JSON.readTree(line));
        assertEquals(30, cases.size(), "itinerary dev set size");
    }

    static List<JsonNode> category(String name) { return cases.stream().filter(c -> name.equals(c.path("category").asText())).toList(); }
    static JsonNode scenario(JsonNode c) { return scenarios.path(c.path("fixtureRef").asText()); }
    static JsonNode proof(JsonNode c) { return scenario(c).path("proof"); }
    static ItineraryOracle oracle(JsonNode c) { return ItineraryOracle.forCase(scenario(c), c.path("input")); }
    static List<String> strings(JsonNode array) { var out = new ArrayList<String>(); array.forEach(n -> out.add(n.asText())); return out; }
    static List<ItineraryOracle.Item> items(JsonNode array) throws Exception {
        var out = new ArrayList<ItineraryOracle.Item>();
        for (JsonNode n : array) out.add(JSON.treeToValue(n, ItineraryOracle.Item.class));
        return out;
    }

    @Test void iti09FindsAcWhenTheProposedAbMissesTheWindow() throws Exception {
        JsonNode c = cases.stream().filter(x -> "iti-09".equals(x.path("caseId").asText())).findFirst().orElseThrow();
        var oracle = oracle(c);
        assertEquals(List.of("a", "b"), strings(scenario(c).path("proposedOrder")));
        var ab = oracle.schedule(List.of("a", "b"));
        assertEquals(ItineraryOracle.END_TIME, ab.cause(), "b opens 11:00, after the 09:20 end");
        var ac = oracle.schedule(List.of("a", "c"));
        assertTrue(ac.feasible());
        assertEquals(items(proof(c).path("witnessSchedules").get(0).path("items")), ac.items());
        assertTrue(oracle.feasibleOrders().contains(List.of("a", "c")));
        assertFalse(oracle.feasibleOrders().contains(List.of("a", "b")));
    }

    @Test void everyFeasibleScenarioReproducesItsHandWrittenWitness() throws Exception {
        var feasible = category("FEASIBLE");
        assertEquals(10, feasible.size());
        for (JsonNode c : feasible) {
            var oracle = oracle(c);
            assertEquals(new TreeSet<>(strings(proof(c).path("candidateIds"))), oracle.candidatePool(), c.path("caseId").asText());
            JsonNode witnesses = proof(c).path("witnessSchedules");
            assertTrue(witnesses.size() > 0);
            for (JsonNode w : witnesses) {
                var outcome = oracle.schedule(strings(w.path("order")));
                assertTrue(outcome.feasible(), c.path("caseId").asText() + " witness rejected: " + outcome.cause());
                assertEquals(items(w.path("items")), outcome.items(), c.path("caseId").asText());
                assertTrue(oracle.feasibleOrders().contains(outcome.order()));
            }
        }
    }

    @Test void everyInfeasibleScenarioIsExhaustivelyRejectedWithTheStatedCause() {
        var infeasible = category("INFEASIBLE");
        assertEquals(5, infeasible.size());
        for (JsonNode c : infeasible) {
            var oracle = oracle(c);
            var expected = new HashMap<List<String>, String>();
            for (JsonNode row : proof(c).path("rejectedOrders")) expected.put(strings(row.path("order")), row.path("cause").asText());
            var actual = new HashMap<List<String>, String>();
            for (var o : oracle.enumerate()) actual.put(o.order(), o.cause());
            assertFalse(actual.isEmpty(), c.path("caseId").asText());
            assertEquals(expected, actual, c.path("caseId").asText());
            assertTrue(oracle.feasibleOrders().isEmpty(), c.path("caseId").asText());
        }
    }

    @Test void everyNeedsDataScenarioReportsExactlyTheMissingFacts() {
        var needsData = category("NEEDS_DATA");
        assertEquals(5, needsData.size());
        for (JsonNode c : needsData) {
            var oracle = oracle(c);
            assertEquals(new TreeSet<>(strings(proof(c).path("missingFacts"))), oracle.missingFacts(), c.path("caseId").asText());
            assertFalse(oracle.missingFacts().isEmpty());
            assertEquals(new TreeSet<>(strings(proof(c).path("candidateIds"))), oracle.candidatePool(), c.path("caseId").asText());
        }
        for (JsonNode c : category("FEASIBLE")) assertTrue(oracle(c).feasibleOrders().size() > 0);
    }

    @Test void eightCandidatesYieldExactly259OrdersFromTheFixedStart() {
        ObjectNode scenario = JSON.createObjectNode();
        ArrayNode locations = scenario.putArray("locations");
        scenario.putArray("legs");
        for (String id : List.of("s", "a", "b", "c", "d", "e", "f", "g")) {
            ObjectNode l = locations.addObject();
            l.put("id", id).put("status", "ACTIVE");
            ObjectNode meta = l.putObject("metadata");
            meta.put("durationMinutes", 30);
            meta.putObject("exceptions");
            meta.putObject("weekly").putObject("WEDNESDAY").put("state", "OPEN_24H").putArray("intervals");
        }
        var oracle = new ItineraryOracle(scenario, java.time.LocalDate.parse("2027-02-10"), "08:00", "18:00", "s");
        var orders = oracle.orders();
        assertEquals(7 + 42 + 210, orders.size());
        assertEquals(orders.size(), new HashSet<>(orders).size());
        assertTrue(orders.stream().allMatch(o -> o.get(0).equals("s") && o.size() >= 2 && o.size() <= 4 && new HashSet<>(o).size() == o.size()));
        assertTrue(oracle.enumerate().stream().allMatch(o -> ItineraryOracle.NO_ROUTE.equals(o.cause())), "no legs: every order lacks a route");
    }

    @Test void oracleIsNotVacuousUnderMutatedFacts() throws Exception {
        JsonNode c = cases.stream().filter(x -> "iti-09".equals(x.path("caseId").asText())).findFirst().orElseThrow();
        ObjectNode mutated = scenario(c).deepCopy();
        for (JsonNode l : mutated.path("locations"))
            if ("c".equals(l.path("id").asText()))
                ((ObjectNode) l.path("metadata").path("weekly").path("WEDNESDAY").path("intervals").get(0)).put("start", "11:00").put("end", "12:00");
        var oracle = ItineraryOracle.forCase(mutated, c.path("input"));
        assertTrue(oracle.feasibleOrders().isEmpty(), "with c moved to 11:00 nothing fits before 09:20");
        assertEquals(ItineraryOracle.END_TIME, oracle.schedule(List.of("a", "c")).cause());

        var witness = items(proof(c).path("witnessSchedules").get(0).path("items"));
        var tampered = new ArrayList<>(witness);
        var last = tampered.get(1);
        tampered.set(1, new ItineraryOracle.Item(last.locationId(), last.arrival(), "09:11", last.waitMinutes(), last.travelMinutes(), last.distanceMeters()));
        assertNotEquals(tampered, oracle(c).schedule(List.of("a", "c")).items(), "a one-minute witness change must be detected");

        ObjectNode noRoute = scenario(c).deepCopy();
        for (JsonNode leg : noRoute.path("legs"))
            if ("a".equals(leg.path("source").asText()) && "c".equals(leg.path("target").asText())) ((ObjectNode) leg).put("status", "NO_ROUTE");
        assertEquals(ItineraryOracle.NO_ROUTE, ItineraryOracle.forCase(noRoute, c.path("input")).schedule(List.of("a", "c")).cause());
    }
}
