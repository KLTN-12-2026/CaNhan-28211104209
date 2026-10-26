package com.carrental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.*;
import com.carrental.security.TokenService;
import com.carrental.security.Actor;
import com.carrental.modules.vehicle.service.VehicleService;

import java.time.Instant;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "app.jwt-secret=integration-test-only-secret-at-least-32-bytes",
    "app.token-minutes=30",
    "app.seed-enabled=true",
    "app.seed-password=kdrive_seed_pw_c7_incident_http_20261011",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@Import(IncidentReportHttpServerTest.TimeConfig.class)
public class IncidentReportHttpServerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

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
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:incidentreporthttp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        r.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        r.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    static class MutableClock extends java.time.Clock {
        Instant value = Instant.parse("2026-10-15T12:00:00Z");
        public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        public java.time.Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return value; }
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired com.carrental.modules.user.service.AccountService accountService;
    @Autowired MutableClock clock;
    @Autowired VehicleService vehicleService;

    private String userToken;
    private String otherUserToken;
    private String staffHnToken;
    private String staffDnToken;
    private String staffSgnToken;
    private String unassignedStaffToken;
    private String adminToken;

    @BeforeEach
    void setupTokens() {
        clock.value = Instant.parse("2026-10-15T12:00:00Z");

        ensureUserExists("other-user-http", "Khách khác HTTP", "other.http@example.com", "USER");
        ensureUserExists("demo-staff-sgn-http", "Staff Sài Gòn HTTP", "staff.sgn.http@example.com", "STAFF");
        ensureUserExists("unassigned-staff-http", "Staff Không Chi Nhánh HTTP", "unassigned.http@example.com", "STAFF");
        if (jdbc.queryForObject("select count(*) from branches where id='sgn'", Long.class) == 0) {
            jdbc.update("insert into branches(id, name, address, status, version) values('sgn', 'Sài Gòn · mẫu', 'Điểm mẫu SG', 'ACTIVE', 0)");
        }
        if (jdbc.queryForObject("select count(*) from staff_branches where user_id='demo-staff-sgn-http' and branch_id='sgn'", Long.class) == 0) {
            jdbc.update("insert into staff_branches(user_id, branch_id) values(?, ?)", "demo-staff-sgn-http", "sgn");
        }

        userToken = "Bearer " + tokens.issue(accountService.require("demo-user"));
        otherUserToken = "Bearer " + tokens.issue(accountService.require("other-user-http"));
        staffHnToken = "Bearer " + tokens.issue(accountService.require("demo-staff-hn"));
        staffDnToken = "Bearer " + tokens.issue(accountService.require("demo-staff-dn"));
        staffSgnToken = "Bearer " + tokens.issue(accountService.require("demo-staff-sgn-http"));
        unassignedStaffToken = "Bearer " + tokens.issue(accountService.require("unassigned-staff-http"));
        adminToken = "Bearer " + tokens.issue(accountService.require("demo-admin"));

        IncidentEvidence.ensurePopulatedRepresentativeRows(jdbc);
    }

    private void ensureUserExists(String id, String fullName, String email, String role) {
        Long cnt = jdbc.queryForObject("select count(*) from users where id=?", Long.class, id);
        if (cnt == 0) {
            jdbc.update("insert into users(id, full_name, email, phone, password_hash, role, status, token_version, version) values(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, fullName, email, "0900000000", "pw_dummy", role, "ACTIVE", 0L, 0L);
        }
    }

    private record FixtureContext(String bookingId, String vehicleId, Instant handoverTime, Instant returnTime) {}

    private FixtureContext createBookingFixture(String status, String pickupBranch, String returnBranch, String vehicleCurrentBranch, boolean withHandover, boolean withReturn) {
        String bookingId = "bkh-" + UUID.randomUUID().toString().substring(0, 8);
        String vehicleId = "vehh-" + UUID.randomUUID().toString().substring(0, 8);

        jdbc.update("insert into vehicles(id, name, plate, brand_id, category_id, home_branch_id, current_branch_id, seats, transmission, daily_price, status, description, version) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            vehicleId, "Xe HTTP " + vehicleId, "29A-" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(), "toyota", "sedan", pickupBranch, vehicleCurrentBranch, 5, "AUTO", 1000000L, "ACTIVE", "Mô tả", 0L);

        Instant startAt = clock.value.minus(Duration.ofDays(4));
        Instant endAt = clock.value.minus(Duration.ofDays(1));
        Instant handoverTime = startAt.plus(Duration.ofHours(1));
        Instant returnTime = endAt.minus(Duration.ofHours(1));

        jdbc.update("insert into bookings(id, user_id, vehicle_id, customer_name, vehicle_name, pickup_branch_id, return_branch_id, pickup_name, return_name, actual_return_branch_id, start_at, end_at, expires_at, status, daily_price, rental_days, rental_total, one_way_fee, total, policy_version, version, created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            bookingId, "demo-user", vehicleId, "Khách hàng", "Xe HTTP " + vehicleId, pickupBranch, returnBranch, pickupBranch, returnBranch, null,
            java.sql.Timestamp.from(startAt), java.sql.Timestamp.from(endAt), java.sql.Timestamp.from(startAt), status, 1000000L, 3, 3000000L, 0L, 3000000L, "rental-2026-09-28", 0L, java.sql.Timestamp.from(startAt.minus(Duration.ofDays(1))));

        if (withHandover) {
            jdbc.update("insert into vehicle_handover_records(id, booking_id, kind, actor_id, branch_id, occurred_at, condition_note, maintenance) values(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), bookingId, "HANDOVER", "demo-staff-hn", pickupBranch, java.sql.Timestamp.from(handoverTime), "Bàn giao xe", false);
        }

        if (withReturn) {
            jdbc.update("insert into vehicle_handover_records(id, booking_id, kind, actor_id, branch_id, occurred_at, condition_note, maintenance) values(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), bookingId, "RETURN", "demo-staff-dn", returnBranch, java.sql.Timestamp.from(returnTime), "Nhận xe", false);
        }

        return new FixtureContext(bookingId, vehicleId, handoverTime, returnTime);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.set("Authorization", token);
        return headers;
    }

    @Test
    void testRealHttp_PositiveReportAndReadbackOverSocket() {
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);

        String url = "http://localhost:" + port + "/api/v1/incidents";
        Instant occurred = fix.handoverTime.plus(Duration.ofHours(2));
        Map<String, Object> req = Map.of(
            "bookingId", fix.bookingId,
            "kind", "ACCIDENT",
            "occurredAt", occurred.toString(),
            "description", "Sự cố giao thông qua real HTTP client",
            "evidenceReference", "http://evidence.test/photo.jpg",
            "blockVehicle", false
        );

        var snapBefore = IncidentEvidence.snapshot(jdbc);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(req, authHeaders(userToken)), Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "Real HTTP response status must be 200 OK");
        assertNotNull(response.getBody());
        assertEquals("OPEN", response.getBody().get("status"));
        assertEquals("ACCIDENT", response.getBody().get("kind"));
        assertEquals(occurred.toString(), response.getBody().get("occurredAt"));
        assertEquals(clock.value.toString(), response.getBody().get("reportedAt"));
        assertEquals(0, ((Number) response.getBody().get("assessedDamage")).intValue());
        assertEquals(0, ((Number) response.getBody().get("thirdPartyCoverage")).intValue());
        assertEquals(0, ((Number) response.getBody().get("requestedAmount")).intValue());
        assertNull(response.getBody().get("responsibilityBasis"));

        String caseId = (String) response.getBody().get("id");
        assertNotNull(caseId);

        // Khẳng định bất biến các bảng không liên quan
        var snapAfter = IncidentEvidence.snapshot(jdbc);
        for (String invariantTable : List.of("bookings", "booking_status_history", "contracts", "invoices", "invoice_items", "reviews", "vehicle_handover_records")) {
            assertEquals(snapBefore.get(invariantTable), snapAfter.get(invariantTable),
                "Table " + invariantTable + " must remain 100% untouched and invariant after positive HTTP report");
        }

        // Readback GET list over HTTP (kiểm tra IDs, linking, fields)
        ResponseEntity<List> listResp = restTemplate.exchange(url + "?bookingId=" + fix.bookingId, HttpMethod.GET, new HttpEntity<>(authHeaders(userToken)), List.class);
        assertEquals(HttpStatus.OK, listResp.getStatusCode());
        assertNotNull(listResp.getBody());
        assertEquals(1, listResp.getBody().size());
        Map firstItem = (Map) listResp.getBody().get(0);
        assertEquals(caseId, firstItem.get("id"));
        assertEquals(fix.bookingId, firstItem.get("bookingId"));
        assertEquals("ACCIDENT", firstItem.get("kind"));
        assertEquals("OPEN", firstItem.get("status"));

        // Readback GET events over HTTP (kiểm tra event details, actor, action)
        ResponseEntity<List> eventsResp = restTemplate.exchange(url + "/" + caseId + "/events", HttpMethod.GET, new HttpEntity<>(authHeaders(userToken)), List.class);
        assertEquals(HttpStatus.OK, eventsResp.getStatusCode());
        assertNotNull(eventsResp.getBody());
        assertEquals(1, eventsResp.getBody().size());
        Map firstEvent = (Map) eventsResp.getBody().get(0);
        assertNotNull(firstEvent.get("id"));
        assertEquals("OPEN", firstEvent.get("action"));
        assertEquals("demo-user", firstEvent.get("actor_id"));
        assertEquals("Tiếp nhận hồ sơ; chưa kết luận trách nhiệm.", firstEvent.get("note"));
        var storedEvent = jdbc.queryForMap("select * from incident_events where id=?", firstEvent.get("id"));
        assertEquals(caseId, storedEvent.get("case_id"));
        assertEquals(clock.value, ((java.sql.Timestamp) storedEvent.get("occurred_at")).toInstant());
        assertNull(storedEvent.get("previous_status"));
        assertEquals("OPEN", storedEvent.get("next_status"));
        for (String column : List.of("assessed_damage", "third_party_coverage", "requested_amount")) {
            assertEquals(0L, ((Number) storedEvent.get(column)).longValue());
        }
        assertNull(storedEvent.get("responsibility_basis"));
    }

    @Test
    void testRealHttp_NegativeCustodyAndInvarianceOverSocket() {
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
        String url = "http://localhost:" + port + "/api/v1/incidents";

        // Occurred 1 microsecond before handover
        Instant badTime = fix.handoverTime.minusNanos(1000);
        Map<String, Object> req = Map.of(
            "bookingId", fix.bookingId,
            "kind", "ACCIDENT",
            "occurredAt", badTime.toString(),
            "description", "Sự cố ngoài ranh giới qua HTTP",
            "evidenceReference", "ref-http",
            "blockVehicle", false
        );

        var snapBefore = IncidentEvidence.snapshot(jdbc);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(req, authHeaders(userToken)), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "Real HTTP outside custody must return 400");
        assertEquals("Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.", response.getBody().get("message"));

        var snapAfter = IncidentEvidence.snapshot(jdbc);
        assertEquals(snapBefore, snapAfter, "Database state must be 100% invariant on HTTP rejection");
    }

    @Test
    void testRealHttp_ForbiddenUserBlockVehicleOverSocket() {
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
        String url = "http://localhost:" + port + "/api/v1/incidents";

        Map<String, Object> req = Map.of(
            "bookingId", fix.bookingId,
            "kind", "DAMAGE",
            "occurredAt", fix.handoverTime.plus(Duration.ofHours(1)).toString(),
            "description", "Khách cố khóa xe qua HTTP",
            "evidenceReference", "ref-http",
            "blockVehicle", true
        );

        var snapBefore = IncidentEvidence.snapshot(jdbc);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(req, authHeaders(userToken)), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), "User attempting to block vehicle must receive 403 Forbidden");

        var snapAfter = IncidentEvidence.snapshot(jdbc);
        assertEquals(snapBefore, snapAfter, "Database state and vehicle status must be 100% invariant on 403 rejection");
        assertEquals("ACTIVE", vehicleService.get(fix.vehicleId).status);
    }

    @Test
    void testRealHttp_ForeignUserDenied404OverSocket() {
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
        String url = "http://localhost:" + port + "/api/v1/incidents";

        Map<String, Object> req = Map.of(
            "bookingId", fix.bookingId,
            "kind", "DAMAGE",
            "occurredAt", fix.handoverTime.plus(Duration.ofHours(1)).toString(),
            "description", "Khách lạ cố báo sự cố qua HTTP",
            "evidenceReference", "ref-http",
            "blockVehicle", false
        );

        var snapBefore = IncidentEvidence.snapshot(jdbc);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(req, authHeaders(otherUserToken)), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "Foreign user must receive 404 Not Found (no booking leakage)");

        var snapAfter = IncidentEvidence.snapshot(jdbc);
        assertEquals(snapBefore, snapAfter, "Database state must be 100% invariant on 404 rejection");
    }

    @Test
    void testRealHttp_UnauthorizedWhenNoTokenOverSocket() {
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
        String url = "http://localhost:" + port + "/api/v1/incidents";

        Map<String, Object> req = Map.of(
            "bookingId", fix.bookingId,
            "kind", "DAMAGE",
            "occurredAt", fix.handoverTime.plus(Duration.ofHours(1)).toString(),
            "description", "Không gửi token",
            "evidenceReference", "ref-http",
            "blockVehicle", false
        );

        var snapBefore = IncidentEvidence.snapshot(jdbc);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(req, authHeaders(null)), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(), "Missing JWT must receive 401 Unauthorized");

        var snapAfter = IncidentEvidence.snapshot(jdbc);
        assertEquals(snapBefore, snapAfter, "Database state must be 100% invariant on 401 rejection");
    }

    @Test
    void testRealHttp_PayloadValidationRejectionsOverSocket() {
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
        String url = "http://localhost:" + port + "/api/v1/incidents";
        Instant validTime = fix.handoverTime.plus(Duration.ofHours(1));

        // 1. Invalid kind
        assertPostHttpRejection(url, userToken, Map.of(
            "bookingId", fix.bookingId, "kind", "INVALID_KIND", "occurredAt", validTime.toString(),
            "description", "Mô tả", "evidenceReference", "ref", "blockVehicle", false
        ), HttpStatus.BAD_REQUEST, "Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.");

        // 2. Blank description
        assertPostHttpRejection(url, userToken, Map.of(
            "bookingId", fix.bookingId, "kind", "ACCIDENT", "occurredAt", validTime.toString(),
            "description", "   ", "evidenceReference", "ref", "blockVehicle", false
        ), HttpStatus.BAD_REQUEST, "Cần nội dung có căn cứ, tối đa 1500 ký tự.");

        // 3. Oversized description (>1500 chars)
        assertPostHttpRejection(url, userToken, Map.of(
            "bookingId", fix.bookingId, "kind", "ACCIDENT", "occurredAt", validTime.toString(),
            "description", "A".repeat(1501), "evidenceReference", "ref", "blockVehicle", false
        ), HttpStatus.BAD_REQUEST, "Cần nội dung có căn cứ, tối đa 1500 ký tự.");

        // 4. Oversized evidence reference (>2000 chars)
        assertPostHttpRejection(url, userToken, Map.of(
            "bookingId", fix.bookingId, "kind", "ACCIDENT", "occurredAt", validTime.toString(),
            "description", "Mô tả chuẩn", "evidenceReference", "http://ref.test/".concat("x".repeat(2000)), "blockVehicle", false
        ), HttpStatus.BAD_REQUEST, "Tham chiếu tài liệu quá dài.");
    }

    @Test
    void testRealHttp_LateReportOnReturnedAndCompletedOverSocket() {
        // Late report trên RETURNED
        FixtureContext fixReturned = createBookingFixture("RETURNED", "hn", "dn", "dn", true, true);
        String url = "http://localhost:" + port + "/api/v1/incidents";

        // a. Occurred exactly at RETURN record time -> ACCEPTED
        Map<String, Object> reqExactReturn = Map.of(
            "bookingId", fixReturned.bookingId, "kind", "DAMAGE", "occurredAt", fixReturned.returnTime.toString(),
            "description", "Báo muộn đúng lúc trả xe trên chuyến RETURNED", "evidenceReference", "ref-ret", "blockVehicle", false
        );
        ResponseEntity<Map> respReturn = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(reqExactReturn, authHeaders(userToken)), Map.class);
        assertEquals(HttpStatus.OK, respReturn.getStatusCode());
        assertEquals("OPEN", respReturn.getBody().get("status"));

        // b. Occurred 1 microsecond after RETURN record time -> REJECTED 400
        Instant oneMicrosAfterReturn = fixReturned.returnTime.plusNanos(1000);
        assertPostHttpRejection(url, userToken, Map.of(
            "bookingId", fixReturned.bookingId, "kind", "DAMAGE", "occurredAt", oneMicrosAfterReturn.toString(),
            "description", "Báo muộn sau khi đã trả xe 1µs", "evidenceReference", "ref-after-ret", "blockVehicle", false
        ), HttpStatus.BAD_REQUEST, "Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.");

        // Late report trên COMPLETED
        FixtureContext fixCompleted = createBookingFixture("COMPLETED", "hn", "dn", "dn", true, true);
        Map<String, Object> reqCompleted = Map.of(
            "bookingId", fixCompleted.bookingId, "kind", "VIOLATION", "occurredAt", fixCompleted.returnTime.minus(Duration.ofHours(2)).toString(),
            "description", "Báo muộn phạt nguội trên chuyến COMPLETED", "evidenceReference", "ref-comp", "blockVehicle", false
        );
        ResponseEntity<Map> respCompleted = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(reqCompleted, authHeaders(userToken)), Map.class);
        assertEquals(HttpStatus.OK, respCompleted.getStatusCode());
        assertEquals("OPEN", respCompleted.getBody().get("status"));
    }

    @Test
    void testRealHttp_BranchAuthorizationAndBlockVehicleOverSocket() {
        String url = "http://localhost:" + port + "/api/v1/incidents";

        // 1. Staff không gán chi nhánh cố đọc booking -> 404 (DomainException::missing)
        FixtureContext fixHn = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
        var snapBefore1 = IncidentEvidence.snapshot(jdbc);
        ResponseEntity<Map> respUnassigned = restTemplate.exchange(url + "?bookingId=" + fixHn.bookingId, HttpMethod.GET, new HttpEntity<>(authHeaders(unassignedStaffToken)), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, respUnassigned.getStatusCode());
        assertEquals(snapBefore1, IncidentEvidence.snapshot(jdbc), "Invariant on 404 GET list");

        // 2. Staff tại chi nhánh hiện tại của xe (HN) báo sự cố và khóa xe thành công -> 200, status=MAINTENANCE
        Instant occurred1 = fixHn.handoverTime.plus(Duration.ofHours(1));
        ResponseEntity<Map> respStaffHn = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(Map.of(
            "bookingId", fixHn.bookingId, "kind", "DAMAGE", "occurredAt", occurred1.toString(),
            "description", "Staff HN lập biên bản và khóa xe", "evidenceReference", "ref-staff-hn", "blockVehicle", true
        ), authHeaders(staffHnToken)), Map.class);
        assertEquals(HttpStatus.OK, respStaffHn.getStatusCode());
        assertEquals("MAINTENANCE", vehicleService.get(fixHn.vehicleId).status);

        // 3. Staff chi nhánh trả cũ (Đà Nẵng 'dn') cố khóa xe khi xe đang ở chi nhánh hiện tại 'hn' -> 403 Forbidden
        FixtureContext fixHn2 = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
        var snapBefore3 = IncidentEvidence.snapshot(jdbc);
        ResponseEntity<Map> respStaffDnWrongBranch = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(Map.of(
            "bookingId", fixHn2.bookingId, "kind", "DAMAGE", "occurredAt", fixHn2.handoverTime.plus(Duration.ofHours(1)).toString(),
            "description", "Staff DN cố khóa xe ở HN", "evidenceReference", "ref-staff-dn", "blockVehicle", true
        ), authHeaders(staffDnToken)), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, respStaffDnWrongBranch.getStatusCode());
        assertEquals(snapBefore3, IncidentEvidence.snapshot(jdbc), "Database invariant on 403 Staff wrong branch");
        assertEquals("ACTIVE", vehicleService.get(fixHn2.vehicleId).status);

        // 4. Admin khóa xe thành công không phụ thuộc chi nhánh -> 200, status=MAINTENANCE
        ResponseEntity<Map> respAdmin = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(Map.of(
            "bookingId", fixHn2.bookingId, "kind", "ACCIDENT", "occurredAt", fixHn2.handoverTime.plus(Duration.ofHours(1)).toString(),
            "description", "Admin chỉ đạo khóa xe", "evidenceReference", "ref-admin", "blockVehicle", true
        ), authHeaders(adminToken)), Map.class);
        assertEquals(HttpStatus.OK, respAdmin.getStatusCode());
        assertEquals("MAINTENANCE", vehicleService.get(fixHn2.vehicleId).status);
    }

    @Test
    void testRealHttp_ForeignUserGetReadbackDenied404OverSocket() {
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
        String url = "http://localhost:" + port + "/api/v1/incidents";

        // Tạo 1 incident case hợp lệ trước
        ResponseEntity<Map> createResp = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(Map.of(
            "bookingId", fix.bookingId, "kind", "DAMAGE", "occurredAt", fix.handoverTime.plus(Duration.ofHours(1)).toString(),
            "description", "Sự cố hợp lệ", "evidenceReference", "ref", "blockVehicle", false
        ), authHeaders(userToken)), Map.class);
        assertEquals(HttpStatus.OK, createResp.getStatusCode());
        String caseId = (String) createResp.getBody().get("id");

        // a. Khách lạ GET list -> 404 & database 100% invariant
        var snapBeforeList = IncidentEvidence.snapshot(jdbc);
        ResponseEntity<Map> getListResp = restTemplate.exchange(url + "?bookingId=" + fix.bookingId, HttpMethod.GET, new HttpEntity<>(authHeaders(otherUserToken)), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, getListResp.getStatusCode());
        assertEquals(snapBeforeList, IncidentEvidence.snapshot(jdbc), "Invariant on 404 GET list foreign user");

        // b. Khách lạ GET events -> 404 & database 100% invariant
        var snapBeforeEvents = IncidentEvidence.snapshot(jdbc);
        ResponseEntity<Map> getEventsResp = restTemplate.exchange(url + "/" + caseId + "/events", HttpMethod.GET, new HttpEntity<>(authHeaders(otherUserToken)), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, getEventsResp.getStatusCode());
        assertEquals(snapBeforeEvents, IncidentEvidence.snapshot(jdbc), "Invariant on 404 GET events foreign user");
    }

    @Test
    void testRealHttp_NullFieldsAndCustodyEndpoints() {
        String url = "http://localhost:" + port + "/api/v1/incidents";
        for (String field : List.of("kind", "occurredAt")) {
            FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
            Map<String, Object> payload = reportPayload(fix, fix.handoverTime);
            payload.put(field, null);
            assertPostHttpRejection(url, userToken, payload, HttpStatus.BAD_REQUEST,
                "Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.");
        }
        for (boolean atStart : List.of(true, false)) {
            FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
            Instant time = atStart ? fix.handoverTime : clock.value;
            var response = restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(reportPayload(fix, time), authHeaders(userToken)), Map.class);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(time.toString(), response.getBody().get("occurredAt"));
        }
        FixtureContext future = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
        assertPostHttpRejection(url, userToken, reportPayload(future, clock.value.plusNanos(1000)),
            HttpStatus.BAD_REQUEST, "Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.");
    }

    @Test
    void testRealHttp_InvalidStatusMissingHandoverAndUnassignedStaffPost() {
        String url = "http://localhost:" + port + "/api/v1/incidents";
        for (String state : List.of("PENDING", "CONFIRMED")) {
            FixtureContext fix = createBookingFixture(state, "hn", "dn", "hn", false, false);
            assertPostHttpRejection(url, userToken, reportPayload(fix, clock.value), HttpStatus.BAD_REQUEST,
                "Chỉ ghi nhận sự cố của chuyến đã giao xe.");
        }
        FixtureContext missing = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", false, false);
        assertPostHttpRejection(url, userToken, reportPayload(missing, clock.value), HttpStatus.CONFLICT,
            "Chưa có biên bản bàn giao xe.");
        FixtureContext denied = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
        assertPostHttpRejection(url, unassignedStaffToken, reportPayload(denied, denied.handoverTime),
            HttpStatus.NOT_FOUND, "Không tìm thấy dữ liệu.");
    }

    private Map<String, Object> reportPayload(FixtureContext fix, Instant time) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", fix.bookingId);
        payload.put("kind", "DAMAGE");
        payload.put("occurredAt", time.toString());
        payload.put("description", "Report boundary regression");
        payload.put("evidenceReference", "fixture-evidence");
        payload.put("blockVehicle", false);
        return payload;
    }

    private void assertPostHttpRejection(String url, String token, Map<String, Object> req, HttpStatus expectedStatus, String expectedMessage) {
        var snapBefore = IncidentEvidence.snapshot(jdbc);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(req, authHeaders(token)), Map.class);
        assertEquals(expectedStatus, response.getStatusCode(), "Rejection HTTP status mismatch");
        if (expectedMessage != null) {
            assertEquals(expectedMessage, response.getBody().get("message"));
        }
        var snapAfter = IncidentEvidence.snapshot(jdbc);
        assertEquals(snapBefore, snapAfter, "Database state must be 100% invariant across all 10 tables on HTTP rejection");
    }
}

