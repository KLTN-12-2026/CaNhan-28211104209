package com.carrental;

import com.carrental.modules.ai.AiDiagnostics;
import com.carrental.modules.ai.AiRequestDeadline;
import com.carrental.modules.ai.routing.OsrmRoutingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** Explicit two-request live-Qwen smoke. Never runs as part of the default Surefire suite. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.seed-enabled=true",
    "app.seed-password=LocationApiTestOnly123!",
    "app.jwt-secret=g4-live-smoke-synthetic-auth-key-at-least-32-bytes",
    "app.qwen-parser-enabled=true",
    "app.qwen-model=qwen3:8b",
    "app.gemini-parser-enabled=false",
    "app.ai-routing-enabled=false",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
class AiLiveSmokeIT {
    private static final String OUTPUT_PROPERTY = "ai.eval.output";
    private static final String PREFLIGHT_PROPERTY = "ai.eval.preflight";
    private static final String PARSER_CASE = "G4SMOKE-PARSER-DEN-001";
    private static final String PLANNER_CASE = "G4SMOKE-PLANNER-H2-001";
    private static final String USER_EMAIL = "user@example.test";
    private static final String ADMIN_EMAIL = "admin@example.test";
    private static final String FIXTURE_PASSWORD = "LocationApiTestOnly123!";
    private static final List<String> SNAPSHOT_TABLES = List.of(
        "branches", "bookings", "booking_status_history", "location_categories", "locations",
        "itineraries", "itinerary_days", "itinerary_items"
    );

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
            "jdbc:h2:mem:ai_live_smoke_g4_20261103;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    record HttpResult(int status, String body, long durationMs) {}

    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AiDiagnostics diagnostics;
    @LocalServerPort int port;
    @MockBean OsrmRoutingClient osrm;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @Test
    void runsOneParserAndOnePlannerRequestAgainstLiveQwenSequentially() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        Path output = Path.of(System.getProperty(OUTPUT_PROPERTY, "")).toAbsolutePath().normalize();
        Path evidenceRoot = repo.resolve(".runtime/coordination-ai-g4-smoke-copilot-20261103-01/evidence");
        Path preflightPath = Path.of(System.getProperty(PREFLIGHT_PROPERTY, "")).toAbsolutePath().normalize();
        assertEquals("true", System.getProperty("ai.eval.liveSmoke"), "Explicit one-off live smoke opt-in required.");
        assertTrue(output.startsWith(evidenceRoot), "Evidence must stay within this smoke task.");
        assertTrue(preflightPath.startsWith(evidenceRoot), "Runtime preflight must stay within this smoke task.");
        assertTrue(Files.isRegularFile(preflightPath), "A fresh metadata-only Ollama preflight is required.");

        Files.createDirectory(output);
        var fixtureSteps = new ArrayList<Map<String, Object>>();
        var attempts = new ArrayList<Map<String, Object>>();
        var run = new LinkedHashMap<String, Object>();
        run.put("caseCountPlanned", 2);
        run.put("caseIdsInOrder", List.of(PARSER_CASE, PLANNER_CASE));
        run.put("plannedAttemptsPerCase", 1);
        run.put("backendProcessPid", ProcessHandle.current().pid());
        run.put("backendPort", port);
        run.put("preflightArtifact", repo.relativize(preflightPath).toString().replace('\\', '/'));
        run.put("attempts", attempts);
        run.put("fixtureSteps", fixtureSteps);
        run.put("sourceRevision", gitHead(repo));
        run.put("sourceHashesBefore", sourceHashes(repo));
        run.put("settings", Map.of(
            "model", "qwen3:8b",
            "parserEnabled", true,
            "plannerEnabled", true,
            "plannerRouteDependency", "synthetic OSRM stub; conclusions limited to Qwen/backend itinerary contract",
            "qwenOptionsFromProductionSource", Map.of("num_ctx", 4096, "num_predict", 512, "temperature", 0.1)
        ));
        writeJsonNew(output.resolve("planned-cases.json"), Map.of(
            "caseCountPlanned", 2,
            "casesInExecutionOrder", List.of(
                Map.of("caseId", PARSER_CASE, "endpoint", "/api/v1/ai/criteria/parse", "attempts", 1,
                    "expectedStatus", "READY_FOR_REVIEW"),
                Map.of("caseId", PLANNER_CASE, "endpoint", "/api/v1/ai/itineraries/preview", "attempts", 1,
                    "expectedStatus", "VALIDATED", "routeDependency", "synthetic OSRM stub")
            )
        ));
        boolean noMutation = false;
        try {
            JsonNode preflight = json.readTree(preflightPath.toFile());
            String modelDigest = preflight.path("targetTag").path("digest").asText();
            assertEquals(preflight.path("manifestSha256").asText(), modelDigest,
                "The local model manifest and Ollama tag must match before inference.");
            assertEquals("0.34.2", preflight.path("responses").path("/api/version")
                .path("payload").path("version").asText());
            for (JsonNode blob : preflight.path("blobReadback")) {
                assertTrue(blob.path("exists").asBoolean());
                assertEquals(blob.path("expectedSize").asLong(), blob.path("actualSize").asLong());
            }
            run.put("modelDigest", modelDigest);
            run.put("runtimeVersionBefore", preflight.path("responses").path("/api/version")
                .path("payload").path("version").asText());
            run.put("modelResidentBefore", preflight.path("targetLoaded").isObject());
            run.put("manifestSha256", preflight.path("manifestSha256").asText());

            try (var connection = jdbc.getDataSource().getConnection()) {
                String dbUrl = connection.getMetaData().getURL();
                assertTrue(dbUrl.startsWith("jdbc:h2:mem:ai_live_smoke_g4_20261103"),
                    "The live smoke must use its dedicated in-memory H2 database.");
                run.put("fixtureDatabase", dbUrl);
            }

            String base = "http://127.0.0.1:" + port;
            String adminToken = login(base, ADMIN_EMAIL, fixtureSteps);
            String userToken = login(base, USER_EMAIL, fixtureSteps);
            String categoryId = createCategory(base, adminToken, fixtureSteps);
            String locationA = createLocation(base, adminToken, categoryId, "G4 Synthetic Stop Alpha",
                16.0601, 108.2235, fixtureSteps);
            String locationB = createLocation(base, adminToken, categoryId, "G4 Synthetic Stop Beta",
                16.0041, 108.2638, fixtureSteps);
            run.put("fixtureLocationIds", List.of(locationA, locationB));
            run.put("fixtureReadback", Map.of(
                "branches", jdbc.queryForObject("select count(*) from branches", Integer.class),
                "locations", jdbc.queryForObject("select count(*) from locations", Integer.class),
                "locationCategories", jdbc.queryForObject("select count(*) from location_categories", Integer.class)
            ));

            var stubLeg = new OsrmRoutingClient.Leg(12000.0, 900.0);
            when(osrm.route(anyList(), any(AiRequestDeadline.class))).thenReturn(
                new OsrmRoutingClient.Estimate(
                    OsrmRoutingClient.Status.VERIFIED, 12000.0, 900.0,
                    "Synthetic test route; not live OSRM", List.of(stubLeg)
                )
            );
            Map<String, Object> beforeInference = databaseSnapshot();

            LocalDate pickup = LocalDate.now().plusDays(10);
            LocalDate returned = pickup.plusDays(2);
            String parserText = "Yêu cầu thử nghiệm tổng hợp G4: thuê xe 5 chỗ, "
                + "hộp số tự động; nhận tại chi nhánh Đà Nẵng · mẫu ngày "
                + pickup.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                + ", trả tại chi nhánh Hà Nội · mẫu ngày "
                + returned.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                + "; ngân sách tổng 2.000.000 VND.";
            Map<String, Object> parserExpected = Map.of(
                "status", "READY_FOR_REVIEW",
                "pickupDate", pickup.toString(),
                "returnDate", returned.toString(),
                "pickupBranchId", "dn",
                "returnBranchId", "hn",
                "passengers", 5,
                "budgetVndTotal", 2_000_000L,
                "transmission", "AUTO"
            );
            Map<String, Object> parserAttempt = attempt(
                base, "/api/v1/ai/criteria/parse", userToken, Map.of("text", parserText),
                PARSER_CASE, AiRequestDeadline.Operation.CRITERIA_EXTRACTION, parserExpected, output
            );
            attempts.add(parserAttempt);

            LocalDate itineraryDate = LocalDate.now().plusDays(10);
            Map<String, Object> plannerExpected = Map.of(
                "status", "VALIDATED",
                "orderedLocationIds", List.of(locationA, locationB),
                "routeDependency", "synthetic OSRM stub"
            );
            Map<String, Object> plannerAttempt = attempt(
                base, "/api/v1/ai/itineraries/preview", userToken,
                Map.of(
                    "tripDate", itineraryDate.toString(),
                    "startTime", "08:00",
                    "endTime", "13:00",
                    "startLocationId", locationA,
                    "preferredCategoryId", categoryId
                ),
                PLANNER_CASE, AiRequestDeadline.Operation.ITINERARY_PREVIEW, plannerExpected, output
            );
            attempts.add(plannerAttempt);

            Map<String, Object> afterInference = databaseSnapshot();
            noMutation = beforeInference.equals(afterInference);
            run.put("databaseBeforeInference", beforeInference);
            run.put("databaseAfterInference", afterInference);
            run.put("noMutationOutsideFixtureSetup", noMutation);
            run.put("qualityMatch", Map.of(
                PARSER_CASE, parserAttempt.get("qualityMatch"),
                PLANNER_CASE, plannerAttempt.get("qualityMatch")
            ));
        } catch (Exception | AssertionError error) {
            run.put("harnessFailureClass", error.getClass().getName());
            throw error;
        } finally {
            run.put("attemptsObserved", attempts.size());
            run.put("fixtureStepsObserved", fixtureSteps.size());
            run.put("noMutationOutsideFixtureSetup", noMutation);
            run.put("sourceHashesAfter", sourceHashes(repo));
            writeJsonNew(output.resolve("smoke-summary.json"), run);
        }

        assertEquals(2, attempts.size(), "Both preselected inference attempts must be recorded.");
        assertEquals(Boolean.TRUE, attempts.get(0).get("providerCallObserved"),
            "No Qwen parser diagnostic was observed for the parser case.");
        assertEquals(Boolean.TRUE, attempts.get(1).get("providerCallObserved"),
            "No Qwen planner diagnostic was observed for the planner case.");
        assertEquals(Boolean.TRUE, parserAttemptQuality(attempts.get(0)),
            "Parser result did not match the preselected synthetic expected output; inspect smoke evidence.");
        assertEquals(Boolean.TRUE, plannerAttemptQuality(attempts.get(1)),
            "Planner result did not match the preselected synthetic expected output; inspect smoke evidence.");
        assertTrue(noMutation, "The backend preview/parse calls must not mutate persisted fixture data.");
    }

    private Map<String, Object> attempt(
            String base,
            String path,
            String token,
            Map<String, Object> body,
            String caseId,
            AiRequestDeadline.Operation operation,
            Map<String, Object> expected,
            Path output
    ) throws Exception {
        Map<String, Long> before = qwenCauseCounts(operation);
        long start = System.nanoTime();
        Integer status = null;
        Long backendHttpDurationMs = null;
        Map<String, Object> observed = Map.of();
        String errorClass = null;
        boolean responseReceived = false;
        String exceptionCause = null;
        try {
            HttpResult response = post(base + path, body, token);
            status = response.status();
            backendHttpDurationMs = response.durationMs();
            responseReceived = true;
            observed = summarize(caseId, json.readTree(response.body()));
        } catch (Exception error) {
            errorClass = error.getClass().getName();
            exceptionCause = AiDiagnostics.transportCause(error).name();
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Map<String, Long> after = qwenCauseCounts(operation);
        Map<String, Long> causeDeltas = new TreeMap<>();
        after.forEach((cause, count) -> {
            long delta = count - before.getOrDefault(cause, 0L);
            if (delta > 0) causeDeltas.put(cause, delta);
        });
        String causeCode = causeDeltas.size() == 1
            ? causeDeltas.keySet().iterator().next()
            : exceptionCause == null ? "UNKNOWN" : exceptionCause;
        boolean qualityMatch = status != null && status == 200 && expectedMatches(caseId, expected, observed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("attemptNumber", 1);
        result.put("httpClientCallInvoked", true);
        result.put("responseReceived", responseReceived);
        result.put("httpStatus", status);
        result.put("durationMs", durationMs);
        result.put("backendHttpDurationMs", backendHttpDurationMs);
        result.put("causeCode", causeCode);
        result.put("diagnosticsCauseDeltas", causeDeltas);
        result.put("providerCallObserved", !causeDeltas.isEmpty());
        result.put("errorClass", errorClass);
        result.put("expected", expected);
        result.put("observed", observed);
        result.put("qualityMatch", qualityMatch);
        writeJsonNew(output.resolve("attempt-" + (caseId.equals(PARSER_CASE) ? "01" : "02") + "-" + caseId + ".json"),
            result);
        return result;
    }

    private Map<String, Object> summarize(String caseId, JsonNode root) {
        if (caseId.equals(PARSER_CASE)) {
            JsonNode draft = root.path("draft");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", root.path("status").asText(""));
            result.put("unresolvedFields", root.path("unresolvedFields").isArray()
                ? json.convertValue(root.path("unresolvedFields"), List.class) : List.of());
            result.put("draftPresent", draft.isObject());
            result.put("pickupDate", textOrNull(draft, "pickupDate"));
            result.put("returnDate", textOrNull(draft, "returnDate"));
            result.put("pickupBranchId", textOrNull(draft, "pickupBranchId"));
            result.put("returnBranchId", textOrNull(draft, "returnBranchId"));
            result.put("passengers", draft.path("passengers").isIntegralNumber() ? draft.path("passengers").intValue() : null);
            result.put("budgetVndTotal", draft.path("budgetVndTotal").isIntegralNumber() ? draft.path("budgetVndTotal").longValue() : null);
            result.put("transmission", textOrNull(draft, "transmission"));
            return result;
        }
        List<String> orderedIds = new ArrayList<>();
        if (root.path("orderedLocationIds").isArray()) {
            root.path("orderedLocationIds").forEach(value -> orderedIds.add(value.asText()));
        }
        return Map.of(
            "status", root.path("status").asText(""),
            "orderedLocationIds", orderedIds,
            "itemCount", root.path("items").isArray() ? root.path("items").size() : 0,
            "fingerprintPresent", root.path("fingerprint").isTextual() && !root.path("fingerprint").asText().isBlank(),
            "noticesCount", root.path("notices").isArray() ? root.path("notices").size() : 0
        );
    }

    private boolean expectedMatches(String caseId, Map<String, Object> expected, Map<String, Object> observed) {
        if (caseId.equals(PARSER_CASE)) {
            return "READY_FOR_REVIEW".equals(observed.get("status"))
                && expected.get("pickupDate").equals(observed.get("pickupDate"))
                && expected.get("returnDate").equals(observed.get("returnDate"))
                && expected.get("pickupBranchId").equals(observed.get("pickupBranchId"))
                && expected.get("returnBranchId").equals(observed.get("returnBranchId"))
                && expected.get("passengers").equals(observed.get("passengers"))
                && expected.get("budgetVndTotal").equals(observed.get("budgetVndTotal"))
                && expected.get("transmission").equals(observed.get("transmission"))
                && ((List<?>) observed.getOrDefault("unresolvedFields", List.of())).isEmpty();
        }
        return "VALIDATED".equals(observed.get("status"))
            && expected.get("orderedLocationIds").equals(observed.get("orderedLocationIds"))
            && Integer.valueOf(2).equals(observed.get("itemCount"))
            && Boolean.TRUE.equals(observed.get("fingerprintPresent"));
    }

    private Boolean parserAttemptQuality(Map<String, Object> attempt) {
        return (Boolean) attempt.get("qualityMatch");
    }

    private Boolean plannerAttemptQuality(Map<String, Object> attempt) {
        return (Boolean) attempt.get("qualityMatch");
    }

    private HttpResult post(String url, Object body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(75))
            .header("Content-Type", "application/json");
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        HttpRequest request = builder
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
            .build();
        long start = System.nanoTime();
        HttpResponse<String> response = http.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(response.statusCode(), response.body(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    private String login(String base, String email, List<Map<String, Object>> steps) throws Exception {
        HttpResult result = post(base + "/api/v1/auth/login",
            Map.of("email", email, "password", FIXTURE_PASSWORD), "");
        steps.add(Map.of("step", "login", "roleAccount", email.equals(ADMIN_EMAIL) ? "ADMIN_FIXTURE" : "USER_FIXTURE",
            "httpStatus", result.status(), "durationMs", result.durationMs()));
        assertEquals(200, result.status(), "Synthetic fixture login failed.");
        String token = json.readTree(result.body()).path("token").asText();
        assertFalse(token.isBlank(), "Synthetic fixture login did not return a token.");
        return token;
    }

    private String createCategory(String base, String adminToken, List<Map<String, Object>> steps) throws Exception {
        HttpResult result = post(base + "/api/v1/admin/location-categories",
            Map.of("name", "G4 Synthetic Qwen Smoke Category"), adminToken);
        steps.add(Map.of("step", "create-category", "httpStatus", result.status(), "durationMs", result.durationMs()));
        assertEquals(201, result.status(), "Synthetic location category setup failed.");
        return json.readTree(result.body()).path("id").asText();
    }

    private String createLocation(
            String base,
            String adminToken,
            String categoryId,
            String name,
            double latitude,
            double longitude,
            List<Map<String, Object>> steps
    ) throws Exception {
        Instant verifiedAt = Instant.now().minusSeconds(1);
        Map<String, Object> weekly = new LinkedHashMap<>();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            weekly.put(day.name(), Map.of("state", "INTERVALS", "intervals",
                List.of(Map.of("start", "07:00", "end", "18:00", "nextDay", false))));
        }
        Map<String, Object> evidence = Map.of("url", "https://example.invalid/g4-qwen-smoke", "verifiedAt", verifiedAt.toString());
        Map<String, Object> metadata = Map.of(
            "timezone", "Asia/Ho_Chi_Minh",
            "weekly", weekly,
            "exceptions", Map.of(),
            "durationMinutes", 60,
            "hoursEvidence", evidence,
            "durationEvidence", evidence
        );
        HttpResult result = post(base + "/api/v1/admin/locations",
            Map.of(
                "categoryId", categoryId,
                "name", name,
                "latitude", latitude,
                "longitude", longitude,
                "status", "ACTIVE",
                "metadata", metadata
            ), adminToken);
        steps.add(Map.of("step", "create-location", "name", name,
            "httpStatus", result.status(), "durationMs", result.durationMs()));
        assertEquals(201, result.status(), "Synthetic location setup failed.");
        return json.readTree(result.body()).path("id").asText();
    }

    private Map<String, Long> qwenCauseCounts(AiRequestDeadline.Operation operation) {
        Map<String, Long> counts = new TreeMap<>();
        for (AiDiagnostics.Event event : diagnostics.snapshot()) {
            if (event.operation() == operation && event.provider() == AiDiagnostics.Provider.QWEN) {
                counts.put(event.cause().name(), event.count());
            }
        }
        return counts;
    }

    private Map<String, Object> databaseSnapshot() throws Exception {
        Map<String, Object> result = new TreeMap<>();
        for (String table : SNAPSHOT_TABLES) {
            List<Map<String, Object>> rows = jdbc.queryForList("select * from " + table + " order by 1");
            result.put(table, Map.of("rows", rows.size(), "sha256", sha256(rows.toString())));
        }
        return result;
    }

    private Map<String, String> sourceHashes(Path repo) throws Exception {
        Map<String, String> result = new TreeMap<>();
        Path main = repo.resolve("backend/src/main");
        try (var files = Files.walk(main)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                result.put(repo.relativize(file).toString().replace('\\', '/'), sha256(Files.readAllBytes(file)));
            }
        }
        for (String file : List.of("backend/pom.xml", "backend/src/test/java/com/carrental/AiLiveSmokeIT.java")) {
            Path source = repo.resolve(file);
            result.put(file, sha256(Files.readAllBytes(source)));
        }
        return result;
    }

    private String gitHead(Path repo) throws Exception {
        Process process = new ProcessBuilder("git", "rev-parse", "HEAD").directory(repo.toFile())
            .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), "Unable to pin repository revision for smoke.");
        return new String(output, StandardCharsets.UTF_8).trim();
    }

    private String sha256(String value) throws Exception {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private String textOrNull(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }

    private void writeJsonNew(Path path, Object value) throws Exception {
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value),
            StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
