package com.carrental.modules.ai;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
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
}
