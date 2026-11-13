package com.carrental.config;

import com.carrental.modules.ai.AiDiagnostics;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final boolean qwenEnabled;
    private final boolean geminiEnabled;
    private final boolean routePreviewEnabled;
    private final String qwenModel;

    public HealthController(
            @Value("${app.qwen-parser-enabled:false}") boolean qwenEnabled,
            @Value("${app.gemini-parser-enabled:false}") boolean geminiEnabled,
            @Value("${app.ai-routing-enabled:false}") boolean routePreviewEnabled,
            @Value("${app.qwen-model:qwen3:8b}") String qwenModel) {
        this.qwenEnabled = qwenEnabled;
        this.geminiEnabled = geminiEnabled;
        this.routePreviewEnabled = routePreviewEnabled;
        this.qwenModel = switch (AiDiagnostics.modelLabel(qwenModel)) {
            case QWEN3_8B -> "qwen3:8b";
            case QWEN3_4B -> "qwen3:4b";
            default -> "UNSUPPORTED";
        };
    }

    @GetMapping("/api/v1/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "ai", Map.of(
                "qwen", Map.of(
                    "configuredEnabled", qwenEnabled,
                    "configuredModel", qwenModel,
                    "readiness", readiness(qwenEnabled)
                ),
                "gemini", Map.of(
                    "configuredEnabled", geminiEnabled,
                    "readiness", readiness(geminiEnabled)
                ),
                "osrm", Map.of(
                    "routePreviewEnabled", routePreviewEnabled,
                    "readiness", "NOT_CHECKED"
                )
            )
        );
    }

    private static String readiness(boolean enabled) {
        return enabled ? "NOT_CHECKED" : "DISABLED";
    }
}
