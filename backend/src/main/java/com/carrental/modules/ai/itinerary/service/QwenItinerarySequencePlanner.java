package com.carrental.modules.ai.itinerary.service;

import com.carrental.exception.DomainException;
import com.carrental.modules.ai.LocalHttp;
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
        public QwenPlanningException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
        public int getStatusCode() {
            return statusCode;
        }
    }

    private final ObjectMapper json;
    private final URI endpoint;
    private final Duration timeout;
    private final String model;
    private final boolean enabled;
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(1);
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
            @Value("${app.qwen-parser-enabled:false}") boolean enabled) {
        this(mapper, URI.create("http://127.0.0.1:11434/api/chat"), Duration.ofSeconds(60), model, enabled);
    }

    QwenItinerarySequencePlanner(ObjectMapper mapper, URI endpoint, Duration timeout, String model, boolean enabled) {
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
        this.json = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    public SequenceResult planSequence(String startId, List<Candidate> candidates, int targetCount) {
        if (candidates == null || candidates.isEmpty()) {
            throw DomainException.bad("Danh sách địa điểm ứng viên không được để trống.");
        }
        if (candidates.stream().noneMatch(c -> c.id().equals(startId))) {
            throw DomainException.bad("Điểm bắt đầu phải nằm trong danh sách ứng viên.");
        }

        // Qwen không được bật: ném lỗi rõ ràng, không fake AI plan (F01)
        if (!enabled) {
            throw new QwenPlanningException(503, "Dịch vụ AI gợi ý lộ trình hiện không khả dụng (bị tắt).");
        }

        if (!capacity.tryAcquire()) {
            // Khi đang tải cao, trả 429
            throw new QwenPlanningException(429, "Dịch vụ AI đang bận xử lý yêu cầu khác, vui lòng thử lại sau.");
        }

        try {
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

            var request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

            var response = LocalHttp.send(client, request, timeout, 16384);
            if (response.status() != 200) {
                throw new QwenPlanningException(503, "Dịch vụ AI trả về mã lỗi HTTP " + response.status());
            }

            var envelope = json.readTree(response.body());
            var message = envelope.path("message");
            if (!envelope.path("done").asBoolean() || !"assistant".equals(message.path("role").asText())) {
                throw new QwenPlanningException(503, "Phản hồi AI không đúng định dạng trợ lý.");
            }

            String content = message.path("content").textValue();
            if (content == null || content.isBlank()) {
                throw new QwenPlanningException(503, "Mô hình AI trả về nội dung trống.");
            }

            JsonNode node = json.readTree(content);
            if (!node.isObject()) {
                throw new QwenPlanningException(503, "Phản hồi AI không phải JSON object.");
            }

            // Thực thi unknown-property validation (F01)
            Set<String> fieldNames = new HashSet<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            fieldNames.remove("orderedLocationIds");
            fieldNames.remove("reasonCode");
            if (!fieldNames.isEmpty()) {
                throw new QwenPlanningException(503, "Phản hồi AI chứa trường không cho phép trong schema: " + fieldNames);
            }

            if (!node.hasNonNull("orderedLocationIds") || !node.path("orderedLocationIds").isArray()) {
                throw new QwenPlanningException(503, "Thiếu trường orderedLocationIds hợp lệ.");
            }

            List<String> ids = new ArrayList<>();
            for (var elem : node.path("orderedLocationIds")) {
                ids.add(elem.asText());
            }

            // Kiểm tra tính hợp lệ nghiêm ngặt:
            if (ids.size() < 2 || ids.size() > 4) {
                throw new QwenPlanningException(503, "Số lượng địa điểm gợi ý (" + ids.size() + ") phải từ 2 đến 4.");
            }
            if (!ids.get(0).equals(startId)) {
                throw new QwenPlanningException(503, "Lộ trình gợi ý không bắt đầu bằng địa điểm yêu cầu.");
            }
            if (new HashSet<>(ids).size() != ids.size()) {
                throw new QwenPlanningException(503, "Lộ trình gợi ý chứa địa điểm trùng lặp.");
            }
            if (!candidateIds.containsAll(ids)) {
                throw new QwenPlanningException(503, "Lộ trình gợi ý chứa địa điểm ngoài danh mục ứng viên.");
            }

            // Validate reasonCode enum (F01)
            String rawReason = node.path("reasonCode").asText();
            SequenceReasonCode reasonCode;
            try {
                reasonCode = SequenceReasonCode.valueOf(rawReason);
            } catch (Exception e) {
                throw new QwenPlanningException(503, "Mã lý do không hợp lệ: " + rawReason);
            }

            return new SequenceResult(ids, reasonCode.name());
        } catch (QwenPlanningException qpe) {
            throw qpe;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new QwenPlanningException(503, "Xử lý AI bị gián đoạn.");
        } catch (Exception e) {
            throw new QwenPlanningException(503, "Lỗi phân tích phản hồi AI: " + e.getMessage());
        } finally {
            capacity.release();
        }
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
