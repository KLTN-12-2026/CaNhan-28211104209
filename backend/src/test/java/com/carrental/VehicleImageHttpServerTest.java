package com.carrental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.*;
import com.carrental.security.TokenService;
import com.carrental.modules.user.service.AccountService;
import com.carrental.modules.vehicle.repository.VehicleImageRepository;
import com.carrental.modules.vehicle.service.VehicleImageService;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.jwt-secret=integration-test-only-secret-at-least-32-bytes",
    "app.token-minutes=30",
    "app.seed-enabled=true",
    "app.seed-password=IntegrationOnly123!",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
public class VehicleImageHttpServerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TokenService tokens;

    @Autowired
    private AccountService accountService;

    private String adminToken;
    private String userToken;
    private String staffToken;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            CancelBoundaryTest.verifySafeIsolatedDatabase(url,
                System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),
                System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),
                System.getenv("KDRIVE_TEST_DB_USER"),
                System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        }
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:vehicleimagehttp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        r.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        r.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void setup() {
        jdbc.update("delete from vehicle_images where vehicle_id in ('v1','v2','v3')");
        ensureUserExists("demo-admin", "Admin C10B", "admin@example.test", "ADMIN");
        ensureUserExists("demo-user", "User C10B", "user@example.test", "USER");
        ensureUserExists("demo-staff-hn", "Staff HN C10B", "staff-hn@example.test", "STAFF");

        adminToken = "Bearer " + tokens.issue(accountService.require("demo-admin"));
        userToken = "Bearer " + tokens.issue(accountService.require("demo-user"));
        staffToken = "Bearer " + tokens.issue(accountService.require("demo-staff-hn"));
    }

    private void ensureUserExists(String id, String fullName, String email, String role) {
        Long cnt = jdbc.queryForObject("select count(*) from users where id=?", Long.class, id);
        if (cnt == 0) {
            jdbc.update("insert into users(id, full_name, email, phone, password_hash, role, status, token_version, version) values(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, fullName, email, "0900000000", "$2a$10$dummyHashNotUsableDirectly12345678901234567890123456", role, "ACTIVE", 0L, 0L);
        }
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("Authorization", token);
        }
        return headers;
    }

    @MockitoSpyBean private VehicleImageRepository images;
    @MockitoSpyBean private JdbcTemplate tracedJdbc;
    @Autowired private VehicleImageService service;

    private ResponseEntity<Map> request(String vehicle, String imageId, HttpMethod method, Map body, String token) {
        String path = "/api/v1/admin/vehicles/" + vehicle + "/images";
        if (imageId != null) path += "/" + imageId;
        if ("/order".equals(imageId)) path = "/api/v1/admin/vehicles/" + vehicle + "/images/order";
        return restTemplate.exchange(path, method, new HttpEntity<>(body, authHeaders(token)), Map.class);
    }

    private Map read(String vehicle) { return request(vehicle, null, HttpMethod.GET, null, adminToken).getBody(); }
    private List<String> ids(String vehicle) { return jdbc.queryForList("select id from vehicle_images where vehicle_id=? order by sort_order, id", String.class, vehicle); }
    private List<Map<String, Object>> fullImages(String vehicle) { return jdbc.queryForList("select id, vehicle_id, url, sort_order, attribution from vehicle_images where vehicle_id=? order by sort_order, id", vehicle); }
    private Map payloadCreate(String url, String attribution, Object revision) { Map body = new HashMap<>(); body.put("url", url); body.put("attribution", attribution); body.put("expectedRevision", revision); return body; }
    private Map payloadUpdate(String url, String attribution, Object revision) { Map body = new HashMap<>(); body.put("url", url); body.put("attribution", attribution); body.put("expectedRevision", revision); return body; }
    private Map payloadReorder(List<String> ids, Object revision) { Map body = new HashMap<>(); body.put("imageIds", ids); body.put("expectedRevision", revision); return body; }
    private Map saveCreate(String vehicle, String url, String attribution) {
        ResponseEntity<Map> response = request(vehicle, null, HttpMethod.POST, payloadCreate(url, attribution, read(vehicle).get("revision")), adminToken);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        return response.getBody();
    }
    private Map saveUpdate(String vehicle, String imageId, String url, String attribution) {
        ResponseEntity<Map> response = request(vehicle, imageId, HttpMethod.PUT, payloadUpdate(url, attribution, read(vehicle).get("revision")), adminToken);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }
    private Map saveReorder(String vehicle, List<String> imageIds) {
        ResponseEntity<Map> response = request(vehicle, "/order", HttpMethod.PUT, payloadReorder(imageIds, read(vehicle).get("revision")), adminToken);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    @Test void permissionsValidationAndAtomicRejection() {
        Map created = saveCreate("v1", "https://example.com/img1.jpg", "Photo 1");
        List<String> before = ids("v1");
        String revision = (String) read("v1").get("revision");

        for (String token : List.of(userToken, staffToken)) {
            assertEquals(HttpStatus.FORBIDDEN, request("v1", null, HttpMethod.GET, null, token).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, request("v1", null, HttpMethod.POST, payloadCreate("https://example.com/x.jpg", "X", revision), token).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, request("v1", before.get(0), HttpMethod.PUT, payloadUpdate("https://example.com/x.jpg", "X", revision), token).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, request("v1", "/order", HttpMethod.PUT, payloadReorder(List.of(), revision), token).getStatusCode());
        }
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT)) {
            assertEquals(HttpStatus.UNAUTHORIZED, request("v1", null, method, payloadCreate("https://example.com/x.jpg", "X", revision), null).getStatusCode());
        }

        // Invalid create requests
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invalidCreates = Arrays.asList(
                Map.of(), // empty
                Map.of("url", "  "), // blank url
                Map.of("url", "x".repeat(501)), // too long
                Map.of("url", "javascript:alert(1)"), // bad scheme
                Map.of("url", "data:image/png;base64,abc"), // data URI
                Map.of("url", "//example.com/img.jpg"), // protocol-relative
                Map.of("url", "http://example.com/img.jpg"), // http not https
                Map.of("url", "https://user:pass@example.com/img.jpg"), // credentials
                Map.of("url", "https://example.com/../etc/passwd"), // traversal
                Map.of("url", "https://example.com/%2e%2e/etc/passwd"), // encoded traversal
                Map.of("url", "https://example.com/img.jpg", "expectedRevision", "invalid"), // bad revision
                payloadCreate("https://example.com/img.jpg", "fixture credit", null) // null revision
        );
        for (Map<String, Object> invalid : invalidCreates) {
            invalid = new HashMap<>(invalid);
            if (invalid.containsKey("url") && !invalid.containsKey("expectedRevision")) invalid.put("expectedRevision", revision);
            assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST, invalid, adminToken).getStatusCode());
        }
        assertEquals(before, ids("v1"));

        // Invalid update requests
        String imageId = before.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invalidUpdates = Arrays.asList(
                Map.of(), // empty
                Map.of("url", "  "), // blank url
                Map.of("url", "x".repeat(501)), // too long
                Map.of("url", "javascript:alert(1)"), // bad scheme
                Map.of("url", "https://example.com/img.jpg", "expectedRevision", "invalid"), // bad revision
                payloadCreate("https://example.com/img.jpg", "fixture credit", null) // null revision
        );
        for (Map<String, Object> invalid : invalidUpdates) {
            invalid = new HashMap<>(invalid);
            if (invalid.containsKey("url") && !invalid.containsKey("expectedRevision")) invalid.put("expectedRevision", revision);
            assertEquals(HttpStatus.BAD_REQUEST, request("v1", imageId, HttpMethod.PUT, invalid, adminToken).getStatusCode());
        }
        assertEquals(before, ids("v1"));

        // Invalid reorder requests
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invalidReorders = Arrays.asList(
                Map.of(), // empty body
                payloadReorder(null, revision), // null array
                Map.of("imageIds", List.of("")), // blank element
                Map.of("imageIds", List.of("a", "a")), // duplicate
                Map.of("imageIds", List.of("unknown")), // unknown
                Map.of("imageIds", List.of(before.get(0), "cross-vehicle")), // cross-vehicle
                Map.of("imageIds", List.of(before.get(0)), "expectedRevision", "invalid"),
                payloadReorder(List.of(before.get(0)), null)
        );
        for (Map<String, Object> invalid : invalidReorders) {
            invalid = new HashMap<>(invalid);
            if (invalid.containsKey("imageIds") && !invalid.containsKey("expectedRevision")) invalid.put("expectedRevision", revision);
            assertEquals(HttpStatus.BAD_REQUEST, request("v1", "/order", HttpMethod.PUT, invalid, adminToken).getStatusCode());
        }
        assertEquals(before, ids("v1"));

        // Empty array when vehicle has images
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", "/order", HttpMethod.PUT, payloadReorder(List.of(), revision), adminToken).getStatusCode());
        assertEquals(before, ids("v1"));

        // Unknown vehicle
        assertEquals(HttpStatus.NOT_FOUND, request("missing", null, HttpMethod.GET, null, adminToken).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request("missing", null, HttpMethod.POST, payloadCreate("https://example.com/x.jpg", "X", revision), adminToken).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request("missing", imageId, HttpMethod.PUT, payloadUpdate("https://example.com/x.jpg", "X", revision), adminToken).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request("missing", "/order", HttpMethod.PUT, payloadReorder(List.of(), revision), adminToken).getStatusCode());

        // Unknown image
        assertEquals(HttpStatus.NOT_FOUND, request("v1", "unknown-image", HttpMethod.PUT, payloadUpdate("https://example.com/x.jpg", "X", revision), adminToken).getStatusCode());
        assertEquals(before, ids("v1"));

        // Cross-vehicle image update
        Map created2 = saveCreate("v2", "https://example.com/img2.jpg", "Photo 2");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> created2Images = (List<Map<String, Object>>) created2.get("images");
        String crossImageId = (String) created2Images.get(0).get("id");
        assertEquals(HttpStatus.NOT_FOUND, request("v1", crossImageId, HttpMethod.PUT, payloadUpdate("https://example.com/x.jpg", "X", revision), adminToken).getStatusCode());
        assertEquals(before, ids("v1"));
    }

    @Test void publicReadAndEmptyImages() {
        // v3 has no images
        List<String> v3Images = ids("v3");
        assertEquals(List.of(), v3Images);

        Map detail = restTemplate.getForObject("/api/v1/vehicles/v3", Map.class);
        assertEquals(List.of(), detail.get("images"));

        List<Map> all = restTemplate.getForObject("/api/v1/vehicles", List.class);
        Map v3 = all.stream().filter(row -> "v3".equals(row.get("id"))).findFirst().orElseThrow();
        assertEquals(List.of(), v3.get("images"));
    }

    @Test void appendEditReorderNoOpAndIndependentVehicleEdit() {
        List<Map<String, Object>> vehicleBefore = jdbc.queryForList("select * from vehicles order by id");
        String booking = UUID.randomUUID().toString();
        jdbc.update("insert into bookings(id,user_id,vehicle_id,pickup_branch_id,return_branch_id,start_at,end_at,created_at,expires_at,status,daily_price,rental_days,rental_total,one_way_fee,total,customer_name,vehicle_name,pickup_name,return_name,policy_version) values (?,'demo-user','v1','dn','dn','2026-10-01 08:00:00','2026-10-02 08:00:00','2026-09-30 08:00:00','2026-09-30 10:00:00','COMPLETED',100,1,100,0,100,'Historical renter','Historical vehicle','Snapshot pickup','Snapshot return','image-test')", booking);
        jdbc.update("insert into contracts(id,booking_id,issued_at,snapshot,terms_version) values (?,?,'2026-09-30 09:00:00','{}','image-test')", UUID.randomUUID().toString(), booking);
        jdbc.update("insert into invoices(id,booking_id,issued_at,total,snapshot) values (?,?,'2026-10-02 09:00:00',100,'{}')", UUID.randomUUID().toString(), booking);
        var history = jdbc.queryForList("select * from bookings where id=?", booking);
        var contracts = jdbc.queryForList("select * from contracts where booking_id=?", booking);
        var invoices = jdbc.queryForList("select * from invoices where booking_id=?", booking);


        // Setup
        String img1 = "https://example.com/car1.jpg";
        String img2 = "https://example.com/car2.jpg";
        String img3 = "https://example.com/car3.jpg";

        Map saved = saveCreate("v1", img1, "Photo 1");
        String rev1 = (String) saved.get("revision");
        List<String> afterFirst = ids("v1");
        assertEquals(1, afterFirst.size());

        saved = saveCreate("v1", img2, "Photo 2");
        String rev2 = (String) saved.get("revision");
        List<String> afterSecond = ids("v1");
        assertEquals(2, afterSecond.size());
        saved = saveCreate("v1", img3, "Photo 3");
        afterSecond = ids("v1");
        assertEquals(3, afterSecond.size());

        // Edit first image
        String firstId = afterFirst.get(0);
        String newUrl = "https://example.com/car1-edited.jpg";
        Map updated = saveUpdate("v1", firstId, newUrl, "Updated photo");
        String rev3 = (String) updated.get("revision");
        assertNotEquals(rev2, rev3);
        List<String> afterEdit = ids("v1");
        assertEquals(afterSecond, afterEdit);
        List<Map<String, Object>> full = fullImages("v1");
        assertEquals(newUrl, full.stream().filter(m -> firstId.equals(m.get("id"))).findFirst().orElseThrow().get("url"));
        assertEquals("Updated photo", full.stream().filter(m -> firstId.equals(m.get("id"))).findFirst().orElseThrow().get("attribution"));

        // Reorder
        List<String> reversed = new ArrayList<>(afterSecond);
        Collections.reverse(reversed);
        clearInvocations(images, tracedJdbc);
        Map reordered = saveReorder("v1", reversed);
        String rev4 = (String) reordered.get("revision");
        assertNotEquals(rev3, rev4);
        List<String> afterReorder = ids("v1");
        assertEquals(reversed, afterReorder);
        verify(images, never()).deleteByVehicleId(anyString());

        // No-op reorder (same order)
        clearInvocations(images, tracedJdbc);
        Map noopReorder = saveReorder("v1", afterReorder);
        assertEquals(rev4, noopReorder.get("revision"));
        verify(images, never()).updateLink(anyString(), any(), any(), anyInt());
        verify(images, never()).updateSortOrderOnly(anyString(), anyInt());
        verify(tracedJdbc, never()).update(anyString(), any(Object[].class));

        // No-op update (same metadata)
        clearInvocations(images, tracedJdbc);
        Map noopUpdate = saveUpdate("v1", firstId, newUrl, "Updated photo");
        assertEquals(rev4, noopUpdate.get("revision"));
        verify(images, never()).updateLink(anyString(), any(), any(), anyInt());
        verify(images, never()).updateSortOrderOnly(anyString(), anyInt());
        verify(tracedJdbc, never()).update(anyString(), any(Object[].class));

        // Verify public API reflects changes
        Map detail = restTemplate.getForObject("/api/v1/vehicles/v1", Map.class);
        List<Map> imagesList = (List<Map>) detail.get("images");
        assertEquals(3, imagesList.size());
        assertEquals(afterReorder.get(0), imagesList.get(0).get("id"));
        assertEquals(afterReorder.get(1), imagesList.get(1).get("id"));
        assertEquals(afterReorder.get(2), imagesList.get(2).get("id"));

        // Other vehicle unchanged
        Map otherDetail = restTemplate.getForObject("/api/v1/vehicles/v2", Map.class);
        assertEquals(0, ((List) otherDetail.get("images")).size());

        // Old vehicle edit preserves images, amenities, version, snapshot
        assertEquals(vehicleBefore, jdbc.queryForList("select * from vehicles order by id"));
        Map vehicleDetail = restTemplate.getForObject("/api/v1/vehicles/v1", Map.class);
        Object originalAmenities = vehicleDetail.get("amenities");
        Object originalImages = vehicleDetail.get("images");
        vehicleDetail.remove("images");
        vehicleDetail.remove("amenities");
        vehicleDetail.put("name", "Updated " + UUID.randomUUID());
        assertEquals(HttpStatus.OK, restTemplate.exchange("/api/v1/admin/vehicles/v1", HttpMethod.PUT, new HttpEntity<>(vehicleDetail, authHeaders(adminToken)), Map.class).getStatusCode());

        Map newVehicleDetail = restTemplate.getForObject("/api/v1/vehicles/v1", Map.class);
        assertEquals(vehicleDetail.get("name"), newVehicleDetail.get("name"));
        assertEquals(((Number) vehicleDetail.get("version")).longValue() + 1, ((Number) newVehicleDetail.get("version")).longValue());
        assertEquals(originalAmenities, newVehicleDetail.get("amenities"));
        assertEquals(originalImages, newVehicleDetail.get("images"));
        assertEquals(afterReorder, ids("v1"));
        assertEquals(history, jdbc.queryForList("select * from bookings where id=?", booking));
        assertEquals(contracts, jdbc.queryForList("select * from contracts where booking_id=?", booking));
        assertEquals(invoices, jdbc.queryForList("select * from invoices where booking_id=?", booking));

        // Stale revision rejected
        String oldRevision = rev4;
        saveCreate("v1", img3, "Photo 3"); // advance revision
        var beforeStale = fullImages("v1");
        assertEquals(HttpStatus.CONFLICT, request("v1", firstId, HttpMethod.PUT, payloadUpdate("https://example.com/stale.jpg", "Stale", oldRevision), adminToken).getStatusCode());
        List<String> validStaleOrder = new ArrayList<>(ids("v1"));
        Collections.reverse(validStaleOrder);
        assertEquals(HttpStatus.CONFLICT, request("v1", "/order", HttpMethod.PUT, payloadReorder(validStaleOrder, oldRevision), adminToken).getStatusCode());
        assertEquals(beforeStale, fullImages("v1"));
    }

    @Test void attributionHandling() {
        // Null/blank attribution becomes null
        Map created = saveCreate("v1", "https://example.com/img.jpg", null);
        List<Map<String, Object>> full = fullImages("v1");
        assertNull(full.get(0).get("attribution"));

        created = saveCreate("v1", "https://example.com/img2.jpg", "  ");
        full = fullImages("v1");
        assertNull(full.stream().filter(m -> "https://example.com/img2.jpg".equals(m.get("url"))).findFirst().orElseThrow().get("attribution"));

        created = saveCreate("v1", "https://example.com/img3.jpg", "  Credit  ");
        full = fullImages("v1");
        assertEquals("Credit", full.stream().filter(m -> "https://example.com/img3.jpg".equals(m.get("url"))).findFirst().orElseThrow().get("attribution"));

        // Update attribution
        String id = ids("v1").get(0);
        Map updated = saveUpdate("v1", id, "https://example.com/img.jpg", "New Credit");
        full = fullImages("v1");
        assertEquals("New Credit", full.stream().filter(m -> id.equals(m.get("id"))).findFirst().orElseThrow().get("attribution"));

        // Update to blank -> null
        updated = saveUpdate("v1", id, "https://example.com/img.jpg", "  ");
        full = fullImages("v1");
        assertNull(full.stream().filter(m -> id.equals(m.get("id"))).findFirst().orElseThrow().get("attribution"));
        saveUpdate("v1", id, "/media/vehicles/credit", "x".repeat(1000));
        var beforeInvalidCredit = fullImages("v1");
        String currentRevision = (String)read("v1").get("revision");
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", id, HttpMethod.PUT,
                payloadUpdate("/media/vehicles/credit", "x".repeat(1001), currentRevision), adminToken).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST,
                payloadCreate("/media/vehicles/credit", "x".repeat(1001), currentRevision), adminToken).getStatusCode());
        assertEquals(beforeInvalidCredit, fullImages("v1"));
    }

    @Test void rollbackAfterRealDeleteAndPartialInsert() {
        saveCreate("v1", "https://example.com/img1.jpg", "Photo 1");
        String revision = (String) read("v1").get("revision");

        var beforeRows = fullImages("v1");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertEquals(2, ids("v1").size(), "Partial insertion visible inside the transaction");
            throw new IllegalStateException("AFTER_REAL_MUTATION");
        }).when(images).insertLink(anyString(), anyString(), anyString(), anyInt(), anyString());

        org.springframework.dao.InvalidDataAccessApiUsageException error = assertThrows(
                org.springframework.dao.InvalidDataAccessApiUsageException.class,
                () -> service.createImage(new com.carrental.security.Actor("demo-admin", "ADMIN", "Admin test"), "v1",
                        new VehicleImageService.CreateRequest("https://example.com/img2.jpg", "Photo 2", revision)));
        assertEquals("AFTER_REAL_MUTATION", error.getMostSpecificCause().getMessage());
        assertEquals(1, ids("v1").size());
        assertEquals(beforeRows, fullImages("v1"));
        assertEquals(revision, read("v1").get("revision"));
    }

    @Test void rollbackAfterRealUpdateAndPartialReorder() {
        saveCreate("v1", "https://example.com/img1.jpg", "Photo 1");
        saveCreate("v1", "https://example.com/img2.jpg", "Photo 2");
        List<String> before = ids("v1");
        String revision = (String) read("v1").get("revision");

        var beforeRows = fullImages("v1");
        // Inject failure during reorder (after first updateSortOrderOnly call)
        // First call uses temp base (negative), second uses final (non-negative). We'll fail on second call.
        doAnswer(invocation -> {
            int sortOrder = invocation.getArgument(1);
            if (sortOrder >= 0) {
                throw new IllegalStateException("AFTER_REAL_REORDER");
            }
            Object result = invocation.callRealMethod();
            assertTrue(fullImages("v1").stream().anyMatch(row -> ((Number)row.get("sort_order")).intValue() < 0));
            return result;
        }).when(images).updateSortOrderOnly(anyString(), anyInt());

        org.springframework.dao.InvalidDataAccessApiUsageException error = assertThrows(
                org.springframework.dao.InvalidDataAccessApiUsageException.class,
                () -> service.reorderImages(new com.carrental.security.Actor("demo-admin", "ADMIN", "Admin test"), "v1",
                        new VehicleImageService.ReorderRequest(List.of(before.get(1), before.get(0)), revision)));
        assertEquals("AFTER_REAL_REORDER", error.getMostSpecificCause().getMessage());
        assertEquals(before, ids("v1")); // rolled back to original order
        assertEquals(beforeRows, fullImages("v1"));
        assertEquals(revision, read("v1").get("revision"));
    }

    @Test void bulkReadUsesBoundedRealQueries() {
        saveCreate("v1", "https://example.com/v1-1.jpg", "P1");
        saveCreate("v1", "https://example.com/v1-2.jpg", "P2");
        saveCreate("v2", "https://example.com/v2-1.jpg", "P1");
        saveCreate("v2", "https://example.com/v2-2.jpg", "P2");
        clearInvocations(tracedJdbc);

        var result = service.getImagesBulk(List.of("v1", "v2", "v3"));
        assertEquals(2, result.get("v1").size());
        assertEquals(2, result.get("v2").size());
        assertEquals(List.of(), result.get("v3"));

        // Should use exactly 1 query for vehicle_images (bulk IN query)
        int queryCount = (int) mockingDetails(tracedJdbc).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("query") && i.getMethod().getParameterTypes().length == 3
                        && i.getMethod().getParameterTypes()[1] == org.springframework.jdbc.core.RowMapper.class)
                .count();
        assertEquals(1, queryCount, "Expected exactly 1 bulk query for vehicle_images");
        clearInvocations(tracedJdbc);
        List<Map> publicRows = restTemplate.getForObject("/api/v1/vehicles", List.class);
        assertEquals(2, ((List)publicRows.stream().filter(row -> "v1".equals(row.get("id"))).findFirst().orElseThrow().get("images")).size());
        long imageQueries = mockingDetails(tracedJdbc).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("query") && i.getMethod().getParameterTypes().length == 3
                    && i.getMethod().getParameterTypes()[1] == org.springframework.jdbc.core.RowMapper.class
                    && i.getArgument(0).toString().contains("from vehicle_images")).count();
        assertEquals(1, imageQueries, "Public list must bulk read images once");
        assertEquals(Map.of(), service.getImagesBulk(List.of()));
    }

    @Test void maxSortOrderAndContiguousOrder() {
        // Verify sort_order starts at 0 and is contiguous
        Map created = saveCreate("v1", "https://example.com/0.jpg", "0");
        List<Map<String, Object>> full = fullImages("v1");
        assertEquals(0, full.get(0).get("sort_order"));

        created = saveCreate("v1", "https://example.com/1.jpg", "1");
        full = fullImages("v1");
        assertEquals(0, full.get(0).get("sort_order"));
        assertEquals(1, full.get(1).get("sort_order"));

        // Reorder should produce contiguous 0..n-1
        List<String> current = ids("v1");
        Collections.reverse(current);
        saveReorder("v1", current);
        full = fullImages("v1");
        assertEquals(0, full.get(0).get("sort_order"));
        assertEquals(1, full.get(1).get("sort_order"));
    }


    @Test void sparseBoundaryOrdersAndUniqueConstraint() {
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        jdbc.update("insert into vehicle_images(id,vehicle_id,url,sort_order) values(?,?,?,?)", a, "v1", "/media/vehicles/a", Integer.MIN_VALUE);
        jdbc.update("insert into vehicle_images(id,vehicle_id,url,sort_order) values(?,?,?,?)", b, "v1", "/media/vehicles/b", Integer.MAX_VALUE);
        var before = fullImages("v1");
        String revision = (String)read("v1").get("revision");
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST, payloadCreate("/media/vehicles/c", null, revision), adminToken).getStatusCode());
        assertEquals(before, fullImages("v1"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("insert into vehicle_images(id,vehicle_id,url,sort_order) values(?,?,?,?)", UUID.randomUUID().toString(), "v1", "/media/vehicles/c", Integer.MAX_VALUE));
        saveReorder("v1", List.of(b,a));
        assertEquals(List.of(b,a), ids("v1"));
        var rows = fullImages("v1");
        assertEquals(0, rows.get(0).get("sort_order"));
        assertEquals(1, rows.get(1).get("sort_order"));
        assertEquals("/media/vehicles/b", rows.get(0).get("url"));
        assertEquals("/media/vehicles/a", rows.get(1).get("url"));
    }

    @Test void urlValidationEdgeCases() {
        String revision = (String) read("v1").get("revision");
        var baseline = fullImages("v1");
        for (String badPath : List.of("https://example.com/%2e%2E/car", "https://example.com/%252e%252E/car", "https://example.com/%2e%2e%2fcar")) {
            assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST,
                    payloadCreate(badPath, "fixture credit", revision), adminToken).getStatusCode());
            assertEquals(baseline, fullImages("v1"));
        }
        // Valid HTTPS
        assertEquals(HttpStatus.CREATED, request("v1", null, HttpMethod.POST, payloadCreate("https://example.com/path?query=..", "ok", revision), adminToken).getStatusCode());

        // Root-relative allowed under /media/vehicles/ with valid filename
        String freshRevision = (String) read("v1").get("revision");
        assertEquals(HttpStatus.CREATED, request("v1", null, HttpMethod.POST, payloadCreate("/media/vehicles/car.jpg", "ok", freshRevision), adminToken).getStatusCode());

        // Invalid root-relative: trailing slash (no filename)
        freshRevision = (String) read("v1").get("revision");
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST, payloadCreate("/media/vehicles/car.jpg/", "ok", freshRevision), adminToken).getStatusCode());

        // Invalid root-relative: outside /media/vehicles/
        freshRevision = (String) read("v1").get("revision");
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST, payloadCreate("/other/path/car.jpg", "ok", freshRevision), adminToken).getStatusCode());

        // Invalid root-relative: query not allowed
        freshRevision = (String) read("v1").get("revision");
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST, payloadCreate("/media/vehicles/car.jpg?query=1", "ok", freshRevision), adminToken).getStatusCode());

        // Invalid root-relative: fragment not allowed
        freshRevision = (String) read("v1").get("revision");
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST, payloadCreate("/media/vehicles/car.jpg#frag", "ok", freshRevision), adminToken).getStatusCode());

        // Invalid root-relative: backslash
        freshRevision = (String) read("v1").get("revision");
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST, payloadCreate("/media/vehicles/car\\.jpg", "ok", freshRevision), adminToken).getStatusCode());

        // Invalid root-relative: percent encoding
        freshRevision = (String) read("v1").get("revision");
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST, payloadCreate("/media/vehicles/car%20.jpg", "ok", freshRevision), adminToken).getStatusCode());

        // Invalid root-relative: empty segment
        freshRevision = (String) read("v1").get("revision");
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST, payloadCreate("/media/vehicles//car.jpg", "ok", freshRevision), adminToken).getStatusCode());

        // Invalid root-relative: dot segment
        freshRevision = (String) read("v1").get("revision");
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST, payloadCreate("/media/vehicles/./car.jpg", "ok", freshRevision), adminToken).getStatusCode());

        // Invalid root-relative: dot-dot segment
        freshRevision = (String) read("v1").get("revision");
        assertEquals(HttpStatus.BAD_REQUEST, request("v1", null, HttpMethod.POST, payloadCreate("/media/vehicles/../car.jpg", "ok", freshRevision), adminToken).getStatusCode());

        // Valid root-relative: nested path with valid segments
        freshRevision = (String) read("v1").get("revision");
        assertEquals(HttpStatus.CREATED, request("v1", null, HttpMethod.POST, payloadCreate("/media/vehicles/folder/subfolder/car.jpg", "ok", freshRevision), adminToken).getStatusCode());
    }

    @Test @EnabledIfEnvironmentVariable(named="KDRIVE_TEST_DB_URL", matches="jdbc:mysql:.*")
    void concurrentMySqlUpdatesHaveOneWinner() throws Exception {
        saveCreate("v1", "https://example.com/base.jpg", "Base");
        String revision = (String) read("v1").get("revision");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (String url : List.of("https://example.com/a.jpg", "https://example.com/b.jpg")) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(10, TimeUnit.SECONDS));
                    return request("v1", null, HttpMethod.POST, payloadCreate(url, "Concurrent", revision), adminToken);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            Map winner = null;
            for (var f : futures) {
                var response = f.get(20, TimeUnit.SECONDS);
                statuses.add(response.getStatusCode().value());
                if (response.getStatusCode().value() == 201) winner = response.getBody();
            }
            Collections.sort(statuses);
            assertEquals(List.of(201, 409), statuses);
            List<Map> winnerItems = (List<Map>) winner.get("images");
            assertEquals(2, winnerItems.size());
            List<String> dbIds = ids("v1");
            assertEquals(winnerItems.size(), dbIds.size());
        } finally {
            go.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test @EnabledIfEnvironmentVariable(named="KDRIVE_TEST_DB_URL", matches="jdbc:mysql:.*")
    void concurrentMySqlReorderHasOneWinner() throws Exception {
        saveCreate("v1", "https://example.com/1.jpg", "1");
        saveCreate("v1", "https://example.com/2.jpg", "2");
        List<String> ids = ids("v1");
        String revision = (String) read("v1").get("revision");
        List<String> reversed = new ArrayList<>(ids);
        Collections.reverse(reversed);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(10, TimeUnit.SECONDS));
                    return request("v1", "/order", HttpMethod.PUT, payloadReorder(reversed, revision), adminToken);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            Map winner = null;
            for (var f : futures) {
                var response = f.get(20, TimeUnit.SECONDS);
                statuses.add(response.getStatusCode().value());
                if (response.getStatusCode().value() == 200) winner = response.getBody();
            }
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
            List<String> dbIds = ids("v1");
            List<Map> winnerItems = (List<Map>) winner.get("images");
            List<String> winnerIds = winnerItems.stream().map(m -> (String) m.get("id")).toList();
            assertEquals(winnerIds, dbIds);
        } finally {
            go.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
