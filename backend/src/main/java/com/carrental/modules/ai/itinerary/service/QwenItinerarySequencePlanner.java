package com.carrental.modules.ai.itinerary.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.ai.AiDiagnostics;
import com.carrental.modules.ai.LocalHttp;
import com.carrental.modules.ai.AiProviderCapacity;
import com.carrental.modules.ai.AiProviderCapacity.Provider;
import com.carrental.modules.ai.AiRequestDeadline;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QwenItinerarySequencePlanner {
    public enum SequenceReasonCode {
        QWEN_OPTIMIZED,
        PROXIMITY_FLOW,
        CULTURAL_SEQUENCE,
        SCENIC_ROUTE,
        TIME_EFFICIENCY
    }

    public static class QwenPlanningException extends RuntimeException {
        private final int statusCode;
        private final AiDiagnostics.Cause cause;
        public QwenPlanningException(int statusCode, String message) {
            this(statusCode, message, AiDiagnostics.Cause.UNKNOWN);
        }
        public QwenPlanningException(int statusCode, String message, AiDiagnostics.Cause cause) {
            super(message);
            this.statusCode = statusCode;
            this.cause = cause;
        }
        public int getStatusCode() {
            return statusCode;
        }
        public AiDiagnostics.Cause getCauseCode() {
            return cause;
        }
    }

    private final ObjectMapper json;
    private final URI endpoint;
    private final Duration timeout;
    private final String model;
    private final boolean enabled;
    private final AiProviderCapacity capacity;
    private final AiDiagnostics diagnostics;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public record Candidate(String id, String name, String categoryName) {}
    public record SequenceResult(List<String> orderedLocationIds, String reasonCode) {}

    @org.springframework.beans.factory.annotation.Autowired
    public QwenItinerarySequencePlanner(
            ObjectMapper mapper,
            @Value("${app.qwen-model:qwen3:8b}") String model,
            @Value("${app.qwen-parser-enabled:false}") boolean enabled,
            AiProviderCapacity capacity,
            AiDiagnostics diagnostics) {
        this(mapper, URI.create("http://127.0.0.1:11434/api/chat"), Duration.ofSeconds(60), model, enabled,
            capacity, diagnostics);
    }

    QwenItinerarySequencePlanner(ObjectMapper mapper, URI endpoint, Duration timeout, String model, boolean enabled) {
        this(mapper, endpoint, timeout, model, enabled, new AiProviderCapacity());
    }

    public QwenItinerarySequencePlanner(
            ObjectMapper mapper, URI endpoint, Duration timeout, String model, boolean enabled,
            AiProviderCapacity capacity) {
        this(mapper, endpoint, timeout, model, enabled, capacity, new AiDiagnostics());
    }

    public QwenItinerarySequencePlanner(
            ObjectMapper mapper, URI endpoint, Duration timeout, String model, boolean enabled,
            AiProviderCapacity capacity, AiDiagnostics diagnostics) {
        if (!Set.of("qwen3:8b", "qwen3:4b").contains(model)) {
            throw new IllegalArgumentException("Chỉ hỗ trợ Qwen3 cục bộ 4B/8B.");
        }
        this.model = model;
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Qwen chỉ được chạy trên loopback.");
        }
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.enabled = enabled;
        this.capacity = capacity;
        this.diagnostics = diagnostics;
        this.json = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    public SequenceResult planSequence(String startId, List<Candidate> candidates, int targetCount) {
        return planSequence(startId, candidates, targetCount,
            AiRequestDeadline.start(AiRequestDeadline.Operation.ITINERARY_PREVIEW));
    }

    public SequenceResult planSequence(
            String startId,
            List<Candidate> candidates,
            int targetCount,
            AiRequestDeadline deadline) {
        if (candidates == null || candidates.isEmpty()) {
            throw DomainException.bad("Danh sách địa điểm ứng viên không được để trống.");
        }
        if (candidates.stream().noneMatch(c -> c.id().equals(startId))) {
            throw DomainException.bad("Điểm bắt đầu phải nằm trong danh sách ứng viên.");
        }

        // Qwen không được bật: ném lỗi rõ ràng, không fake AI plan (F01)
        if (!enabled) {
            record(deadline, AiDiagnostics.Cause.DISABLED);
            throw failure(503, "Dịch vụ AI gợi ý lộ trình hiện không khả dụng (bị tắt).", AiDiagnostics.Cause.DISABLED);
        }
        try {
            deadline.requireRemaining();
        } catch (AiRequestDeadline.DeadlineExceededException error) {
            record(deadline, AiDiagnostics.Cause.DEADLINE);
            throw failure(503, "Thời hạn xử lý hành trình đã hết.", AiDiagnostics.Cause.DEADLINE);
        }

        AiProviderCapacity.Permit permit;
        try {
            permit = capacity.acquire(Provider.QWEN);
        } catch (AiProviderCapacity.CapacityExceededException error) {
            record(deadline, AiDiagnostics.Cause.CAPACITY);
            throw failure(429, "Dịch vụ AI đang bận xử lý yêu cầu khác, vui lòng thử lại sau.", AiDiagnostics.Cause.CAPACITY);
        }

        try (permit) {
            var candidateIds = candidates.stream().map(Candidate::id).toList();
            var allowedReasonCodes = Arrays.stream(SequenceReasonCode.values()).map(Enum::name).toList();

            var properties = new LinkedHashMap<String, Object>();
            properties.put("orderedLocationIds", Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", candidateIds),
                "minItems", Math.min(2, candidates.size()),
                "maxItems", Math.min(4, candidates.size())
            ));
            properties.put("reasonCode", Map.of(
                "type", "string",
                "enum", allowedReasonCodes
            ));
            var schema = Map.of(
                "type", "object",
                "properties", properties,
                "additionalProperties", false,
                "required", List.of("orderedLocationIds", "reasonCode")
            );

            var candidateText = candidates.stream()
                .map(c -> String.format("- ID: %s | Tên: %s | Loại: %s", c.id(), c.name(), c.categoryName()))
                .reduce("", (a, b) -> a + "\n" + b);

            var prompt = String.format(
                "Hãy chọn một chuỗi gồm %d địa điểm từ danh sách ứng viên sau, bắt đầu tại ID '%s'.\n" +
                "Chỉ trả về JSON tuân thủ schema, không thêm giải thích ngoài JSON.\nDanh sách ứng viên:\n%s",
                Math.min(targetCount, candidates.size()), startId, candidateText
            );

            var body = Map.of(
                "model", model,
                "think", false,
                "stream", false,
                "keep_alive", "2m",
                "format", schema,
                "options", Map.of("num_ctx", 4096, "num_predict", 512, "temperature", 0.1),
                "messages", List.of(
                    Map.of("role", "system", "content", "You are an itinerary sequence planner for Da Nang - Hoi An. Select 2-4 location IDs starting strictly with the chosen start ID. Return JSON only."),
                    Map.of("role", "user", "content", prompt)
                )
            );

            Duration callTimeout = deadline.timeoutFor(timeout);
            var request = HttpRequest.newBuilder(endpoint)
                .timeout(callTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

            deadline.requireRemaining();
            var response = LocalHttp.send(client, request, timeout, 16384, deadline);
            if (response.status() != 200) {
                throw failure(503, "Dịch vụ AI hiện không khả dụng.", AiDiagnostics.Cause.PROVIDER_HTTP);
            }

            JsonNode envelope;
            try {
                envelope = json.readTree(response.body());
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw failure(503, "Phản hồi AI không đúng định dạng.", AiDiagnostics.Cause.ENVELOPE_INVALID);
            }
            var message = envelope.path("message");
            if (!envelope.path("done").asBoolean() || !"assistant".equals(message.path("role").asText())) {
                throw failure(503, "Phản hồi AI không đúng định dạng trợ lý.", AiDiagnostics.Cause.ENVELOPE_INVALID);
            }

            String content = message.path("content").textValue();
            if (content == null || content.isBlank()) {
                throw failure(503, "Mô hình AI trả về nội dung trống.", AiDiagnostics.Cause.ENVELOPE_INVALID);
            }

            JsonNode node;
            try {
                node = json.readTree(content);
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw failure(503, "Phản hồi AI không tuân thủ schema.", AiDiagnostics.Cause.SCHEMA_INVALID);
            }
            if (!node.isObject()) {
                throw failure(503, "Phản hồi AI không phải JSON object.", AiDiagnostics.Cause.SCHEMA_INVALID);
            }

            // Thực thi unknown-property validation (F01)
            Set<String> fieldNames = new HashSet<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            fieldNames.remove("orderedLocationIds");
            fieldNames.remove("reasonCode");
            if (!fieldNames.isEmpty()) {
                throw failure(503, "Phản hồi AI chứa trường không cho phép trong schema.", AiDiagnostics.Cause.SCHEMA_INVALID);
            }

            if (!node.hasNonNull("orderedLocationIds") || !node.path("orderedLocationIds").isArray()) {
                throw failure(503, "Thiếu trường orderedLocationIds hợp lệ.", AiDiagnostics.Cause.SCHEMA_INVALID);
            }

            List<String> ids = new ArrayList<>();
            for (var elem : node.path("orderedLocationIds")) {
                ids.add(elem.asText());
            }

            // Kiểm tra tính hợp lệ nghiêm ngặt:
            if (ids.size() < 2 || ids.size() > 4) {
                throw failure(503, "Số lượng địa điểm gợi ý phải từ 2 đến 4.", AiDiagnostics.Cause.INVALID_REFERENCE);
            }
            if (!ids.get(0).equals(startId)) {
                throw failure(503, "Lộ trình gợi ý không bắt đầu bằng địa điểm yêu cầu.", AiDiagnostics.Cause.INVALID_REFERENCE);
            }
            if (new HashSet<>(ids).size() != ids.size()) {
                throw failure(503, "Lộ trình gợi ý chứa địa điểm trùng lặp.", AiDiagnostics.Cause.INVALID_REFERENCE);
            }
            if (!candidateIds.containsAll(ids)) {
                throw failure(503, "Lộ trình gợi ý chứa địa điểm ngoài danh mục ứng viên.", AiDiagnostics.Cause.INVALID_REFERENCE);
            }

            // Validate reasonCode enum (F01)
            String rawReason = node.path("reasonCode").asText();
            SequenceReasonCode reasonCode;
            try {
                reasonCode = SequenceReasonCode.valueOf(rawReason);
            } catch (Exception e) {
                throw failure(503, "Mã lý do không hợp lệ.", AiDiagnostics.Cause.SCHEMA_INVALID);
            }

            deadline.requireRemaining();
            record(deadline, AiDiagnostics.Cause.SUCCESS);
            return new SequenceResult(ids, reasonCode.name());
        } catch (QwenPlanningException qpe) {
            record(deadline, qpe.getCauseCode());
            throw qpe;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            record(deadline, AiDiagnostics.Cause.INTERRUPTED);
            throw failure(503, "Xử lý AI bị gián đoạn.", AiDiagnostics.Cause.INTERRUPTED);
        } catch (Exception e) {
            AiDiagnostics.Cause cause = AiDiagnostics.transportCause(e);
            record(deadline, cause);
            throw failure(503, "Dịch vụ AI hiện không khả dụng.", cause);
        }
    }

    private QwenPlanningException failure(int status, String message, AiDiagnostics.Cause cause) {
        return new QwenPlanningException(status, message, cause);
    }

    private void record(AiRequestDeadline deadline, AiDiagnostics.Cause cause) {
        diagnostics.record(deadline.operation(), AiDiagnostics.Provider.QWEN, model, cause, deadline.elapsedNanos());
    }

    public SequenceResult deterministicPlan(String startId, List<Candidate> candidates, int targetCount) {
        List<String> list = new ArrayList<>();
        list.add(startId);
        for (Candidate c : candidates) {
            if (!c.id().equals(startId) && list.size() < Math.min(targetCount, 4)) {
                list.add(c.id());
            }
        }
        return new SequenceResult(list, SequenceReasonCode.PROXIMITY_FLOW.name());
    }
}
