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

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.jwt-secret=integration-test-only-secret-at-least-32-bytes",
    "app.token-minutes=30",
    "app.seed-enabled=true",
    "app.seed-password=IntegrationOnly123!",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
public class AccountAssignmentHttpServerTest {

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
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:accountassignmenthttp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        r.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        r.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void setup() {
        ensureUserExists("demo-admin", "Admin C8", "admin@example.test", "ADMIN");
        ensureUserExists("demo-user", "User C8", "user@example.test", "USER");
        ensureUserExists("demo-staff-hn", "Staff HN C8", "staff-hn@example.test", "STAFF");

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

    @Test
    void testAdminCreatesStaffAndEnforcesSecurityAndValidation() throws Exception {
        String baseEmail = "staff-c8a-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String normalizedEmail = baseEmail.strip().toLowerCase(Locale.ROOT);
        String password = "StaffPassword123!";

        Map<String, Object> req = Map.of(
            "email", normalizedEmail,
            "fullName", "Staff Chuyên Nghiệp",
            "password", password
        );

        // 1. Admin creates STAFF ACTIVE
        ResponseEntity<Map> createResp = restTemplate.exchange(
            "/api/v1/admin/accounts",
            HttpMethod.POST,
            new HttpEntity<>(req, authHeaders(adminToken)),
            Map.class
        );
        assertEquals(HttpStatus.OK, createResp.getStatusCode(), "Admin create staff should return 200 OK");
        Map body = createResp.getBody();
        assertNotNull(body);
        String staffId = (String) body.get("id");
        assertNotNull(staffId);
        assertEquals("Staff Chuyên Nghiệp", body.get("fullName"));
        assertEquals(normalizedEmail, body.get("email"));
        assertEquals("STAFF", body.get("role"));
        assertEquals("ACTIVE", body.get("status"));
        long createdVersion = ((Number) body.get("version")).longValue();
        assertTrue(createdVersion >= 0L, "Created staff version must be non-negative");

        // Assert response omits passwordHash, tokenVersion, secrets
        assertFalse(body.containsKey("password"));
        assertFalse(body.containsKey("passwordHash"));
        assertFalse(body.containsKey("tokenVersion"));
        assertFalse(body.containsKey("secret"));

        // 2. Login succeeds with created credentials
        ResponseEntity<Map> loginResp = restTemplate.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", normalizedEmail, "password", password),
            Map.class
        );
        assertEquals(HttpStatus.OK, loginResp.getStatusCode(), "Login with created staff should succeed");
        assertNotNull(loginResp.getBody().get("token"));

        // Also test case-insensitive email login
        ResponseEntity<Map> loginUpperResp = restTemplate.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", baseEmail.toUpperCase(Locale.ROOT), "password", password),
            Map.class
        );
        assertEquals(HttpStatus.OK, loginUpperResp.getStatusCode(), "Login with uppercase email should succeed");

        // 3. SQL readback: 1 account, STAFF role, ACTIVE status, BCrypt hashed password
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select role, status, email, password_hash, token_version, version from users where id=?",
            staffId
        );
        assertEquals(1, rows.size(), "Exactly 1 user row in database");
        Map<String, Object> staffRow = rows.get(0);
        assertEquals("STAFF", staffRow.get("role"));
        assertEquals("ACTIVE", staffRow.get("status"));
        assertEquals(normalizedEmail, staffRow.get("email"));
        String hash = (String) staffRow.get("password_hash");
        assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$"), "Password must be BCrypt hashed");
        assertEquals(0L, ((Number) staffRow.get("token_version")).longValue());

        // 4. Duplicate normalized email rejected with 409 Conflict
        ResponseEntity<Map> dupResp = restTemplate.exchange(
            "/api/v1/admin/accounts",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("email", normalizedEmail.toUpperCase(Locale.ROOT), "fullName", "Khác", "password", password), authHeaders(adminToken)),
            Map.class
        );
        assertEquals(HttpStatus.CONFLICT, dupResp.getStatusCode(), "Duplicate email must return 409 Conflict");
        Long countAfterDup = jdbc.queryForObject("select count(*) from users where email=?", Long.class, normalizedEmail);
        assertEquals(1L, countAfterDup, "No extra account created on conflict");

        // 5. Invalid name/email/password rejected with 400 Bad Request and no write
        long totalUsersBefore = jdbc.queryForObject("select count(*) from users", Long.class);

        // invalid email
        ResponseEntity<Map> badEmail = restTemplate.exchange("/api/v1/admin/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("email", "invalid-email", "fullName", "Valid Name", "password", password), authHeaders(adminToken)), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, badEmail.getStatusCode());

        // invalid short password (< 8 chars)
        ResponseEntity<Map> badPw = restTemplate.exchange("/api/v1/admin/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("email", "valid@example.test", "fullName", "Valid Name", "password", "short"), authHeaders(adminToken)), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, badPw.getStatusCode());

        // blank full name
        ResponseEntity<Map> badName = restTemplate.exchange("/api/v1/admin/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("email", "valid@example.test", "fullName", "   ", "password", password), authHeaders(adminToken)), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, badName.getStatusCode());

        assertEquals(totalUsersBefore, (long) jdbc.queryForObject("select count(*) from users", Long.class), "No writes on validation failure");

        // 6. Non-ADMIN and anonymous rejected
        ResponseEntity<Map> userForbidden = restTemplate.exchange("/api/v1/admin/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("email", "another@example.test", "fullName", "Name", "password", password), authHeaders(userToken)), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, userForbidden.getStatusCode());

        ResponseEntity<Map> staffForbidden = restTemplate.exchange("/api/v1/admin/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("email", "another@example.test", "fullName", "Name", "password", password), authHeaders(staffToken)), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, staffForbidden.getStatusCode());

        ResponseEntity<Map> anonDenied = restTemplate.exchange("/api/v1/admin/accounts", HttpMethod.POST,
            new HttpEntity<>(Map.of("email", "another@example.test", "fullName", "Name", "password", password), authHeaders(null)), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anonDenied.getStatusCode());

        // 7. Concurrent duplicate email handling
        String concurrentEmail = "concurrent-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Integer> createCall = () -> {
            ResponseEntity<Map> resp = restTemplate.exchange(
                "/api/v1/admin/accounts",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", concurrentEmail, "fullName", "Concurrent Staff", "password", password), authHeaders(adminToken)),
                Map.class
            );
            return resp.getStatusCode().value();
        };

        Future<Integer> f1 = pool.submit(createCall);
        Future<Integer> f2 = pool.submit(createCall);
        int s1 = f1.get();
        int s2 = f2.get();
        pool.shutdown();

        // Exactly one should succeed with 200, the other should receive 409
        List<Integer> statuses = List.of(s1, s2);
        assertTrue(statuses.contains(200), "One concurrent request must succeed with 200");
        assertTrue(statuses.contains(409), "The duplicate concurrent request must return 409 Conflict");

        Long concurrentCount = jdbc.queryForObject("select count(*) from users where email=?", Long.class, concurrentEmail);
        assertEquals(1L, concurrentCount, "Exactly 1 user row in database after concurrent creation");
    }

    @Test
    void testLockAndUnlockStaffWithVersionAndTokenRevocation() {
        String email = "lockable-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String password = "Staff review fixture 2026!";

        // 1. Create staff
        ResponseEntity<Map> created = restTemplate.exchange(
            "/api/v1/admin/accounts",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("email", email, "fullName", "Lockable Staff", "password", password), authHeaders(adminToken)),
            Map.class
        );
        assertEquals(HttpStatus.OK, created.getStatusCode());
        String staffId = (String) created.getBody().get("id");
        long initialVersion = ((Number) created.getBody().get("version")).longValue();

        // 2. Login to get token1
        ResponseEntity<Map> loginResp = restTemplate.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", email, "password", password),
            Map.class
        );
        assertEquals(HttpStatus.OK, loginResp.getStatusCode());
        String token1 = "Bearer " + loginResp.getBody().get("token");

        // token1 works on protected GET
        ResponseEntity<List> profile1 = restTemplate.exchange("/api/v1/me/branches", HttpMethod.GET,
            new HttpEntity<>(authHeaders(token1)), List.class);
        assertEquals(HttpStatus.OK, profile1.getStatusCode());

        // 3. Stale version rejected with 409 Conflict
        ResponseEntity<Map> staleLock = restTemplate.exchange(
            "/api/v1/admin/accounts/" + staffId,
            HttpMethod.PATCH,
            new HttpEntity<>(Map.of("status", "LOCKED", "version", initialVersion + 99), authHeaders(adminToken)),
            Map.class
        );
        assertEquals(HttpStatus.CONFLICT, staleLock.getStatusCode(), "Stale version must return 409 Conflict");

        // Missing version rejected with 400 Bad Request
        ResponseEntity<Map> missingVersion = restTemplate.exchange(
            "/api/v1/admin/accounts/" + staffId,
            HttpMethod.PATCH,
            new HttpEntity<>(Map.of("status", "LOCKED"), authHeaders(adminToken)),
            Map.class
        );
        assertEquals(HttpStatus.BAD_REQUEST, missingVersion.getStatusCode(), "Missing version must return 400 Bad Request");

        // Negative version rejected with 400 Bad Request
        ResponseEntity<Map> negativeVersion = restTemplate.exchange(
            "/api/v1/admin/accounts/" + staffId,
            HttpMethod.PATCH,
            new HttpEntity<>(Map.of("status", "LOCKED", "version", -1), authHeaders(adminToken)),
            Map.class
        );
        assertEquals(HttpStatus.BAD_REQUEST, negativeVersion.getStatusCode(), "Negative version must return 400 Bad Request");

        // Invalid status rejected with 400 Bad Request
        ResponseEntity<Map> badStatus = restTemplate.exchange(
            "/api/v1/admin/accounts/" + staffId,
            HttpMethod.PATCH,
            new HttpEntity<>(Map.of("status", "DELETED", "version", initialVersion), authHeaders(adminToken)),
            Map.class
        );
        assertEquals(HttpStatus.BAD_REQUEST, badStatus.getStatusCode(), "Invalid status must return 400 Bad Request");

        // Non-ADMIN cannot lock (403)
        ResponseEntity<Map> userLock = restTemplate.exchange(
            "/api/v1/admin/accounts/" + staffId,
            HttpMethod.PATCH,
            new HttpEntity<>(Map.of("status", "LOCKED", "version", initialVersion), authHeaders(userToken)),
            Map.class
        );
        assertEquals(HttpStatus.FORBIDDEN, userLock.getStatusCode());

        // Admin target cannot be locked (403)
        ResponseEntity<Map> lockAdmin = restTemplate.exchange(
            "/api/v1/admin/accounts/demo-admin",
            HttpMethod.PATCH,
            new HttpEntity<>(Map.of("status", "LOCKED", "version", 0), authHeaders(adminToken)),
            Map.class
        );
        assertEquals(HttpStatus.FORBIDDEN, lockAdmin.getStatusCode(), "Admin target lock must return 403 Forbidden");

        // 4. Lock STAFF with valid expected version
        ResponseEntity<Map> locked = restTemplate.exchange(
            "/api/v1/admin/accounts/" + staffId,
            HttpMethod.PATCH,
            new HttpEntity<>(Map.of("status", "LOCKED", "version", initialVersion), authHeaders(adminToken)),
            Map.class
        );
        assertEquals(HttpStatus.OK, locked.getStatusCode());
        assertEquals("LOCKED", locked.getBody().get("status"));
        long lockedVersion = ((Number) locked.getBody().get("version")).longValue();
        assertTrue(lockedVersion > initialVersion, "Version must increment on status change");

        // SQL readback: status LOCKED, token_version incremented
        Map<String, Object> lockedRow = jdbc.queryForMap(
            "select status, token_version, version from users where id=?",
            staffId
        );
        assertEquals("LOCKED", lockedRow.get("status"));
        assertEquals(1L, ((Number) lockedRow.get("token_version")).longValue());

        // token1 is now rejected with 401 on protected GET
        ResponseEntity<Map> profileAfterLock = restTemplate.exchange("/api/v1/me/branches", HttpMethod.GET,
            new HttpEntity<>(authHeaders(token1)), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, profileAfterLock.getStatusCode(), "Previously issued token must fail with 401");

        // Login fails with 401 when locked
        ResponseEntity<Map> lockedLogin = restTemplate.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", email, "password", password),
            Map.class
        );
        assertEquals(HttpStatus.UNAUTHORIZED, lockedLogin.getStatusCode(), "Locked account login must fail with 401");

        // 5. Unlock STAFF with expected version
        ResponseEntity<Map> unlocked = restTemplate.exchange(
            "/api/v1/admin/accounts/" + staffId,
            HttpMethod.PATCH,
            new HttpEntity<>(Map.of("status", "ACTIVE", "version", lockedVersion), authHeaders(adminToken)),
            Map.class
        );
        assertEquals(HttpStatus.OK, unlocked.getStatusCode());
        assertEquals("ACTIVE", unlocked.getBody().get("status"));
        long unlockedVersion = ((Number) unlocked.getBody().get("version")).longValue();
        assertTrue(unlockedVersion > lockedVersion, "Version must increment on unlock");

        // SQL readback: status ACTIVE, token_version incremented again
        Map<String, Object> unlockedRow = jdbc.queryForMap(
            "select status, token_version, version from users where id=?",
            staffId
        );
        assertEquals("ACTIVE", unlockedRow.get("status"));
        assertEquals(2L, ((Number) unlockedRow.get("token_version")).longValue());

        // OLD token1 STILL receives 401
        ResponseEntity<Map> oldTokenAfterUnlock = restTemplate.exchange("/api/v1/me/branches", HttpMethod.GET,
            new HttpEntity<>(authHeaders(token1)), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, oldTokenAfterUnlock.getStatusCode(), "Old token must still fail with 401 after unlock");

        // Fresh login succeeds and fresh token receives 200
        ResponseEntity<Map> freshLogin = restTemplate.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", email, "password", password),
            Map.class
        );
        assertEquals(HttpStatus.OK, freshLogin.getStatusCode(), "Fresh login after unlock must succeed");
        String freshToken = "Bearer " + freshLogin.getBody().get("token");

        ResponseEntity<List> freshProfile = restTemplate.exchange("/api/v1/me/branches", HttpMethod.GET,
            new HttpEntity<>(authHeaders(freshToken)), List.class);
        assertEquals(HttpStatus.OK, freshProfile.getStatusCode(), "Fresh token must succeed with 200");
    }

    @Test
    void testStaffBranchAssignmentAndBulkQuery() {
        String email = "staff-assign-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String password = "Staff review fixture 2026!";

        // 1. Create staff
        ResponseEntity<Map> created = restTemplate.exchange(
            "/api/v1/admin/accounts",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("email", email, "fullName", "Staff Chi Nhánh", "password", password), authHeaders(adminToken)),
            Map.class
        );
        assertEquals(HttpStatus.OK, created.getStatusCode());
        String staffId = (String) created.getBody().get("id");

        // Ensure branches exist (hn, dn)
        ensureBranchExists("hn", "Chi nhánh Hà Nội", "ACTIVE");
        ensureBranchExists("dn", "Chi nhánh Đà Nẵng", "ACTIVE");
        ensureBranchExists("inactive-br", "Chi nhánh Tạm ngừng", "INACTIVE");

        // 2. Assign branch A ("hn")
        ResponseEntity<Void> assignHn = restTemplate.exchange(
            "/api/v1/admin/staff-branches",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", staffId, "branchId", "hn"), authHeaders(adminToken)),
            Void.class
        );
        assertEquals(HttpStatus.OK, assignHn.getStatusCode());

        // 3. Assign branch B ("dn")
        ResponseEntity<Void> assignDn = restTemplate.exchange(
            "/api/v1/admin/staff-branches",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", staffId, "branchId", "dn"), authHeaders(adminToken)),
            Void.class
        );
        assertEquals(HttpStatus.OK, assignDn.getStatusCode());

        // 4. Repeated same assignment is idempotent (no duplicate rows)
        ResponseEntity<Void> assignRepeat = restTemplate.exchange(
            "/api/v1/admin/staff-branches",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", staffId, "branchId", "hn"), authHeaders(adminToken)),
            Void.class
        );
        assertEquals(HttpStatus.OK, assignRepeat.getStatusCode());

        Long linkCount = jdbc.queryForObject("select count(*) from staff_branches where user_id=?", Long.class, staffId);
        assertEquals(2L, linkCount, "Must have exactly 2 branch links in database (idempotent)");

        // 5. Bulk Admin GET /api/v1/admin/staff-branches
        ResponseEntity<List> bulkResp = restTemplate.exchange(
            "/api/v1/admin/staff-branches",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(adminToken)),
            List.class
        );
        assertEquals(HttpStatus.OK, bulkResp.getStatusCode());
        List<Map<String, Object>> bulkList = bulkResp.getBody();
        assertNotNull(bulkList);

        // Find our staff's links in bulk
        List<String> assignedBranches = bulkList.stream()
            .filter(item -> staffId.equals(item.get("userId")))
            .map(item -> (String) item.get("branchId"))
            .sorted()
            .toList();
        assertEquals(List.of("dn", "hn"), assignedBranches);

        // Verify bulk items do not contain secret/password/profile
        for (Map<String, Object> item : bulkList) {
            assertTrue(item.containsKey("userId"));
            assertTrue(item.containsKey("branchId"));
            assertFalse(item.containsKey("password"));
            assertFalse(item.containsKey("passwordHash"));
            assertFalse(item.containsKey("tokenVersion"));
            assertFalse(item.containsKey("fullName"));
        }

        // 6. Own Staff GET /api/v1/me/branches
        String staffTokenForUser = "Bearer " + tokens.issue(accountService.require(staffId));
        ResponseEntity<List> ownBranches = restTemplate.exchange(
            "/api/v1/me/branches",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(staffTokenForUser)),
            List.class
        );
        assertEquals(HttpStatus.OK, ownBranches.getStatusCode());
        List<String> ownList = ownBranches.getBody().stream().map(Object::toString).sorted().toList();
        assertEquals(List.of("dn", "hn"), ownList);

        // 7. Non-STAFF rejected (USER, ADMIN, nonexistent)
        ResponseEntity<Void> assignUser = restTemplate.exchange(
            "/api/v1/admin/staff-branches",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", "demo-user", "branchId", "hn"), authHeaders(adminToken)),
            Void.class
        );
        assertEquals(HttpStatus.BAD_REQUEST, assignUser.getStatusCode(), "Assigning USER must return 400");

        ResponseEntity<Void> assignAdmin = restTemplate.exchange(
            "/api/v1/admin/staff-branches",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", "demo-admin", "branchId", "hn"), authHeaders(adminToken)),
            Void.class
        );
        assertEquals(HttpStatus.BAD_REQUEST, assignAdmin.getStatusCode(), "Assigning ADMIN must return 400");

        ResponseEntity<Void> assignNonexistentUser = restTemplate.exchange(
            "/api/v1/admin/staff-branches",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", "nonexistent-user-id", "branchId", "hn"), authHeaders(adminToken)),
            Void.class
        );
        assertEquals(HttpStatus.BAD_REQUEST, assignNonexistentUser.getStatusCode(), "Assigning nonexistent user must return 400");

        // 8. Nonexistent branch and inactive branch rejected
        ResponseEntity<Void> assignNonexistentBranch = restTemplate.exchange(
            "/api/v1/admin/staff-branches",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", staffId, "branchId", "nonexistent-branch-id"), authHeaders(adminToken)),
            Void.class
        );
        assertEquals(HttpStatus.NOT_FOUND, assignNonexistentBranch.getStatusCode(), "Assigning nonexistent branch must return 404");

        ResponseEntity<Void> assignInactiveBranch = restTemplate.exchange(
            "/api/v1/admin/staff-branches",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", staffId, "branchId", "inactive-br"), authHeaders(adminToken)),
            Void.class
        );
        assertEquals(HttpStatus.CONFLICT, assignInactiveBranch.getStatusCode(), "Assigning inactive branch must return 409 Conflict");

        // 9. Unauthorized bulk read and assign POST rejected
        ResponseEntity<Map> userBulk = restTemplate.exchange("/api/v1/admin/staff-branches", HttpMethod.GET,
            new HttpEntity<>(authHeaders(userToken)), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, userBulk.getStatusCode());

        ResponseEntity<Map> staffBulk = restTemplate.exchange("/api/v1/admin/staff-branches", HttpMethod.GET,
            new HttpEntity<>(authHeaders(staffToken)), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, staffBulk.getStatusCode());

        ResponseEntity<Map> anonBulk = restTemplate.exchange("/api/v1/admin/staff-branches", HttpMethod.GET,
            new HttpEntity<>(authHeaders(null)), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anonBulk.getStatusCode());

        ResponseEntity<Void> userAssignPost = restTemplate.exchange("/api/v1/admin/staff-branches", HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", staffId, "branchId", "hn"), authHeaders(userToken)), Void.class);
        assertEquals(HttpStatus.FORBIDDEN, userAssignPost.getStatusCode());

        ResponseEntity<Void> staffAssignPost = restTemplate.exchange("/api/v1/admin/staff-branches", HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", staffId, "branchId", "hn"), authHeaders(staffToken)), Void.class);
        assertEquals(HttpStatus.FORBIDDEN, staffAssignPost.getStatusCode());

        ResponseEntity<Void> anonAssignPost = restTemplate.exchange("/api/v1/admin/staff-branches", HttpMethod.POST,
            new HttpEntity<>(Map.of("userId", staffId, "branchId", "hn"), authHeaders(null)), Void.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anonAssignPost.getStatusCode());

        // Existing links remain intact
        Long finalCount = jdbc.queryForObject("select count(*) from staff_branches where user_id=?", Long.class, staffId);
        assertEquals(2L, finalCount, "Staff branch links must remain intact");
    }

    private void ensureBranchExists(String id, String name, String status) {
        Long cnt = jdbc.queryForObject("select count(*) from branches where id=?", Long.class, id);
        if (cnt == 0) {
            jdbc.update("insert into branches(id, name, address, status, version) values(?, ?, ?, ?, ?)",
                id, name, "Địa chỉ " + name, status, 0L);
        } else {
            jdbc.update("update branches set status=? where id=?", status, id);
        }
    }
}

