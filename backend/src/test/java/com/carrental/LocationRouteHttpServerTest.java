package com.carrental;

import static org.junit.jupiter.api.Assertions.*;

import com.carrental.modules.ai.routing.OsrmRoutingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.jwt-secret=location-route-http-test-secret-32-bytes",
    "app.ai-routing-enabled=true",
    "app.seed-enabled=true",
    "app.seed-password=LocationRouteTestOnly123!",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
public class LocationRouteHttpServerTest {
    private static final String PREVIEW_PATH = "/api/v1/ai/routes/preview";
    private static final String ADMIN_LOCATIONS = "/api/v1/admin/locations";
    private static final String ADMIN_CATEGORIES = "/api/v1/admin/location-categories";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                url,
                System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),
                System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),
                System.getenv("KDRIVE_TEST_DB_USER"),
                System.getenv("KDRIVE_TEST_DB_PASSWORD")
            );
        }
        registry.add("spring.datasource.url", () -> url == null
            ? "jdbc:h2:mem:locationroutehttp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            : url);
        registry.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private record HttpResponseHolder(int statusCode, String body) {}

    private HttpResponseHolder sendHttp(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(url(path))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json");

        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        if ("POST".equalsIgnoreCase(method)) {
            builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else if ("GET".equalsIgnoreCase(method)) {
            builder.GET();
        } else {
            throw new IllegalArgumentException("Unsupported method: " + method);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new HttpResponseHolder(response.statusCode(), response.body());
    }

    private String token(String email) throws Exception {
        String reqBody = json.writeValueAsString(Map.of("email", email, "password", "LocationRouteTestOnly123!"));
        HttpResponseHolder res = sendHttp("POST", "/api/v1/auth/login", null, reqBody);
        assertEquals(200, res.statusCode(), "Login failed for " + email + ": " + res.body());
        return json.readTree(res.body()).path("token").asText();
    }

    private String createCategory(String adminToken) throws Exception {
        String name = "Category " + UUID.randomUUID();
        HttpResponseHolder res = sendHttp("POST", ADMIN_CATEGORIES, adminToken, json.writeValueAsString(Map.of("name", name)));
        assertEquals(201, res.statusCode(), "Create category failed: " + res.body());
        return json.readTree(res.body()).path("id").asText();
    }

    private String createLocation(String adminToken, String categoryId, String name, String lat, String lng, String status) throws Exception {
        HttpResponseHolder res = sendHttp("POST", ADMIN_LOCATIONS, adminToken, json.writeValueAsString(Map.of(
            "categoryId", categoryId,
            "name", name,
            "latitude", new BigDecimal(lat),
            "longitude", new BigDecimal(lng),
            "status", status
        )));
        assertEquals(201, res.statusCode(), "Create location failed: " + res.body());
        return json.readTree(res.body()).path("id").asText();
    }

    private Map<String, List<Map<String, Object>>> captureFullDatabaseSnapshot() {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        // 1. Snapshot bảng locations (sắp xếp id ổn định)
        snapshot.put("locations", jdbc.queryForList("SELECT * FROM locations ORDER BY id ASC"));
        snapshot.put("location_categories", jdbc.queryForList("SELECT * FROM location_categories ORDER BY id"));
        snapshot.put("booking_status_history", jdbc.queryForList("SELECT * FROM booking_status_history ORDER BY id"));
        // 2. Snapshot bảng itineraries
        snapshot.put("itineraries", jdbc.queryForList("SELECT id, user_id, title, criteria, created_at FROM itineraries ORDER BY id ASC"));
        // 3. Snapshot bảng itinerary_days
        snapshot.put("itinerary_days", jdbc.queryForList("SELECT id, itinerary_id, trip_date FROM itinerary_days ORDER BY id ASC"));
        // 4. Snapshot bảng itinerary_items
        snapshot.put("itinerary_items", jdbc.queryForList("SELECT id, day_id, location_id, sort_order, snapshot FROM itinerary_items ORDER BY id ASC"));
        // 5. Snapshot bảng bookings
        snapshot.put("bookings", jdbc.queryForList("SELECT id, user_id, vehicle_id, pickup_branch_id, return_branch_id, actual_return_branch_id, start_at, end_at, created_at, expires_at, status, daily_price, rental_days, rental_total, one_way_fee, total, customer_name, vehicle_name, pickup_name, return_name, policy_version, version FROM bookings ORDER BY id ASC"));
        return snapshot;
    }

    private void assertSnapshotsEqual(String stepDescription, Map<String, List<Map<String, Object>>> before, Map<String, List<Map<String, Object>>> after) {
        assertEquals(before.keySet(), after.keySet(), stepDescription + " - Table list changed");
        for (String table : before.keySet()) {
            List<Map<String, Object>> rowsBefore = before.get(table);
            List<Map<String, Object>> rowsAfter = after.get(table);
            assertEquals(rowsBefore.size(), rowsAfter.size(),
                String.format("%s - Table '%s' row count changed from %d to %d", stepDescription, table, rowsBefore.size(), rowsAfter.size()));
            assertEquals(rowsBefore, rowsAfter,
                String.format("%s - Table '%s' data mutated! Before: %s, After: %s", stepDescription, table, rowsBefore, rowsAfter));
        }
    }

    private void seedRepresentativeBusinessData() {
        // Đảm bảo có dữ liệu đại diện cho bookings và itineraries trước khi bắt đầu bài test
        if (jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class) == 0) {
            String bookingId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO bookings(id, user_id, vehicle_id, pickup_branch_id, return_branch_id, start_at, end_at, created_at, expires_at, status, daily_price, rental_days, rental_total, one_way_fee, total, customer_name, vehicle_name, pickup_name, return_name, policy_version, version) " +
                "VALUES (?, 'demo-user', 'v1', 'dn', 'dn', '2026-10-25 08:00:00', '2026-10-27 08:00:00', '2026-10-19 10:00:00', '2026-10-19 12:00:00', 'CONFIRMED', 650000, 2, 1300000, 0, 1300000, 'Khách mẫu', 'Toyota Vios', 'Đà Nẵng · mẫu', 'Đà Nẵng · mẫu', 'calendar-2026-10-17', 0)",
                bookingId);
            jdbc.update("INSERT INTO booking_status_history(id, booking_id, previous_status, next_status, actor_id, occurred_at, reason) VALUES (?, ?, 'PENDING', 'CONFIRMED', 'demo-admin', '2026-10-19 10:00:00', 'Synthetic routing fixture')",
                UUID.randomUUID().toString(), bookingId);
        }

        if (jdbc.queryForObject("SELECT count(*) FROM itineraries", Integer.class) == 0) {
            String itineraryId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO itineraries(id, user_id, title, criteria, created_at) VALUES (?, 'demo-user', 'Hành trình mẫu Đà Nẵng', 'Du lịch gia đình', '2026-10-19 10:00:00')",
                itineraryId);
            String dayId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO itinerary_days(id, itinerary_id, trip_date) VALUES (?, ?, '2026-10-25')", dayId, itineraryId);
        }
    }

    private record TestFixtureLocations(String p1, String p2, String p3, String pInactive, String pOffNetwork, String userToken, String staffToken, String adminToken) {}

    private TestFixtureLocations setupFixtureLocations() throws Exception {
        seedRepresentativeBusinessData();

        String admin = token("admin@example.test");
        String staff = token("staff.dn@example.test");
        String user = token("user@example.test");

        String cat = createCategory(admin);

        // Point 1: Cầu Rồng Đà Nẵng (16.0611, 108.2232)
        String p1 = createLocation(admin, cat, "Cầu Rồng " + UUID.randomUUID(), "16.0611000", "108.2232000", "ACTIVE");
        // Point 2: Phố cổ Hội An (15.8801, 108.3380)
        String p2 = createLocation(admin, cat, "Phố cổ Hội An " + UUID.randomUUID(), "15.8801000", "108.3380000", "ACTIVE");
        // Point 3: Bán đảo Sơn Trà (16.1130, 108.2750)
        String p3 = createLocation(admin, cat, "Sơn Trà " + UUID.randomUUID(), "16.1130000", "108.2750000", "ACTIVE");
        // Point 4: Địa điểm không hoạt động (INACTIVE)
        String pInactive = createLocation(admin, cat, "Điểm đóng cửa " + UUID.randomUUID(), "16.0500000", "108.2200000", "INACTIVE");
        // Point 5: Địa điểm nằm ngoài mạng lưới giao thông (0, 0)
        String pOffNetwork = createLocation(admin, cat, "Điểm ngoài đảo " + UUID.randomUUID(), "0.0000000", "0.0000000", "ACTIVE");

        String dayId = jdbc.queryForObject("SELECT id FROM itinerary_days ORDER BY id LIMIT 1", String.class);
        Integer lastOrder = jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), 0) FROM itinerary_items WHERE day_id = ?", Integer.class, dayId);
        jdbc.update("INSERT INTO itinerary_items(id, day_id, location_id, sort_order, snapshot) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), dayId, p1, lastOrder + 1, "Synthetic routing fixture; not a generated itinerary");
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM booking_status_history", Integer.class) > 0);
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM itinerary_items", Integer.class) > 0);

        return new TestFixtureLocations(p1, p2, p3, pInactive, pOffNetwork, user, staff, admin);
    }

    @Test
    void httpPermissionsAndInputNegativesWithPerRequestDatabaseInvariance() throws Exception {
        TestFixtureLocations fixture = setupFixtureLocations();

        // Danh sách các ca kiểm tra phân quyền & validation âm
        List<Map.Entry<String, RunnableWithException>> testCases = new ArrayList<>();

        // 1. Phân quyền (Permissions):
        testCases.add(Map.entry("Guest unauthenticated -> 401", () -> {
            HttpResponseHolder res = sendHttp("POST", PREVIEW_PATH, null, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p1, fixture.p2))));
            assertEquals(401, res.statusCode());
        }));

        testCases.add(Map.entry("Staff forbidden -> 403", () -> {
            HttpResponseHolder res = sendHttp("POST", PREVIEW_PATH, fixture.staffToken, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p1, fixture.p2))));
            assertEquals(403, res.statusCode());
        }));

        testCases.add(Map.entry("Admin forbidden -> 403", () -> {
            HttpResponseHolder res = sendHttp("POST", PREVIEW_PATH, fixture.adminToken, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p1, fixture.p2))));
            assertEquals(403, res.statusCode());
        }));

        // 2. Ca âm về Dữ liệu đầu vào (Input Validation Negatives) với role USER:
        testCases.add(Map.entry("Missing points (< 2) -> 400", () -> {
            HttpResponseHolder res = sendHttp("POST", PREVIEW_PATH, fixture.userToken, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p1))));
            assertEquals(400, res.statusCode());
        }));

        testCases.add(Map.entry("Too many points (> 8) -> 400", () -> {
            HttpResponseHolder res = sendHttp("POST", PREVIEW_PATH, fixture.userToken, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p1, fixture.p2, fixture.p3, fixture.p1, fixture.p2, fixture.p3, fixture.p1, fixture.p2, fixture.p3))));
            assertEquals(400, res.statusCode());
        }));

        testCases.add(Map.entry("Duplicate points -> 400", () -> {
            HttpResponseHolder res = sendHttp("POST", PREVIEW_PATH, fixture.userToken, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p1, fixture.p1))));
            assertEquals(400, res.statusCode());
        }));

        testCases.add(Map.entry("Non-existent location ID -> 400", () -> {
            HttpResponseHolder res = sendHttp("POST", PREVIEW_PATH, fixture.userToken, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p1, UUID.randomUUID().toString()))));
            assertEquals(400, res.statusCode());
        }));

        testCases.add(Map.entry("Inactive location ID -> 400", () -> {
            HttpResponseHolder res = sendHttp("POST", PREVIEW_PATH, fixture.userToken, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p1, fixture.pInactive))));
            assertEquals(400, res.statusCode());
        }));

        testCases.add(Map.entry("Empty JSON object {} -> 400", () -> {
            HttpResponseHolder res = sendHttp("POST", PREVIEW_PATH, fixture.userToken, "{}");
            assertEquals(400, res.statusCode());
        }));

        // Sửa F03: JSON malformed có lỗi cú pháp (Syntax Error) thực sự
        testCases.add(Map.entry("Malformed JSON syntax error -> 400", () -> {
            HttpResponseHolder res = sendHttp("POST", PREVIEW_PATH, fixture.userToken, "{\"locationIds\": [");
            assertEquals(400, res.statusCode());
        }));

        // Thực thi từng ca và kiểm tra snapshot bất biến records trước và sau TỪNG request (Sửa F02)
        for (var tc : testCases) {
            String description = tc.getKey();
            Map<String, List<Map<String, Object>>> before = captureFullDatabaseSnapshot();
            tc.getValue().run();
            Map<String, List<Map<String, Object>>> after = captureFullDatabaseSnapshot();
            assertSnapshotsEqual("Per-request invariance after: " + description, before, after);
        }
    }

    @Test
    void snapshotDetectsUpdatesWithoutRowCountChanges() throws Exception {
        setupFixtureLocations();
        for (var target : Map.of("locations", "name", "location_categories", "name",
                "booking_status_history", "reason", "itinerary_items", "snapshot").entrySet()) {
            String table = target.getKey(), column = target.getValue();
            String id = jdbc.queryForObject("SELECT id FROM " + table + " ORDER BY id LIMIT 1", String.class);
            String original = jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id);
            var before = captureFullDatabaseSnapshot();
            try {
                jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?", "Intentional mutation probe", id);
                AssertionError detected = assertThrows(AssertionError.class,
                    () -> assertSnapshotsEqual("Mutation probe", before, captureFullDatabaseSnapshot()));
                assertTrue(detected.getMessage().contains("data mutated"));
            } finally {
                jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?", original, id);
            }
            assertSnapshotsEqual("Mutation probe restoration", before, captureFullDatabaseSnapshot());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KDRIVE_ROUTING_MODE", matches = "online")
    void onlineRoutePreview_RequiresLiveOsrm_MustBeVerified_BothDirectionsAndMultiPoint() throws Exception {
        // Sửa F01: BẮT BUỘC OSRM online và trả về VERIFIED. Nếu OSRM vắng thì bài test này PHẢI FAIL, không được fallback sang UNAVAILABLE!
        TestFixtureLocations fixture = setupFixtureLocations();

        // 1. Tuyến thuận: p1 -> p2 (Cầu Rồng -> Phố cổ Hội An)
        Map<String, List<Map<String, Object>>> snapshotBeforeFwd = captureFullDatabaseSnapshot();
        HttpResponseHolder fwdRes = sendHttp("POST", PREVIEW_PATH, fixture.userToken, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p1, fixture.p2))));
        assertEquals(200, fwdRes.statusCode());
        JsonNode fwdNode = json.readTree(fwdRes.body());

        String fwdStatus = fwdNode.path("status").asText();
        assertEquals("VERIFIED", fwdStatus, "Online-required suite MUST receive VERIFIED status from live OSRM graph");
        double fwdMeters = fwdNode.path("distanceMeters").asDouble();
        double fwdSeconds = fwdNode.path("durationSeconds").asDouble();
        assertTrue(fwdMeters > 10000 && fwdMeters < 100000, "Distance Da Nang - Hoi An should be between 10km and 100km: " + fwdMeters);
        assertTrue(fwdSeconds > 300 && fwdSeconds < 10000, "Duration should be reasonable: " + fwdSeconds);
        assertTrue(fwdNode.path("attribution").asText().contains("OpenStreetMap"));

        Map<String, List<Map<String, Object>>> snapshotAfterFwd = captureFullDatabaseSnapshot();
        assertSnapshotsEqual("Database invariance after forward route preview", snapshotBeforeFwd, snapshotAfterFwd);

        // 2. Tuyến ngược (Sửa F03): p2 -> p1 (Phố cổ Hội An -> Cầu Rồng)
        Map<String, List<Map<String, Object>>> snapshotBeforeRev = captureFullDatabaseSnapshot();
        HttpResponseHolder revRes = sendHttp("POST", PREVIEW_PATH, fixture.userToken, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p2, fixture.p1))));
        assertEquals(200, revRes.statusCode());
        JsonNode revNode = json.readTree(revRes.body());

        assertEquals("VERIFIED", revNode.path("status").asText(), "Reverse route MUST receive VERIFIED status from live OSRM graph");
        double revMeters = revNode.path("distanceMeters").asDouble();
        double revSeconds = revNode.path("durationSeconds").asDouble();
        assertTrue(revMeters > 10000 && revMeters < 100000, "Reverse distance Hoi An - Da Nang should be between 10km and 100km: " + revMeters);
        assertTrue(revSeconds > 300 && revSeconds < 10000, "Reverse duration should be reasonable: " + revSeconds);
        assertTrue(revNode.path("attribution").asText().contains("OpenStreetMap"));

        Map<String, List<Map<String, Object>>> snapshotAfterRev = captureFullDatabaseSnapshot();
        assertSnapshotsEqual("Database invariance after reverse route preview", snapshotBeforeRev, snapshotAfterRev);

        // 3. Tuyến 3 điểm: p1 -> p2 -> p3 (Cầu Rồng -> Hội An -> Sơn Trà)
        Map<String, List<Map<String, Object>>> snapshotBeforeMulti = captureFullDatabaseSnapshot();
        HttpResponseHolder multiRes = sendHttp("POST", PREVIEW_PATH, fixture.userToken, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p1, fixture.p2, fixture.p3))));
        assertEquals(200, multiRes.statusCode());
        JsonNode multiNode = json.readTree(multiRes.body());

        assertEquals("VERIFIED", multiNode.path("status").asText(), "3-point route MUST receive VERIFIED status from live OSRM graph");
        double multiMeters = multiNode.path("distanceMeters").asDouble();
        double multiSeconds = multiNode.path("durationSeconds").asDouble();
        assertTrue(multiMeters > fwdMeters, "3-point route distance must exceed 2-point leg: multi=" + multiMeters + ", fwd=" + fwdMeters);
        assertTrue(multiSeconds > fwdSeconds, "3-point route duration must exceed 2-point leg: multi=" + multiSeconds + ", fwd=" + fwdSeconds);

        Map<String, List<Map<String, Object>>> snapshotAfterMulti = captureFullDatabaseSnapshot();
        assertSnapshotsEqual("Database invariance after 3-point route preview", snapshotBeforeMulti, snapshotAfterMulti);

        // 4. Tuyến có điểm ngoài mạng lưới giao thông (0, 0) -> NO_ROUTE
        Map<String, List<Map<String, Object>>> snapshotBeforeOff = captureFullDatabaseSnapshot();
        HttpResponseHolder offRes = sendHttp("POST", PREVIEW_PATH, fixture.userToken, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p1, fixture.pOffNetwork))));
        assertEquals(200, offRes.statusCode());
        JsonNode offNode = json.readTree(offRes.body());

        assertEquals("NO_ROUTE", offNode.path("status").asText(), "Off-network point must result in NO_ROUTE on live OSRM");
        assertTrue(offNode.path("distanceMeters").isNull());
        assertTrue(offNode.path("durationSeconds").isNull());

        Map<String, List<Map<String, Object>>> snapshotAfterOff = captureFullDatabaseSnapshot();
        assertSnapshotsEqual("Database invariance after off-network route preview", snapshotBeforeOff, snapshotAfterOff);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KDRIVE_ROUTING_MODE", matches = "offline")
    void offlineRoutePreview_WhenOsrmStopped_ReturnsUnavailableWithNoInventedEstimates() throws Exception {
        // Sửa F01: Bài kiểm thử riêng cho trạng thái Offline của OSRM (khi OSRM dừng hoặc unreachable)
        // Yêu cầu: status phải là UNAVAILABLE, metrics bắt buộc là null (không bịa số), và DB bất biến
        TestFixtureLocations fixture = setupFixtureLocations();

        Map<String, List<Map<String, Object>>> snapshotBefore = captureFullDatabaseSnapshot();
        HttpResponseHolder res = sendHttp("POST", PREVIEW_PATH, fixture.userToken, json.writeValueAsString(Map.of("locationIds", List.of(fixture.p1, fixture.p2))));
        assertEquals(200, res.statusCode());
        JsonNode node = json.readTree(res.body());

        assertEquals("UNAVAILABLE", node.path("status").asText(), "Offline-required suite must not accept an online provider");
        assertTrue(node.path("distanceMeters").isNull(), "Offline response must have null distanceMeters");
        assertTrue(node.path("durationSeconds").isNull(), "Offline response must have null durationSeconds");
        assertTrue(node.path("attribution").asText().contains("OpenStreetMap"));

        Map<String, List<Map<String, Object>>> snapshotAfter = captureFullDatabaseSnapshot();
        assertSnapshotsEqual("Database invariance after offline route preview check", snapshotBefore, snapshotAfter);
    }

    @FunctionalInterface
    private interface RunnableWithException {
        void run() throws Exception;
    }
}
