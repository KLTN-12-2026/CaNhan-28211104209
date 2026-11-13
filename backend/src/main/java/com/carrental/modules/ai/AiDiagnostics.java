package com.carrental.modules.ai;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class AiDiagnostics {
    public enum Provider {
        QWEN,
        OSRM
    }

    public enum Model {
        QWEN3_8B,
        QWEN3_4B,
        NOT_PINNED,
        UNSUPPORTED
    }

    public enum Cause {
        SUCCESS,
        NO_ROUTE,
        DISABLED,
        CAPACITY,
        DEADLINE,
        CONNECT,
        PROVIDER_TIMEOUT,
        PROVIDER_HTTP,
        RESPONSE_TOO_LARGE,
        ENVELOPE_INVALID,
        SCHEMA_INVALID,
        INVALID_REFERENCE,
        INTERRUPTED,
        UNKNOWN
    }

    public record Event(
        AiRequestDeadline.Operation operation,
        Provider provider,
        Model model,
        String version,
        Cause cause,
        long elapsedMillis,
        long count
    ) {}

    private record Key(
        AiRequestDeadline.Operation operation,
        Provider provider,
        Model model,
        Cause cause
    ) {}

    private static final String VERSION_NOT_CHECKED = "NOT_CHECKED";
    private static final Logger LOGGER = LoggerFactory.getLogger(AiDiagnostics.class);
    private final ConcurrentHashMap<Key, LongAdder> counts = new ConcurrentHashMap<>();

    public Event record(
            AiRequestDeadline.Operation operation,
            Provider provider,
            String configuredModel,
            Cause cause,
            long elapsedNanos) {
        Model model = modelLabel(configuredModel);
        Key key = new Key(operation, provider, model, cause);
        LongAdder counter = counts.computeIfAbsent(key, ignored -> new LongAdder());
        counter.increment();
        Event event = new Event(
            operation, provider, model, VERSION_NOT_CHECKED, cause,
            Math.max(0, elapsedNanos) / 1_000_000, counter.sum()
        );
        LOGGER.info(
            "ai_provider_result operation={} provider={} model={} version={} cause={} elapsed_ms={} count={}",
            event.operation(), event.provider(), event.model(), event.version(),
            event.cause(), event.elapsedMillis(), event.count()
        );
        return event;
    }

    public List<Event> snapshot() {
        List<Event> events = new ArrayList<>();
        counts.forEach((key, counter) -> events.add(new Event(
            key.operation(), key.provider(), key.model(), VERSION_NOT_CHECKED,
            key.cause(), 0, counter.sum()
        )));
        events.sort(Comparator.comparing(Event::operation)
            .thenComparing(Event::provider)
            .thenComparing(Event::model)
            .thenComparing(Event::cause));
        return List.copyOf(events);
    }

    public static Model modelLabel(String configuredModel) {
        return switch (configuredModel == null ? "" : configuredModel) {
            case "qwen3:8b" -> Model.QWEN3_8B;
            case "qwen3:4b" -> Model.QWEN3_4B;
            case "NOT_PINNED" -> Model.NOT_PINNED;
            default -> Model.UNSUPPORTED;
        };
    }

    public static Cause transportCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AiRequestDeadline.DeadlineExceededException) return Cause.DEADLINE;
            if (cause instanceof LocalHttp.ResponseTooLargeException) return Cause.RESPONSE_TOO_LARGE;
            if (cause instanceof InterruptedException) return Cause.INTERRUPTED;
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) return Cause.CONNECT;
            if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) return Cause.PROVIDER_TIMEOUT;
        }
        return Cause.UNKNOWN;
    }
}
