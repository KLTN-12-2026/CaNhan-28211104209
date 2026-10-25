package com.carrental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
    "app.jwt-secret=location-api-test-secret-at-least-32-bytes",
    "app.seed-enabled=true",
    "app.seed-password=LocationApiTestOnly123!"
})
@AutoConfigureMockMvc
class LocationIntegrationTest {
    private static final String CATEGORIES = "/api/v1/admin/location-categories";
    private static final String LOCATIONS = "/api/v1/admin/locations";

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
            ? "jdbc:h2:mem:locationapi;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            : url);
        registry.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String token(String email) throws Exception {
        String request = json.writeValueAsString(Map.of("email", email, "password", "LocationApiTestOnly123!"));
        String response = mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(request))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("token").asText();
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String body(Object input) throws Exception {
        return json.writeValueAsString(input);
    }

    private String createCategory(String admin) throws Exception {
        String name = "Location type " + UUID.randomUUID();
        String response = mvc.perform(authorized(post(CATEGORIES), admin)
                .contentType("application/json").content(body(Map.of("name", name))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("id").asText();
    }

    private Map<String, Object> input(String categoryId, String name, String latitude, String longitude, String state) {
        return Map.of(
            "categoryId", categoryId,
            "name", name,
            "latitude", new BigDecimal(latitude),
            "longitude", new BigDecimal(longitude),
            "status", state
        );
    }

    @Test
    void adminCanCreateReadUpdateCategoryCoordinatesAndStatus() throws Exception {
        String admin = token("admin@example.test");
        String firstCategory = createCategory(admin);
        String secondCategory = createCategory(admin);
        String name = "Park " + UUID.randomUUID();

        String createdBody = mvc.perform(authorized(post(LOCATIONS), admin)
                .contentType("application/json").content(body(input(firstCategory, " " + name + " ", "0", "0", "INACTIVE"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.categoryId").value(firstCategory))
            .andExpect(jsonPath("$.categoryName").isString())
            .andExpect(jsonPath("$.latitude").value(0))
            .andExpect(jsonPath("$.longitude").value(0))
            .andExpect(jsonPath("$.status").value("INACTIVE"))
            .andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(createdBody);
        String id = created.path("id").asText();
        String oldRevision = created.path("revision").asText();

        mvc.perform(authorized(get(LOCATIONS + "/" + id), admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.status").value("INACTIVE"));

        String updatedBody = mvc.perform(authorized(put(LOCATIONS + "/" + id), admin)
                .contentType("application/json")
                .content(body(Map.of(
                    "categoryId", secondCategory,
                    "name", name + " updated",
                    "latitude", new BigDecimal("90"),
                    "longitude", new BigDecimal("180"),
                    "status", "ACTIVE",
                    "expectedRevision", oldRevision
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categoryId").value(secondCategory))
            .andExpect(jsonPath("$.name").value(name + " updated"))
            .andExpect(jsonPath("$.latitude").value(90))
            .andExpect(jsonPath("$.longitude").value(180))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andReturn().getResponse().getContentAsString();
        String currentRevision = json.readTree(updatedBody).path("revision").asText();

        mvc.perform(authorized(put(LOCATIONS + "/" + id), admin)
                .contentType("application/json")
                .content(body(Map.of(
                    "categoryId", firstCategory,
                    "name", name + " stale",
                    "latitude", new BigDecimal("1"),
                    "longitude", new BigDecimal("2"),
                    "status", "INACTIVE",
                    "expectedRevision", oldRevision
                ))))
            .andExpect(status().isConflict());

        mvc.perform(authorized(get(LOCATIONS), admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '%s')].name".formatted(id)).value(org.hamcrest.Matchers.hasItem(name + " updated")))
            .andExpect(jsonPath("$[?(@.id == '%s')].revision".formatted(id)).value(org.hamcrest.Matchers.hasItem(currentRevision)));

        assertEquals(1, jdbc.queryForObject("select count(*) from locations where id=? and category_id=? and name=? and status='ACTIVE'", Integer.class, id, secondCategory, name + " updated"));
        assertEquals(0, jdbc.queryForObject("select count(*) from locations where id=? and category_id=?", Integer.class, id, firstCategory));
    }

    @Test
    void locationInputsAreValidatedBeforeAnyRowIsWritten() throws Exception {
        String admin = token("admin@example.test");
        String category = createCategory(admin);
        String unknownCategory = UUID.randomUUID().toString();
        int before = jdbc.queryForObject("select count(*) from locations", Integer.class);
        String name = "Invalid location " + UUID.randomUUID();

        mvc.perform(authorized(post(LOCATIONS), admin).contentType("application/json")
                .content(body(input(category, name, "-90.0000001", "0", "ACTIVE"))))
            .andExpect(status().isBadRequest());
        mvc.perform(authorized(post(LOCATIONS), admin).contentType("application/json")
                .content(body(input(category, name, "0", "180.0000001", "ACTIVE"))))
            .andExpect(status().isBadRequest());
        mvc.perform(authorized(post(LOCATIONS), admin).contentType("application/json")
                .content(body(input(category, name, "1.12345678", "0", "ACTIVE"))))
            .andExpect(status().isBadRequest());
        mvc.perform(authorized(post(LOCATIONS), admin).contentType("application/json")
                .content(body(input(category, name, "0", "0", "UNKNOWN"))))
            .andExpect(status().isBadRequest());
        mvc.perform(authorized(post(LOCATIONS), admin).contentType("application/json")
                .content(body(input(unknownCategory, name, "0", "0", "ACTIVE"))))
            .andExpect(status().isBadRequest());
        mvc.perform(authorized(post(LOCATIONS), admin).contentType("application/json")
                .content(body(input(category, "x".repeat(201), "0", "0", "ACTIVE"))))
            .andExpect(status().isBadRequest());

        assertEquals(before, jdbc.queryForObject("select count(*) from locations", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from locations where name=?", Integer.class, name));
    }

    @Test
    void locationEndpointsRequireAdminAndDoNotProvideDelete() throws Exception {
        String user = token("user@example.test");
        String staff = token("staff.dn@example.test");
        String admin = token("admin@example.test");
        String id = UUID.randomUUID().toString();
        Map<String, Object> update = Map.of(
            "categoryId", UUID.randomUUID().toString(),
            "name", "No write",
            "latitude", BigDecimal.ZERO,
            "longitude", BigDecimal.ZERO,
            "status", "ACTIVE",
            "expectedRevision", "0".repeat(64)
        );

        mvc.perform(get(LOCATIONS)).andExpect(status().isUnauthorized());
        mvc.perform(authorized(get(LOCATIONS), user)).andExpect(status().isForbidden());
        mvc.perform(authorized(get(LOCATIONS + "/" + id), staff)).andExpect(status().isForbidden());
        mvc.perform(authorized(post(LOCATIONS), user).contentType("application/json")
                .content(body(input(UUID.randomUUID().toString(), "No write", "0", "0", "ACTIVE"))))
            .andExpect(status().isForbidden());
        mvc.perform(authorized(put(LOCATIONS + "/" + id), staff).contentType("application/json").content(body(update)))
            .andExpect(status().isForbidden());
        mvc.perform(authorized(delete(LOCATIONS + "/" + id), admin)).andExpect(status().isMethodNotAllowed());
        mvc.perform(authorized(get(LOCATIONS + "/" + id), admin)).andExpect(status().isNotFound());
    }
    @Test
    void customerCatalogAndRoutingRespectHttpPermissionsAndVisibility() throws Exception {
        String admin=token("admin@example.test"), user=token("user@example.test"), staff=token("staff.dn@example.test");
        String category=createCategory(admin);
        String visible=UUID.randomUUID().toString(), hidden=UUID.randomUUID().toString();
        for(String name:java.util.List.of(visible,hidden))mvc.perform(authorized(post(LOCATIONS),admin)
            .contentType("application/json").content(body(input(category,name,"16","108",name.equals(visible)?"ACTIVE":"INACTIVE"))))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/ai/locations")).andExpect(status().isUnauthorized());
        mvc.perform(authorized(get("/api/v1/ai/locations"),admin)).andExpect(status().isForbidden());
        mvc.perform(authorized(get("/api/v1/ai/locations"),staff)).andExpect(status().isForbidden());
        String response=mvc.perform(authorized(get("/api/v1/ai/locations"),user)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(response.contains(visible));
        org.junit.jupiter.api.Assertions.assertFalse(response.contains(hidden));
        org.junit.jupiter.api.Assertions.assertFalse(response.contains("revision"));
        String route=body(Map.of("locationIds",java.util.List.of("a","b")));
        mvc.perform(post("/api/v1/ai/routes/preview").contentType("application/json").content(route)).andExpect(status().isUnauthorized());
        mvc.perform(authorized(post("/api/v1/ai/routes/preview"),staff).contentType("application/json").content(route)).andExpect(status().isForbidden());
        mvc.perform(authorized(post("/api/v1/ai/routes/preview"),user).contentType("application/json").content(route)).andExpect(status().isServiceUnavailable());
    }
    @Test void visitMetadataIsCuratedRevisionedAndVisibleOnlyThroughAllowedCatalog() throws Exception {
        String admin=token("admin@example.test"), user=token("user@example.test"), staff=token("staff.dn@example.test");
        String category=createCategory(admin);
        var payload=new java.util.HashMap<String,Object>(input(category,"Metadata "+UUID.randomUUID(),"16","108","ACTIVE"));
        var evidence=Map.of("url","https://example.org/official","verifiedAt","2020-01-01T00:00:00Z");
        var metadata=Map.of("timezone","Asia/Ho_Chi_Minh","weekly",Map.of("MONDAY",Map.of("state","OPEN_24H","intervals",java.util.List.of())),"exceptions",Map.of(),"durationMinutes",60,"hoursEvidence",evidence,"durationEvidence",evidence);
        payload.put("metadata",metadata);
        mvc.perform(authorized(post(LOCATIONS),staff).contentType("application/json").content(body(payload))).andExpect(status().isForbidden());
        JsonNode created=json.readTree(mvc.perform(authorized(post(LOCATIONS),admin).contentType("application/json").content(body(payload)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.metadata.durationMinutes").value(60)).andReturn().getResponse().getContentAsString());
        String id=created.path("id").asText(),revision=created.path("revision").asText();
        String catalog=mvc.perform(authorized(get("/api/v1/ai/locations"),user)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode visible=null;for(JsonNode row:json.readTree(catalog))if(id.equals(row.path("id").asText()))visible=row;
        assertEquals(60,visible.path("metadata").path("durationMinutes").asInt());
        payload.put("expectedRevision",revision);
        payload.put("metadata",Map.of("timezone","Asia/Ho_Chi_Minh","weekly",Map.of(),"exceptions",Map.of()));
        mvc.perform(authorized(put(LOCATIONS+"/"+id),admin).contentType("application/json").content(body(payload)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.metadata.weekly.MONDAY.state").value("UNKNOWN"));
        mvc.perform(authorized(put(LOCATIONS+"/"+id),admin).contentType("application/json").content(body(payload))).andExpect(status().isConflict());
        payload.remove("expectedRevision");payload.put("metadata",Map.of("timezone","UTC","weekly",Map.of(),"exceptions",Map.of()));
        int count=jdbc.queryForObject("select count(*) from locations",Integer.class);
        mvc.perform(authorized(post(LOCATIONS),admin).contentType("application/json").content(body(payload))).andExpect(status().isBadRequest());
        assertEquals(count,jdbc.queryForObject("select count(*) from locations",Integer.class));
    }

    @Test void naturalCriteriaEndpointIsAuthenticatedUserOnlyAndDisabledByDefault() throws Exception {
        String payload=body(Map.of("text","4 người,3triệu"));
        mvc.perform(post("/api/v1/ai/criteria/parse").contentType("application/json").content(payload)).andExpect(status().isUnauthorized());
        for(String email:java.util.List.of("admin@example.test","staff.dn@example.test"))mvc.perform(authorized(post("/api/v1/ai/criteria/parse"),token(email)).contentType("application/json").content(payload)).andExpect(status().isForbidden());
        mvc.perform(authorized(post("/api/v1/ai/criteria/parse"),token("user@example.test")).contentType("application/json").content(payload)).andExpect(status().isServiceUnavailable());
    }
}
