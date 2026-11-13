package com.carrental.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HealthControllerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void disabledProvidersAreReportedWithoutClaimingReadiness() throws Exception {
        var health = new HealthController(false, false, false, "qwen3:8b").health();
        var document = json.valueToTree(health);

        assertEquals("UP", document.path("status").asText());
        assertFalse(document.path("ai").path("qwen").path("configuredEnabled").asBoolean());
        assertEquals("DISABLED", document.path("ai").path("qwen").path("readiness").asText());
        assertEquals("DISABLED", document.path("ai").path("gemini").path("readiness").asText());
        assertFalse(document.path("ai").path("osrm").path("routePreviewEnabled").asBoolean());
        assertEquals("NOT_CHECKED", document.path("ai").path("osrm").path("readiness").asText());
    }

    @Test
    void enabledMeansConfiguredOnlyAndUnrecognizedModelIsNeverReflected() throws Exception {
        var health = new HealthController(true, true, true, "private-token-sentinel").health();
        String response = json.writeValueAsString(health);
        var document = json.readTree(response);

        assertEquals("NOT_CHECKED", document.path("ai").path("qwen").path("readiness").asText());
        assertEquals("NOT_CHECKED", document.path("ai").path("gemini").path("readiness").asText());
        assertTrue(document.path("ai").path("osrm").path("routePreviewEnabled").asBoolean());
        assertEquals("UNSUPPORTED", document.path("ai").path("qwen").path("configuredModel").asText());
        assertFalse(response.contains("private-token-sentinel"));
        assertFalse(response.contains("127.0.0.1"));
    }
}
