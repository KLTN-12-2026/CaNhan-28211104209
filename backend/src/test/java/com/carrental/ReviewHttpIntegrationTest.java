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
import com.carrental.security.TokenService;
import com.carrental.modules.user.service.AccountService;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
    "app.jwt-secret=integration-test-only-secret-at-least-32-bytes",
    "app.seed-enabled=true",
    "app.seed-password=kdrive_seed_pw_c6_review_20261010",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@AutoConfigureMockMvc
@Import(ReviewHttpIntegrationTest.TimeConfig.class)
public class ReviewHttpIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            CancelBoundaryTest.verifySafeIsolatedDatabase(url, System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"), System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"), System.getenv("KDRIVE_TEST_DB_USER"), System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        }
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:reviewhttp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
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

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokenService;
    @Autowired AccountService accountService;
    @Autowired MutableClock clock;

    private String tokenUser;
    private String tokenOtherUser;
    private String tokenStaff;
    private String tokenAdmin;

    private final String userId = "demo-user";
    private final String otherUserId = "user-other-test";
    private final String staffId = "demo-staff-dn";
    private final String adminId = "demo-admin";

    private final String vehicle1Id = "v1";
    private final String vehicle2Id = "v2";

    @BeforeEach
    void setUp() {
        clock.value = Instant.parse("2026-10-10T10:00:00Z");
        jdbc.update("DELETE FROM reviews");

        // Ensure otherUser exists
        Integer otherUserCount = jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, otherUserId);
        if (otherUserCount == null || otherUserCount == 0) {
            jdbc.update("INSERT INTO users(id,email,password_hash,full_name,role,status,token_version,version) VALUES(?,?,?,?,?,?,0,0)",
                otherUserId, "otheruser@example.test", "hash", "Khách hàng khác", "USER", "ACTIVE");
        }

        tokenUser = "Bearer " + tokenService.issue(accountService.require(userId));
        tokenOtherUser = "Bearer " + tokenService.issue(accountService.require(otherUserId));
        tokenStaff = "Bearer " + tokenService.issue(accountService.require(staffId));
        tokenAdmin = "Bearer " + tokenService.issue(accountService.require(adminId));
    }

    private String createBooking(String uId, String vehicleId, String status) {
        String bookingId = UUID.randomUUID().toString();
        Instant start = clock.value.minus(Duration.ofDays(5));
        Instant end = clock.value.minus(Duration.ofDays(2));
        jdbc.update(
            "INSERT INTO bookings(id,user_id,vehicle_id,customer_name,vehicle_name,pickup_branch_id,return_branch_id,pickup_name,return_name,actual_return_branch_id,start_at,end_at,expires_at,status,daily_price,rental_days,rental_total,one_way_fee,total,policy_version,created_at,version) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
            bookingId, uId, vehicleId, "Khách test", "Xe test", "dn", "dn", "Đà Nẵng · mẫu", "Đà Nẵng · mẫu", "dn",
            java.sql.Timestamp.from(start), java.sql.Timestamp.from(end), java.sql.Timestamp.from(start.plus(Duration.ofHours(2))),
            status, 800000L, 3, 2400000L, 0L, 2400000L, "v1_policy", java.sql.Timestamp.from(start.minus(Duration.ofDays(1)))
        );
        return bookingId;
    }

    private int countReviews() {
        return jdbc.queryForObject("SELECT count(*) FROM reviews", Integer.class);
    }


    private int probeSequence;

    private String input(String booking, int rating, String content) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
            Map.of("bookingId", booking, "rating", rating, "content", content));
    }

    private void reject(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                        int expected, Class<?> cause) throws Exception {
        var before = ReviewEvidence.snapshot(jdbc);
        var result = mockMvc.perform(request).andExpect(status().is(expected)).andReturn();
        if (cause != null) assertInstanceOf(cause, result.getResolvedException());
        var after = ReviewEvidence.snapshot(jdbc);
        ReviewEvidence.record("http-rejection-" + (++probeSequence) + "-" + UUID.randomUUID(), before, after);
        assertEquals(before, after, "Every rejected request must preserve full fixture state");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(String token, String body) {
        return post("/api/v1/reviews").header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void testC6_04_RBAC_Http_Security_Endpoints() throws Exception {
        String booking = createBooking(userId, vehicle1Id, "COMPLETED");
        String body = input(booking, 5, "RBAC");
        reject(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(body), 401, null);
        reject(create(tokenStaff, body), 403, null);
        reject(create(tokenAdmin, body), 403, null);
        reject(get("/api/v1/admin/reviews"), 401, null);
        reject(get("/api/v1/admin/reviews").header("Authorization", tokenUser), 403, null);
        reject(get("/api/v1/admin/reviews").header("Authorization", tokenStaff), 403, null);
        mockMvc.perform(get("/api/v1/admin/reviews").header("Authorization", tokenAdmin)).andExpect(status().isOk());
        for (String token : List.of(tokenUser, tokenStaff)) {
            reject(patch("/api/v1/admin/reviews/any-id").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true,\"version\":0}"), 403, null);
        }
    }

    @Test
    void testC6_01_User_Create_And_Readback_Http() throws Exception {
        for (int rating : List.of(1, 5)) {
            String booking = createBooking(userId, vehicle1Id, "COMPLETED");
            String text = rating == 1 ? "D".repeat(1000) : "   Xe sạch sẽ và êm ái.   ";
            mockMvc.perform(get("/api/v1/bookings/" + booking + "/review").header("Authorization", tokenUser))
                .andExpect(status().isOk()).andExpect(content().string(""));
            mockMvc.perform(create(tokenUser, input(booking, rating, text))).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/bookings/" + booking + "/review").header("Authorization", tokenUser))
                .andExpect(status().isOk()).andExpect(jsonPath("$.booking_id").value(booking))
                .andExpect(jsonPath("$.rating").value(rating)).andExpect(jsonPath("$.content").value(text.strip()))
                .andExpect(jsonPath("$.hidden").value(false)).andExpect(jsonPath("$.version").value(0));
            var row = jdbc.queryForMap("SELECT * FROM reviews WHERE booking_id=?", booking);
            assertEquals(text.strip(), row.get("content"));
            assertEquals(rating, ((Number) row.get("rating")).intValue());
            ReviewEvidence.record("positive-" + rating, Map.of(), row);
        }
    }

    private record Outcome(int index, int status, Exception cause) {}

    @Test
    void testC6_02_Duplicate_Sequential_And_Concurrent_Http() throws Exception {
        String booking = createBooking(userId, vehicle1Id, "COMPLETED");
        mockMvc.perform(create(tokenUser, input(booking, 5, "Winner content"))).andExpect(status().isOk());
        reject(create(tokenUser, input(booking, 4, "Attempt overwrite")), 409,
            org.springframework.dao.DataIntegrityViolationException.class);

        String fresh = createBooking(userId, vehicle1Id, "COMPLETED");
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int i=0; i<2; i++) {
                final int index=i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS), "Start barrier timed out");
                    var response=mockMvc.perform(create(tokenUser, input(fresh, index+1, "Concurrent " + index))).andReturn();
                    return new Outcome(index, response.getResponse().getStatus(), response.getResolvedException());
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var outcomes = new ArrayList<Outcome>();
            for (var future : futures) outcomes.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(List.of(200,409), outcomes.stream().map(Outcome::status).sorted().toList());
            var winner=outcomes.stream().filter(o -> o.status()==200).findFirst().orElseThrow();
            var loser=outcomes.stream().filter(o -> o.status()==409).findFirst().orElseThrow();
            assertInstanceOf(org.springframework.dao.DataIntegrityViolationException.class, loser.cause());
            var rows=jdbc.queryForList("SELECT * FROM reviews WHERE booking_id=?", fresh);
            assertEquals(1, rows.size());
            var row=rows.get(0);
            assertEquals(fresh,row.get("booking_id"));
            assertEquals("Concurrent " + winner.index(),row.get("content"));
            assertEquals(winner.index()+1,((Number)row.get("rating")).intValue());
            var relation=jdbc.queryForMap("SELECT user_id,vehicle_id FROM bookings WHERE id=?",fresh);
            assertEquals(Map.of("user_id",userId,"vehicle_id",vehicle1Id),relation);
            assertEquals(false,row.get("hidden"));
            assertEquals(0L,((Number)row.get("version")).longValue());
            ReviewEvidence.record("http-race-winner", Map.of("winner",winner.index(),"loser",loser.index(),"relation",relation),row);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS), "HTTP executor did not terminate");
            ReviewEvidence.record("http-executor",Map.of(),Map.of("terminated",executor.isTerminated()));
        }
    }

    @Test
    void testC6_03_Boundary_Input_And_State_Http_With_Invariance() throws Exception {
        String booking=createBooking(userId,vehicle1Id,"COMPLETED");
        // Seed representative history/documents so no-write proof covers existing data, not empty counts.
        jdbc.update("INSERT INTO booking_status_history(id,booking_id,next_status,actor_id,occurred_at,reason) VALUES(?,?,?,?,?,?)",
            UUID.randomUUID().toString(),booking,"COMPLETED",userId,java.sql.Timestamp.from(clock.value),"F04 sentinel");
        jdbc.update("INSERT INTO contracts(id,booking_id,issued_at,snapshot,terms_version) VALUES(?,?,?,?,?)",
            UUID.randomUUID().toString(),booking,java.sql.Timestamp.from(clock.value),"{\"sentinel\":true}","v1");
        String invoice=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO invoices(id,booking_id,issued_at,total,snapshot) VALUES(?,?,?,?,?)",
            invoice,booking,java.sql.Timestamp.from(clock.value),123L,"{\"sentinel\":true}");
        jdbc.update("INSERT INTO invoice_items(id,invoice_id,name,quantity,unit_price,amount) VALUES(?,?,?,?,?,?)",
            UUID.randomUUID().toString(),invoice,"sentinel",1,123L,123L);
        reject(create(tokenOtherUser,input(booking,5,"Foreign")),404,com.carrental.exception.DomainException.class);
        reject(get("/api/v1/bookings/"+booking+"/review").header("Authorization",tokenOtherUser),404,com.carrental.exception.DomainException.class);
        for (String state : List.of("PENDING","CONFIRMED","IN_PROGRESS","RETURNED","CANCELLED","EXPIRED")) {
            String id=createBooking(userId,vehicle1Id,state);
            reject(create(tokenUser,input(id,5,"Wrong state")),400,com.carrental.exception.DomainException.class);
        }
        for (int rating : List.of(0,6)) reject(create(tokenUser,input(booking,rating,"Rating")),400,com.carrental.exception.DomainException.class);
        for (String text : List.of("", "   ", "C".repeat(1001)))
            reject(create(tokenUser,input(booking,5,text)),400,com.carrental.exception.DomainException.class);
        for (String body : List.of(
            "{\"bookingId\":\""+booking+"\",\"rating\":5,\"content\":null}",
            "{\"rating\":5,\"content\":\"Missing\"}",
            "{\"bookingId\":null,\"rating\":5,\"content\":\"Null\"}",
            "{\"bookingId\":\""+booking+"\",\"content\":\"Missing rating\"}",
            "{\"bookingId\":\""+booking+"\",\"rating\":null,\"content\":\"Null rating\"}"))
            reject(create(tokenUser,body),400,com.carrental.exception.DomainException.class);
        reject(create(tokenUser,input("missing-booking",5,"Missing")),404,com.carrental.exception.DomainException.class);
        for (String body : List.of("{malformed-json", "{\"bookingId\":{},\"rating\":5,\"content\":\"Type\"}"))
            reject(create(tokenUser,body),400,org.springframework.http.converter.HttpMessageNotReadableException.class);
    }

    @Test
    void testC6_05_And_06_Admin_Moderation_OptimisticLocking_Public_Http() throws Exception {
        String booking=createBooking(userId,vehicle1Id,"COMPLETED");
        String other=createBooking(otherUserId,vehicle2Id,"COMPLETED");
        mockMvc.perform(create(tokenUser,input(booking,5,"Vehicle1"))).andExpect(status().isOk());
        mockMvc.perform(create(tokenOtherUser,input(other,4,"Vehicle2"))).andExpect(status().isOk());
        String id=jdbc.queryForObject("SELECT id FROM reviews WHERE booking_id=?",String.class,booking);
        var before=jdbc.queryForMap("SELECT * FROM reviews WHERE id=?",id);
        for (String body : List.of("{}", "{\"version\":0}", "{\"hidden\":true}",
            "{\"hidden\":null,\"version\":0}", "{\"hidden\":true,\"version\":null}")) {
            reject(patch("/api/v1/admin/reviews/"+id).header("Authorization",tokenAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(body),400,com.carrental.exception.DomainException.class);
        }
        for (String body : List.of("{\"hidden\":{},\"version\":0}","{\"hidden\":true,\"version\":\"invalid\"}"))
            reject(patch("/api/v1/admin/reviews/"+id).header("Authorization",tokenAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(body),400,org.springframework.http.converter.HttpMessageNotReadableException.class);
        reject(patch("/api/v1/admin/reviews/"+id).header("Authorization",tokenAdmin)
            .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true,\"version\":-1}"),409,com.carrental.exception.DomainException.class);
        for (boolean hidden : List.of(true,false)) {
            long version=((Number)before.get("version")).longValue();
            clock.value=clock.value.plusSeconds(1);
            mockMvc.perform(patch("/api/v1/admin/reviews/"+id).header("Authorization",tokenAdmin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":"+hidden+",\"version\":"+version+"}"))
                .andExpect(status().isOk());
            var after=jdbc.queryForMap("SELECT * FROM reviews WHERE id=?",id);
            ReviewEvidence.moderation(before,after,hidden,adminId,clock.value);
            ReviewEvidence.record("moderation-"+hidden,before,after);
            mockMvc.perform(get("/api/v1/vehicles/"+vehicle1Id+"/reviews"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(hidden?0:1));
            mockMvc.perform(get("/api/v1/vehicles/"+vehicle2Id+"/reviews"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].booking_id").doesNotExist()).andExpect(jsonPath("$[0].user_id").doesNotExist());
            mockMvc.perform(get("/api/v1/bookings/"+booking+"/review").header("Authorization",tokenUser))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.hidden").value(hidden)).andExpect(jsonPath("$.content").value("Vehicle1"));
            reject(patch("/api/v1/admin/reviews/"+id).header("Authorization",tokenAdmin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":"+(!hidden)+",\"version\":"+version+"}"),409,com.carrental.exception.DomainException.class);
            before=after;
        }
    }
}
