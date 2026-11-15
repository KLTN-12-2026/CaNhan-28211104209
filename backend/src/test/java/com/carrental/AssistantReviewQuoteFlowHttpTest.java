package com.carrental;

import static com.carrental.AssistantReviewQuoteFlowSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.carrental.modules.ai.AiDiagnostics;
import com.carrental.modules.ai.AiProviderCapacity;
import com.carrental.modules.ai.parser.QwenCriteriaParser;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.user.service.AccountService;
import com.carrental.modules.user.service.LicenseService;
import com.carrental.security.Actor;
import com.carrental.security.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/**
 * Scripted provider: the production QwenCriteriaParser (schema, envelope and extraction validation)
 * talks to a loopback stub speaking the Ollama chat protocol with pre-written model content.
 * This proves the backend parse → review/edit → recommendation → vehicle → quote chain over HTTP;
 * it is not evidence of Qwen extraction quality. AssistantReviewQuoteLiveIT covers the live model.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.jwt-secret=assistant-flow-test-secret-at-least-32-bytes",
    "app.ai-recommendation-enabled=true", "app.qwen-parser-enabled=false", "app.gemini-parser-enabled=false",
    "app.seed-enabled=true", "app.seed-password=AssistantFlowOnly123!",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@Import(AssistantReviewQuoteFlowHttpTest.Config.class)
class AssistantReviewQuoteFlowHttpTest {
    static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    static final Map<String, String> SCRIPT = new HashMap<>();
    static final List<String> PROVIDER_REQUESTS = new CopyOnWriteArrayList<>();
    static HttpServer stub;
    /** Test-only hook executed inside the provider call, i.e. while the HTTP parse request is in flight. */
    static volatile Runnable duringParse;

    static String rental(String pickupDate, String returnDate, Integer passengers, List<String> unresolved) {
        String r = returnDate == null ? "null" : "\"" + returnDate + "\"";
        String rb = returnDate == null ? "null" : "\"" + DN + "\"";
        return "{\"decision\":\"RENTAL\",\"extraction\":{\"intent\":\"" + (unresolved.isEmpty() ? "RECOMMEND_VEHICLE" : "NEEDS_CLARIFICATION")
            + "\",\"pickupDate\":\"" + pickupDate + "\",\"returnDate\":" + r + ",\"pickupBranchName\":\"" + DN
            + "\",\"returnBranchName\":" + rb + ",\"passengers\":" + passengers + ",\"budgetVndTotal\":3000000,"
            + "\"transmission\":\"AUTO\",\"unresolvedFields\":" + new ObjectMapper().valueToTree(unresolved) + ",\"conflicts\":[]}}";
    }
    static final String OUT_OF_SCOPE = "Thời tiết Đà Nẵng tuần sau thế nào?";
    static final String CONFLICT = "Nhận xe tại " + DN + " ngày 22/10/2026 và ngày 23/10/2026, trả ngày 26/10/2026; 4 người, tổng 3 triệu.";
    static {
        SCRIPT.put(FULL_TEXT, rental("2026-10-22", "2026-10-26", 4, List.of()));
        SCRIPT.put(MISSING_RETURN_TEXT, rental("2026-10-22", null, 4, List.of("returnDate", "returnBranchName")));
        SCRIPT.put(OUT_OF_SCOPE, "{\"decision\":\"OUT_OF_SCOPE\"}");
        SCRIPT.put(CONFLICT, "{\"decision\":\"CONFLICT\"}");
    }

    @TestConfiguration static class Config {
        @Bean @Primary Clock flowClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean QwenCriteriaParser scriptedQwen(ObjectMapper mapper, AiProviderCapacity capacity, AiDiagnostics diagnostics) throws Exception {
            stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/api/chat", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                PROVIDER_REQUESTS.add(request);
                var hook = duringParse;
                if (hook != null) hook.run();
                var body = mapper.readTree(request);
                String text = body.path("messages").get(1).path("content").asText();
                String content = SCRIPT.get(text);
                byte[] reply = content == null ? new byte[0] : mapper.writeValueAsBytes(Map.of(
                    "model", "qwen3:8b", "done", true, "done_reason", "stop",
                    "message", Map.of("role", "assistant", "content", content)));
                exchange.sendResponseHeaders(content == null ? 500 : 200, reply.length == 0 ? -1 : reply.length);
                try (var out = exchange.getResponseBody()) { out.write(reply); }
            });
            stub.start();
            return new QwenCriteriaParser(mapper, URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/api/chat"),
                Duration.ofSeconds(10), "qwen3:8b", capacity, diagnostics);
        }
    }
    @AfterAll static void stopStub() { if (stub != null) stub.stop(0); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) { AssistantReviewQuoteFlowSupport.database(r, "assistant_flow"); }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired AccountService accounts;
    @Autowired LicenseService licenses;
    @Autowired BookingService bookings;
    final Actor user = new Actor("demo-user", "USER", "Flow fixture");
    AssistantReviewQuoteFlowSupport flow;

    @BeforeEach void setup() {
        flow = new AssistantReviewQuoteFlowSupport(http, jdbc, tokens, accounts);
        flow.resetFixture(licenses, user);
        PROVIDER_REQUESTS.clear();
        duringParse = null;
    }
    @AfterEach void clearHook() { duringParse = null; }

    @Test void editedCriteriaDriveRecommendationVehicleViewAndQuoteWithoutRecords() {
        var beforeParse = flow.baseline();
        var parsed = flow.parse("demo-user", FULL_TEXT);
        assertEquals(200, parsed.getStatusCode().value());
        assertFullDraft(parsed.getBody());
        assertEquals(1, PROVIDER_REQUESTS.size());
        flow.reviewEditRecommendViewQuote(beforeParse, parsed.getBody());
        assertEquals(1, PROVIDER_REQUESTS.size(), "Recommendation and quote must not call the model again");
        String sent = PROVIDER_REQUESTS.get(0);
        for (String forbidden : List.of("demo-user", "Bearer", "FLOW-123", "Flow fixture"))
            assertFalse(sent.contains(forbidden), "Provider request must not carry account data: " + forbidden);
    }

    @Test void clarificationDraftIsNotSearchedUntilRenterCompletesIt() {
        var beforeParse = flow.baseline();
        var parsed = flow.parse("demo-user", MISSING_RETURN_TEXT);
        assertEquals(200, parsed.getStatusCode().value());
        assertMissingReturnDraft(parsed.getBody());
        assertEquals(1, PROVIDER_REQUESTS.size());
        flow.clarifyThenComplete(beforeParse, parsed.getBody());
        assertEquals(1, PROVIDER_REQUESTS.size(), "Completing the draft must not call the model again");
    }

    /**
     * Sensitivity of the no-mutation readback (G5-M3-R1): a write made while the successful parse
     * request is in flight must fail the comparison against the pre-parse baseline, for vehicles and
     * for bookings. A baseline taken after parse (the earlier ordering) does not see it.
     */
    @Test void noMutationReadbackDetectsWritesMadeDuringSuccessfulParse() {
        var beforeVehicleProbe = flow.baseline();
        duringParse = () -> jdbc.update("update vehicles set daily_price=daily_price+1 where id='v1'");
        var parsed = flow.parse("demo-user", FULL_TEXT);
        duringParse = null;
        assertFullDraft(parsed.getBody());
        var afterParse = flow.baseline();
        var vehicleMiss = assertThrows(org.opentest4j.AssertionFailedError.class, () -> flow.assertUnchangedSince(beforeVehicleProbe));
        assertTrue(vehicleMiss.getMessage().contains("vehicles changed"), vehicleMiss::getMessage);
        flow.assertUnchangedSince(afterParse); // a post-parse baseline is blind to the parse-time write
        jdbc.update("update vehicles set daily_price=650000 where id='v1'");

        var pickup = LocalDate.parse("2026-10-22");
        var returns = LocalDate.parse("2026-10-26");
        long total = bookings.quote(user, new BookingService.Input("v2", "hn", "hn", null, null, null, pickup, returns)).total();
        var beforeBookingProbe = flow.baseline();
        duringParse = () -> bookings.create(user, new BookingService.Input("v2", "hn", "hn", null, null, total, pickup, returns));
        parsed = flow.parse("demo-user", FULL_TEXT);
        duringParse = null;
        assertFullDraft(parsed.getBody());
        var bookingMiss = assertThrows(org.opentest4j.AssertionFailedError.class, () -> flow.assertUnchangedSince(beforeBookingProbe));
        assertTrue(bookingMiss.getMessage().contains("bookings changed"), bookingMiss::getMessage);
        assertEquals(1, jdbc.queryForObject("select count(*) from bookings", Integer.class));
        assertEquals(2, PROVIDER_REQUESTS.size());
    }

    @Test void parseRolesPrivacyOutOfScopeAndConflictNeverYieldADraft() {
        var before = flow.baseline();
        assertEquals(401, flow.parse(null, FULL_TEXT).getStatusCode().value());
        assertEquals(403, flow.parse("demo-staff-dn", FULL_TEXT).getStatusCode().value());
        assertEquals(403, flow.parse("demo-admin", FULL_TEXT).getStatusCode().value());
        assertEquals(400, flow.parse("demo-user", FULL_TEXT + " Email a@b.vn").getStatusCode().value());
        assertEquals(400, flow.parse("demo-user", FULL_TEXT + " Gọi 0901234567").getStatusCode().value());
        assertEquals(0, PROVIDER_REQUESTS.size(), "Refused requests must not reach the provider");

        var outOfScope = flow.parse("demo-user", OUT_OF_SCOPE).getBody();
        assertEquals("OUT_OF_SCOPE", outOfScope.path("status").asText());
        assertTrue(outOfScope.path("draft").isNull());
        var conflict = flow.parse("demo-user", CONFLICT).getBody();
        assertEquals("NEEDS_CLARIFICATION", conflict.path("status").asText());
        assertTrue(conflict.path("draft").isNull());
        assertEquals(List.of("conflicts"), List.of(conflict.path("unresolvedFields").get(0).asText()));
        var broken = flow.parse("demo-user", "Câu không có trong kịch bản; provider trả 500.");
        assertEquals(503, broken.getStatusCode().value(), "Provider failure must not become an invented draft");
        flow.assertUnchangedSince(before);
    }

    @Test void quoteAfterEditedRecommendationUsesCurrentPrice() {
        var beforeParse = flow.baseline();
        var parsed = flow.parse("demo-user", FULL_TEXT).getBody();
        assertFullDraft(parsed);
        var edited = flow.criteriaFromDraft(parsed.path("draft"));
        edited.put("returnBranchId", "hn");
        edited.put("budgetVndTotal", 4_100_000L);
        var top = flow.recommend("demo-user", edited).getBody().path("results").get(0);
        String vehicleId = top.path("vehicleId").asText();
        flow.assertUnchangedSince(beforeParse); // parse + recommendation wrote nothing
        long original = jdbc.queryForObject("select daily_price from vehicles where id=?", Long.class, vehicleId);
        // Deliberate fixture mutation (not caused by the request under test): the price changes after recommendation.
        jdbc.update("update vehicles set daily_price=? where id=?", original + 50_000, vehicleId);
        var afterFixtureMutation = flow.baseline();
        var quote = flow.quote("demo-user", vehicleId, edited);
        assertEquals(200, quote.getStatusCode().value());
        assertEquals(original + 50_000, quote.getBody().path("dailyPrice").asLong());
        assertEquals((original + 50_000) * 4 + ONE_WAY_FEE, quote.getBody().path("total").asLong());
        assertNotEquals(top.path("total").asLong(), quote.getBody().path("total").asLong());
        flow.assertUnchangedSince(afterFixtureMutation);
    }

    @Test void quoteAfterEditedRecommendationRejectsNewScheduleConflict() {
        var beforeParse = flow.baseline();
        var parsed = flow.parse("demo-user", FULL_TEXT).getBody();
        assertFullDraft(parsed);
        var edited = flow.criteriaFromDraft(parsed.path("draft"));
        edited.put("returnBranchId", "hn");
        edited.put("budgetVndTotal", 4_100_000L);
        var top = flow.recommend("demo-user", edited).getBody().path("results").get(0);
        String vehicleId = top.path("vehicleId").asText();
        flow.assertUnchangedSince(beforeParse); // parse + recommendation wrote nothing
        var pickup = LocalDate.parse("2026-10-22");
        var returns = LocalDate.parse("2026-10-26");
        // Deliberate fixture mutation (not caused by the request under test): another rental takes the slot.
        long fresh = bookings.quote(user, new BookingService.Input(vehicleId, "dn", "dn", null, null, null, pickup, returns)).total();
        assertNotNull(bookings.create(user, new BookingService.Input(vehicleId, "dn", "dn", null, null, fresh, pickup, returns)).id());
        var afterFixtureMutation = flow.baseline();
        var quote = flow.quote("demo-user", vehicleId, edited);
        assertEquals(409, quote.getStatusCode().value());
        assertTrue(quote.getBody().toString().contains("Xe đã có đơn trùng khoảng thuê."));
        flow.assertUnchangedSince(afterFixtureMutation);
    }
}
