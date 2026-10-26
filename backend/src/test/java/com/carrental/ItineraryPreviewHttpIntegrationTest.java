package com.carrental;

import com.carrental.modules.ai.itinerary.service.QwenItinerarySequencePlanner;
import com.carrental.modules.ai.routing.OsrmRoutingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "app.seed-enabled=true",
    "app.seed-password=LocationApiTestOnly123!",
    "app.jwt-secret=preview-http-test-secret-at-least-32-bytes"
})
@AutoConfigureMockMvc
public class ItineraryPreviewHttpIntegrationTest {
    private static final String PREVIEW_URL = "/api/v1/ai/itineraries/preview";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockBean
    private OsrmRoutingClient osrmRoutingClient;

    @MockBean
    private QwenItinerarySequencePlanner sequencePlanner;

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
            ? "jdbc:h2:mem:itinerarypreview;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            : url);
    }

    private String userToken;
    private String adminToken;
    private String locId1;
    private String locId2;

    @BeforeEach
    void setUp() throws Exception {
        var userLoginRes = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "user@example.test", "password", "LocationApiTestOnly123!"))))
            .andExpect(status().isOk())
            .andReturn();
        userToken = json.readTree(userLoginRes.getResponse().getContentAsString()).path("token").asText();

        var adminLoginRes = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "admin@example.test", "password", "LocationApiTestOnly123!"))))
            .andExpect(status().isOk())
            .andReturn();
        adminToken = json.readTree(adminLoginRes.getResponse().getContentAsString()).path("token").asText();

        // Tạo category và 2 locations
        var catRes = mvc.perform(post("/api/v1/admin/location-categories")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Văn hóa Preview " + System.nanoTime()))))
            .andExpect(status().isCreated())
            .andReturn();
        String catId = json.readTree(catRes.getResponse().getContentAsString()).path("id").asText();

        String metaA = """
            {
                "timezone": "Asia/Ho_Chi_Minh",
                "weekly": {
                    "MONDAY": {"state": "INTERVALS", "intervals": [{"start": "07:30", "end": "17:00", "nextDay": false}]},
                    "TUESDAY": {"state": "INTERVALS", "intervals": [{"start": "07:30", "end": "17:00", "nextDay": false}]},
                    "WEDNESDAY": {"state": "INTERVALS", "intervals": [{"start": "07:30", "end": "17:00", "nextDay": false}]},
                    "THURSDAY": {"state": "INTERVALS", "intervals": [{"start": "07:30", "end": "17:00", "nextDay": false}]},
                    "FRIDAY": {"state": "INTERVALS", "intervals": [{"start": "07:30", "end": "17:00", "nextDay": false}]},
                    "SATURDAY": {"state": "INTERVALS", "intervals": [{"start": "07:30", "end": "17:00", "nextDay": false}]},
                    "SUNDAY": {"state": "INTERVALS", "intervals": [{"start": "07:30", "end": "17:00", "nextDay": false}]}
                },
                "exceptions": {},
                "durationMinutes": 60,
                "hoursEvidence": {"url": "https://example.com/a", "verifiedAt": "2026-10-01T00:00:00Z"},
                "durationEvidence": {"url": "https://example.com/a", "verifiedAt": "2026-10-01T00:00:00Z"}
            }
            """;

        String metaB = """
            {
                "timezone": "Asia/Ho_Chi_Minh",
                "weekly": {
                    "MONDAY": {"state": "OPEN_24H", "intervals": []},
                    "TUESDAY": {"state": "OPEN_24H", "intervals": []},
                    "WEDNESDAY": {"state": "OPEN_24H", "intervals": []},
                    "THURSDAY": {"state": "OPEN_24H", "intervals": []},
                    "FRIDAY": {"state": "OPEN_24H", "intervals": []},
                    "SATURDAY": {"state": "OPEN_24H", "intervals": []},
                    "SUNDAY": {"state": "OPEN_24H", "intervals": []}
                },
                "exceptions": {},
                "durationMinutes": 45,
                "hoursEvidence": {"url": "https://example.com/b", "verifiedAt": "2026-10-01T00:00:00Z"},
                "durationEvidence": {"url": "https://example.com/b", "verifiedAt": "2026-10-01T00:00:00Z"}
            }
            """;

        var locRes1 = mvc.perform(post("/api/v1/admin/locations")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "categoryId", catId,
                    "name", "Điểm A Preview " + System.nanoTime(),
                    "latitude", 16.0601,
                    "longitude", 108.2235,
                    "status", "ACTIVE",
                    "metadata", json.readTree(metaA)
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        locId1 = json.readTree(locRes1.getResponse().getContentAsString()).path("id").asText();

        var locRes2 = mvc.perform(post("/api/v1/admin/locations")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "categoryId", catId,
                    "name", "Điểm B Preview " + System.nanoTime(),
                    "latitude", 16.0041,
                    "longitude", 108.2638,
                    "status", "ACTIVE",
                    "metadata", json.readTree(metaB)
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        locId2 = json.readTree(locRes2.getResponse().getContentAsString()).path("id").asText();
    }

    @Test
    void postPreview_RequiresUserRole() throws Exception {
        mvc.perform(post(PREVIEW_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());

        mvc.perform(post(PREVIEW_URL)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "tripDate", LocalDate.now().plusDays(2).toString(),
                    "startTime", "08:00",
                    "endTime", "13:00",
                    "startLocationId", locId1
                ))))
            .andExpect(status().isForbidden());
    }

    @Test
    void postPreview_ValidatesPayload() throws Exception {
        mvc.perform(post(PREVIEW_URL)
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void postPreview_ReturnsValidatedWithFullSnapshotAndAttribution() throws Exception {
        when(sequencePlanner.planSequence(eq(locId1), anyList(), anyInt()))
            .thenReturn(new QwenItinerarySequencePlanner.SequenceResult(List.of(locId1, locId2), "QWEN_OPTIMIZED"));

        // Mock OSRM leg routing
        var leg = new OsrmRoutingClient.Leg(12000.0, 1080.0); // 12km, 18 phút
        when(osrmRoutingClient.route(anyList())).thenReturn(
            new OsrmRoutingClient.Estimate(OsrmRoutingClient.Status.VERIFIED, 12000.0, 1080.0, "OSM Test", List.of(leg))
        );

        String tripDate = LocalDate.now().plusDays(2).toString();

        mvc.perform(post(PREVIEW_URL)
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "tripDate", tripDate,
                    "startTime", "08:00",
                    "endTime", "13:00",
                    "startLocationId", locId1
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("VALIDATED"))
            .andExpect(jsonPath("$.criteria.startLocationId").value(locId1))
            .andExpect(jsonPath("$.criteria.contractVersion").value("kdrive-itinerary-v1"))
            .andExpect(jsonPath("$.fingerprint").isNotEmpty())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].locationId").value(locId1))
            .andExpect(jsonPath("$.items[0].arrival").value("08:00"))
            .andExpect(jsonPath("$.items[0].departure").value("09:00"))
            .andExpect(jsonPath("$.items[0].dwellMinutes").value(60))
            .andExpect(jsonPath("$.items[0].travelToNextMinutes").value(18))
            .andExpect(jsonPath("$.items[0].fullHoursDescription").isNotEmpty())
            .andExpect(jsonPath("$.items[0].osmAttribution").value("© OpenStreetMap contributors; OSRM driving estimate"))
            .andExpect(jsonPath("$.items[1].locationId").value(locId2))
            .andExpect(jsonPath("$.items[1].arrival").value("09:18"));
    }

    @Test
    void postPreview_ReturnsUnavailableWhenQwenFails() throws Exception {
        when(sequencePlanner.planSequence(any(), any(), anyInt()))
            .thenThrow(new QwenItinerarySequencePlanner.QwenPlanningException(503, "Dịch vụ AI hiện không khả dụng (bị tắt)."));

        String tripDate = LocalDate.now().plusDays(2).toString();

        mvc.perform(post(PREVIEW_URL)
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "tripDate", tripDate,
                    "startTime", "08:00",
                    "endTime", "13:00",
                    "startLocationId", locId1
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.fingerprint").isEmpty())
            .andExpect(jsonPath("$.notices[0]").value("Dịch vụ AI hiện không khả dụng (bị tắt)."));
    }

    @Test
    void postPreview_ReturnsTooManyRequestsWhenQwenBusy() throws Exception {
        when(sequencePlanner.planSequence(any(), any(), anyInt()))
            .thenThrow(new QwenItinerarySequencePlanner.QwenPlanningException(429, "Dịch vụ AI đang bận"));

        String tripDate = LocalDate.now().plusDays(2).toString();

        mvc.perform(post(PREVIEW_URL)
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "tripDate", tripDate,
                    "startTime", "08:00",
                    "endTime", "13:00",
                    "startLocationId", locId1
                ))))
            .andExpect(status().isTooManyRequests());
    }
}
