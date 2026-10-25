package com.carrental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.booking.entity.Booking;
import com.carrental.modules.vehicle.service.VehicleService;
import com.carrental.modules.review.service.ReviewService;
import com.carrental.security.Actor;
import com.carrental.exception.DomainException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
    "app.jwt-secret=integration-test-only-secret-at-least-32-bytes",
    "app.seed-enabled=true",
    "app.seed-password=kdrive_seed_pw_c6_review_20261010",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@Import(ReviewIntegrationTest.TimeConfig.class)
public class ReviewIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            CancelBoundaryTest.verifySafeIsolatedDatabase(url, System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"), System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"), System.getenv("KDRIVE_TEST_DB_USER"), System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        }
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:reviewintegration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        r.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        r.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    static class MutableClock extends java.time.Clock {
        Instant value = Instant.now();
        public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        public java.time.Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return value; }
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    @Autowired BookingService bookingService;
    @Autowired VehicleService vehicleService;
    @Autowired ReviewService reviewService;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired TransactionTemplate tx;

    private final Actor userA = new Actor("demo-user", "USER", "Khách hàng A");
    private final Actor userB = new Actor("user-b-test", "USER", "Khách hàng B");
    private final Actor staff = new Actor("demo-staff-hn", "STAFF", "Nhân viên");
    private final Actor admin = new Actor("demo-admin", "ADMIN", "Quản trị viên");
    private final Actor guest = new Actor("guest", "GUEST", "Khách vãng lai");

    private String vehicle1Id = "v1";
    private String vehicle2Id = "v2";

    @BeforeEach
    void setUp() {
        clock.value = Instant.parse("2026-10-10T10:00:00Z");
        jdbc.update("DELETE FROM reviews");
        // Ensure test userB exists
        Integer userBCount = jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, userB.id());
        if (userBCount == null || userBCount == 0) {
            jdbc.update("INSERT INTO users(id,email,password_hash,full_name,role,status,token_version,version) VALUES(?,?,?,?,?,?,0,0)",
                userB.id(), "userb@example.com", "hash", userB.fullName(), userB.role(), "ACTIVE");
        }
    }

    private String createBooking(Actor actor, String vehicleId, String status) {
        String bookingId = UUID.randomUUID().toString();
        Instant start = clock.value.minus(Duration.ofDays(5));
        Instant end = clock.value.minus(Duration.ofDays(2));
        jdbc.update(
            "INSERT INTO bookings(id,user_id,vehicle_id,customer_name,vehicle_name,pickup_branch_id,return_branch_id,pickup_name,return_name,actual_return_branch_id,start_at,end_at,expires_at,status,daily_price,rental_days,rental_total,one_way_fee,total,policy_version,created_at,version) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
            bookingId, actor.id(), vehicleId, actor.fullName(), "Xe test", "dn", "dn", "Đà Nẵng · mẫu", "Đà Nẵng · mẫu", "dn",
            java.sql.Timestamp.from(start), java.sql.Timestamp.from(end), java.sql.Timestamp.from(start.plus(Duration.ofHours(2))),
            status, 800000L, 3, 2400000L, 0L, 2400000L, "v1_policy", java.sql.Timestamp.from(start.minus(Duration.ofDays(1)))
        );
        return bookingId;
    }

    @Test
    void testC6_01_UserA_CreateReview_Readback_Success() {
        String bookingId = createBooking(userA, vehicle1Id, "COMPLETED");

        reviewService.create(userA, bookingId, 5, "Dịch vụ rất tốt!");

        Integer count = jdbc.queryForObject("SELECT count(*) FROM reviews WHERE booking_id=?", Integer.class, bookingId);
        assertEquals(1, count);

        // Readback via getForBooking
        Object reviewObj = reviewService.getForBooking(userA, bookingId);
        assertNotNull(reviewObj);
        assertTrue(reviewObj instanceof Map<?,?>);
        Map<?,?> map = (Map<?,?>) reviewObj;
        assertEquals(bookingId, map.get("booking_id"));
        assertEquals(5, ((Number) map.get("rating")).intValue());
        assertEquals("Dịch vụ rất tốt!", map.get("content"));
        assertEquals(false, map.get("hidden"));
        assertEquals(0L, ((Number) map.get("version")).longValue());
    }

    @Test
    void testC6_02_SequentialAndConcurrentDuplicateReview_Rejected() throws Exception {
        String bookingId = createBooking(userA, vehicle1Id, "COMPLETED");

        // First creation succeeds
        reviewService.create(userA, bookingId, 5, "Đánh giá lần 1");


        var beforeDuplicate=ReviewEvidence.snapshot(jdbc);
        assertThrows(DataIntegrityViolationException.class,
            () -> reviewService.create(userA,bookingId,4,"Overwrite"));
        assertEquals(beforeDuplicate,ReviewEvidence.snapshot(jdbc));

        String fresh=createBooking(userA,vehicle1Id,"COMPLETED");
        var executor=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2);
        var start=new CountDownLatch(1);
        record Result(int index, boolean success) {}
        List<Future<Result>> futures=new ArrayList<>();
        try {
            for (int i=0;i<2;i++) {
                final int index=i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5,TimeUnit.SECONDS));
                    try {
                        reviewService.create(userA,fresh,index+1,"Service winner "+index);
                        return new Result(index,true);
                    } catch (DataIntegrityViolationException expected) {
                        return new Result(index,false);
                    }
                }));
            }
            assertTrue(ready.await(5,TimeUnit.SECONDS));
            start.countDown();
            var results=new ArrayList<Result>();
            for (var future:futures) results.add(future.get(15,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(Result::success).count());
            var winner=results.stream().filter(Result::success).findFirst().orElseThrow();
            var rows=jdbc.queryForList("SELECT * FROM reviews WHERE booking_id=?",fresh);
            assertEquals(1,rows.size());
            assertEquals("Service winner "+winner.index(),rows.get(0).get("content"));
            assertEquals(winner.index()+1,((Number)rows.get(0).get("rating")).intValue());
            assertEquals(fresh,rows.get(0).get("booking_id"));
            ReviewEvidence.record("service-race-winner",Map.of("winner",winner.index()),rows.get(0));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));
            ReviewEvidence.record("service-executor",Map.of(),Map.of("terminated",executor.isTerminated()));
        }
    }

    @Test
    void testC6_03_BoundaryAndForeignBooking_Rejected() {
        String completedA = createBooking(userA, vehicle1Id, "COMPLETED");
        String inProgressA = createBooking(userA, vehicle1Id, "IN_PROGRESS");
        String confirmedA = createBooking(userA, vehicle1Id, "CONFIRMED");

        // Foreign booking: User B tries to review User A's booking -> 404 DomainException
        DomainException exForeign = assertThrows(DomainException.class, () -> {
            reviewService.create(userB, completedA, 5, "Cố tình đánh giá đơn người khác");
        });
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, exForeign.status);

        // Not COMPLETED status -> DomainException 400
        DomainException exInProgress = assertThrows(DomainException.class, () -> {
            reviewService.create(userA, inProgressA, 5, "Đơn chưa xong");
        });
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, exInProgress.status);

        DomainException exConfirmed = assertThrows(DomainException.class, () -> {
            reviewService.create(userA, confirmedA, 5, "Đơn mới xác nhận");
        });
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, exConfirmed.status);

        // Invalid rating boundary: 0 and 6
        assertThrows(DomainException.class, () -> reviewService.create(userA, completedA, 0, "Rating 0"));
        assertThrows(DomainException.class, () -> reviewService.create(userA, completedA, 6, "Rating 6"));

        // Content boundary: null, blank, > 1000 chars
        assertThrows(DomainException.class, () -> reviewService.create(userA, completedA, 5, null));
        assertThrows(DomainException.class, () -> reviewService.create(userA, completedA, 5, "   "));
        String tooLong = "A".repeat(1001);
        assertThrows(DomainException.class, () -> reviewService.create(userA, completedA, 5, tooLong));

        // Exact boundary 1000 chars and rating 1 and 5 must succeed
        String valid1000 = "B".repeat(1000);
        assertDoesNotThrow(() -> reviewService.create(userA, completedA, 1, valid1000));
        Map<?,?> r = (Map<?,?>) reviewService.getForBooking(userA, completedA);
        assertEquals(1000, ((String) r.get("content")).length());
    }

    @Test
    void testC6_04_RBAC_Permissions() {
        String bookingId = createBooking(userA, vehicle1Id, "COMPLETED");

        // Guest cannot create -> DomainException 403 (or 401 via auth)
        assertThrows(DomainException.class, () -> reviewService.create(guest, bookingId, 5, "Khách đánh giá"));

        // Staff / Admin cannot create review
        assertThrows(DomainException.class, () -> reviewService.create(staff, bookingId, 5, "Staff đánh giá"));
        assertThrows(DomainException.class, () -> reviewService.create(admin, bookingId, 5, "Admin đánh giá"));

        // User / Staff cannot list or patch admin reviews
        assertThrows(DomainException.class, () -> reviewService.list(userA));
        assertThrows(DomainException.class, () -> reviewService.list(staff));
        assertThrows(DomainException.class, () -> reviewService.moderate(userA, "any-id", true, 0));
        assertThrows(DomainException.class, () -> reviewService.moderate(staff, "any-id", true, 0));

        // Admin can list admin reviews
        assertDoesNotThrow(() -> reviewService.list(admin));
    }

    @Test
    void testC6_05_Admin_HideShow_PublicList_Isolation() {
        String bookingA = createBooking(userA, vehicle1Id, "COMPLETED");
        String bookingB = createBooking(userB, vehicle2Id, "COMPLETED");

        reviewService.create(userA, bookingA, 5, "Xe 1 chạy êm");
        reviewService.create(userB, bookingB, 4, "Xe 2 tiết kiệm xăng");

        @SuppressWarnings("unchecked")
        List<Map<String,Object>> publicVehicle1Before = (List<Map<String,Object>>) reviewService.publicList(vehicle1Id);
        assertEquals(1, publicVehicle1Before.size());
        assertEquals("Xe 1 chạy êm", publicVehicle1Before.get(0).get("content"));
        // Assert public does not expose booking_id or private user info
        assertFalse(publicVehicle1Before.get(0).containsKey("booking_id"));
        assertFalse(publicVehicle1Before.get(0).containsKey("user_id"));

        String reviewId = (String) publicVehicle1Before.get(0).get("id");

        var beforeHidden=jdbc.queryForMap("SELECT * FROM reviews WHERE id=?",reviewId);
        reviewService.moderate(admin, reviewId, true, 0);
        ReviewEvidence.moderation(beforeHidden,jdbc.queryForMap("SELECT * FROM reviews WHERE id=?",reviewId),true,admin.id(),clock.value);

        @SuppressWarnings("unchecked")
        List<Map<String,Object>> publicVehicle1Hidden = (List<Map<String,Object>>) reviewService.publicList(vehicle1Id);
        assertEquals(0, publicVehicle1Hidden.size(), "Sau khi hide, public không còn thấy review");

        // Other vehicle review remains untouched
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> publicVehicle2 = (List<Map<String,Object>>) reviewService.publicList(vehicle2Id);
        assertEquals(1, publicVehicle2.size());
        assertEquals("Xe 2 tiết kiệm xăng", publicVehicle2.get(0).get("content"));

        // Check DB audit fields for reviewId
        Map<String,Object> dbRow = jdbc.queryForMap("SELECT moderated_by, moderated_at, version, hidden FROM reviews WHERE id=?", reviewId);
        assertEquals(admin.id(), dbRow.get("moderated_by"));
        assertNotNull(dbRow.get("moderated_at"));
        assertEquals(1L, ((Number) dbRow.get("version")).longValue());
        assertEquals(true, dbRow.get("hidden"));

        // Admin shows review again (version is now 1)
        var beforeShown=jdbc.queryForMap("SELECT * FROM reviews WHERE id=?",reviewId);
        reviewService.moderate(admin, reviewId, false, 1);
        ReviewEvidence.moderation(beforeShown,jdbc.queryForMap("SELECT * FROM reviews WHERE id=?",reviewId),false,admin.id(),clock.value);

        @SuppressWarnings("unchecked")
        List<Map<String,Object>> publicVehicle1Shown = (List<Map<String,Object>>) reviewService.publicList(vehicle1Id);
        assertEquals(1, publicVehicle1Shown.size());
        assertEquals("Xe 1 chạy êm", publicVehicle1Shown.get(0).get("content"));

        Map<String,Object> dbRowShown = jdbc.queryForMap("SELECT version, hidden FROM reviews WHERE id=?", reviewId);
        assertEquals(2L, ((Number) dbRowShown.get("version")).longValue());
        assertEquals(false, dbRowShown.get("hidden"));
    }

    @Test
    void testC6_06_OptimisticLocking_StaleVersion_Conflict() {
        String booking = createBooking(userA, vehicle1Id, "COMPLETED");
        reviewService.create(userA, booking, 5, "Tuyệt vời");

        Map<?,?> review = (Map<?,?>) reviewService.getForBooking(userA, booking);
        String reviewId = (String) review.get("id");
        long initialVersion = ((Number) review.get("version")).longValue();
        assertEquals(0L, initialVersion);

        // Admin 1 moderates using version 0 -> success
        reviewService.moderate(admin, reviewId, true, 0L);

        var beforeStale=ReviewEvidence.snapshot(jdbc);
        // Admin 2 tries to moderate using stale version 0 -> throws 409 Conflict
        DomainException exConflict = assertThrows(DomainException.class, () -> {
            reviewService.moderate(admin, reviewId, false, 0L);
        });
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, exConflict.status);

        assertEquals(beforeStale,ReviewEvidence.snapshot(jdbc));
        // Verify full row state unchanged by failed attempt
        Map<String,Object> row = jdbc.queryForMap("SELECT hidden, version FROM reviews WHERE id=?", reviewId);
        assertEquals(true, row.get("hidden"));
        assertEquals(1L, ((Number) row.get("version")).longValue());
    }
}
