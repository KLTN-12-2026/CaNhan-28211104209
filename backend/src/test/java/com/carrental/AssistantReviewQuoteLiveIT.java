package com.carrental;

import static com.carrental.AssistantReviewQuoteFlowSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.carrental.modules.ai.parser.QwenCriteriaParser;
import com.carrental.modules.user.service.AccountService;
import com.carrental.modules.user.service.LicenseService;
import com.carrental.security.Actor;
import com.carrental.security.TokenService;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/**
 * Live local Qwen (Ollama, qwen3:8b) through the real HTTP parse endpoint, then the same
 * review/edit → recommendation → vehicle → quote chain. One request per case, no retries.
 * Opt-in only: KDRIVE_AI_LIVE_FLOW=true. Without it the suite fails instead of skipping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.jwt-secret=assistant-live-flow-secret-at-least-32-bytes",
    "app.ai-recommendation-enabled=true", "app.qwen-parser-enabled=true", "app.qwen-model=qwen3:8b",
    "app.gemini-parser-enabled=false", "app.ai-routing-enabled=false",
    "app.seed-enabled=true", "app.seed-password=AssistantLiveFlowOnly123!",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@Import(AssistantReviewQuoteLiveIT.Config.class)
@TestMethodOrder(MethodOrderer.MethodName.class)
class AssistantReviewQuoteLiveIT {
    @TestConfiguration static class Config {
        @Bean @Primary Clock flowClock() { return Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) { AssistantReviewQuoteFlowSupport.database(r, "assistant_live_flow"); }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired AccountService accounts;
    @Autowired LicenseService licenses;
    @Autowired ApplicationContext context;
    AssistantReviewQuoteFlowSupport flow;

    @BeforeEach void setup() {
        assertEquals("true", System.getenv("KDRIVE_AI_LIVE_FLOW"), "Explicit live-Qwen opt-in required");
        assertEquals(QwenCriteriaParser.class, context.getBean(QwenCriteriaParser.class).getClass(),
            "Live suite must use the production Qwen parser bean");
        flow = new AssistantReviewQuoteFlowSupport(http, jdbc, tokens, accounts);
        flow.resetFixture(licenses, new Actor("demo-user", "USER", "Live flow fixture"));
    }

    @Test void a_liveDraftEditedByRenterDrivesRecommendationVehicleAndQuote() {
        var beforeParse = flow.baseline();
        long started = System.nanoTime();
        var parsed = flow.parse("demo-user", FULL_TEXT);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        System.out.println("LIVE_PARSE case=full status=" + parsed.getStatusCode().value() + " elapsedMs=" + elapsedMs + " body=" + parsed.getBody());
        assertEquals(200, parsed.getStatusCode().value());
        assertFullDraft(parsed.getBody());
        var edited = flow.reviewEditRecommendViewQuote(beforeParse, parsed.getBody());
        System.out.println("LIVE_FLOW case=full editedCriteria=" + flow.toJson(edited) + " result=PASS");
    }

    @Test void b_liveMissingReturnDateIsNotFilledAndStillRequiresRenter() {
        var beforeParse = flow.baseline();
        long started = System.nanoTime();
        var parsed = flow.parse("demo-user", MISSING_RETURN_TEXT);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        System.out.println("LIVE_PARSE case=missing-return status=" + parsed.getStatusCode().value() + " elapsedMs=" + elapsedMs + " body=" + parsed.getBody());
        assertEquals(200, parsed.getStatusCode().value());
        assertMissingReturnDraft(parsed.getBody());
        flow.clarifyThenComplete(beforeParse, parsed.getBody());
    }
}
