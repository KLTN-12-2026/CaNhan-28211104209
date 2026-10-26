package com.carrental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import com.carrental.security.TokenService;
import com.carrental.security.Actor;
import com.carrental.modules.operation.service.IncidentService;
import com.carrental.modules.vehicle.service.VehicleService;

import java.time.Instant;
import java.time.Duration;
import java.util.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
    "app.jwt-secret=integration-test-only-secret-at-least-32-bytes",
    "app.token-minutes=30",
    "app.seed-enabled=true",
    "app.seed-password=kdrive_seed_pw_c7_incident_20261011",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@AutoConfigureMockMvc
@Import(IncidentReportIntegrationTest.TimeConfig.class)
public class IncidentReportIntegrationTest {

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
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:incidentreport;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
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

    static class InjectedFaultException extends RuntimeException {
        public InjectedFaultException(String message) {
            super(message);
        }
    }

    @Autowired MockMvc mvc;
    @org.springframework.boot.test.mock.mockito.SpyBean JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired com.carrental.modules.user.service.AccountService accountService;
    @Autowired MutableClock clock;
    @Autowired IncidentService incidentService;
    @Autowired com.carrental.modules.operation.repository.IncidentCaseRepository incidentCaseRepository;
    @Autowired VehicleService vehicleService;
    @Autowired TransactionTemplate tx;

    private String userToken;
    private String otherUserToken;
    private String staffHnToken;
    private String staffDnToken;
    private String staffSgnToken;
    private String adminToken;

    private void assertNegativePost(String token, String content, org.springframework.test.web.servlet.ResultMatcher statusMatcher, String expectedMessage) throws Exception {
        Map<String, Object> before = IncidentEvidence.snapshot(jdbc);
        var req = post("/api/v1/incidents").contentType(MediaType.APPLICATION_JSON).content(content);
        if (token != null) {
            req.header("Authorization", token);
        }
        var actions = mvc.perform(req).andExpect(statusMatcher);
        if (expectedMessage != null) {
            actions.andExpect(jsonPath("$.message").value(expectedMessage));
        }
        Map<String, Object> after = IncidentEvidence.snapshot(jdbc);
        assertEquals(before, after, "Database table state across all 10 tables must remain 100% invariant on negative rejection");
    }

    private void assertNegativeGet(String token, String uri, String paramName, String paramValue, org.springframework.test.web.servlet.ResultMatcher statusMatcher, String expectedMessage) throws Exception {
        Map<String, Object> before = IncidentEvidence.snapshot(jdbc);
        var req = get(uri);
        if (token != null) {
            req.header("Authorization", token);
        }
        if (paramName != null && paramValue != null) {
            req.param(paramName, paramValue);
        }
        var actions = mvc.perform(req).andExpect(statusMatcher);
        if (expectedMessage != null) {
            actions.andExpect(jsonPath("$.message").value(expectedMessage));
        }
        Map<String, Object> after = IncidentEvidence.snapshot(jdbc);
        assertEquals(before, after, "Database table state across all 10 tables must remain 100% invariant on negative GET rejection");
    }

    @BeforeEach
    void setupTokens() {
        clock.value = Instant.parse("2026-10-15T12:00:00Z");

        // Đảm bảo users và chi nhánh sgn tồn tại cho FK trong DB
        ensureUserExists("other-user", "Khách khác", "other@example.com", "USER");
        ensureUserExists("demo-staff-sgn", "Staff Sài Gòn", "staff.sgn@example.com", "STAFF");
        if (jdbc.queryForObject("select count(*) from branches where id='sgn'", Long.class) == 0) {
            jdbc.update("insert into branches(id, name, address, status, version) values('sgn', 'Sài Gòn · mẫu', 'Điểm mẫu SG', 'ACTIVE', 0)");
        }
        // Gán staff-sgn vào chi nhánh sgn
        if (jdbc.queryForObject("select count(*) from staff_branches where user_id='demo-staff-sgn' and branch_id='sgn'", Long.class) == 0) {
            jdbc.update("insert into staff_branches(user_id, branch_id) values(?, ?)", "demo-staff-sgn", "sgn");
        }

        userToken = "Bearer " + tokens.issue(accountService.require("demo-user"));
        otherUserToken = "Bearer " + tokens.issue(accountService.require("other-user"));
        staffHnToken = "Bearer " + tokens.issue(accountService.require("demo-staff-hn"));
        staffDnToken = "Bearer " + tokens.issue(accountService.require("demo-staff-dn"));
        staffSgnToken = "Bearer " + tokens.issue(accountService.require("demo-staff-sgn"));
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

    /**
     * Helper tạo fixture booking và handover records độc lập với id duy nhất
     */
    private record FixtureContext(String bookingId, String vehicleId, Instant handoverTime, Instant returnTime) {}

    private FixtureContext createBookingFixture(String status, String pickupBranch, String returnBranch, String vehicleCurrentBranch, boolean withHandover, boolean withReturn) {
        String bookingId = "bk-" + UUID.randomUUID().toString().substring(0, 8);
        String vehicleId = "veh-" + UUID.randomUUID().toString().substring(0, 8);

        // Tạo vehicle với homeBranch=pickupBranch và currentBranch=vehicleCurrentBranch
        jdbc.update("insert into vehicles(id, name, plate, brand_id, category_id, home_branch_id, current_branch_id, seats, transmission, daily_price, status, description, version) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            vehicleId, "Xe Test " + vehicleId, "29A-" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(), "toyota", "sedan", pickupBranch, vehicleCurrentBranch, 5, "AUTO", 1000000L, "ACTIVE", "Mô tả", 0L);

        Instant startAt = clock.value.minus(Duration.ofDays(4));
        Instant endAt = clock.value.minus(Duration.ofDays(1));
        Instant handoverTime = startAt.plus(Duration.ofHours(1));
        Instant returnTime = endAt.minus(Duration.ofHours(1));

        jdbc.update("insert into bookings(id, user_id, vehicle_id, customer_name, vehicle_name, pickup_branch_id, return_branch_id, pickup_name, return_name, actual_return_branch_id, start_at, end_at, expires_at, status, daily_price, rental_days, rental_total, one_way_fee, total, policy_version, version, created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            bookingId, "demo-user", vehicleId, "Khách hàng", "Xe Test " + vehicleId, pickupBranch, returnBranch, pickupBranch, returnBranch, null,
            java.sql.Timestamp.from(startAt), java.sql.Timestamp.from(endAt), java.sql.Timestamp.from(startAt), status, 1000000L, 4, 4000000L, 0L, 4000000L, "rental-2026-09-28", 0L, java.sql.Timestamp.from(startAt.minus(Duration.ofDays(1))));

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

    // =========================================================================
    // 1. Custody Time Boundary Tests (IN_PROGRESS, RETURNED, COMPLETED)
    // =========================================================================

    @Test
    void testCustodyBoundary_InProgress_ExactAndMicroseconds() throws Exception {
        // IN_PROGRESS: custodyStart = HANDOVER.occurred_at, custodyEnd = clock.instant()
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);

        // a. Exact custodyStart (HANDOVER time) -> ACCEPTED (inclusive)
        mvc.perform(post("/api/v1/incidents")
                .header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "bookingId": "%s",
                        "kind": "ACCIDENT",
                        "occurredAt": "%s",
                        "description": "Sự cố đúng thời điểm bắt đầu bàn giao",
                        "evidenceReference": "ref-exact-start",
                        "blockVehicle": false
                    }
                    """.formatted(fix.bookingId, fix.handoverTime.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.kind").value("ACCIDENT"));

        // b. Outside custodyStart by 1 microsecond before -> REJECTED (400)
        Instant oneMicrosBeforeStart = fix.handoverTime.minusNanos(1000);
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "DAMAGE",
                "occurredAt": "%s",
                "description": "Sự cố trước bàn giao 1 microsecond",
                "evidenceReference": "ref-before-start",
                "blockVehicle": false
            }
            """.formatted(fix.bookingId, oneMicrosBeforeStart.toString()),
            status().isBadRequest(), "Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.");

        // c. Exact custodyEnd (clock.instant() for IN_PROGRESS) -> ACCEPTED
        mvc.perform(post("/api/v1/incidents")
                .header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "bookingId": "%s",
                        "kind": "VIOLATION",
                        "occurredAt": "%s",
                        "description": "Sự cố đúng thời điểm hiện tại",
                        "evidenceReference": "ref-exact-now",
                        "blockVehicle": false
                    }
                    """.formatted(fix.bookingId, clock.value.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // d. Outside custodyEnd by 1 microsecond after clock.instant() -> REJECTED (400)
        Instant oneMicrosAfterNow = clock.value.plusNanos(1000);
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "VIOLATION",
                "occurredAt": "%s",
                "description": "Sự cố sau hiện tại 1 microsecond",
                "evidenceReference": "ref-future-micros",
                "blockVehicle": false
            }
            """.formatted(fix.bookingId, oneMicrosAfterNow.toString()),
            status().isBadRequest(), "Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.");

        // e. Future occurrence (1 day in future) -> REJECTED (400)
        Instant future = clock.value.plus(Duration.ofDays(1));
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "ACCIDENT",
                "occurredAt": "%s",
                "description": "Sự cố ở tương lai",
                "evidenceReference": "ref-future",
                "blockVehicle": false
            }
            """.formatted(fix.bookingId, future.toString()),
            status().isBadRequest(), "Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.");
    }

    @Test
    void testCustodyBoundary_LateReportOnReturnedOrCompleted() throws Exception {
        // Chuyến đi đã RETURNED, bàn giao ở quá khứ, trả xe lúc 2 giờ trước, hiện tại là clock.instant()
        FixtureContext fix = createBookingFixture("RETURNED", "hn", "dn", "dn", true, true);

        // a. Báo muộn: occurredAt nằm giữa handoverTime và returnTime, nhưng reportedAt là hiện tại -> ACCEPTED
        Instant validOccurred = fix.handoverTime.plus(Duration.ofHours(5));
        mvc.perform(post("/api/v1/incidents")
                .header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "bookingId": "%s",
                        "kind": "DAMAGE",
                        "occurredAt": "%s",
                        "description": "Báo muộn sự cố đã xảy ra trong chuyến đi",
                        "evidenceReference": "ref-late-valid",
                        "blockVehicle": false
                    }
                    """.formatted(fix.bookingId, validOccurred.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reportedAt").value(clock.value.toString()))
                .andExpect(jsonPath("$.occurredAt").value(validOccurred.toString()));

        // b. Exact returnTime -> ACCEPTED (inclusive)
        mvc.perform(post("/api/v1/incidents")
                .header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "bookingId": "%s",
                        "kind": "DAMAGE",
                        "occurredAt": "%s",
                        "description": "Sự cố lúc trả xe",
                        "evidenceReference": "ref-exact-return",
                        "blockVehicle": false
                    }
                    """.formatted(fix.bookingId, fix.returnTime.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // c. Outside returnTime by 1 microsecond after -> REJECTED (400)
        Instant oneMicrosAfterReturn = fix.returnTime.plusNanos(1000);
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "DAMAGE",
                "occurredAt": "%s",
                "description": "Sự cố sau khi đã trả xe 1 microsecond",
                "evidenceReference": "ref-after-return",
                "blockVehicle": false
            }
            """.formatted(fix.bookingId, oneMicrosAfterReturn.toString()),
            status().isBadRequest(), "Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.");

        // d. COMPLETED status: tương tự, cho phép báo muộn nếu occurredAt hợp lệ
        FixtureContext fixCompleted = createBookingFixture("COMPLETED", "hn", "dn", "dn", true, true);
        mvc.perform(post("/api/v1/incidents")
                .header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "bookingId": "%s",
                        "kind": "VIOLATION",
                        "occurredAt": "%s",
                        "description": "Phạt nguội trong thời gian thuê của đơn đã hoàn tất",
                        "evidenceReference": "ref-completed-valid",
                        "blockVehicle": false
                    }
                    """.formatted(fixCompleted.bookingId, fixCompleted.handoverTime.plus(Duration.ofHours(2)).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void testCustodyBoundary_MissingHandoverOrInvalidBookingStatus() throws Exception {
        // a. PENDING status -> REJECTED (400)
        FixtureContext fixPending = createBookingFixture("PENDING", "hn", "dn", "hn", false, false);
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "ACCIDENT",
                "occurredAt": "%s",
                "description": "Báo sự cố cho đơn PENDING",
                "evidenceReference": null,
                "blockVehicle": false
            }
            """.formatted(fixPending.bookingId, clock.value.toString()),
            status().isBadRequest(), "Chỉ ghi nhận sự cố của chuyến đã giao xe.");

        // b. CONFIRMED status -> REJECTED (400)
        FixtureContext fixConfirmed = createBookingFixture("CONFIRMED", "hn", "dn", "hn", false, false);
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "ACCIDENT",
                "occurredAt": "%s",
                "description": "Báo sự cố cho đơn CONFIRMED",
                "evidenceReference": null,
                "blockVehicle": false
            }
            """.formatted(fixConfirmed.bookingId, clock.value.toString()),
            status().isBadRequest(), "Chỉ ghi nhận sự cố của chuyến đã giao xe.");

        // c. IN_PROGRESS status nhưng thiếu handover record -> REJECTED (409 Conflict)
        FixtureContext fixNoHandover = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", false, false);
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "ACCIDENT",
                "occurredAt": "%s",
                "description": "Báo sự cố cho đơn thiếu bàn giao",
                "evidenceReference": null,
                "blockVehicle": false
            }
            """.formatted(fixNoHandover.bookingId, clock.value.toString()),
            status().isConflict(), "Chưa có biên bản bàn giao xe.");
    }

    // =========================================================================
    // 2. HTTP Validation Tests (Kind, OccurredAt, Description, EvidenceReference)
    // =========================================================================

    @Test
    void testValidation_InvalidKindAndFields() throws Exception {
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);

        // a. Invalid kind ("FIRE") -> 400
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "FIRE",
                "occurredAt": "%s",
                "description": "Cháy xe",
                "evidenceReference": null,
                "blockVehicle": false
            }
            """.formatted(fix.bookingId, fix.handoverTime.plus(Duration.ofMinutes(10)).toString()),
            status().isBadRequest(), "Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.");

        // b. Null kind -> 400
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": null,
                "occurredAt": "%s",
                "description": "Không có loại sự cố",
                "evidenceReference": null,
                "blockVehicle": false
            }
            """.formatted(fix.bookingId, fix.handoverTime.plus(Duration.ofMinutes(10)).toString()),
            status().isBadRequest(), "Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.");

        // c. Null occurredAt -> 400
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "DAMAGE",
                "occurredAt": null,
                "description": "Không có thời gian",
                "evidenceReference": null,
                "blockVehicle": false
            }
            """.formatted(fix.bookingId),
            status().isBadRequest(), "Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.");

        // d. Blank description -> 400
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "DAMAGE",
                "occurredAt": "%s",
                "description": "   ",
                "evidenceReference": null,
                "blockVehicle": false
            }
            """.formatted(fix.bookingId, fix.handoverTime.plus(Duration.ofMinutes(10)).toString()),
            status().isBadRequest(), "Cần nội dung có căn cứ, tối đa 1500 ký tự.");

        // e. Oversized description (>1500 ký tự) -> 400
        String longDesc = "a".repeat(1501);
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "DAMAGE",
                "occurredAt": "%s",
                "description": "%s",
                "evidenceReference": null,
                "blockVehicle": false
            }
            """.formatted(fix.bookingId, fix.handoverTime.plus(Duration.ofMinutes(10)).toString(), longDesc),
            status().isBadRequest(), "Cần nội dung có căn cứ, tối đa 1500 ký tự.");

        // f. Oversized evidenceReference (>2000 ký tự) -> 400
        String longEvidence = "e".repeat(2001);
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "DAMAGE",
                "occurredAt": "%s",
                "description": "Mô tả hợp lệ",
                "evidenceReference": "%s",
                "blockVehicle": false
            }
            """.formatted(fix.bookingId, fix.handoverTime.plus(Duration.ofMinutes(10)).toString(), longEvidence),
            status().isBadRequest(), "Tham chiếu tài liệu quá dài.");
    }

    // =========================================================================
    // 3. Permissions & Leakage Tests (Foreign User, Unassigned Staff, Guest/No Auth)
    // =========================================================================

    @Test
    void testPermissions_ForeignUserAndUnassignedStaff_Denied404Or401() throws Exception {
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);

        // a. Khách lạ (otherUser) POST báo sự cố -> 404 (Không rò rỉ dữ liệu sự tồn tại của đơn hàng)
        assertNegativePost(otherUserToken, """
            {
                "bookingId": "%s",
                "kind": "DAMAGE",
                "occurredAt": "%s",
                "description": "Khách lạ cố tình báo sự cố",
                "evidenceReference": null,
                "blockVehicle": false
            }
            """.formatted(fix.bookingId, fix.handoverTime.plus(Duration.ofMinutes(10)).toString()),
            status().isNotFound(), "Không tìm thấy dữ liệu.");

        // b. Khách lạ GET list incidents -> 404
        assertNegativeGet(otherUserToken, "/api/v1/incidents", "bookingId", fix.bookingId,
            status().isNotFound(), "Không tìm thấy dữ liệu.");

        // c. Staff Sài Gòn (staffSgn) không thuộc pickupBranch (hn) hay returnBranch (dn) -> 404 khi truy cập đơn
        assertNegativePost(staffSgnToken, """
            {
                "bookingId": "%s",
                "kind": "DAMAGE",
                "occurredAt": "%s",
                "description": "Staff chi nhánh khác cố báo sự cố",
                "evidenceReference": null,
                "blockVehicle": false
            }
            """.formatted(fix.bookingId, fix.handoverTime.plus(Duration.ofMinutes(10)).toString()),
            status().isNotFound(), "Không tìm thấy dữ liệu.");

        // d. Guest / Chưa đăng nhập -> 401 Unauthorized
        assertNegativePost(null, """
            {
                "bookingId": "%s",
                "kind": "DAMAGE",
                "occurredAt": "%s",
                "description": "Không có token",
                "evidenceReference": null,
                "blockVehicle": false
            }
            """.formatted(fix.bookingId, fix.handoverTime.plus(Duration.ofMinutes(10)).toString()),
            status().isUnauthorized(), null);
    }

    // =========================================================================
    // 4. Vehicle Lock Permissions Tests (USER, STAFF current branch vs historical, ADMIN)
    // =========================================================================

    @Test
    void testBlockVehicle_UserForbidden_StaffCurrentBranchVsHistorical_AdminAllowed() throws Exception {
        // Precondition rõ ràng:
        // pickupBranch = 'hn' (demo-staff-hn phụ trách)
        // returnBranch = 'dn' (demo-staff-dn phụ trách)
        // Nhưng vehicle.currentBranchId = 'sgn' (xe hiện ở Sài Gòn, staff-sgn phụ trách)
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "sgn", true, false);

        // a. USER cố tình blockVehicle=true -> 403 Forbidden, xe KHÔNG đổi trạng thái
        assertNegativePost(userToken, """
            {
                "bookingId": "%s",
                "kind": "DAMAGE",
                "occurredAt": "%s",
                "description": "Khách muốn khóa xe",
                "evidenceReference": null,
                "blockVehicle": true
            }
            """.formatted(fix.bookingId, fix.handoverTime.plus(Duration.ofMinutes(10)).toString()),
            status().isForbidden(), "Không có quyền thực hiện thao tác.");

        assertEquals("ACTIVE", vehicleService.get(fix.vehicleId).status, "Xe phải giữ nguyên ACTIVE khi user bị từ chối");

        // b. STAFF Hà Nội (thuộc pickupBranch 'hn', đọc được booking) cố tình blockVehicle=true ->
        // Bị từ chối 403 vì vehicle.currentBranchId = 'sgn' chứ không phải 'hn'!
        assertNegativePost(staffHnToken, """
            {
                "bookingId": "%s",
                "kind": "DAMAGE",
                "occurredAt": "%s",
                "description": "Staff HN cố khóa xe đang ở SGN",
                "evidenceReference": null,
                "blockVehicle": true
            }
            """.formatted(fix.bookingId, fix.handoverTime.plus(Duration.ofMinutes(10)).toString()),
            status().isForbidden(), "Không có quyền thực hiện thao tác.");

        assertEquals("ACTIVE", vehicleService.get(fix.vehicleId).status, "Xe phải giữ nguyên ACTIVE khi Staff sai chi nhánh hiện tại");

        // c. Ca hợp lệ: Xe có currentBranchId = 'hn', Staff Hà Nội (staffHn) báo sự cố và blockVehicle=true -> THÀNH CÔNG, xe chuyển sang MAINTENANCE
        FixtureContext fixHnVehicle = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
        mvc.perform(post("/api/v1/incidents")
                .header("Authorization", staffHnToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "bookingId": "%s",
                        "kind": "ACCIDENT",
                        "occurredAt": "%s",
                        "description": "Staff HN khóa xe tại chi nhánh HN",
                        "evidenceReference": "ref-staff-block",
                        "blockVehicle": true
                    }
                    """.formatted(fixHnVehicle.bookingId, fixHnVehicle.handoverTime.plus(Duration.ofMinutes(10)).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        assertEquals("MAINTENANCE", vehicleService.get(fixHnVehicle.vehicleId).status, "Xe phải chuyển sang MAINTENANCE khi Staff đúng chi nhánh khóa");

        // d. Ca hợp lệ: ADMIN blockVehicle=true -> THÀNH CÔNG, xe chuyển sang MAINTENANCE (bất kể chi nhánh của xe)
        FixtureContext fixAdmin = createBookingFixture("IN_PROGRESS", "hn", "dn", "sgn", true, false);
        mvc.perform(post("/api/v1/incidents")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "bookingId": "%s",
                        "kind": "ACCIDENT",
                        "occurredAt": "%s",
                        "description": "Admin khóa xe",
                        "evidenceReference": "ref-admin-block",
                        "blockVehicle": true
                    }
                    """.formatted(fixAdmin.bookingId, fixAdmin.handoverTime.plus(Duration.ofMinutes(10)).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        assertEquals("MAINTENANCE", vehicleService.get(fixAdmin.vehicleId).status, "Xe phải chuyển sang MAINTENANCE khi Admin khóa");
    }

    // =========================================================================
    // 5. Audit, Immutability & Event Invariance Tests
    // =========================================================================

    @Test
    void testAuditAndEventInvariance_SuccessfulReportAndByteFieldInvariance() throws Exception {
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);

        int totalCasesBefore = jdbc.queryForObject("select count(*) from incident_cases", Integer.class);
        int totalEventsBefore = jdbc.queryForObject("select count(*) from incident_events", Integer.class);

        // Chụp snapshot trạng thái trước khi tạo hồ sơ thành công
        Map<String, Object> tablesBefore1 = IncidentEvidence.snapshot(jdbc);

        Instant occurred1 = fix.handoverTime.plus(Duration.ofHours(1));
        // Tạo sự cố thứ 1
        String res1 = mvc.perform(post("/api/v1/incidents")
                .header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "bookingId": "%s",
                        "kind": "DAMAGE",
                        "occurredAt": "%s",
                        "description": "Vết xước cánh cửa",
                        "evidenceReference": "img-01.jpg",
                        "blockVehicle": false
                    }
                    """.formatted(fix.bookingId, occurred1.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.assessedDamage").value(0))
                .andExpect(jsonPath("$.thirdPartyCoverage").value(0))
                .andExpect(jsonPath("$.requestedAmount").value(0))
                .andReturn().getResponse().getContentAsString();

        String case1Id = com.jayway.jsonpath.JsonPath.read(res1, "$.id");

        // Khẳng định delta: Đúng +1 case và +1 event sau khi POST thành công (C7A-F04)
        int totalCasesAfter1 = jdbc.queryForObject("select count(*) from incident_cases", Integer.class);
        int totalEventsAfter1 = jdbc.queryForObject("select count(*) from incident_events", Integer.class);
        assertEquals(totalCasesBefore + 1, totalCasesAfter1, "Total incident_cases must increase by exactly 1");
        assertEquals(totalEventsAfter1, totalEventsBefore + 1, "Total incident_events must increase by exactly 1");

        // Khẳng định bất biến các bảng nghiệp vụ và tài chính: Không tự ý trừ cọc hay tác động hợp đồng, hóa đơn
        Map<String, Object> tablesAfter1 = IncidentEvidence.snapshot(jdbc);
        for (String invariantTable : List.of("bookings", "booking_status_history", "contracts", "invoices", "invoice_items", "reviews", "vehicle_handover_records")) {
            assertEquals(tablesBefore1.get(invariantTable), tablesAfter1.get(invariantTable),
                "Table " + invariantTable + " must remain 100% untouched and invariant after incident report creation");
        }

        // Khẳng định HTTP response trả về chính xác timestamp ISO-8601
        assertEquals(occurred1.toString(), com.jayway.jsonpath.JsonPath.read(res1, "$.occurredAt"));
        assertEquals(clock.value.toString(), com.jayway.jsonpath.JsonPath.read(res1, "$.reportedAt"));

        // Kiểm tra toàn diện mọi trường của IncidentCase row (C7A-F04, C7A-R04)
        var persistedCase1 = incidentCaseRepository.findById(case1Id).orElseThrow();
        assertEquals(case1Id, persistedCase1.id);
        assertEquals(fix.bookingId, persistedCase1.bookingId);
        assertEquals("demo-user", persistedCase1.reportedBy);
        assertEquals("DAMAGE", persistedCase1.kind);
        assertEquals(occurred1, persistedCase1.occurredAt, "Persisted occurredAt Instant must match occurred1 exactly");
        assertEquals(clock.value, persistedCase1.reportedAt, "Persisted reportedAt Instant must match clock.value exactly");
        assertEquals("Vết xước cánh cửa", persistedCase1.description);
        assertEquals("img-01.jpg", persistedCase1.evidenceReference);
        assertEquals("OPEN", persistedCase1.status);
        assertEquals(0L, persistedCase1.assessedDamage);
        assertEquals(0L, persistedCase1.thirdPartyCoverage);
        assertEquals(0L, persistedCase1.requestedAmount);
        assertNull(persistedCase1.responsibilityBasis);
        assertEquals(0L, persistedCase1.version);

        var caseRow1 = jdbc.queryForMap("select * from incident_cases where id=?", case1Id);
        assertEquals(case1Id, caseRow1.get("id"));
        assertEquals(fix.bookingId, caseRow1.get("booking_id"));
        assertEquals("demo-user", caseRow1.get("reported_by"));
        assertEquals("DAMAGE", caseRow1.get("kind"));
        assertNotNull(caseRow1.get("occurred_at"), "occurred_at column must be present and not null");
        assertNotNull(caseRow1.get("reported_at"), "reported_at column must be present and not null");
        assertEquals("Vết xước cánh cửa", caseRow1.get("description"));
        assertEquals("img-01.jpg", caseRow1.get("evidence_reference"));
        assertEquals("OPEN", caseRow1.get("status"));
        assertEquals(0L, ((Number) caseRow1.get("assessed_damage")).longValue());
        assertEquals(0L, ((Number) caseRow1.get("third_party_coverage")).longValue());
        assertEquals(0L, ((Number) caseRow1.get("requested_amount")).longValue());
        assertNull(caseRow1.get("responsibility_basis"), "responsibility_basis must be null on initial creation (no auto conclusion)");
        assertEquals(0L, ((Number) caseRow1.get("version")).longValue());

        // Kiểm tra toàn diện event row của case 1
        var eventRows1 = jdbc.queryForList("select * from incident_events where case_id=?", case1Id);
        assertEquals(1, eventRows1.size(), "Phải tạo đúng 1 event OPEN khi tạo hồ sơ");
        var ev1 = eventRows1.get(0);
        assertEquals(case1Id, ev1.get("case_id"));
        assertEquals("OPEN", ev1.get("action"));
        assertEquals("demo-user", ev1.get("actor_id"));
        assertEquals("Tiếp nhận hồ sơ; chưa kết luận trách nhiệm.", ev1.get("note"));
        assertNull(ev1.get("previous_status"));
        assertEquals("OPEN", ev1.get("next_status"));
        assertEquals(0L, ((Number) ev1.get("assessed_damage")).longValue());
        assertEquals(0L, ((Number) ev1.get("third_party_coverage")).longValue());
        assertEquals(0L, ((Number) ev1.get("requested_amount")).longValue());
        assertNull(ev1.get("responsibility_basis"));
        assertEquals(clock.value, ((java.sql.Timestamp) ev1.get("occurred_at")).toInstant());

        // Lấy snapshot toàn bộ event 1 để kiểm tra tính bất biến (byte/field invariance)
        Map<String, Object> ev1Snapshot = new HashMap<>(ev1);

        // Tạo sự cố thứ 2 cho cùng booking
        Instant occurred2 = fix.handoverTime.plus(Duration.ofHours(2));
        String res2 = mvc.perform(post("/api/v1/incidents")
                .header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "bookingId": "%s",
                        "kind": "VIOLATION",
                        "occurredAt": "%s",
                        "description": "Lỗi quá tốc độ",
                        "evidenceReference": "cam-02.jpg",
                        "blockVehicle": false
                    }
                    """.formatted(fix.bookingId, occurred2.toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String case2Id = com.jayway.jsonpath.JsonPath.read(res2, "$.id");
        assertNotEquals(case1Id, case2Id);

        // Khẳng định delta sau case thứ 2: Đúng +2 cases và +2 events so với ban đầu
        int totalCasesAfter2 = jdbc.queryForObject("select count(*) from incident_cases", Integer.class);
        int totalEventsAfter2 = jdbc.queryForObject("select count(*) from incident_events", Integer.class);
        assertEquals(totalCasesBefore + 2, totalCasesAfter2, "Total incident_cases must increase by exactly 2");
        assertEquals(totalEventsBefore + 2, totalEventsAfter2, "Total incident_events must increase by exactly 2");

        // Khẳng định hàng event 1 hoàn toàn không bị sửa đổi (byte/field identical)
        var ev1After = jdbc.queryForMap("select * from incident_events where id=?", ev1.get("id"));
        for (String col : ev1Snapshot.keySet()) {
            assertEquals(ev1Snapshot.get(col), ev1After.get(col), "Field " + col + " trong event cũ không được thay đổi");
        }

        // Readback GET /api/v1/incidents?bookingId=...
        mvc.perform(get("/api/v1/incidents")
                .header("Authorization", userToken)
                .param("bookingId", fix.bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Readback GET /api/v1/incidents/{id}/events
        mvc.perform(get("/api/v1/incidents/" + case1Id + "/events")
                .header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("OPEN"));

        // Khách lạ xem events của case 1 -> 404 (bảo đảm không rò rỉ và so sánh snapshot bất biến toàn bộ 10 bảng)
        assertNegativeGet(otherUserToken, "/api/v1/incidents/" + case1Id + "/events", null, null, status().isNotFound(), null);
    }

    // =========================================================================
    // 6. Transaction Rollback Integrity Test
    // =========================================================================

    @Test
    void testTransactionRollback_WhenFaultOccursAfterVehicleMutation() {
        // Chi nhánh HN: staffHn có quyền khóa xe (veh.currentBranchId = 'hn')
        FixtureContext fix = createBookingFixture("IN_PROGRESS", "hn", "dn", "hn", true, false);
        assertEquals("ACTIVE", vehicleService.get(fix.vehicleId).status);

        Map<String, Object> snapshotBefore = IncidentEvidence.snapshot(jdbc);
        int casesBefore = jdbc.queryForObject("select count(*) from incident_cases", Integer.class);
        int eventsBefore = jdbc.queryForObject("select count(*) from incident_events", Integer.class);

        Actor staffHn = new Actor("demo-staff-hn", "STAFF", "Staff Hà Nội");
        IncidentService.Input inputWithBlockVehicle = new IncidentService.Input(
            fix.bookingId, "ACCIDENT", fix.handoverTime.plus(Duration.ofHours(1)),
            "Sự cố nghiêm trọng cần khóa xe", "ref-rollback", true
        );

        // Spy JdbcTemplate: khi câu lệnh INSERT INTO incident_events được thực thi (ngay sau repo.saveAndFlush() và mutation xe),
        // thực hiện insert thật xuống DB qua callRealMethod(), sau đó mới ném InjectedFaultException để kiểm chứng rollback toàn diện
        // cả vehicle status lẫn IncidentCase và IncidentEvent đã ghi.
        java.util.concurrent.atomic.AtomicBoolean eventWrittenBeforeFault = new java.util.concurrent.atomic.AtomicBoolean(false);
        org.mockito.Mockito.doAnswer(inv -> {
            inv.callRealMethod(); // Ghi event thật xuống DB!
            eventWrittenBeforeFault.set(true);
            throw new InjectedFaultException("Simulated unexpected fault inside incidentService.create right after vehicle mutation and saveAndFlush");
        }).when(jdbc).update(org.mockito.ArgumentMatchers.contains("insert into incident_events"), org.mockito.ArgumentMatchers.any(Object[].class));

        try {
            InjectedFaultException ex = assertThrows(InjectedFaultException.class, () -> {
                incidentService.create(staffHn, inputWithBlockVehicle);
            });
            assertEquals("Simulated unexpected fault inside incidentService.create right after vehicle mutation and saveAndFlush", ex.getMessage());
            assertTrue(eventWrittenBeforeFault.get(), "Real event INSERT must have executed prior to injected fault");

            // Xác nhận sau rollback:
            // 1. Vehicle status phải rollback về "ACTIVE"
            assertEquals("ACTIVE", vehicleService.get(fix.vehicleId).status, "Vehicle status must rollback to ACTIVE on service transaction failure");

            // 2. Không có hàng IncidentCase hoặc IncidentEvent nào bị lưu lại
            int casesAfter = jdbc.queryForObject("select count(*) from incident_cases", Integer.class);
            int eventsAfter = jdbc.queryForObject("select count(*) from incident_events", Integer.class);
            assertEquals(casesBefore, casesAfter, "Incident cases must not grow on transaction failure");
            assertEquals(eventsBefore, eventsAfter, "Incident events must not grow on transaction failure");

            // 3. Toàn bộ 10 bảng dữ liệu giữ nguyên 100% bất biến sau rollback
            Map<String, Object> snapshotAfter = IncidentEvidence.snapshot(jdbc);
            assertEquals(snapshotBefore, snapshotAfter, "Entire database snapshot must remain 100% invariant after service rollback");
        } finally {
            org.mockito.Mockito.reset(jdbc);
        }
    }
}
