package com.carrental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
    "app.jwt-secret=location-category-test-secret-at-least-32-bytes",
    "app.seed-enabled=true",
    "app.seed-password=LocationApiTestOnly123!"
})
@AutoConfigureMockMvc
class LocationCategoryIntegrationTest {
    private static final String CATEGORIES = "/api/v1/admin/location-categories";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            CancelBoundaryTest.verifySafeIsolatedDatabase(
                url,
                System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),
                System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),
                System.getenv("KDRIVE_TEST_DB_USER"),
                System.getenv("KDRIVE_TEST_DB_PASSWORD")
            );
        }
        registry.add("spring.datasource.url", () -> url == null
            ? "jdbc:h2:mem:locationcategory;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            : url);
        registry.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String token(String email) throws Exception {
        String body = json.writeValueAsString(Map.of("email", email, "password", "LocationApiTestOnly123!"));
        String response = mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String value = json.readTree(response).path("token").asText();
        assertNotNull(value);
        return value;
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    @Test
    void adminCanCreateListAndUpdateCategoryWithCurrentRevision() throws Exception {
        String admin = token("admin@example.test");
        String name = "Location category " + UUID.randomUUID();

        String createdBody = mvc.perform(authorized(post(CATEGORIES), admin)
                .contentType("application/json").content(body(Map.of("name", "  " + name + "  "))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.revision").isString())
            .andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(createdBody);
        String id = created.path("id").asText();
        String firstRevision = created.path("revision").asText();

        mvc.perform(authorized(get(CATEGORIES), admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '%s')].name".formatted(id)).value(org.hamcrest.Matchers.hasItem(name)));

        String updatedName = name + " updated";
        String updatedBody = mvc.perform(authorized(put(CATEGORIES + "/" + id), admin)
                .contentType("application/json")
                .content(body(Map.of("name", updatedName, "expectedRevision", firstRevision))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value(updatedName))
            .andReturn().getResponse().getContentAsString();
        String currentRevision = json.readTree(updatedBody).path("revision").asText();

        mvc.perform(authorized(put(CATEGORIES + "/" + id), admin)
                .contentType("application/json")
                .content(body(Map.of("name", name + " stale", "expectedRevision", firstRevision))))
            .andExpect(status().isConflict());

        mvc.perform(authorized(get(CATEGORIES), admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '%s')].name".formatted(id)).value(org.hamcrest.Matchers.hasItem(updatedName)))
            .andExpect(jsonPath("$[?(@.id == '%s')].revision".formatted(id)).value(org.hamcrest.Matchers.hasItem(currentRevision)));

        assertEquals(1, jdbc.queryForObject("select count(*) from location_categories where id=? and name=?", Integer.class, id, updatedName));
    }

    @Test
    void categoryEndpointsRequireAdminAndNeverExposeDelete() throws Exception {
        String user = token("user@example.test");
        String staff = token("staff.dn@example.test");
        String admin = token("admin@example.test");
        String id = UUID.randomUUID().toString();
        String update = body(Map.of("name", "No write", "expectedRevision", "0".repeat(64)));

        mvc.perform(get(CATEGORIES)).andExpect(status().isUnauthorized());
        mvc.perform(authorized(get(CATEGORIES), user)).andExpect(status().isForbidden());
        mvc.perform(authorized(get(CATEGORIES), staff)).andExpect(status().isForbidden());
        mvc.perform(authorized(post(CATEGORIES), user).contentType("application/json").content(body(Map.of("name", "No write"))))
            .andExpect(status().isForbidden());
        mvc.perform(authorized(put(CATEGORIES + "/" + id), staff).contentType("application/json").content(update))
            .andExpect(status().isForbidden());
        mvc.perform(authorized(delete(CATEGORIES + "/" + id), admin)).andExpect(status().isMethodNotAllowed());
        mvc.perform(authorized(put(CATEGORIES + "/" + id), admin).contentType("application/json").content(update))
            .andExpect(status().isNotFound());
    }

    @Test
    void invalidAndDuplicateNamesAreRejectedWithoutExtraRows() throws Exception {
        String admin = token("admin@example.test");
        String uniqueName = "Normalized category " + UUID.randomUUID();

        mvc.perform(authorized(post(CATEGORIES), admin).contentType("application/json").content(body(Map.of("name", "   "))))
            .andExpect(status().isBadRequest());
        mvc.perform(authorized(post(CATEGORIES), admin).contentType("application/json").content(body(Map.of("name", "x".repeat(101)))))
            .andExpect(status().isBadRequest());

        mvc.perform(authorized(post(CATEGORIES), admin).contentType("application/json").content(body(Map.of("name", " " + uniqueName + " "))))
            .andExpect(status().isCreated());
        mvc.perform(authorized(post(CATEGORIES), admin).contentType("application/json").content(body(Map.of("name", uniqueName))))
            .andExpect(status().isConflict());

        assertEquals(1, jdbc.queryForObject("select count(*) from location_categories where name=?", Integer.class, uniqueName));
    }
}