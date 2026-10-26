package com.carrental;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
    "app.jwt-secret=integration-test-only-secret-at-least-32-bytes",
    "app.seed-enabled=true",
    "app.seed-password=IntegrationOnly123!",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@AutoConfigureMockMvc
class BackendRegressionTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:regression;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        r.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        r.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private JsonNode request(MockHttpServletRequestBuilder req, String token, Object body, int status) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType("application/json").content(json.writeValueAsString(body));
        var result = mvc.perform(req).andReturn();
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? json.createObjectNode() : json.readTree(content);
    }

    private String login(String email) throws Exception {
        return request(post("/api/v1/auth/login"), null, Map.of("email", email, "password", "IntegrationOnly123!"), 200).path("token").asText();
    }

    @Test
    void regressionL05VehicleEnumNullAndInvalidHandledAsBadRequest() throws Exception {
        String admin = login("admin@example.test");
        // Null transmission
        request(post("/api/v1/admin/vehicles"), admin, Map.of(
            "name", "Car Invalid", "plate", "51F-11111", "dailyPrice", 600000,
            "seats", 5, "homeBranchId", "dn", "status", "ACTIVE"
        ), 400);

        // Invalid transmission
        request(post("/api/v1/admin/vehicles"), admin, Map.of(
            "name", "Car Invalid", "plate", "51F-11111", "dailyPrice", 600000,
            "seats", 5, "homeBranchId", "dn", "transmission", "INVALID_TX", "status", "ACTIVE"
        ), 400);

        // Null status in operation patch
        String staffDn = login("staff.dn@example.test");
        request(patch("/api/v1/vehicles/v1/operation"), staffDn, Map.of("version", 0), 400);
    }

    @Test
    void regressionL06RouteExpectedVersionLostUpdateGuard() throws Exception {
        String admin = login("admin@example.test");
        // Read current version of route dn -> hn
        JsonNode routes = request(get("/api/v1/branch-routes"), null, null, 200);
        long curVer = 0L;
        for (JsonNode r : routes) {
            if ("dn".equals(r.path("pickupBranchId").asText()) && "hn".equals(r.path("returnBranchId").asText())) {
                curVer = r.path("version").asLong();
                break;
            }
        }

        // Correct version update
        request(put("/api/v1/admin/branch-routes"), admin, Map.of(
            "pickupBranchId", "dn", "returnBranchId", "hn", "fee", 1500001, "enabled", true, "expectedVersion", curVer
        ), 200);

        // Stale expected version conflict
        request(put("/api/v1/admin/branch-routes"), admin, Map.of(
            "pickupBranchId", "dn", "returnBranchId", "hn", "fee", 1700000, "enabled", true, "expectedVersion", curVer
        ), 409);
    }

    @Test
    void regressionL11AuthEndpointsDoNotRequireToken() throws Exception {
        String userToken = login("user@example.test");
        assertNotNull(userToken);
        assertFalse(userToken.isBlank());
    }
}
