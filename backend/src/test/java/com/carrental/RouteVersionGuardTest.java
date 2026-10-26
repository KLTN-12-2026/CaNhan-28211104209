package com.carrental;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * F01 / L06: PUT /api/v1/admin/branch-routes phai nhan expectedVersion bat buoc.
 * Hop dong version: 0 = tuyen chua ton tai (tao moi), N > 0 = tuyen phai ton tai o dung version N.
 */
@SpringBootTest(properties = {
    "app.jwt-secret=integration-test-only-secret-at-least-32-bytes",
    "app.seed-enabled=true",
    "app.seed-password=IntegrationOnly123!",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@AutoConfigureMockMvc
class RouteVersionGuardTest {

    private static final String PICKUP = "dn";
    private static final String RETURN = "hn";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:routeguard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        r.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        r.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private int perform(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        req.contentType("application/json").content(json.writeValueAsString(body));
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private void expectRoute(String token, Object body, int expected) throws Exception {
        int actual = perform(put("/api/v1/admin/branch-routes"), token, body);
        assertEquals(expected, actual, "Body gui: " + body);
    }

    private String login(String email) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("email", email, "password", "IntegrationOnly123!")))).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private Map<String, Object> routeBody(String pickup, String destination, long fee, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickupBranchId", pickup);
        body.put("returnBranchId", destination);
        body.put("fee", fee);
        body.put("enabled", enabled);
        return body;
    }

    private Map<String, Object> routeBody(String pickup, String destination, long fee, boolean enabled, Long expectedVersion) {
        Map<String, Object> body = routeBody(pickup, destination, fee, enabled);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private long routeColumn(String pickup, String destination, String column) {
        Long value = jdbc.queryForObject(
            "select " + column + " from branch_routes where pickup_branch_id=? and return_branch_id=?",
            Long.class, pickup, destination);
        assertNotNull(value, () -> "Khong tim thay tuyen " + pickup + " -> " + destination);
        return value;
    }

    private long routeVersion(String pickup, String destination) {
        return routeColumn(pickup, destination, "version");
    }

    private int countRoutes(String pickup, String destination) {
        return jdbc.queryForObject(
            "select count(*) from branch_routes where pickup_branch_id=? and return_branch_id=?",
            Integer.class, pickup, destination);
    }

    @Test
    void routeUpdateRejectsMissingNullOrNegativeExpectedVersion() throws Exception {
        String admin = login("admin@example.test");
        long versionBefore = routeVersion(PICKUP, RETURN);
        long feeBefore = routeColumn(PICKUP, RETURN, "fee");

        expectRoute(admin, routeBody(PICKUP, RETURN, 1_500_001L, true), 400);

        expectRoute(admin, routeBody(PICKUP, RETURN, 1_500_002L, true, null), 400);

        expectRoute(admin, routeBody(PICKUP, RETURN, 1_500_003L, true, -1L), 400);

        assertEquals(versionBefore, routeVersion(PICKUP, RETURN),
            "Thieu / null / am expectedVersion khong duoc tang version cua tuyen");
        assertEquals(feeBefore, routeColumn(PICKUP, RETURN, "fee"),
            "Thieu / null / am expectedVersion khong duoc ghi phi cua tuyen");
    }

    @Test
    void correctVersionAppliesOnceAndStaleFormCannotOverwrite() throws Exception {
        String admin = login("admin@example.test");
        long versionBefore = routeVersion(PICKUP, RETURN);
        long appliedFee = 1_234_567L;

        expectRoute(admin, routeBody(PICKUP, RETURN, appliedFee, false, versionBefore), 200);

        assertEquals(versionBefore + 1, routeVersion(PICKUP, RETURN),
            "Cap nhat dung version phai tang version dung mot lan bang co che @Version cua persistence");
        assertEquals(appliedFee, routeColumn(PICKUP, RETURN, "fee"), "Phi cua form moi phai duoc ap dung");

        long versionAfterApply = routeVersion(PICKUP, RETURN);

        expectRoute(admin, routeBody(PICKUP, RETURN, 9_999_999L, true, versionBefore), 409);

        assertEquals(versionAfterApply, routeVersion(PICKUP, RETURN), "Form cu gui lai khong duoc tang version");
        assertEquals(appliedFee, routeColumn(PICKUP, RETURN, "fee"), "Form cu gui lai khong duoc ghi de phi da luu");
    }

    @Test
    void creatingNewRouteRequiresZeroExpectedVersion() throws Exception {
        String admin = login("admin@example.test");
        jdbc.update("delete from branch_routes where pickup_branch_id=? and return_branch_id=?", "hn", "dn");
        assertEquals(0, countRoutes("hn", "dn"), "Tien do: tuyen hn -> dn phai chua ton tai");

        expectRoute(admin, routeBody("hn", "dn", 1_400_000L, true, 7L), 409);
        assertEquals(0, countRoutes("hn", "dn"), "Khong duoc tao tuyen khi expectedVersion khac 0");

        expectRoute(admin, routeBody("hn", "dn", 1_400_000L, true, 0L), 200);
        assertEquals(1, countRoutes("hn", "dn"), "expectedVersion 0 phai tao duoc tuy moi");
        assertEquals(1_400_000L, routeColumn("hn", "dn", "fee"), "Tuyen moi phai giu phi gui len");
        assertEquals(0L, routeVersion("hn", "dn"), "Tuyen moi phai bat dau o version 0");
    }

    @Test
    void concurrentRouteUpdatesOnlyOneRequestWins() throws Exception {
        assumeTrue(System.getenv("KDRIVE_TEST_DB_URL") != null,
            "Can MySQL that de chung minh co che @Version khi hai cap nhat giao nhau.");

        String admin = login("admin@example.test");
        long staleVersion = routeVersion(PICKUP, RETURN);

        long[] fees = {1_500_011L, 1_500_022L};
        int threads = fees.length;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        AtomicInteger unexpected = new AtomicInteger(0);
        AtomicLong winnerFee = new AtomicLong(-1L);
        AtomicReference<String> unexpectedDetail = new AtomicReference<>();

        for (long fee : fees) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    int status = perform(put("/api/v1/admin/branch-routes"), admin,
                        routeBody(PICKUP, RETURN, fee, true, staleVersion));
                    if (status == 200) {
                        accepted.incrementAndGet();
                        winnerFee.set(fee);
                    } else if (status == 409) {
                        rejected.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                        unexpectedDetail.compareAndSet(null, "HTTP " + status + " cho fee " + fee);
                    }
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                    unexpectedDetail.compareAndSet(null, e.toString());
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "Hai thread phai san sang truoc khi bat dau");
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Hai request phai ket thuc");

        assertEquals(0, unexpected.get(), () -> "Chi chap nhan 200 hoac 409, gap loi khac: " + unexpectedDetail.get());
        assertEquals(1, accepted.get(), "Chi mot request cap nhat tuyen duoc thang");
        assertEquals(1, rejected.get(), "Request con lai phai nhan 409 Conflict");
        assertEquals(staleVersion + 1, routeVersion(PICKUP, RETURN),
            "Hai cap nhat giao nhau chi duoc tang version mot lan");
        assertEquals(winnerFee.get(), routeColumn(PICKUP, RETURN, "fee"),
            "Chi phi cua request thang duoc giu lai, request thua khong ghi de");
    }
}