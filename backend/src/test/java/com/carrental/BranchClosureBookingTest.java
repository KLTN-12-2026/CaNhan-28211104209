package com.carrental;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.carrental.exception.DomainException;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.branch.service.BranchService;
import com.carrental.modules.user.service.AccountService;
import com.carrental.modules.user.service.LicenseService;
import com.carrental.security.Actor;
import com.carrental.security.TokenService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.jwt-secret=branch-booking-test-secret-at-least-32-bytes",
    "app.seed-enabled=true", "app.seed-password=BranchBookingTestOnly123!",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@Import(BranchClosureBookingTest.TimeConfig.class)
class BranchClosureBookingTest {
    static class FixtureClock extends Clock {
        volatile Instant value = Instant.parse("2026-10-20T08:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return value; }
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary FixtureClock fixtureClock() { return new FixtureClock(); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        if (url != null && !url.isBlank()) CancelBoundaryTest.verifySafeIsolatedDatabase(url,
            System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"), System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),
            System.getenv("KDRIVE_TEST_DB_USER"), System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        registry.add("spring.datasource.url", () -> url == null || url.isBlank()
            ? "jdbc:h2:mem:branchbooking;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        registry.add("spring.datasource.username", () -> url == null || url.isBlank() ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> url == null || url.isBlank() ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    @Autowired TestRestTemplate http;
    @Autowired BookingService bookings;
    @Autowired BranchService branches;
    @Autowired LicenseService licenses;
    @Autowired AccountService accounts;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired FixtureClock clock;
    final Actor user = new Actor("demo-user", "USER", "Fixture user");
    final Actor admin = new Actor("demo-admin", "ADMIN", "Fixture admin");
    String userToken, adminToken, dnToken, hnToken;

    @BeforeEach void setup() {
        CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);
        for (String table : List.of("reviews", "incident_events", "incident_cases", "vehicle_handover_records",
                "invoice_items", "invoices", "contracts", "booking_status_history", "bookings")) jdbc.update("delete from " + table);
        for (var branch : branches.list()) branches.changeStatus(admin, branch.id(), "ACTIVE", branch.version());
        jdbc.update("update vehicles set status='ACTIVE',current_branch_id='dn' where id in ('v1','v3')");
        jdbc.update("update vehicles set status='ACTIVE',current_branch_id='hn' where id='v2'");
        jdbc.update("update branch_routes set enabled=true");
        clock.value = Instant.parse("2026-10-20T08:00:00Z");
        var old = licenses.mine(user);
        licenses.save(user, new LicenseService.View(old == null ? null : old.id(), "A2-123456", "VN_2025", "B",
            LocalDate.of(2030, 12, 31), false, "PENDING", old == null ? 0 : old.version()));
        LicensePhotoFixture.approved(licenses, user);
        userToken = token("demo-user"); adminToken = token("demo-admin");
        dnToken = token("demo-staff-dn"); hnToken = token("demo-staff-hn");
    }
    private String token(String id) { return "Bearer " + tokens.issue(accounts.require(id)); }
    private BookingService.Input input() {
        Instant start = clock.instant().plusSeconds(3600);
        return new BookingService.Input("v1", "dn", "hn", start, start.plusSeconds(86400), 2150000L);
    }
    private Map<String, Object> body(BookingService.Input i) {
        return Map.of("vehicleId", i.vehicleId(), "pickupBranchId", i.pickupBranchId(), "returnBranchId", i.returnBranchId(),
            "startAt", i.startAt().toString(), "endAt", i.endAt().toString(), "expectedTotal", i.expectedTotal());
    }
    private ResponseEntity<Map> request(String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.set("Authorization", token);
        return http.exchange("/api/v1" + path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
    private Map createHttp(BookingService.Input i) {
        assertEquals(HttpStatus.OK, request("/bookings/quote", body(i), userToken).getStatusCode());
        var created = request("/bookings", body(i), userToken);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody().toString());
        return created.getBody();
    }
    private void status(String id, String status) {
        long version = jdbc.queryForObject("select version from branches where id=?", Long.class, id);
        branches.changeStatus(admin, id, status, version);
    }
    private Map<String, List<Map<String, Object>>> snapshot(String... tables) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : tables) {
            String order = table.equals("staff_branches") ? "user_id,branch_id" : table.equals("flyway_schema_history") ? "installed_rank" : "id";
            result.put(table, jdbc.queryForList("select * from " + table + " order by " + order));
        }
        return result;
    }
    private void assertSame(Map<String, List<Map<String, Object>>> before) {
        assertEquals(before, snapshot(before.keySet().toArray(String[]::new)));
    }
    private void closedResponse(ResponseEntity<Map> response) {
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Chi nhánh không hoạt động"), response.getBody().toString());
    }
    private ResponseEntity<Map> action(String id, String next, long version, String token, boolean licenseChecked) {
        return request("/bookings/" + id + "/actions", Map.of("next", next, "version", version,
            "condition", "Fixture vehicle condition", "reason", "Fixture workflow", "licenseChecked", licenseChecked), token);
    }

    @Test void closedPickupOrReturnRejectQuoteCreateAndStaleQuoteWithoutWrites() {
        var input = input();
        assertEquals(HttpStatus.OK, request("/bookings/quote", body(input), userToken).getStatusCode());
        for (String closed : List.of("dn", "hn")) {
            status(closed, "INACTIVE");
            var before = snapshot("bookings", "booking_status_history", "vehicles", "branch_routes", "staff_branches", "flyway_schema_history");
            closedResponse(request("/bookings/quote", body(input), userToken));
            closedResponse(request("/bookings", body(input), userToken));
            assertSame(before);
            status(closed, "ACTIVE");
        }
        Map created = createHttp(input);
        assertEquals("PENDING", created.get("status"));
        assertEquals(1L, jdbc.queryForObject("select count(*) from bookings", Long.class));
        assertEquals(1L, jdbc.queryForObject("select count(*) from booking_status_history", Long.class));
    }

    @Test void existingBookingCompletesAtClosedBranchesWithPermissionsAndStableSnapshots() {
        var input = input(); Map pending = createHttp(input); String id = (String) pending.get("id");
        var beforeClose = snapshot("vehicles", "branch_routes", "staff_branches", "bookings", "booking_status_history", "flyway_schema_history");
        status("dn", "INACTIVE"); status("hn", "INACTIVE"); assertSame(beforeClose);
        var confirmed = action(id, "CONFIRMED", 0, dnToken, false);
        assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody().toString());
        assertEquals("CONFIRMED", confirmed.getBody().get("status"));
        var contract = snapshot("contracts");
        assertEquals(1L, jdbc.queryForObject("select count(*) from contracts where booking_id=?", Long.class, id));
        String contractText = jdbc.queryForObject("select snapshot from contracts where booking_id=?", String.class, id);
        assertTrue(contractText.contains((String) pending.get("pickupName")));
        assertTrue(contractText.contains((String) pending.get("returnName")));
        for (String branch : List.of("dn", "hn")) {
            var b = branches.list().stream().filter(x -> x.id().equals(branch)).findFirst().orElseThrow();
            branches.save(admin, branch, "Renamed " + branch, b.address(), b.version());
        }
        assertSame(contract);
        assertEquals(HttpStatus.CONFLICT, action(id, "IN_PROGRESS", 1, dnToken, true).getStatusCode());
        clock.value = input.startAt().plusSeconds(1);
        assertEquals(HttpStatus.FORBIDDEN, action(id, "IN_PROGRESS", 1, hnToken, true).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, action(id, "IN_PROGRESS", 1, dnToken, false).getStatusCode());
        var handed = action(id, "IN_PROGRESS", 1, dnToken, true);
        assertEquals(HttpStatus.OK, handed.getStatusCode());
        assertEquals("IN_PROGRESS", handed.getBody().get("status"));
        assertEquals("dn", jdbc.queryForObject("select current_branch_id from vehicles where id='v1'", String.class));
        assertEquals(HttpStatus.FORBIDDEN, action(id, "RETURNED", 2, dnToken, false).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, action(id, "RETURNED", 2, adminToken, false).getStatusCode());
        assertEquals(HttpStatus.OK, action(id, "RETURNED", 2, hnToken, false).getStatusCode());
        assertEquals("hn", jdbc.queryForObject("select current_branch_id from vehicles where id='v1'", String.class));
        assertEquals(HttpStatus.OK, action(id, "COMPLETED", 3, hnToken, false).getStatusCode());
        assertEquals("COMPLETED", jdbc.queryForObject("select status from bookings where id=?", String.class, id));
        assertEquals("hn", jdbc.queryForObject("select actual_return_branch_id from bookings where id=?", String.class, id));
        assertEquals(5L, jdbc.queryForObject("select count(*) from booking_status_history where booking_id=?", Long.class, id));
        assertEquals(2L, jdbc.queryForObject("select count(*) from vehicle_handover_records where booking_id=?", Long.class, id));
        assertEquals(1L, jdbc.queryForObject("select count(*) from invoices where booking_id=?", Long.class, id));
        String invoiceText = jdbc.queryForObject("select snapshot from invoices where booking_id=?", String.class, id);
        assertTrue(invoiceText.contains((String) pending.get("pickupName")));
        assertFalse(invoiceText.contains("Renamed"));
        var snapshots = snapshot("contracts", "invoices", "invoice_items", "branch_routes", "staff_branches", "flyway_schema_history");
        status("dn", "ACTIVE"); status("hn", "ACTIVE"); assertSame(snapshots);
    }

    @Test void oldBookingStillRequiresActorLicenseVehicleTimeAndVersion() {
        String id = (String) createHttp(input()).get("id");
        status("dn", "INACTIVE"); status("hn", "INACTIVE");
        var before = snapshot("bookings", "booking_status_history", "contracts", "vehicles");
        for (String token : List.of(userToken, adminToken, hnToken)) {
            assertEquals(HttpStatus.FORBIDDEN, action(id, "CONFIRMED", 0, token, false).getStatusCode()); assertSame(before);
        }
        jdbc.update("update driver_licenses set status='REJECTED' where user_id='demo-user'");
        var licenseError = action(id, "CONFIRMED", 0, dnToken, false);
        assertEquals(HttpStatus.BAD_REQUEST, licenseError.getStatusCode());
        assertTrue(licenseError.getBody().toString().contains("GPLX")); assertSame(before);
        jdbc.update("update driver_licenses set status='PENDING' where user_id='demo-user'");
        jdbc.update("update vehicles set status='MAINTENANCE' where id='v1'");
        var maintenance = action(id, "CONFIRMED", 0, dnToken, false);
        assertEquals(HttpStatus.CONFLICT, maintenance.getStatusCode());
        assertTrue(maintenance.getBody().toString().contains("Xe không hoạt động"));
        assertEquals(0L, jdbc.queryForObject("select count(*) from contracts", Long.class));
        jdbc.update("update vehicles set status='ACTIVE' where id='v1'");
        assertEquals(HttpStatus.OK, action(id, "CONFIRMED", 0, dnToken, false).getStatusCode());
        var confirmed = snapshot("bookings", "booking_status_history", "contracts", "vehicles");
        assertEquals(HttpStatus.CONFLICT, action(id, "IN_PROGRESS", 0, dnToken, true).getStatusCode()); assertSame(confirmed);
        assertEquals(HttpStatus.CONFLICT, action(id, "IN_PROGRESS", 1, dnToken, true).getStatusCode()); assertSame(confirmed);
    }

    @Test void missingBranchAndDisabledRouteAreNotBypassedAfterReopen() {
        var data = new HashMap<>(body(input())); data.remove("pickupBranchId");
        assertEquals(HttpStatus.BAD_REQUEST, request("/bookings/quote", data, userToken).getStatusCode());
        data.put("pickupBranchId", "missing-branch");
        assertEquals(HttpStatus.NOT_FOUND, request("/bookings/quote", data, userToken).getStatusCode());
        status("hn", "INACTIVE"); status("hn", "ACTIVE");
        jdbc.update("update branch_routes set enabled=false where pickup_branch_id='dn' and return_branch_id='hn'");
        var before = snapshot("bookings", "booking_status_history", "branch_routes", "vehicles");
        for (String path : List.of("/bookings/quote", "/bookings")) {
            var r = request(path, body(input()), userToken);
            assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
            assertTrue(r.getBody().toString().contains("Tuyến nhận/trả đã ngừng phục vụ"));
        }
        assertSame(before);
    }

    private void mysqlOnly() { assumeTrue(System.getenv("KDRIVE_TEST_DB_URL") != null && System.getenv("KDRIVE_TEST_DB_URL").startsWith("jdbc:mysql:"), "MySQL lock-wait proof requires isolated MySQL"); }
    private void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS), "Latch deadline"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private void actualBranchLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long count = jdbc.queryForObject("select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks b on w.BLOCKING_ENGINE_LOCK_ID=b.ENGINE_LOCK_ID where b.OBJECT_SCHEMA=DATABASE() and b.OBJECT_NAME='branches'", Long.class);
            if (count != null && count > 0) { System.out.println("C11-A2 MYSQL BRANCH LOCK WAIT OBSERVED"); return; }
            Thread.sleep(25); // Poll actual DB evidence, never use elapsed sleep to declare a race passed.
        }
        fail("No actual MySQL branch row lock wait observed");
    }

    @Test void closeWinsBeforeCreateCommitAndLeavesNoBooking() throws Exception {
        mysqlOnly(); var input = input();
        long version = jdbc.queryForObject("select version from branches where id='hn'", Long.class);
        var before = snapshot("bookings", "booking_status_history", "vehicles", "branch_routes", "staff_branches", "flyway_schema_history");
        CountDownLatch closed = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> close = pool.submit(() -> tx.execute(s -> { branches.changeStatus(admin, "hn", "INACTIVE", version); closed.countDown(); await(release); return null; }));
            await(closed);
            Future<?> create = pool.submit(() -> bookings.create(user, input));
            actualBranchLockWait(); assertFalse(create.isDone());
            release.countDown(); close.get(15, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class, () -> create.get(15, TimeUnit.SECONDS));
            assertInstanceOf(DomainException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("Chi nhánh không hoạt động"));
            assertEquals(409, ((DomainException) error.getCause()).status.value()); assertSame(before);
            assertEquals("INACTIVE", jdbc.queryForObject("select status from branches where id='hn'", String.class));
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    @Test void createWinsAndCloseWaitsUntilBookingCommit() throws Exception {
        mysqlOnly(); var input = input();
        long version = jdbc.queryForObject("select version from branches where id='hn'", Long.class);
        CountDownLatch prepared = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BookingService.View> create = pool.submit(() -> tx.execute(s -> { var booking = bookings.create(user, input); prepared.countDown(); await(release); return booking; }));
            await(prepared);
            Future<?> close = pool.submit(() -> branches.changeStatus(admin, "hn", "INACTIVE", version));
            actualBranchLockWait(); assertFalse(close.isDone());
            assertEquals(0L, jdbc.queryForObject("select count(*) from bookings", Long.class), "Uncommitted create not visible");
            release.countDown(); var booking = create.get(15, TimeUnit.SECONDS); close.get(15, TimeUnit.SECONDS);
            assertEquals(1L, jdbc.queryForObject("select count(*) from bookings", Long.class));
            assertEquals("PENDING", jdbc.queryForObject("select status from bookings where id=?", String.class, booking.id()));
            assertEquals(1L, jdbc.queryForObject("select count(*) from booking_status_history", Long.class));
            assertEquals("INACTIVE", jdbc.queryForObject("select status from branches where id='hn'", String.class));
            var confirmed = action(booking.id(), "CONFIRMED", booking.version(), dnToken, false);
            assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody().toString());
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS)); }
    }
}
