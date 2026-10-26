package com.carrental.modules.ai.itinerary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QwenItinerarySequencePlannerTest {
    final ObjectMapper json = new ObjectMapper();
    final List<QwenItinerarySequencePlanner.Candidate> pool = List.of(
        new QwenItinerarySequencePlanner.Candidate("id-a", "Test A", "Culture"),
        new QwenItinerarySequencePlanner.Candidate("id-b", "Test B", "Culture"));
    final String valid = "{\"orderedLocationIds\":[\"id-a\",\"id-b\"],\"reasonCode\":\"PROXIMITY_FLOW\"}";

    @Test void realHttpStrictJsonAndIds() throws Exception {
        AtomicReference<String> content = new AtomicReference<>(valid);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] payload = json.writeValueAsBytes(java.util.Map.of("done", true, "message", java.util.Map.of("role", "assistant", "content", content.get())));
            exchange.sendResponseHeaders(200, payload.length);exchange.getResponseBody().write(payload);exchange.close();
        });
        server.start();
        try {
            var planner = new QwenItinerarySequencePlanner(json, URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/chat"), Duration.ofSeconds(2), "qwen3:8b", true);
            assertEquals(List.of("id-a","id-b"), planner.planSequence("id-a", pool, 2).orderedLocationIds());
            for (String invalid : List.of("not json", valid.substring(0,valid.length()-1)+",\"extra\":true}",
                valid.replace("id-b", "outside"), valid.replace("id-b", "id-a"),
                valid.replace("[\"id-a\",\"id-b\"]", "[\"id-b\",\"id-a\"]"),
                valid.replace("PROXIMITY_FLOW", "INVENTED_REASON"),
                valid.replace("[\"id-a\",\"id-b\"]", "[\"id-a\"]"))) {
                content.set(invalid);
                assertEquals(503, assertThrows(QwenItinerarySequencePlanner.QwenPlanningException.class, () -> planner.planSequence("id-a", pool, 2)).getStatusCode());
            }
        } finally {server.stop(0);}
    }

    @Test void capacityAndDisabledDoNotFallback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService worker=Executors.newCachedThreadPool();server.setExecutor(worker);
        server.createContext("/api/chat", exchange -> {
            entered.countDown();
            try {release.await(5, TimeUnit.SECONDS);byte[] body=json.writeValueAsBytes(java.util.Map.of("done",true,"message",java.util.Map.of("role","assistant","content",valid)));exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}
            catch (InterruptedException e) {Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });server.start();
        try {
            URI endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/chat");
            var planner=new QwenItinerarySequencePlanner(json,endpoint,Duration.ofSeconds(2),"qwen3:8b",true);
            Future<?> first=worker.submit(() -> planner.planSequence("id-a",pool,2));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals(429,assertThrows(QwenItinerarySequencePlanner.QwenPlanningException.class,()->planner.planSequence("id-a",pool,2)).getStatusCode());
            release.countDown();assertNotNull(first.get(3,TimeUnit.SECONDS));
            var disabled=new QwenItinerarySequencePlanner(json,endpoint,Duration.ofMillis(50),"qwen3:8b",false);
            assertEquals(503,assertThrows(QwenItinerarySequencePlanner.QwenPlanningException.class,()->disabled.planSequence("id-a",pool,2)).getStatusCode());
        } finally {release.countDown();server.stop(0);worker.shutdownNow();}
    }

    @Test void wholeCallDeadlineRejectsStalledHttpResponse() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService worker=Executors.newCachedThreadPool();server.setExecutor(worker);
        CountDownLatch release=new CountDownLatch(1);
        server.createContext("/api/chat",exchange->{
            try {release.await(3,TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally{exchange.close();}
        });server.start();
        try {
            var planner=new QwenItinerarySequencePlanner(json,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/chat"),Duration.ofMillis(100),"qwen3:8b",true);
            long started=System.nanoTime();
            assertEquals(503,assertThrows(QwenItinerarySequencePlanner.QwenPlanningException.class,()->planner.planSequence("id-a",pool,2)).getStatusCode());
            assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<1500,"deadline must not wait for stalled upstream");
        } finally {release.countDown();server.stop(0);worker.shutdownNow();}
    }
}
