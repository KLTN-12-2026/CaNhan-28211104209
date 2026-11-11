package com.carrental;

import com.carrental.exception.DomainException;
import com.carrental.modules.booking.service.*;
import com.carrental.modules.operation.service.*;
import com.carrental.modules.user.service.*;
import com.carrental.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {"app.jwt-secret=schedule-protection-test-secret-at-least-32-bytes",
        "app.seed-enabled=true", "app.seed-password=ScheduleFixtureOnly123!", "app.deposit-enabled=false",
        "app.return-policy-enabled=false", "app.deposit-method=DEMO_QR_MANUAL", "app.payment-reconcile-ms=86400000",
        "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"})
@AutoConfigureMockMvc
@Import(ScheduleProtectionHttpTest.TimeConfig.class)
class ScheduleProtectionHttpTest {
    static final Path PHOTOS;
    static { try { PHOTOS = Files.createTempDirectory("schedule-protection-synthetic-"); } catch (Exception e) { throw new IllegalStateException(e); } }
    static class TestClock extends Clock {
        Instant now;
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary TestClock scheduleClock() { var c = new TestClock(); c.now = Instant.parse("2026-10-25T03:00:00Z"); return c; } }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        if (url != null) CancelBoundaryTest.verifySafeIsolatedDatabase(url, System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"), System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"), System.getenv("KDRIVE_TEST_DB_USER"), System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:schedule-protection;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        r.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        r.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        r.add("app.license-photo-directory", () -> PHOTOS.toString());
    }
    @Autowired BookingService bookings;
    @Autowired VehicleSearchService search;
    @Autowired RentalWorkflow rental;
    @Autowired DepositWorkflow deposits;
    @Autowired LicenseService licenses;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    @Autowired AccountService accounts;
    final Actor user = new Actor("demo-user", "USER", "Synthetic"), staff = new Actor("demo-staff-dn", "STAFF", "Synthetic");
    String predecessor;
    @BeforeEach void setup() {
        CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);
        if (System.getenv("KDRIVE_TEST_DB_URL") != null) {
            try (var connection = jdbc.getDataSource().getConnection()) {
                assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
                assertEquals(System.getenv("KDRIVE_TEST_EXPECTED_SCHEMA"), connection.getCatalog());
            } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
        }
        ReflectionTestUtils.setField(bookings, "depositEnabled", false);
        for (String table : List.of("booking_return_settlements", "payment_events", "payment_refunds", "payment_transactions", "reviews", "incident_events", "incident_cases", "vehicle_handover_records", "invoice_items", "invoices", "contracts", "booking_status_history", "bookings")) jdbc.update("delete from " + table);
        jdbc.update("update vehicles set current_branch_id='dn',status='ACTIVE',daily_price=650000 where id='v1'");
        clock.now = Instant.parse("2026-10-25T03:00:00Z");
        var old = licenses.mine(user);
        licenses.save(user, new LicenseService.View(null, "SYNTHETIC-SCHEDULE-NOT-REAL", "VN_2025", "B", LocalDate.of(2035,12,31), false, "PENDING", old == null ? 0 : old.version()));
        LicensePhotoFixture.approved(licenses, user);
        predecessor = bookings.create(user, input(25, 27, 1300000L)).id();
        action(predecessor, "CONFIRMED", false); action(predecessor, "IN_PROGRESS", false);
        clock.now = Instant.parse("2026-10-27T16:50:00Z"); // ten minutes before Vietnam midnight
    }
    BookingService.Input input(int start, int end, Long total) {
        return new BookingService.Input("v1", "dn", "dn", null, null, total, LocalDate.of(2026,10,start), LocalDate.of(2026,10,end));
    }
    BookingService.View current(String id) { return bookings.view(bookings.require(user, id)); }
    void action(String id, String next, boolean maintenance) {
        rental.apply(staff, id, new BookingService.Action(next, current(id).version(), "Synthetic test", "Synthetic condition", true, maintenance));
    }
    String nextBooking(boolean deposit) {
        ReflectionTestUtils.setField(bookings, "depositEnabled", deposit);
        return bookings.create(user, input(28, 29, 650000L)).id();
    }
    boolean available() {
        return search.search(new VehicleSearchService.Input(null,null,"dn","dn",null,null,null,null,null,null,LocalDate.of(2026,10,28),LocalDate.of(2026,10,29)))
                .items().stream().anyMatch(i -> i.vehicle().id().equals("v1"));
    }
    void midnight() { clock.now = Instant.parse("2026-10-27T17:00:00Z"); }
    void conflict(Runnable action, String text) {
        var error = assertThrows(DomainException.class, action::run);
        assertEquals(409, error.status.value()); assertTrue(error.getMessage().contains(text), error.getMessage());
    }
    long count(String table, String id) { return jdbc.queryForObject("select count(*) from " + table + " where booking_id=?", Long.class, id); }
    @Test void staleQuoteCannotCreateAfterMidnightAndSearchAgrees() throws Exception {
        assertTrue(available()); assertEquals(650000L, bookings.quote(user, input(28,29,null)).total());
        midnight(); assertFalse(available());
        conflict(() -> bookings.quote(user, input(28,29,null)), "quá hạn");
        conflict(() -> bookings.create(user, input(28,29,650000L)), "quá hạn");
        var response = mvc.perform(post("/api/v1/bookings/quote")
                .header("Authorization", "Bearer " + tokens.issue(accounts.require(user.id())))
                .contentType("application/json").content("{\"vehicleId\":\"v1\",\"pickupBranchId\":\"dn\",\"returnBranchId\":\"dn\",\"pickupDate\":\"2026-10-28\",\"returnDate\":\"2026-10-29\"}"))
                .andReturn().getResponse();
        assertEquals(409, response.getStatus()); assertTrue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("quá hạn"));
        assertEquals(1, jdbc.queryForObject("select count(*) from bookings", Integer.class));
    }
    @Test void regularConfirmationRechecksScheduleAndDoesNotIssueContract() {
        String b = nextBooking(false); midnight();
        conflict(() -> action(b, "CONFIRMED", false), "quá hạn");
        assertEquals("PENDING", current(b).status()); assertEquals(0, count("contracts", b));
    }
    @Test void depositApprovalRejectsInactiveVehicleWithoutWritingApproval() {
        String b = nextBooking(true);
        for (String status : List.of("MAINTENANCE", "INACTIVE")) {
            jdbc.update("update vehicles set status=? where id='v1'", status);
            conflict(() -> deposits.approve(staff, b, current(b).version()), "sẵn sàng");
            assertNull(current(b).deposit().approvedAt()); assertEquals(0, count("payment_events", b));
        }
    }
    @Test void depositApprovalRechecksOverdueSchedule() {
        String b = nextBooking(true); midnight();
        conflict(() -> deposits.approve(staff, b, current(b).version()), "quá hạn");
        assertNull(current(b).deposit().approvedAt()); assertEquals(0, count("payment_events", b));
    }
    @Test void timelyQrReceiptAfterPredecessorBecomesOverdueIsNotAppliedOrContracted() {
        String b = nextBooking(true); deposits.approve(staff, b, current(b).version()); midnight();
        deposits.verifyQrDeposit(staff, b, new BookingService.VerifyInput("SYNTHETIC-G4-RECEIPT",195000L,clock.instant(),BookingService.requestCode(b),"SYNTHETIC-PAYER","g4-receipt",current(b).version(),true));
        assertEquals("PENDING", current(b).status()); assertEquals(0, count("contracts", b));
        assertEquals(195000L, current(b).deposit().receivedAmount()); assertEquals(0L, current(b).deposit().appliedAmount());
        assertEquals(195000L, jdbc.queryForObject("select sum(amount) from payment_refunds where booking_id=?", Long.class, b));
    }
    @Test void handoverBlockedUntilPredecessorActuallyReturned() {
        String b = nextBooking(false); action(b, "CONFIRMED", false); midnight();
        conflict(() -> action(b, "IN_PROGRESS", false), "đang được thuê");
        assertEquals(0, count("vehicle_handover_records", b)); assertEquals("CONFIRMED", current(b).status());
        action(predecessor, "RETURNED", false); action(b, "IN_PROGRESS", false);
        assertEquals("IN_PROGRESS", current(b).status()); assertEquals(1, count("vehicle_handover_records", b));
    }
    @Test void maintenanceAfterReturnStillBlocksHandoverAndQuote() {
        String b = nextBooking(false); action(b, "CONFIRMED", false); midnight(); action(predecessor, "RETURNED", true);
        conflict(() -> action(b, "IN_PROGRESS", false), "sẵn sàng"); assertFalse(available());
        conflict(() -> bookings.quote(user, input(30,31,null)), "hoạt động");
        assertEquals(0, count("vehicle_handover_records", b));
    }
    @Test void earlyReturnKeepsReservedDateButAllowsNextDay() {
        action(predecessor, "RETURNED", false);
        conflict(() -> bookings.quote(user, input(27,28,null)), "trùng");
        assertTrue(available()); assertEquals(650000L, bookings.quote(user, input(28,29,null)).total());
    }
    int attempt(Runnable work) {
        try { work.run(); return 200; } catch (DomainException e) { return e.status.value(); }
    }
    List<Integer> race(Runnable first, Runnable second) throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return attempt(first); });
            var b = pool.submit(() -> { start.await(); return attempt(second); });
            start.countDown();
            return List.of(a.get(20, java.util.concurrent.TimeUnit.SECONDS), b.get(20, java.util.concurrent.TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)); }
    }
    @Test void concurrentReturnAndCreateNeverLeaveAnUnsafeOrPartialBooking() throws Exception {
        midnight();
        var results = race(() -> action(predecessor, "RETURNED", false), () -> bookings.create(user, input(28,29,650000L)));
        assertEquals(200, results.get(0)); assertTrue(Set.of(200,409).contains(results.get(1)), results.toString());
        assertEquals("RETURNED", current(predecessor).status());
        assertEquals(results.get(1) == 200 ? 2 : 1, jdbc.queryForObject("select count(*) from bookings", Integer.class));
        assertEquals(2, count("vehicle_handover_records", predecessor)); // one HANDOVER plus one RETURN
        assertEquals(1300000L, current(predecessor).total());
        if (results.get(1) == 409) assertEquals("PENDING", bookings.create(user, input(28,29,650000L)).status());
        assertEquals(1, jdbc.queryForObject("select count(*) from bookings where status='PENDING'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from contracts", Integer.class));
    }
    @Test void concurrentReturnAndNextHandoverNeverDoubleAssignVehicle() throws Exception {
        String b = nextBooking(false); action(b, "CONFIRMED", false); midnight();
        var results = race(() -> action(predecessor, "RETURNED", false), () -> action(b, "IN_PROGRESS", false));
        assertEquals(200, results.get(0)); assertTrue(Set.of(200,409).contains(results.get(1)), results.toString());
        assertEquals("RETURNED", current(predecessor).status());
        assertEquals(results.get(1) == 200 ? "IN_PROGRESS" : "CONFIRMED", current(b).status());
        assertEquals(results.get(1) == 200 ? 1 : 0, count("vehicle_handover_records", b));
        if (results.get(1) == 409) action(b, "IN_PROGRESS", false);
        assertEquals(1, jdbc.queryForObject("select count(*) from bookings where status='IN_PROGRESS'", Integer.class));
        assertEquals(1, count("vehicle_handover_records", b));
        assertEquals(2, jdbc.queryForObject("select count(*) from contracts", Integer.class));
        assertEquals(650000L, current(b).total());
    }
}
