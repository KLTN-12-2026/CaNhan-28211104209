package com.carrental.modules.ai;

import com.carrental.modules.ai.itinerary.service.QwenItinerarySequencePlanner;
import com.carrental.modules.ai.parser.QwenCriteriaParser;
import com.carrental.modules.ai.routing.OsrmRoutingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiProviderCapacityHttpTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiProviderCapacity capacity = new AiProviderCapacity();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maximumActive = new AtomicInteger();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger nextStatus = new AtomicInteger(200);
    private final AtomicInteger holdsRemaining = new AtomicInteger();
    private final AtomicInteger bodyHoldsRemaining = new AtomicInteger();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private ExecutorService calls;
    private CountDownLatch entered;
    private CountDownLatch release;
    private CountDownLatch handlerFinished;
    private URI endpoint;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        calls = Executors.newCachedThreadPool();
        handlerFinished = new CountDownLatch(1);
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            requestCount.incrementAndGet();
            try (exchange) {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                if (holdsRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test release latch timed out.");
                    }
                }
                int status = nextStatus.getAndSet(200);
                byte[] response = response(requestBody);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                if (bodyHoldsRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    int firstChunk = Math.min(1, response.length);
                    exchange.getResponseBody().write(response, 0, firstChunk);
                    exchange.getResponseBody().flush();
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Response body release latch timed out.");
                    }
                    if (response.length > firstChunk) {
                        exchange.getResponseBody().write(response, firstChunk, response.length - firstChunk);
                    }
                } else {
                    exchange.getResponseBody().write(response);
                }
            } catch (Exception ignored) {
                exchange.close();
            } finally {
                active.decrementAndGet();
                handlerFinished.countDown();
            }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat");
    }

    @AfterEach
    void stopServer() {
        if (release != null) {
            release.countDown();
        }
        if (server != null) {
            server.stop(0);
        }
        if (calls != null) {
            calls.shutdownNow();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void qwenCapacityIsSharedAcrossParserAndItineraryHttpCalls() throws Exception {
        var parser = parser();
        var planner = planner();

        assertBusyAcrossAdapters(parser, planner);
        assertEquals(2, requestCount.get());
        assertEquals(1, maximumActive.get());
    }

    @Test
    void osrmCapacityIsSharedAcrossDistinctClientsAtTheHttpHandler() throws Exception {
        URI osrmBase = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        var directRouteClient = new OsrmRoutingClient(json, osrmBase, capacity);
        var itineraryClient = new OsrmRoutingClient(json, osrmBase, capacity);
        var fallbackRouteClient = new OsrmRoutingClient(json, osrmBase, capacity);
        entered = new CountDownLatch(2);
        release = new CountDownLatch(1);
        holdsRemaining.set(2);

        Future<OsrmRoutingClient.Estimate> direct = calls.submit(() -> directRouteClient.route(points()));
        Future<OsrmRoutingClient.Estimate> itinerary = calls.submit(() -> itineraryClient.route(points()));
        assertTrue(entered.await(3, TimeUnit.SECONDS), "Two outbound HTTP calls should reach the controlled handler.");

        var rejected = assertThrows(
            AiProviderCapacity.CapacityExceededException.class,
            () -> fallbackRouteClient.route(points())
        );
        assertEquals(AiProviderCapacity.Provider.OSRM, rejected.provider());
        assertEquals(2, requestCount.get());
        assertEquals(2, maximumActive.get());

        release.countDown();
        assertEquals(OsrmRoutingClient.Status.VERIFIED, direct.get(3, TimeUnit.SECONDS).status());
        assertEquals(OsrmRoutingClient.Status.VERIFIED, itinerary.get(3, TimeUnit.SECONDS).status());
        assertEquals(2, maximumActive.get());
        assertEquals(OsrmRoutingClient.Status.VERIFIED, fallbackRouteClient.route(points()).status());
    }

    @Test
    void itineraryBudgetSpentByQwenPreventsTheFollowingOsrmDispatch() throws Exception {
        AtomicLong ticker = new AtomicLong(100);
        var deadline = AiRequestDeadline.withTicker(Duration.ofSeconds(60), ticker::get);
        var planner = planner();
        var diagnostics = new AiDiagnostics();
        var osrm = new OsrmRoutingClient(
            json,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
            capacity,
            diagnostics
        );

        assertEquals(
            List.of("start", "second"),
            planner.planSequence("start", candidates(), 2, deadline).orderedLocationIds()
        );
        assertEquals(1, requestCount.get());
        ticker.addAndGet(Duration.ofSeconds(60).toNanos());

        assertEquals(OsrmRoutingClient.Status.UNAVAILABLE, osrm.route(points(), deadline).status());
        assertEquals(1, requestCount.get(), "An expired itinerary budget must not dispatch its OSRM stage.");
        assertEquals(AiDiagnostics.Cause.DEADLINE, diagnostics.snapshot().get(0).cause());
    }

    @Test
    void providerTimeoutCancelsActualHttpAndReleasesSharedPermitForOtherAdapter() throws Exception {
        var diagnostics = new AiDiagnostics();
        var parser = new QwenCriteriaParser(json, endpoint, Duration.ofMillis(300), "qwen3:8b", capacity, diagnostics);
        var planner = planner();
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        handlerFinished = new CountDownLatch(1);
        bodyHoldsRemaining.set(1);

        Future<com.carrental.exception.DomainException> failedParser = calls.submit(() ->
            assertThrows(com.carrental.exception.DomainException.class,
                () -> parser.parse("synthetic timeout request",
                    AiRequestDeadline.start(Duration.ofSeconds(3), AiRequestDeadline.Operation.CRITERIA_EXTRACTION))));
        assertTrue(entered.await(3, TimeUnit.SECONDS), "Parser request should reach a held HTTP response body.");
        var busyPlanner = assertThrows(
            QwenItinerarySequencePlanner.QwenPlanningException.class,
            () -> planner.planSequence("start", candidates(), 2)
        );
        assertEquals(429, busyPlanner.getStatusCode());

        var failure = failedParser.get(3, TimeUnit.SECONDS);
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, failure.status);
        assertTrue(diagnostics.snapshot().stream()
            .anyMatch(event -> event.cause() == AiDiagnostics.Cause.PROVIDER_TIMEOUT));
        release.countDown();
        assertTrue(handlerFinished.await(2, TimeUnit.SECONDS), "Cancelled HTTP response handler should finish.");

        assertEquals(List.of("start", "second"), planner.planSequence("start", candidates(), 2).orderedLocationIds());
        assertEquals(2, requestCount.get());
        assertEquals(1, maximumActive.get(), "Recovered shared capacity must not be over-released.");
    }

    @Test
    void interruptedActualHttpCallClosesTransportAndReleasesPermitWithoutLosingInterrupt() throws Exception {
        var diagnostics = new AiDiagnostics();
        var parser = new QwenCriteriaParser(json, endpoint, Duration.ofSeconds(2), "qwen3:8b", capacity, diagnostics);
        var planner = planner();
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        handlerFinished = new CountDownLatch(1);
        bodyHoldsRemaining.set(1);
        var finished = new CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interruptPreserved = new java.util.concurrent.atomic.AtomicInteger();
        Thread caller = new Thread(() -> {
            try {
                parser.parse("synthetic interrupt request",
                    AiRequestDeadline.start(Duration.ofSeconds(4), AiRequestDeadline.Operation.CRITERIA_EXTRACTION));
            } catch (Throwable error) {
                failure.set(error);
                interruptPreserved.set(Thread.currentThread().isInterrupted() ? 1 : 0);
            } finally {
                finished.countDown();
            }
        });
        caller.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS), "Parser request should reach a held HTTP response body.");
        caller.interrupt();
        assertTrue(finished.await(3, TimeUnit.SECONDS), "Interrupted adapter call must clean up within a bound.");
        caller.join(1000);
        assertFalse(caller.isAlive());

        assertInstanceOf(com.carrental.exception.DomainException.class, failure.get());
        assertEquals(1, interruptPreserved.get(), "The parser adapter must restore the caller interrupt flag.");
        assertTrue(diagnostics.snapshot().stream()
            .anyMatch(event -> event.cause() == AiDiagnostics.Cause.INTERRUPTED));
        release.countDown();
        assertTrue(handlerFinished.await(2, TimeUnit.SECONDS), "Interrupted HTTP response handler should finish.");

        assertEquals(List.of("start", "second"), planner.planSequence("start", candidates(), 2).orderedLocationIds());
        assertEquals(2, requestCount.get());
        assertEquals(1, maximumActive.get(), "Recovered shared capacity must remain bounded to one Qwen request.");
    }

    private void assertBusyAcrossAdapters(QwenCriteriaParser parser, QwenItinerarySequencePlanner planner)
            throws Exception {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        holdsRemaining.set(1);
        Future<?> first;

        first = calls.submit(() -> parser.parse("synthetic request"));
        assertTrue(entered.await(3, TimeUnit.SECONDS), "Parser request should reach the controlled HTTP handler.");
        var plannerBusy = assertThrows(
            QwenItinerarySequencePlanner.QwenPlanningException.class,
            () -> planner.planSequence("start", candidates(), 2)
        );
        assertEquals(429, plannerBusy.getStatusCode());
        assertEquals(1, requestCount.get());
        release.countDown();
        first.get(3, TimeUnit.SECONDS);

        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        holdsRemaining.set(1);
        Future<?> second = calls.submit(() -> planner.planSequence("start", candidates(), 2));
        assertTrue(entered.await(3, TimeUnit.SECONDS), "Planner request should reach the controlled HTTP handler.");
        var parserBusy = assertThrows(
            com.carrental.exception.DomainException.class,
            () -> parser.parse("synthetic request")
        );
        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, parserBusy.status);
        assertEquals(2, requestCount.get());
        release.countDown();
        second.get(3, TimeUnit.SECONDS);
    }

    private QwenCriteriaParser parser() {
        return new QwenCriteriaParser(json, endpoint, Duration.ofSeconds(2), "qwen3:8b", capacity);
    }

    private QwenItinerarySequencePlanner planner() {
        return new QwenItinerarySequencePlanner(json, endpoint, Duration.ofSeconds(2), "qwen3:8b", true, capacity);
    }

    private byte[] response(byte[] requestBody) throws Exception {
        if (requestBody.length == 0) {
            return json.writeValueAsBytes(java.util.Map.of(
                "code", "Ok",
                "routes", List.of(java.util.Map.of(
                    "distance", 1000,
                    "duration", 300,
                    "legs", List.of(java.util.Map.of("distance", 1000, "duration", 300))
                ))
            ));
        }
        JsonNode request = json.readTree(requestBody);
        if (request.path("format").path("properties").has("intent")) {
            return envelope("{\"intent\":\"NEEDS_CLARIFICATION\",\"pickupDate\":null,\"returnDate\":null,"
                + "\"pickupBranchName\":null,\"returnBranchName\":null,\"passengers\":null,"
                + "\"budgetVndTotal\":null,\"transmission\":null,\"unresolvedFields\":[],\"conflicts\":[]}");
        }
        if (request.has("format")) {
            return envelope("{\"orderedLocationIds\":[\"start\",\"second\"],\"reasonCode\":\"TIME_EFFICIENCY\"}");
        }
        throw new IllegalStateException("Unknown test provider request.");
    }

    private byte[] envelope(String content) throws Exception {
        return json.writeValueAsBytes(java.util.Map.of(
            "done", true,
            "done_reason", "stop",
            "message", java.util.Map.of("role", "assistant", "content", content)
        ));
    }

    private static List<QwenItinerarySequencePlanner.Candidate> candidates() {
        return List.of(
            new QwenItinerarySequencePlanner.Candidate("start", "Start", "Museum"),
            new QwenItinerarySequencePlanner.Candidate("second", "Second", "Museum")
        );
    }

    private static List<OsrmRoutingClient.Point> points() {
        return List.of(new OsrmRoutingClient.Point(16.05, 108.2), new OsrmRoutingClient.Point(16.06, 108.21));
    }
}
