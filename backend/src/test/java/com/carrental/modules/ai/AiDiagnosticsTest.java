package com.carrental.modules.ai;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiDiagnosticsTest {
    @Test
    void countersUseOnlyBoundedLabelsAndKeepProviderVersionUnknown() {
        var diagnostics = new AiDiagnostics();

        var first = diagnostics.record(
            AiRequestDeadline.Operation.ITINERARY_PREVIEW,
            AiDiagnostics.Provider.QWEN,
            "qwen3:8b",
            AiDiagnostics.Cause.PROVIDER_HTTP,
            Duration.ofMillis(27).toNanos()
        );
        var second = diagnostics.record(
            AiRequestDeadline.Operation.ITINERARY_PREVIEW,
            AiDiagnostics.Provider.QWEN,
            "qwen3:8b",
            AiDiagnostics.Cause.PROVIDER_HTTP,
            Duration.ofMillis(35).toNanos()
        );
        var unrecognized = diagnostics.record(
            AiRequestDeadline.Operation.ROUTE_PREVIEW,
            AiDiagnostics.Provider.OSRM,
            "private-path-token-sentinel",
            AiDiagnostics.Cause.UNKNOWN,
            0
        );

        assertEquals(27, first.elapsedMillis());
        assertEquals(1, first.count());
        assertEquals(2, second.count());
        assertEquals("NOT_CHECKED", first.version());
        assertEquals(AiDiagnostics.Model.UNSUPPORTED, unrecognized.model());
        assertEquals(2, diagnostics.snapshot().stream()
            .filter(event -> event.cause() == AiDiagnostics.Cause.PROVIDER_HTTP).findFirst().orElseThrow().count());
        assertFalse(diagnostics.snapshot().toString().contains("private-path-token-sentinel"));
    }

    @Test
    void transportFailuresMapToFiniteCausesWithoutInspectingMessages() {
        assertEquals(AiDiagnostics.Cause.CONNECT,
            AiDiagnostics.transportCause(new RuntimeException("payload-sentinel", new ConnectException("private"))));
        assertEquals(AiDiagnostics.Cause.CONNECT,
            AiDiagnostics.transportCause(new HttpConnectTimeoutException("private")));
        assertEquals(AiDiagnostics.Cause.PROVIDER_TIMEOUT,
            AiDiagnostics.transportCause(new HttpTimeoutException("private")));
        assertEquals(AiDiagnostics.Cause.PROVIDER_TIMEOUT,
            AiDiagnostics.transportCause(new TimeoutException("private")));
        assertEquals(AiDiagnostics.Cause.RESPONSE_TOO_LARGE,
            AiDiagnostics.transportCause(new LocalHttp.ResponseTooLargeException()));
        assertEquals(AiDiagnostics.Cause.DEADLINE,
            AiDiagnostics.transportCause(new AiRequestDeadline.DeadlineExceededException()));
        assertEquals(AiDiagnostics.Cause.UNKNOWN, AiDiagnostics.transportCause(new IllegalStateException("private")));
    }

    @Test
    void actualDiagnosticsSinkNeverRecordsPromptBodyProviderErrorOrTokenSentinels() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(AiDiagnostics.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);

        var requestBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("X-Synthetic-Token", "token-sentinel");
                byte[] body = "{\"body\":\"body-sentinel\",\"error\":\"provider-error-sentinel\"}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var diagnostics = new AiDiagnostics();
            var parser = new com.carrental.modules.ai.parser.QwenCriteriaParser(
                new ObjectMapper(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                Duration.ofSeconds(2),
                "qwen3:8b",
                new AiProviderCapacity(),
                diagnostics
            );

            assertThrows(com.carrental.exception.DomainException.class, () -> parser.parse("prompt-sentinel"));
            assertTrue(requestBody.get().contains("prompt-sentinel"), "The synthetic prompt must reach the HTTP adapter.");

            String actualLogSink = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(actualLogSink.contains("cause=PROVIDER_HTTP"));
            for (String sentinel : new String[] {
                    "prompt-sentinel", "body-sentinel", "provider-error-sentinel", "token-sentinel"
            }) {
                assertFalse(actualLogSink.contains(sentinel), "Diagnostics log leaked " + sentinel);
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            server.stop(0);
        }
    }
}
