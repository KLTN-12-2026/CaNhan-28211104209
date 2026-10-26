package com.carrental;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest(properties = {
    "app.jwt-secret=pilot-import-test-secret-at-least-32-bytes",
    "app.seed-enabled=true",
    "app.seed-password=LocationApiTestOnly123!"
})
@AutoConfigureMockMvc
class LocationsPilotDatasetTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:pilottest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private File resolvePilotFile() {
        File file = new File("../scripts/ai/locations-pilot.json");
        if (file.exists()) return file;
        file = new File("scripts/ai/locations-pilot.json");
        if (file.exists()) return file;
        throw new IllegalStateException("Cannot resolve locations-pilot.json from: " + Path.of(".").toAbsolutePath());
    }

    private String adminToken() throws Exception {
        String body = json.writeValueAsString(Map.of("email", "admin@example.test", "password", "LocationApiTestOnly123!"));
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).path("token").asText();
    }

    @Test
    void pilotJson_SchemaAndProvenanceValidation() throws Exception {
        File file = resolvePilotFile();
        assertTrue(file.exists(), "locations-pilot.json must exist");
        JsonNode root = json.readTree(file);
        assertEquals("kdrive-locations-pilot-v1", root.path("schemaVersion").asText());
        JsonNode locations = root.path("locations");
        assertTrue(locations.isArray());
        assertEquals(8, locations.size(), "Pilot dataset must contain exactly 8 locations");

        int validMetadataCount = 0;
        int unknownCount = 0;

        for (JsonNode loc : locations) {
            String name = loc.path("name").asText();
            assertFalse(name.isBlank(), "Location name must not be blank");
            double lat = loc.path("latitude").asDouble();
            double lng = loc.path("longitude").asDouble();
            assertTrue(lat >= 15.4 && lat <= 16.7, "Latitude must be within Da Nang - Hoi An bounding box");
            assertTrue(lng >= 107.4 && lng <= 108.8, "Longitude must be within Da Nang - Hoi An bounding box");

            JsonNode prov = loc.path("provenance");
            assertTrue(prov.hasNonNull("address"), "Provenance must have address");
            assertTrue(prov.hasNonNull("coordinatesSource"), "Provenance must have coordinatesSource");

            JsonNode meta = loc.path("metadata");
            assertEquals("Asia/Ho_Chi_Minh", meta.path("timezone").asText());

            JsonNode weekly = meta.path("weekly");
            boolean allUnknown = true;
            for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
                if (!"UNKNOWN".equals(weekly.path(day).path("state").asText())) {
                    allUnknown = false;
                }
            }

            if (allUnknown) {
                unknownCount++;
                assertTrue(meta.path("durationMinutes").isNull());
                assertTrue(meta.path("hoursEvidence").isNull());
            } else {
                validMetadataCount++;
                assertTrue(meta.path("durationMinutes").asInt() > 0);
                assertTrue(meta.path("hoursEvidence").path("url").asText().startsWith("http"));
                assertTrue(meta.path("durationEvidence").path("url").asText().startsWith("http"));
            }
        }

        assertTrue(validMetadataCount >= 4, "Must have at least 4 locations with full verified visit facts");
        assertTrue(unknownCount >= 2, "Must have locations deliberately left UNKNOWN without inventing facts");
    }

    @Test
    void pilotJson_CanBeAppliedToBackendAndIsIdempotent() throws Exception {
        String token = adminToken();
        File file = resolvePilotFile();
        JsonNode root = json.readTree(file);
        JsonNode locations = root.path("locations");

        // Helper map categories
        Map<String, String> catMap = new HashMap<>();

        // 1. First Pass (Apply to clean fixture)
        for (JsonNode loc : locations) {
            String catName = loc.path("categoryName").asText();
            if (!catMap.containsKey(catName)) {
                MvcResult catRes = mvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/location-categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", catName))))
                    .andReturn();
                if (catRes.getResponse().getStatus() == 201) {
                    catMap.put(catName, json.readTree(catRes.getResponse().getContentAsString()).path("id").asText());
                } else {
                    // Try to find existing
                    MvcResult listRes = mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/location-categories")
                            .header("Authorization", "Bearer " + token))
                        .andReturn();
                    for (JsonNode existingCat : json.readTree(listRes.getResponse().getContentAsString())) {
                        if (existingCat.path("name").asText().equals(catName)) {
                            catMap.put(catName, existingCat.path("id").asText());
                        }
                    }
                }
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("categoryId", catMap.get(catName));
            payload.put("name", loc.path("name").asText());
            payload.put("latitude", new BigDecimal(loc.path("latitude").asText()));
            payload.put("longitude", new BigDecimal(loc.path("longitude").asText()));
            payload.put("status", loc.path("status").asText());
            if (!loc.path("metadata").isNull()) {
                payload.put("metadata", json.convertValue(loc.path("metadata"), Map.class));
            }

            MvcResult locRes = mvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/locations")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
                .andReturn();
            assertEquals(201, locRes.getResponse().getStatus(), "Location creation must succeed: " + loc.path("name").asText() + " - " + locRes.getResponse().getContentAsString());
        }

        // 2. Verification of public catalog view
        MvcResult publicCatalog = mvc.perform(MockMvcRequestBuilders.get("/api/v1/ai/locations")
                .header("Authorization", "Bearer " + userToken()))
            .andReturn();
        assertEquals(200, publicCatalog.getResponse().getStatus());
        JsonNode catalogList = json.readTree(publicCatalog.getResponse().getContentAsString());
        assertTrue(catalogList.size() >= 8, "Public catalog must return created locations");

        // 3. Second Pass: Idempotency check via existing list
        MvcResult adminList = mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/locations")
                .header("Authorization", "Bearer " + token))
            .andReturn();
        JsonNode existingAdminLocations = json.readTree(adminList.getResponse().getContentAsString());
        Map<String, JsonNode> existingByName = new HashMap<>();
        existingAdminLocations.forEach(n -> existingByName.put(n.path("name").asText(), n));

        for (JsonNode loc : locations) {
            String name = loc.path("name").asText();
            assertTrue(existingByName.containsKey(name), "Location already exists in catalog");
            JsonNode ex = existingByName.get(name);
            assertEquals(new BigDecimal(loc.path("latitude").asText()).stripTrailingZeros(),
                new BigDecimal(ex.path("latitude").asText()).stripTrailingZeros());
            assertEquals(new BigDecimal(loc.path("longitude").asText()).stripTrailingZeros(),
                new BigDecimal(ex.path("longitude").asText()).stripTrailingZeros());
        }
    }

    private String userToken() throws Exception {
        String body = json.writeValueAsString(Map.of("email", "user@example.test", "password", "LocationApiTestOnly123!"));
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).path("token").asText();
    }
}
