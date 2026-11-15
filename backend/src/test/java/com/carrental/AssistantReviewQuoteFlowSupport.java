package com.carrental;

import static org.junit.jupiter.api.Assertions.*;

import com.carrental.modules.user.service.AccountService;
import com.carrental.modules.user.service.LicenseService;
import com.carrental.security.Actor;
import com.carrental.security.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Customer flow shared by the scripted-provider and live-Qwen suites:
 * parse text, the renter edits the reviewed draft, the renter asks for recommendations,
 * opens a vehicle and requests a quote. Every step goes through the public HTTP API.
 */
final class AssistantReviewQuoteFlowSupport {
    static final String DN = "Đà Nẵng · mẫu";
    static final String FULL_TEXT = "Nhận xe tại " + DN + " ngày 22/10/2026, trả tại " + DN
        + " ngày 26/10/2026; 4 người, tổng 3 triệu, số tự động.";
    static final String MISSING_RETURN_TEXT = "Nhận xe tại " + DN
        + " ngày 22/10/2026; 4 người, tổng 3 triệu, số tự động. Chưa biết ngày trả.";
    static final long ONE_WAY_FEE = 1_500_000L;
    static final List<String> RECORD_TABLES = List.of("bookings", "booking_status_history", "contracts", "invoices");

    private final TestRestTemplate http;
    private final JdbcTemplate jdbc;
    private final TokenService tokens;
    private final AccountService accounts;
    private final ObjectMapper json = new ObjectMapper();

    AssistantReviewQuoteFlowSupport(TestRestTemplate http, JdbcTemplate jdbc, TokenService tokens, AccountService accounts) {
        this.http = http; this.jdbc = jdbc; this.tokens = tokens; this.accounts = accounts;
    }

    static void database(DynamicPropertyRegistry registry, String h2Name) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        boolean nativeDb = url != null && !url.isBlank();
        if (nativeDb) CancelBoundaryTest.verifySafeIsolatedDatabase(url,
            System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"), System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),
            System.getenv("KDRIVE_TEST_DB_USER"), System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        registry.add("spring.datasource.url", () -> nativeDb ? url
            : "jdbc:h2:mem:" + h2Name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> nativeDb ? System.getenv("KDRIVE_TEST_DB_USER") : "sa");
        registry.add("spring.datasource.password", () -> nativeDb ? System.getenv("KDRIVE_TEST_DB_PASSWORD") : "");
    }

    /** Same deterministic catalog as RecommendationHttpTest: v1/v3 at dn, v2 at hn, all routes open. */
    void resetFixture(LicenseService licenses, Actor user) {
        CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);
        for (String table : List.of("reviews", "incident_events", "incident_cases", "vehicle_handover_records",
                "invoice_items", "invoices", "contracts", "booking_status_history", "bookings"))
            jdbc.update("delete from " + table);
        jdbc.update("update branches set status='ACTIVE'");
        jdbc.update("update branch_routes set enabled=true");
        jdbc.update("update vehicles set status='ACTIVE',current_branch_id='dn',seats=5,transmission='AUTO',daily_price=650000 where id='v1'");
        jdbc.update("update vehicles set status='ACTIVE',current_branch_id='dn',seats=5,transmission='AUTO',daily_price=600000 where id='v3'");
        jdbc.update("update vehicles set status='ACTIVE',current_branch_id='hn' where id='v2'");
        var old = licenses.mine(user);
        licenses.save(user, new LicenseService.View(old == null ? null : old.id(), "FLOW-123", "VN_2025", "B",
            LocalDate.of(2030, 12, 31), false, "PENDING", old == null ? 0 : old.version()));
        LicensePhotoFixture.approved(licenses, user);
    }

    HttpHeaders headers(String accountId) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accountId != null) headers.setBearerAuth(tokens.issue(accounts.require(accountId)));
        return headers;
    }

    ResponseEntity<JsonNode> parse(String accountId, String text) {
        return http.exchange("/api/v1/ai/criteria/parse", HttpMethod.POST,
            new HttpEntity<>(Map.of("text", text), headers(accountId)), JsonNode.class);
    }

    ResponseEntity<JsonNode> recommend(String accountId, Map<String, Object> criteria) {
        return http.exchange("/api/v1/ai/recommendations", HttpMethod.POST,
            new HttpEntity<>(Map.of("requestId", "flow-1", "criteria", criteria), headers(accountId)), JsonNode.class);
    }

    ResponseEntity<JsonNode> vehicle(String vehicleId) {
        return http.exchange("/api/v1/vehicles/" + vehicleId, HttpMethod.GET, new HttpEntity<>(headers("demo-user")), JsonNode.class);
    }

    ResponseEntity<JsonNode> quote(String accountId, String vehicleId, Map<String, Object> criteria) {
        var input = new LinkedHashMap<String, Object>();
        input.put("vehicleId", vehicleId);
        for (String key : List.of("pickupBranchId", "returnBranchId", "pickupDate", "returnDate")) input.put(key, criteria.get(key));
        return http.exchange("/api/v1/bookings/quote", HttpMethod.POST, new HttpEntity<>(input, headers(accountId)), JsonNode.class);
    }

    /** What the widget submits after the renter has reviewed the draft: the draft fields, possibly edited. */
    Map<String, Object> criteriaFromDraft(JsonNode draft) {
        var criteria = new LinkedHashMap<String, Object>();
        for (String key : List.of("pickupDate", "returnDate", "pickupBranchId", "returnBranchId"))
            if (draft.hasNonNull(key)) criteria.put(key, draft.get(key).asText());
        if (draft.hasNonNull("passengers")) criteria.put("passengers", draft.get("passengers").asInt());
        if (draft.hasNonNull("budgetVndTotal")) criteria.put("budgetVndTotal", draft.get("budgetVndTotal").asLong());
        var preferences = new LinkedHashMap<String, Object>();
        if (draft.hasNonNull("transmission")) preferences.put("transmission", draft.get("transmission").asText());
        preferences.put("amenityIds", List.of());
        criteria.put("preferences", preferences);
        return criteria;
    }

    Map<String, List<Map<String, Object>>> records() {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : RECORD_TABLES) rows.put(table, jdbc.queryForList("select * from " + table + " order by 1"));
        return rows;
    }

    List<Map<String, Object>> vehicles() { return jdbc.queryForList("select * from vehicles order by id"); }

    /** Business rows that the request chain under test must leave untouched. Capture before the HTTP parse. */
    record Baseline(Map<String, List<Map<String, Object>>> records, List<Map<String, Object>> vehicles) {}

    Baseline baseline() { return new Baseline(records(), vehicles()); }

    void assertUnchangedSince(Baseline baseline) {
        var now = records();
        for (String table : RECORD_TABLES)
            assertEquals(baseline.records().get(table), now.get(table), table + " changed during the request chain");
        assertEquals(baseline.vehicles(), vehicles(), "vehicles changed during the request chain");
    }

    static void assertFullDraft(JsonNode body) {
        assertEquals("READY_FOR_REVIEW", body.path("status").asText(), body::toString);
        JsonNode draft = body.path("draft");
        assertEquals("2026-10-22", draft.path("pickupDate").asText());
        assertEquals("2026-10-26", draft.path("returnDate").asText());
        assertEquals("dn", draft.path("pickupBranchId").asText());
        assertEquals("dn", draft.path("returnBranchId").asText());
        assertEquals(4, draft.path("passengers").asInt());
        assertEquals(3_000_000L, draft.path("budgetVndTotal").asLong());
        assertEquals("AUTO", draft.path("transmission").asText());
        assertEquals(0, body.path("unresolvedFields").size(), body::toString);
    }

    static void assertMissingReturnDraft(JsonNode body) {
        assertEquals("NEEDS_CLARIFICATION", body.path("status").asText(), body::toString);
        JsonNode draft = body.path("draft");
        assertTrue(draft.path("returnDate").isNull(), body::toString);
        assertEquals("2026-10-22", draft.path("pickupDate").asText());
        assertEquals("dn", draft.path("pickupBranchId").asText());
        var unresolved = new HashSet<String>();
        body.path("unresolvedFields").forEach(f -> unresolved.add(f.asText()));
        assertTrue(unresolved.contains("returnDate"), body::toString);
    }

    /**
     * The decisive chain. The parsed draft is dn→dn, 4 people, 3,000,000 VND. The renter changes the
     * return branch to hn and the budget to 4,100,000; the edited values, not the draft, must drive
     * the recommendation and the quote. {@code beforeParse} must be captured before the HTTP parse so
     * the final readback covers the parse itself. Returns the edited criteria.
     */
    Map<String, Object> reviewEditRecommendViewQuote(Baseline beforeParse, JsonNode parsed) {
        Map<String, Object> draftCriteria = criteriaFromDraft(parsed.path("draft"));

        var unedited = recommend("demo-user", draftCriteria);
        assertEquals(200, unedited.getStatusCode().value());
        assertEquals("OK", unedited.getBody().path("status").asText(), unedited.getBody()::toString);
        for (JsonNode item : unedited.getBody().path("results")) assertEquals(0, item.path("oneWayFee").asLong());

        var returnOnly = new LinkedHashMap<>(draftCriteria);
        returnOnly.put("returnBranchId", "hn");
        var tooTight = recommend("demo-user", returnOnly);
        assertEquals(200, tooTight.getStatusCode().value());
        assertEquals("NO_MATCH", tooTight.getBody().path("status").asText(),
            "Edited route with the original 3,000,000 budget must not fit 3,900,000+ totals");

        var edited = new LinkedHashMap<>(returnOnly);
        edited.put("budgetVndTotal", 4_100_000L);
        var recommendation = recommend("demo-user", edited);
        assertEquals(200, recommendation.getStatusCode().value());
        JsonNode body = recommendation.getBody();
        assertEquals("OK", body.path("status").asText(), body::toString);
        assertEquals("hn", body.path("criteria").path("returnBranchId").asText());
        assertEquals(4_100_000L, body.path("criteria").path("budgetVndTotal").asLong());
        assertTrue(body.path("results").size() > 0);
        for (JsonNode item : body.path("results")) {
            assertEquals(ONE_WAY_FEE, item.path("oneWayFee").asLong());
            assertEquals(item.path("rentalTotal").asLong() + ONE_WAY_FEE, item.path("total").asLong());
            assertTrue(item.path("total").asLong() <= 4_100_000L);
            assertEquals(4, item.path("rentalDays").asInt());
        }
        JsonNode top = body.path("results").get(0);
        String vehicleId = top.path("vehicleId").asText();

        var detail = vehicle(vehicleId);
        assertEquals(200, detail.getStatusCode().value());
        assertEquals(vehicleId, detail.getBody().path("id").asText());
        assertTrue(detail.getBody().path("seats").asInt() >= 4);
        assertEquals("ACTIVE", detail.getBody().path("status").asText());

        var quote = quote("demo-user", vehicleId, edited);
        assertEquals(200, quote.getStatusCode().value(), () -> String.valueOf(quote.getBody()));
        assertEquals(vehicleId, quote.getBody().path("vehicleId").asText());
        assertEquals("hn", quote.getBody().path("returnBranchId").asText());
        assertEquals(ONE_WAY_FEE, quote.getBody().path("oneWayFee").asLong());
        assertEquals(top.path("total").asLong(), quote.getBody().path("total").asLong());
        assertEquals(top.path("rentalTotal").asLong(), quote.getBody().path("rentalTotal").asLong());

        assertEquals(401, quote(null, vehicleId, edited).getStatusCode().value());
        assertUnchangedSince(beforeParse);
        return edited;
    }

    /** NEEDS_CLARIFICATION draft sent as-is is not searched; once the renter fills the date it is. */
    void clarifyThenComplete(Baseline beforeParse, JsonNode parsed) {
        Map<String, Object> criteria = criteriaFromDraft(parsed.path("draft"));
        assertFalse(criteria.containsKey("returnDate"));
        var incomplete = recommend("demo-user", criteria);
        assertEquals(200, incomplete.getStatusCode().value());
        assertEquals("NEEDS_CLARIFICATION", incomplete.getBody().path("status").asText());
        assertEquals(0, incomplete.getBody().path("results").size());
        assertTrue(incomplete.getBody().path("unresolvedFields").toString().contains("returnDate"));
        criteria.put("returnDate", "2026-10-26");
        if (!criteria.containsKey("returnBranchId")) criteria.put("returnBranchId", "dn");
        var complete = recommend("demo-user", criteria);
        assertEquals("OK", complete.getBody().path("status").asText(), complete.getBody()::toString);
        assertEquals("2026-10-26", complete.getBody().path("criteria").path("returnDate").asText());
        assertUnchangedSince(beforeParse);
    }

    String toJson(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
