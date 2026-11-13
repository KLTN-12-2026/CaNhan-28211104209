package com.carrental.modules.ai;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalHttpTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService serverExecutor;
    private CountDownLatch bodyStarted;
    private CountDownLatch release;
    private CountDownLatch handlerFinished;
    private AtomicInteger requests;

    @AfterEach
    void stop() {
        if (release != null) {
            release.countDown();
        }
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    private HttpRequest request(boolean stallHeaders, boolean stallBody, byte[] body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        bodyStarted = new CountDownLatch(1);
        release = new CountDownLatch(1);
        handlerFinished = new CountDownLatch(1);
        requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                if (stallHeaders && !release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Header release latch timed out.");
                }
                exchange.sendResponseHeaders(200, 0);
                if (stallBody) {
                    exchange.getResponseBody().write(body, 0, Math.min(1, body.length));
                    exchange.getResponseBody().flush();
                    bodyStarted.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Body release latch timed out.");
                    }
                    exchange.getResponseBody().write(body);
                } else {
                    exchange.getResponseBody().write(body);
                }
            } catch (IOException ignored) {
                // Client cancellation closes the streaming response.
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                handlerFinished.countDown();
            }
        });
        server.start();
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
    }

    @Test
    void expiredDeadlineDoesNotDispatchAnHttpRequest() throws Exception {
        var request = request(false, false, new byte[] {1});
        AtomicInteger tickerReads = new AtomicInteger();
        var deadline = AiRequestDeadline.withTicker(Duration.ofSeconds(1),
            () -> tickerReads.incrementAndGet() <= 2 ? 0 : Duration.ofSeconds(2).toNanos());

        assertThrows(AiRequestDeadline.DeadlineExceededException.class,
            () -> LocalHttp.send(HttpClient.newHttpClient(), request, Duration.ofSeconds(1), 1024, deadline));
        assertEquals(0, requests.get(), "An expired deadline must be rejected before task submission or HTTP dispatch.");
    }

    @Test
    void responseBodyCannotExceedByteLimit() throws Exception {
        var request = request(false, false, new byte[32]);
        assertThrows(LocalHttp.ResponseTooLargeException.class,
            () -> LocalHttp.send(HttpClient.newHttpClient(), request, Duration.ofSeconds(2), 16));
    }

    @Test
    void providerTimeoutOnStalledHeadersKeepsProviderCauseWhileBudgetRemains() throws Exception {
        var request = request(true, false, new byte[] {1});
        var deadline = AiRequestDeadline.start(Duration.ofMillis(1800), AiRequestDeadline.Operation.ROUTE_PREVIEW);
        long startedAt = System.nanoTime();

        var error = assertThrows(TimeoutException.class,
            () -> LocalHttp.send(HttpClient.newHttpClient(), request, Duration.ofMillis(1100), 1024, deadline));

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue(deadline.remaining().toMillis() > 0, "The overall request budget must still remain.");
        assertEquals(AiDiagnostics.Cause.PROVIDER_TIMEOUT, AiDiagnostics.transportCause(error));
        assertTrue(elapsedMillis < 1600, "The provider cap should bound a stalled response header.");
        release.countDown();
        assertTrue(handlerFinished.await(2, TimeUnit.SECONDS));
    }

    @Test
    void providerTimeoutOnStalledBodyClosesStreamAndReturnsWithinBound() throws Exception {
        var request = request(false, true, new byte[32]);
        var deadline = AiRequestDeadline.start(Duration.ofMillis(1800), AiRequestDeadline.Operation.ROUTE_PREVIEW);
        long startedAt = System.nanoTime();

        var error = assertThrows(TimeoutException.class,
            () -> LocalHttp.send(HttpClient.newHttpClient(), request, Duration.ofMillis(1100), 1024, deadline));

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));
        assertTrue(deadline.remaining().toMillis() > 0);
        assertEquals(AiDiagnostics.Cause.PROVIDER_TIMEOUT, AiDiagnostics.transportCause(error));
        assertTrue(elapsedMillis < 1600, "Cancellation and executor cleanup must remain bounded.");
        release.countDown();
        assertTrue(handlerFinished.await(2, TimeUnit.SECONDS), "The cancelled response handler should close.");
    }

    @Test
    void requestDeadlineWinsWhenProviderCapExceedsRemainingBudget() throws Exception {
        var request = request(false, true, new byte[32]);
        var deadline = AiRequestDeadline.start(Duration.ofMillis(150), AiRequestDeadline.Operation.ROUTE_PREVIEW);
        long startedAt = System.nanoTime();

        var error = assertThrows(AiRequestDeadline.DeadlineExceededException.class,
            () -> LocalHttp.send(HttpClient.newHttpClient(), request, Duration.ofSeconds(2), 1024, deadline));

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue(elapsedMillis < 1200, "The request budget must bound the stalled response body.");
        assertEquals(AiDiagnostics.Cause.DEADLINE, AiDiagnostics.transportCause(error));
        release.countDown();
        assertTrue(handlerFinished.await(2, TimeUnit.SECONDS));
    }

    @Test
    void sufficientBudgetAllowsSuccessfulResponse() throws Exception {
        var request = request(false, false, new byte[] {1, 2, 3});
        var deadline = AiRequestDeadline.start(Duration.ofSeconds(2), AiRequestDeadline.Operation.ROUTE_PREVIEW);

        var response = LocalHttp.send(HttpClient.newHttpClient(), request, Duration.ofSeconds(1), 16, deadline);

        assertEquals(200, response.status());
        assertEquals(3, response.body().length());
        assertTrue(handlerFinished.await(1, TimeUnit.SECONDS));
    }
}
