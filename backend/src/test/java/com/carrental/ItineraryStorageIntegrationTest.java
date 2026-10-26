package com.carrental;

import com.carrental.modules.ai.itinerary.dto.CreateItineraryRequest;
import com.carrental.modules.ai.itinerary.dto.ItineraryPreviewRequest;
import com.carrental.modules.ai.itinerary.service.QwenItinerarySequencePlanner;
import com.carrental.modules.ai.routing.OsrmRoutingClient;
import com.fasterxml.jackson.databind.JsonNode;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "app.seed-enabled=true",
    "app.seed-password=LocationApiTestOnly123!",
    "app.jwt-secret=storage-http-test-secret-at-least-32-bytes"
})
@AutoConfigureMockMvc
public class ItineraryStorageIntegrationTest {
    private static final String PREVIEW_URL = "/api/v1/ai/itineraries/preview";
    private static final String ITINERARIES_URL = "/api/v1/itineraries";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private OsrmRoutingClient osrmRoutingClient;

    @MockBean
    private QwenItinerarySequencePlanner sequencePlanner;

    private String userToken;
    private String adminToken;
    private String locAId;
    private String locBId;

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
            registry.add("spring.datasource.username", () -> System.getenv("KDRIVE_TEST_DB_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        }
        registry.add("spring.datasource.url", () -> url == null
            ? "jdbc:h2:mem:itinerarystorage;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            : url);
    }

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

        var catRes = mvc.perform(post("/api/v1/admin/location-categories")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Văn hóa Storage " + System.nanoTime()))))
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
                    "name", "Bảo tàng Storage Test " + System.nanoTime(),
                    "latitude", 16.0601,
                    "longitude", 108.2235,
                    "status", "ACTIVE",
                    "metadata", json.readTree(metaA)
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        locAId = json.readTree(locRes1.getResponse().getContentAsString()).path("id").asText();

        var locRes2 = mvc.perform(post("/api/v1/admin/locations")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "categoryId", catId,
                    "name", "Bãi biển Storage Test " + System.nanoTime(),
                    "latitude", 16.0041,
                    "longitude", 108.2638,
                    "status", "ACTIVE",
                    "metadata", json.readTree(metaB)
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        locBId = json.readTree(locRes2.getResponse().getContentAsString()).path("id").asText();

        var leg = new OsrmRoutingClient.Leg(12000.0, 1080.0);
        when(osrmRoutingClient.route(anyList())).thenReturn(
            new OsrmRoutingClient.Estimate(OsrmRoutingClient.Status.VERIFIED, 12000.0, 1080.0, "OSM Test", List.of(leg))
        );
    }

    @Test
    void saveAndRetrieveItineraryFlow() throws Exception {
        when(sequencePlanner.planSequence(eq(locAId), anyList(), anyInt()))
            .thenReturn(new QwenItinerarySequencePlanner.SequenceResult(List.of(locAId, locBId), "QWEN_OPTIMIZED"));

        String tripDate = LocalDate.now().plusDays(3).toString();

        // 1. Thực hiện xem trước (Preview)
        ItineraryPreviewRequest previewReq = new ItineraryPreviewRequest(
            LocalDate.parse(tripDate),
            "08:00",
            "17:00",
            locAId,
            null
        );

        var previewRes = mvc.perform(post(PREVIEW_URL)
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(previewReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("VALIDATED"))
            .andReturn();

        JsonNode previewNode = json.readTree(previewRes.getResponse().getContentAsString());
        String fingerprint = previewNode.path("fingerprint").asText();
        assertNotNull(fingerprint);
        assertFalse(fingerprint.isBlank());

        // Snapshot counts trước khi lưu
        int beforeItineraries = jdbc.queryForObject("SELECT count(*) FROM itineraries", Integer.class);
        int beforeDays = jdbc.queryForObject("SELECT count(*) FROM itinerary_days", Integer.class);
        int beforeItems = jdbc.queryForObject("SELECT count(*) FROM itinerary_items", Integer.class);

        // 2. Thử gửi fingerprint giả mạo/stale -> Phải bị từ chối 409 Conflict (F02)
        CreateItineraryRequest forgedReq = new CreateItineraryRequest(
            "Hành trình giả mạo",
            "fake-fingerprint-conflict",
            LocalDate.parse(tripDate),
            "08:00",
            "17:00",
            locAId,
            null,
            List.of(locAId, locBId)
        );
        mvc.perform(post(ITINERARIES_URL)
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(forgedReq)))
            .andExpect(status().isConflict());

        // Đảm bảo không có record nào bị ghi khi lỗi 409
        assertEquals(beforeItineraries, jdbc.queryForObject("SELECT count(*) FROM itineraries", Integer.class));
        assertEquals(beforeDays, jdbc.queryForObject("SELECT count(*) FROM itinerary_days", Integer.class));
        assertEquals(beforeItems, jdbc.queryForObject("SELECT count(*) FROM itinerary_items", Integer.class));

        // 3. Gửi request lưu hợp lệ (F02: Gửi orderedLocationIds, không gọi lại Qwen)
        CreateItineraryRequest saveReq = new CreateItineraryRequest(
            "Chuyến đi Đà Nẵng 1 ngày",
            fingerprint,
            LocalDate.parse(tripDate),
            "08:00",
            "17:00",
            locAId,
            null,
            List.of(locAId, locBId)
        );
        var saveRes = mvc.perform(post(ITINERARIES_URL)
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(saveReq)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.title").value("Chuyến đi Đà Nẵng 1 ngày"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].fullHoursDescription").isNotEmpty())
            .andExpect(jsonPath("$.items[0].osmAttribution").value("© OpenStreetMap contributors; OSRM driving estimate"))
            .andExpect(jsonPath("$.items[0].contractVersion").value("kdrive-itinerary-v1"))
            .andReturn();

        String itineraryId = json.readTree(saveRes.getResponse().getContentAsString()).path("id").asText();

        // Kiểm tra đúng 3 bảng DB được ghi nguyên tử (atomic)
        assertEquals(beforeItineraries + 1, jdbc.queryForObject("SELECT count(*) FROM itineraries", Integer.class));
        assertEquals(beforeDays + 1, jdbc.queryForObject("SELECT count(*) FROM itinerary_days", Integer.class));
        assertEquals(beforeItems + 2, jdbc.queryForObject("SELECT count(*) FROM itinerary_items", Integer.class));

        // 4. Lấy danh sách GET /api/v1/itineraries
        mvc.perform(get(ITINERARIES_URL)
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(itineraryId))
            .andExpect(jsonPath("$[0].title").value("Chuyến đi Đà Nẵng 1 ngày"))
            .andExpect(jsonPath("$[0].tripDate").value(tripDate))
            .andExpect(jsonPath("$[0].locationCount").value(2));

        // 5. Xem chi tiết GET /api/v1/itineraries/{id}
        var detailRes = mvc.perform(get(ITINERARIES_URL + "/" + itineraryId)
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(itineraryId))
            .andExpect(jsonPath("$.title").value("Chuyến đi Đà Nẵng 1 ngày"))
            .andExpect(jsonPath("$.fingerprint").value(fingerprint))
            .andExpect(jsonPath("$.items[0].locationId").value(locAId))
            .andExpect(jsonPath("$.items[0].dwellMinutes").value(60))
            .andExpect(jsonPath("$.items[0].fullHoursDescription").isNotEmpty())
            .andExpect(jsonPath("$.items[0].osmAttribution").isNotEmpty())
            .andExpect(jsonPath("$.items[1].locationId").value(locBId))
            .andExpect(jsonPath("$.items[1].dwellMinutes").value(45))
            .andReturn();

        // Kiểm tra snapshot bất biến chứa metadata đầy đủ (F07)
        String rawSnapshot = jdbc.queryForObject(
            "SELECT snapshot FROM itinerary_items WHERE day_id = (SELECT id FROM itinerary_days WHERE itinerary_id = ?) AND sort_order = 1",
            String.class,
            itineraryId
        );
        assertNotNull(rawSnapshot);
        assertTrue(rawSnapshot.contains("visitMetadata"));
        assertTrue(rawSnapshot.contains("Asia/Ho_Chi_Minh"));

        // 6. Kiểm tra duplicate IDs bị từ chối 400 Bad Request (F02)
        int beforeDupeCheck = jdbc.queryForObject("SELECT count(*) FROM itineraries", Integer.class);
        CreateItineraryRequest dupeReq = new CreateItineraryRequest(
            "Lộ trình trùng ID",
            fingerprint,
            LocalDate.parse(tripDate),
            "08:00",
            "17:00",
            locAId,
            null,
            List.of(locAId, locAId)
        );
        mvc.perform(post(ITINERARIES_URL)
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(dupeReq)))
            .andExpect(status().isBadRequest());
        assertEquals(beforeDupeCheck, jdbc.queryForObject("SELECT count(*) FROM itineraries", Integer.class));

        // 7. Kiểm tra quyền bảo mật: Anonymous bị 401, Admin bị 403 (không truy cập luồng của khách)
        mvc.perform(get(ITINERARIES_URL + "/" + itineraryId))
            .andExpect(status().isUnauthorized());

        mvc.perform(get(ITINERARIES_URL + "/" + itineraryId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isForbidden());

        var other = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", "ai4-" + System.nanoTime() + "@example.test",
                "fullName", "Other fixture user", "password", "IsolatedOnly123!"))))
            .andExpect(status().isCreated()).andReturn();
        String otherToken = json.readTree(other.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(get(ITINERARIES_URL + "/" + itineraryId).header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isNotFound());
        mvc.perform(get(ITINERARIES_URL).header("Authorization", "Bearer " + otherToken))
            .andExpect(jsonPath("$.length()").value(0));

        // Edit authoritative evidence without changing arrival/departure: old preview must still conflict.
        var locationResult = mvc.perform(get("/api/v1/admin/locations/" + locAId)
            .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk()).andReturn();
        var update = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(locationResult.getResponse().getContentAsString());
        update.put("expectedRevision", update.path("revision").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) update.path("metadata").path("hoursEvidence"))
            .put("url", "https://example.com/updated-source");
        update.remove(List.of("id", "revision", "categoryName"));
        mvc.perform(put("/api/v1/admin/locations/" + locAId).header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON).content(update.toString())).andExpect(status().isOk());
        mvc.perform(post(ITINERARIES_URL).header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(saveReq)))
            .andExpect(status().isConflict());
        assertEquals(beforeItineraries + 1, jdbc.queryForObject("SELECT count(*) FROM itineraries", Integer.class));

        when(osrmRoutingClient.route(anyList())).thenThrow(new IllegalStateException("Provider must not be used to read"));
        when(sequencePlanner.planSequence(anyString(), anyList(), anyInt()))
            .thenThrow(new IllegalStateException("Qwen must not be used to read"));
        var offlineRead = mvc.perform(get(ITINERARIES_URL + "/" + itineraryId)
            .header("Authorization", "Bearer " + userToken)).andExpect(status().isOk()).andReturn();
        assertEquals(json.readTree(detailRes.getResponse().getContentAsString()),
            json.readTree(offlineRead.getResponse().getContentAsString()), "saved snapshot immutable after catalog edit/providers off");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "KDRIVE_TEST_DB_URL", matches = "jdbc:mysql:.*")
    void nativeMySqlRollbackAfterFirstItemInsert() throws Exception {
        when(sequencePlanner.planSequence(eq(locAId), anyList(), anyInt()))
            .thenReturn(new QwenItinerarySequencePlanner.SequenceResult(List.of(locAId, locBId), "QWEN_OPTIMIZED"));
        LocalDate date = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(3);
        var preview = mvc.perform(post(PREVIEW_URL).header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ItineraryPreviewRequest(date, "08:00", "17:00", locAId, null))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALIDATED")).andReturn();
        String fp = json.readTree(preview.getResponse().getContentAsString()).path("fingerprint").asText();
        List<Integer> before = List.of("itineraries", "itinerary_days", "itinerary_items").stream()
            .map(table -> jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).toList();
        jdbc.execute("CREATE TRIGGER ai4_fail_second_item BEFORE INSERT ON itinerary_items FOR EACH ROW BEGIN "
            + "IF NEW.sort_order=2 AND EXISTS(SELECT 1 FROM itinerary_items WHERE day_id=NEW.day_id AND sort_order=1) THEN "
            + "SIGNAL SQLSTATE '23000' SET MESSAGE_TEXT='AI4_AFTER_FIRST_ITEM'; END IF; END");
        try {
            mvc.perform(post(ITINERARIES_URL).header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new CreateItineraryRequest(
                    "Rollback fixture", fp, date, "08:00", "17:00", locAId, null, List.of(locAId, locBId)))))
                .andExpect(status().isConflict());
            List<Integer> after = List.of("itineraries", "itinerary_days", "itinerary_items").stream()
                .map(table -> jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).toList();
            assertEquals(before, after, "rollback must retain no itinerary/day/first item");
        } finally {
            jdbc.execute("DROP TRIGGER ai4_fail_second_item");
        }
    }
}
