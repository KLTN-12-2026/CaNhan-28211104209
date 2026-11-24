package com.carrental;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.carrental.modules.ai.AiRequestDeadline;
import com.carrental.modules.ai.itinerary.ItineraryOracle;
import com.carrental.modules.ai.itinerary.service.QwenItinerarySequencePlanner;
import com.carrental.modules.ai.routing.OsrmRoutingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * G6.4b live itinerary preview on the registered regional OSRM graph (127.0.0.1:5000), run only through
 * run-routing-proof.py --itinerary-live (or run-mysql.py --suites ItineraryLivePreviewIT --routing-mode ...).
 * Coordinates and names are real pilot locations (scripts/ai/locations-pilot.json); opening hours and
 * durations are SYNTHETIC test facts, not verified visit data. Leg facts are re-read directly from OSRM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.jwt-secret=itinerary-live-preview-secret-at-least-32-bytes",
    "app.seed-enabled=true", "app.seed-password=ItineraryLiveOnly123!",
    "app.qwen-parser-enabled=true", "app.qwen-model=qwen3:8b", "app.gemini-parser-enabled=false",
    "app.ai-routing-enabled=true",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ItineraryLivePreviewIT {
    static final String SYNTHETIC = "https://example.invalid/synthetic-g6-4b-test-facts";
    /** name, latitude, longitude from scripts/ai/locations-pilot.json (first eight entries). */
    static final String[][] PILOT = {
        {"Bảo tàng Điêu khắc Chăm Đà Nẵng", "16.0602", "108.22317"},
        {"Danh thắng Ngũ Hành Sơn", "16.0043044", "108.263527"},
        {"Bảo tàng Đà Nẵng", "16.07483", "108.22398"},
        {"Bảo tàng Mỹ thuật Đà Nẵng", "16.07137", "108.21768"},
        {"Hội quán Phúc Kiến Hội An", "15.8778", "108.3308"},
        {"Chùa Linh Ứng Bãi Bụt Sơn Trà", "16.10017", "108.27785"},
        {"Bãi biển Mỹ Khê Đà Nẵng", "16.05973", "108.24647"},
        {"Cầu Rồng Đà Nẵng", "16.0611", "108.2232"},
    };

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            CancelBoundaryTest.verifySafeIsolatedDatabase(url, System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),
                System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"), System.getenv("KDRIVE_TEST_DB_USER"), System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        }
        registry.add("spring.datasource.url", () -> url == null
            ? "jdbc:h2:mem:itinerarylive;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        registry.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @MockitoSpyBean OsrmRoutingClient osrm;
    @MockitoSpyBean QwenItinerarySequencePlanner planner;

    final HttpClient direct = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    final Map<String, JsonNode> metadataById = new LinkedHashMap<>();
    final Map<String, double[]> coordinatesById = new LinkedHashMap<>();
    String userToken, startId, lateId;
    LocalDate tripDate;

    String login(String email) {
        var r = http.postForEntity("/api/v1/auth/login", Map.of("email", email, "password", "ItineraryLiveOnly123!"), JsonNode.class);
        assertEquals(200, r.getStatusCode().value());
        return r.getBody().path("token").asText();
    }

    HttpHeaders auth(String token) {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    ObjectNode metadata(String open, String close, int minutes) {
        ObjectNode m = json.createObjectNode().put("timezone", "Asia/Ho_Chi_Minh");
        ObjectNode weekly = m.putObject("weekly");
        for (DayOfWeek d : DayOfWeek.values()) {
            ObjectNode day = weekly.putObject(d.name()).put("state", "INTERVALS");
            day.putArray("intervals").addObject().put("start", open).put("end", close).put("nextDay", false);
        }
        m.putObject("exceptions");
        m.put("durationMinutes", minutes);
        m.putObject("hoursEvidence").put("url", SYNTHETIC).put("verifiedAt", "2026-11-01T00:00:00Z");
        m.putObject("durationEvidence").put("url", SYNTHETIC).put("verifiedAt", "2026-11-01T00:00:00Z");
        return m;
    }

    @BeforeAll
    void seedCatalog() {
        CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);
        jdbc.update("update locations set status='INACTIVE'");
        String admin = login("admin@example.test");
        userToken = login("user@example.test");
        var category = http.exchange("/api/v1/admin/location-categories", HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "G6.4b live " + System.nanoTime()), auth(admin)), JsonNode.class);
        assertEquals(201, category.getStatusCode().value());
        String categoryId = category.getBody().path("id").asText();
        for (int i = 0; i < PILOT.length; i++) {
            boolean late = i == PILOT.length - 1;
            ObjectNode meta = late ? metadata("19:00", "21:00", 30) : metadata("07:00", "21:00", 45);
            var created = http.exchange("/api/v1/admin/locations", HttpMethod.POST, new HttpEntity<>(Map.of(
                "categoryId", categoryId, "name", PILOT[i][0], "latitude", new BigDecimal(PILOT[i][1]),
                "longitude", new BigDecimal(PILOT[i][2]), "status", "ACTIVE", "metadata", meta), auth(admin)), JsonNode.class);
            assertEquals(201, created.getStatusCode().value(), String.valueOf(created.getBody()));
            String id = created.getBody().path("id").asText();
            metadataById.put(id, meta);
            coordinatesById.put(id, new double[]{Double.parseDouble(PILOT[i][1]), Double.parseDouble(PILOT[i][2])});
            if (i == 0) startId = id;
            if (late) lateId = id;
        }
        tripDate = LocalDate.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh"))).plusDays(7);
    }

    @BeforeEach
    void resetSpies() { clearInvocations(osrm, planner); }

    JsonNode preview(String start, String end) {
        var body = new LinkedHashMap<String, Object>();
        body.put("tripDate", tripDate.toString());
        body.put("startTime", start);
        body.put("endTime", end);
        body.put("startLocationId", startId);
        body.put("preferredCategoryId", null);
        var r = http.exchange("/api/v1/ai/itineraries/preview", HttpMethod.POST, new HttpEntity<>(body, auth(userToken)), JsonNode.class);
        assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));
        return r.getBody();
    }

    Map<String, List<Map<String, Object>>> records() {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String t : List.of("itineraries", "itinerary_days", "itinerary_items", "bookings", "locations"))
            rows.put(t, jdbc.queryForList("select * from " + t + " order by 1"));
        return rows;
    }

    /** Direct OSRM readback with the same coordinate format and snapping radius the backend uses. */
    double[] directLeg(String from, String to) throws Exception {
        double[] a = coordinatesById.get(from), b = coordinatesById.get(to);
        String coordinates = coord(new OsrmRoutingClient.Point(a[0], a[1])) + ";" + coord(new OsrmRoutingClient.Point(b[0], b[1]));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:5000/route/v1/driving/" + coordinates
            + "?overview=false&steps=false&alternatives=false&radiuses=1000;1000")).timeout(Duration.ofSeconds(5)).GET().build();
        JsonNode body = json.readTree(direct.send(request, HttpResponse.BodyHandlers.ofString()).body());
        if (!"Ok".equals(body.path("code").asText())) return null;
        JsonNode route = body.path("routes").get(0);
        return new double[]{route.path("distance").asDouble(), route.path("duration").asDouble()};
    }

    void assertLegsMatchDirectOsrm(JsonNode response) throws Exception {
        var order = new ArrayList<String>();
        response.path("orderedLocationIds").forEach(n -> order.add(n.asText()));
        JsonNode items = response.path("items");
        assertEquals(order.size(), items.size());
        for (int i = 0; i + 1 < order.size(); i++) {
            double[] leg = directLeg(order.get(i), order.get(i + 1));
            assertNotNull(leg, "graph has no route " + order.get(i) + "->" + order.get(i + 1));
            assertEquals((int) Math.ceil(leg[1] / 60.0), items.get(i).path("travelToNextMinutes").asInt(), "leg " + i + " minutes");
            assertEquals(leg[0], items.get(i).path("distanceToNextMeters").asDouble(), 1.0, "leg " + i + " meters");
            int departure = minutes(items.get(i).path("departure").asText());
            assertEquals(departure + items.get(i).path("travelToNextMinutes").asInt(), minutes(items.get(i + 1).path("arrival").asText()), "arrival chain");
        }
    }

    /** Same textual form as OsrmRoutingClient.Point#coordinates (package-private there). */
    static String coord(OsrmRoutingClient.Point p) { return Double.toString(p.longitude()) + "," + Double.toString(p.latitude()); }

    static int minutes(String hhmm) { LocalTime t = LocalTime.parse(hhmm); return t.getHour() * 60 + t.getMinute(); }

    static void report(String label, JsonNode response, long elapsedMs, long osrmCalls) {
        System.out.println("LIVE_ITINERARY case=" + label + " status=" + response.path("status").asText()
            + " planSource=" + response.path("planSource").asText(null) + " order=" + response.path("orderedLocationIds")
            + " elapsedMs=" + elapsedMs + " osrmCalls=" + osrmCalls + " notices=" + response.path("notices"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KDRIVE_ROUTING_MODE", matches = "online")
    void onlineRealQwenAndOsrm_PreviewMatchesDirectOsrmLegs() throws Exception {
        assertEquals("true", System.getenv("KDRIVE_AI_LIVE_ITINERARY"), "Explicit live Qwen opt-in required");
        var before = records();
        long started = System.nanoTime();
        JsonNode response = preview("08:00", "17:00");
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        report("qwen-live", response, elapsed, mockingDetails(osrm).getInvocations().size());
        assertEquals("VALIDATED", response.path("status").asText(), response.toString());
        assertTrue(Set.of("QWEN", "BACKEND_SEARCH").contains(response.path("planSource").asText()));
        verify(planner, atLeastOnce()).planSequence(anyString(), anyList(), anyInt(), any(AiRequestDeadline.class));
        var candidates = new HashSet<String>();
        response.path("candidateSet").path("candidateIds").forEach(n -> candidates.add(n.asText()));
        assertEquals(coordinatesById.keySet(), candidates, "all eight synthetic-fact pilot points are candidates");
        response.path("orderedLocationIds").forEach(n -> assertTrue(candidates.contains(n.asText())));
        assertEquals(startId, response.path("orderedLocationIds").get(0).asText());
        assertLegsMatchDirectOsrm(response);
        assertTrue(elapsed < 65_000, "within the frontend 65s timeout");
        assertEquals(before, records(), "preview must not write");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KDRIVE_ROUTING_MODE", matches = "online")
    void onlineForcedSearch_MatchesOracleOnRealGraphWithinBudget() throws Exception {
        // The late location only opens 19:00-21:00, so this planner answer cannot fit 08:00-17:00 (no Qwen call).
        doReturn(new QwenItinerarySequencePlanner.SequenceResult(List.of(startId, lateId), "TEST_FORCED_INFEASIBLE"))
            .when(planner).planSequence(anyString(), anyList(), anyInt(), any(AiRequestDeadline.class));
        var before = records();
        long started = System.nanoTime();
        JsonNode response = preview("08:00", "17:00");
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        var invocations = mockingDetails(osrm).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("route")).toList();
        report("forced-search", response, elapsed, invocations.size());
        assertEquals("VALIDATED", response.path("status").asText(), response.toString());
        assertEquals("BACKEND_SEARCH", response.path("planSource").asText());

        // Independent oracle on directly re-read legs for every ordered pair.
        ObjectNode scenario = json.createObjectNode();
        ArrayNode locations = scenario.putArray("locations");
        for (var e : metadataById.entrySet())
            locations.addObject().put("id", e.getKey()).put("status", "ACTIVE").put("categoryId", "c").set("metadata", e.getValue());
        ArrayNode legs = scenario.putArray("legs");
        for (String a : coordinatesById.keySet()) for (String b : coordinatesById.keySet()) {
            if (a.equals(b)) continue;
            double[] leg = directLeg(a, b);
            ObjectNode node = legs.addObject().put("source", a).put("target", b);
            if (leg == null) node.put("status", "NO_ROUTE");
            else node.put("status", "VERIFIED").put("distanceMeters", leg[0]).put("durationSeconds", leg[1]);
        }
        var oracle = new ItineraryOracle(scenario, tripDate, "08:00", "17:00", startId);
        var best = oracle.enumerate().stream().filter(ItineraryOracle.Outcome::feasible).min(Comparator
            .comparingInt((ItineraryOracle.Outcome o) -> -o.order().size())
            .thenComparingInt(o -> o.items().stream().mapToInt(x -> x.waitMinutes() + (x.travelMinutes() == null ? 0 : x.travelMinutes())).sum())
            .thenComparing(o -> String.join("\u0000", o.order()))).orElseThrow();
        var order = new ArrayList<String>();
        response.path("orderedLocationIds").forEach(n -> order.add(n.asText()));
        assertEquals(best.order(), order, "backend search must pick the oracle's best order");
        for (int i = 0; i < order.size(); i++) {
            assertEquals(best.items().get(i).arrival(), response.path("items").get(i).path("arrival").asText(), "arrival " + i);
            assertEquals(best.items().get(i).departure(), response.path("items").get(i).path("departure").asText(), "departure " + i);
        }
        assertLegsMatchDirectOsrm(response);

        var pairs = new ArrayList<List<String>>();
        for (var inv : invocations) {
            @SuppressWarnings("unchecked") List<OsrmRoutingClient.Point> pts = (List<OsrmRoutingClient.Point>) inv.getArgument(0);
            if (pts.size() == 2) pairs.add(List.of(coord(pts.get(0)), coord(pts.get(1))));
        }
        assertEquals(pairs.size(), new HashSet<>(pairs).size(), "each directed leg routed at most once");
        assertTrue(pairs.size() <= 7 * 8);
        assertTrue(elapsed < 60_000, "backend search within the 60s request budget: " + elapsed + "ms");
        assertEquals(before, records(), "preview must not write");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KDRIVE_ROUTING_MODE", matches = "offline")
    void offlineOsrmStopped_PreviewIsUnavailableNotInfeasible() {
        String second = coordinatesById.keySet().stream().skip(1).findFirst().orElseThrow();
        doReturn(new QwenItinerarySequencePlanner.SequenceResult(List.of(startId, second), "TEST_FIXED_ORDER"))
            .when(planner).planSequence(anyString(), anyList(), anyInt(), any(AiRequestDeadline.class));
        var before = records();
        long started = System.nanoTime();
        JsonNode response = preview("08:00", "17:00");
        report("offline", response, (System.nanoTime() - started) / 1_000_000, mockingDetails(osrm).getInvocations().size());
        assertEquals("UNAVAILABLE", response.path("status").asText(), response.toString());
        assertTrue(response.path("planSource").isNull());
        assertEquals(0, response.path("items").size());
        assertTrue(response.path("fingerprint").isNull());
        assertEquals(before, records());
    }
}
