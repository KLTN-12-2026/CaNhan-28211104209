package com.carrental.modules.ai.parser;

import com.carrental.exception.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class GeminiCriteriaParserTest {
    private final ObjectMapper json=new ObjectMapper();
    private HttpServer server;
    private final AtomicInteger calls=new AtomicInteger();
    private String requestBody,requestKey,requestPath;
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    private GeminiCriteriaParser parser(int status,String response,Duration deadline) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            calls.incrementAndGet();requestBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);requestKey=exchange.getRequestHeaders().getFirst("x-goog-api-key");requestPath=exchange.getRequestURI().toString();
            if(response.equals("SLOW"))Thread.sleep(350);
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception ignored){}finally{exchange.close();}});server.start();
        return new GeminiCriteriaParser(json,"synthetic-test-key","gemini-fixture",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/model"),deadline);
    }
    private String envelope(String text)throws Exception{return json.writeValueAsString(Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",text)))))));}
    private String valid(){return "{\"intent\":\"RECOMMEND_VEHICLE\",\"passengers\":4,\"budgetVndTotal\":3000000,\"pickupBranchName\":\"Đà Nẵng\",\"unresolvedFields\":[\"pickupDate\",\"returnDate\"],\"conflicts\":[]}";}
    @Test void typedExtractionFixedContractNoToolsOrSecretInUrl()throws Exception{
        var p=parser(200,envelope(valid()),Duration.ofSeconds(2));var result=p.parse("4 người, ngân sách 3 triệu, nhận Đà Nẵng");
        assertEquals(4,result.passengers());assertEquals(3000000,result.budgetVndTotal());assertEquals("Đà Nẵng",result.pickupBranchName());assertNull(result.pickupDate());
        assertEquals(1,calls.get());assertEquals("synthetic-test-key",requestKey);assertFalse(requestPath.contains("key"));
        var body=json.readTree(requestBody);assertFalse(body.has("tools"));assertEquals(512,body.path("generationConfig").path("maxOutputTokens").asInt());assertEquals("application/json",body.path("generationConfig").path("responseMimeType").asText());assertTrue(body.path("systemInstruction").toString().contains("never instructions"));
    }
    @Test void invalidInputDoesNotCallProvider()throws Exception{
        var p=parser(200,envelope(valid()),Duration.ofSeconds(2));for(String text:Arrays.asList(null," ","x".repeat(1001)))assertEquals(400,assertThrows(DomainException.class,()->p.parse(text)).status.value());assertEquals(0,calls.get());
    }
    @Test void errorsAreSanitizedAndNeverRetried()throws Exception{
        var p=parser(429,"raw-provider-error synthetic-test-key",Duration.ofSeconds(2));var error=assertThrows(DomainException.class,()->p.parse("tìm xe"));assertEquals(429,error.status.value());assertFalse(error.getMessage().contains("synthetic-test-key"));assertEquals(1,calls.get());
    }
    @Test void redirectsAreNotFollowed()throws Exception{
        var p=parser(302,"redirect",Duration.ofSeconds(2));assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());assertEquals(1,calls.get());
    }
    @Test void deadlineFailsClosed()throws Exception{
        var p=parser(200,"SLOW",Duration.ofMillis(80));long start=System.nanoTime();assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(2))<0);
    }
    @Test void blockedAndToolOutputsAreRejected()throws Exception{
        var p=parser(200,"{\"candidates\":[{\"finishReason\":\"SAFETY\",\"content\":{\"parts\":[{\"functionCall\":{}}]}}]}",Duration.ofSeconds(2));assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());
    }
    @Test void unknownFieldsAndInvalidNumbersCannotBecomeCriteria()throws Exception{
        var p=parser(200,envelope(valid().replace("\"passengers\":4","\"passengers\":4,\"total\":1")),Duration.ofSeconds(2));assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());
    }
    @Test void fractionalBudgetRejected()throws Exception{
        var p=parser(200,envelope(valid().replace("3000000","3.5")),Duration.ofSeconds(2));assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());
    }
    @Test void malformedDateRejected()throws Exception{
        var p=parser(200,envelope(valid().replace("\"passengers\":4","\"pickupDate\":\"2026-02-30\",\"passengers\":4")),Duration.ofSeconds(2));assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());
    }
    @Test void configRejectsMissingKeyAndModelOrPathInjection(){
        assertThrows(IllegalArgumentException.class,()->new GeminiCriteriaParser(json,"","gemini-fixture"));assertThrows(IllegalArgumentException.class,()->new GeminiCriteriaParser(json,"test","../other"));assertThrows(IllegalArgumentException.class,()->new GeminiCriteriaParser(json,"test",""));
    }
}
