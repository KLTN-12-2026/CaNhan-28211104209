package com.carrental;

import static org.junit.jupiter.api.Assertions.*;

import com.carrental.modules.user.service.AccountService;
import com.carrental.security.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.jwt-secret=pilot-import-test-secret-at-least-32-bytes",
        "app.seed-enabled=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
    }
)
@EnabledIfEnvironmentVariable(
    named = "KDRIVE_TEST_DB_URL",
    matches = "jdbc:mysql://127\\.0\\.0\\.1:33676/kdrive_c14_.*"
)
class LocationPilotImporterMySqlTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired AccountService accounts;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("A marked C14 MySQL test database is required.");
        }
        CancelBoundaryTest.verifySafeIsolatedDatabase(
            url,
            System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),
            System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),
            System.getenv("KDRIVE_TEST_DB_USER"),
            System.getenv("KDRIVE_TEST_DB_PASSWORD")
        );
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("KDRIVE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    @Test
    void importerPrevalidationDryRunPartialRecoveryIdempotenceConflictAndReadback() throws Exception {
        String token = createAdminToken();
        Path scripts = Path.of(System.getProperty("user.dir"), "..", "scripts", "ai").toAbsolutePath().normalize();
        Path importer = scripts.resolve("import_locations_pilot.py");
        Path dataset = scripts.resolve("locations-pilot.json");
        assertTrue(Files.isRegularFile(importer), "Runner must stage the pinned importer beside backend-copy.");
        assertTrue(Files.isRegularFile(dataset), "Runner must stage the pinned pilot dataset beside backend-copy.");
        JsonNode source = json.readTree(dataset.toFile());
        assertEquals(8, source.path("locations").size());
        assertEquals(0L, count("location_categories"));
        assertEquals(0L, count("locations"));

        Path invalidDataset = Files.createTempFile("pilot-invalid-", ".json");
        try {
            ObjectNode invalid = (ObjectNode) source.deepCopy();
            ((ObjectNode) invalid.path("locations").get(7)).put("status", "UNKNOWN");
            json.writeValue(invalidDataset.toFile(), invalid);
            ImportResult rejected = runImporter(importer, invalidDataset, "http://127.0.0.1:1", token, false);
            assertEquals(1, rejected.exitCode());
            assertFalse(rejected.report().path("validationSuccess").asBoolean());
            assertFalse(rejected.output().contains("Connection refused"), rejected.output());
            assertEquals(0L, count("location_categories"), "Invalid data must be rejected before category writes.");
            assertEquals(0L, count("locations"), "Invalid data must be rejected before location writes.");
        } finally {
            Files.deleteIfExists(invalidDataset);
        }

        String backendUrl = "http://127.0.0.1:" + port;
        ImportResult dryRun = runImporter(importer, dataset, backendUrl, token, false);
        assertEquals(0, dryRun.exitCode(), dryRun.output());
        assertTrue(dryRun.report().path("dryRun").asBoolean());
        assertTrue(dryRun.report().path("validationSuccess").asBoolean());
        assertEquals(8, dryRun.report().path("locationsCreated").size());
        assertEquals(0L, count("location_categories"));
        assertEquals(0L, count("locations"));

        try (FailingLocationProxy proxy = new FailingLocationProxy(backendUrl, 4)) {
            ImportResult partial = runImporter(importer, dataset, proxy.url(), token, true);
            assertEquals(1, partial.exitCode());
            assertTrue(partial.report().path("errors").size() > 0);
            assertTrue(partial.output().contains("Injected partial failure"), partial.output());
            assertEquals(3L, count("location_categories"));
            assertEquals(3L, count("locations"), "The injected fourth POST must leave exactly three persisted locations.");
            assertEquals(3, readLocations(token).size());
        }

        ImportResult recovery = runImporter(importer, dataset, backendUrl, token, true);
        assertEquals(0, recovery.exitCode(), recovery.output());
        assertEquals(5, recovery.report().path("locationsCreated").size());
        assertEquals(3, recovery.report().path("locationsSkipped").size());
        assertEquals(8L, count("locations"));
        assertReadbackMatchesPilot(source, readLocations(token));

        ImportResult idempotent = runImporter(importer, dataset, backendUrl, token, true);
        assertEquals(0, idempotent.exitCode(), idempotent.output());
        assertEquals(0, idempotent.report().path("locationsCreated").size());
        assertEquals(8, idempotent.report().path("locationsSkipped").size());
        assertEquals(8L, count("locations"));

        JsonNode cham = findByName(readLocations(token), "Bảo tàng Điêu khắc Chăm Đà Nẵng");
        BigDecimal expectedLatitude = new BigDecimal(source.path("locations").get(0).path("latitude").asText());
        String driftedLatitude = "16.5555555";
        jdbc.update("update locations set latitude=? where id=?", new BigDecimal(driftedLatitude), cham.path("id").asText());
        try {
            ImportResult conflict = runImporter(importer, dataset, backendUrl, token, true);
            assertEquals(0, conflict.exitCode(), conflict.output());
            assertEquals(1, conflict.report().path("locationsConflict").size());
            assertEquals(7, conflict.report().path("locationsSkipped").size());
            JsonNode drifted = findByName(readLocations(token), "Bảo tàng Điêu khắc Chăm Đà Nẵng");
            assertEquals(0, new BigDecimal(drifted.path("latitude").asText()).compareTo(new BigDecimal(driftedLatitude)));
        } finally {
            jdbc.update("update locations set latitude=? where id=?", expectedLatitude, cham.path("id").asText());
        }
        assertReadbackMatchesPilot(source, readLocations(token));
        System.out.println("PILOT_MYSQL_ASSERTIONS=invalid-preflight:no-write;dry-run:8-planned/0-write;partial:3-of-8;recovery:5-created/3-skipped;idempotent:8-skipped;conflict:1-preserved;readback:8");
    }

    private String createAdminToken() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
            "insert into users(id, full_name, email, phone, password_hash, role, status, token_version, version) values(?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id,
            "Pilot Import Test Admin",
            "pilot-import-" + id + "@example.test",
            "0900000000",
            "$2a$10$dummyHashNotUsableDirectly12345678901234567890123456",
            "ADMIN",
            "ACTIVE",
            0L,
            0L
        );
        return tokens.issue(accounts.require(id));
    }

    private ImportResult runImporter(Path importer, Path dataset, String baseUrl, String token, boolean apply) throws Exception {
        String python = System.getenv("KDRIVE_PILOT_PYTHON");
        assertNotNull(python, "C14 runner must pass its Python executable to the integration test.");
        List<String> command = new ArrayList<>(List.of(
            python, "-X", "utf8", importer.toString(), "--json", dataset.toString(), "--base-url", baseUrl
        ));
        if (apply) command.add("--apply");
        Path outputFile = Files.createTempFile("pilot-import-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(outputFile.toFile());
            builder.environment().put("KDRIVE_ADMIN_TOKEN", token);
            Process process = builder.start();
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError("Pilot importer process exceeded 180 seconds.");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            System.out.println("PILOT_IMPORT_EXIT=" + process.exitValue() + "\n" + output);
            return new ImportResult(process.exitValue(), json.readTree(output), output);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private JsonNode readLocations(String token) {
        return readAdmin("/api/v1/admin/locations", token);
    }

    private JsonNode readAdmin(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<JsonNode> response = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private long count(String table) {
        if (!List.of("location_categories", "locations").contains(table)) {
            throw new IllegalArgumentException("Unexpected table name.");
        }
        Long result = jdbc.queryForObject("select count(*) from " + table, Long.class);
        assertNotNull(result);
        return result;
    }

    private void assertReadbackMatchesPilot(JsonNode source, JsonNode stored) {
        assertEquals(8, stored.size());
        for (JsonNode expected : source.path("locations")) {
            JsonNode actual = findByName(stored, expected.path("name").asText());
            assertNotNull(actual);
            assertEquals(expected.path("status").asText(), actual.path("status").asText());
            assertEquals(0, new BigDecimal(expected.path("latitude").asText())
                .compareTo(new BigDecimal(actual.path("latitude").asText())));
            assertEquals(0, new BigDecimal(expected.path("longitude").asText())
                .compareTo(new BigDecimal(actual.path("longitude").asText())));
            assertEquals(expected.path("metadata"), actual.path("metadata"));
        }
        long activeCount = 0;
        for (JsonNode location : stored) {
            if ("ACTIVE".equals(location.path("status").asText())) activeCount++;
        }
        assertEquals(1, activeCount);
    }

    private JsonNode findByName(JsonNode locations, String name) {
        for (JsonNode location : locations) {
            if (name.equals(location.path("name").asText())) return location;
        }
        return null;
    }

    private record ImportResult(int exitCode, JsonNode report, String output) {}

    private static final class FailingLocationProxy implements AutoCloseable {
        private final String backendUrl;
        private final int failFromLocationPost;
        private final AtomicInteger locationPosts = new AtomicInteger();
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();

        private FailingLocationProxy(String backendUrl, int failFromLocationPost) throws IOException {
            this.backendUrl = backendUrl;
            this.failFromLocationPost = failFromLocationPost;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && "/api/v1/admin/locations".equals(path)
                    && locationPosts.incrementAndGet() >= failFromLocationPost) {
                send(exchange, 503, "{\"error\":\"Injected partial failure\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            URI target = URI.create(backendUrl + exchange.getRequestURI());
            HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(15));
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) request.header("Authorization", authorization);
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType != null) request.header("Content-Type", contentType);
            HttpRequest.BodyPublisher publisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
            HttpRequest forwarded = request.method(exchange.getRequestMethod(), publisher).build();
            try {
                HttpResponse<byte[]> response = client.send(forwarded, HttpResponse.BodyHandlers.ofByteArray());
                response.headers().firstValue("Content-Type")
                    .ifPresent(value -> exchange.getResponseHeaders().set("Content-Type", value));
                send(exchange, response.statusCode(), response.body());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                send(exchange, 502, "{\"error\":\"Proxy forwarding interrupted\"}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                send(exchange, 502, "{\"error\":\"Proxy forwarding failed\"}".getBytes(StandardCharsets.UTF_8));
            }
        }

        private void send(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
