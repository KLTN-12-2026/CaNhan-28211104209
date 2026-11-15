package com.carrental.modules.ai.parser;

import com.carrental.exception.DomainException;
import com.carrental.modules.ai.AiDiagnostics;
import com.carrental.modules.ai.AiProviderCapacity;
import com.carrental.modules.ai.AiRequestDeadline;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class QwenCriteriaParserTest {
    private final ObjectMapper json=new ObjectMapper();
    private final AiDiagnostics diagnostics=new AiDiagnostics();
    private HttpServer server;
    private final AtomicInteger calls=new AtomicInteger();
    private String requestBody,requestKey,requestPath;
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    private QwenCriteriaParser parser(int status,String response,Duration deadline) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            calls.incrementAndGet();requestBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);requestKey=exchange.getRequestHeaders().getFirst("x-goog-api-key");requestPath=exchange.getRequestURI().toString();
            if(response.equals("SLOW"))Thread.sleep(350);
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception ignored){}finally{exchange.close();}});server.start();
        return new QwenCriteriaParser(json,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/model"),
            deadline,"qwen3:8b",new AiProviderCapacity(),diagnostics);
    }
    private String envelope(String text)throws Exception{return json.writeValueAsString(Map.of("done",true,"done_reason","stop","message",Map.of("role","assistant","content",text)));}
    private String valid(){return "{\"intent\":\"RECOMMEND_VEHICLE\",\"passengers\":4,\"budgetVndTotal\":3000000,\"pickupBranchName\":\"Đà Nẵng\",\"unresolvedFields\":[\"pickupDate\",\"returnDate\"],\"conflicts\":[]}";}
    @Test void typedExtractionFixedContractNoToolsOrSecretInUrl()throws Exception{
        var p=parser(200,envelope(valid()),Duration.ofSeconds(2));var result=p.parse("4 người, ngân sách 3 triệu, nhận Đà Nẵng");
        assertEquals(4,result.passengers());assertEquals(3000000,result.budgetVndTotal());assertEquals("Đà Nẵng",result.pickupBranchName());assertNull(result.pickupDate());
        assertEquals(1,calls.get());assertNull(requestKey);
        assertEquals(AiDiagnostics.Cause.SUCCESS,diagnostics.snapshot().get(0).cause());
        var body=json.readTree(requestBody);assertFalse(body.has("tools"));assertFalse(body.path("think").asBoolean());assertFalse(body.path("stream").asBoolean());assertEquals("qwen3:8b",body.path("model").asText());assertTrue(body.path("format").isObject());
        var unresolved=body.at("/format/properties/unresolvedFields");
        assertEquals(7,unresolved.path("maxItems").intValue());
        assertEquals(json.readTree("[\"pickupDate\",\"returnDate\",\"pickupBranchName\",\"returnBranchName\",\"passengers\",\"budgetVndTotal\",\"transmission\"]"),unresolved.at("/items/enum"));
    }
    @Test void invalidInputDoesNotCallProvider()throws Exception{
        var p=parser(200,envelope(valid()),Duration.ofSeconds(2));for(String text:Arrays.asList(null," ","x".repeat(1001)))assertEquals(400,assertThrows(DomainException.class,()->p.parse(text)).status.value());assertEquals(0,calls.get());
    }
    @Test void requestSchemaAllowsOnlyMentionedBranchNamesOrNull()throws Exception{
        var p=parser(200,envelope("{\"decision\":\"RENTAL\",\"extraction\":"+valid()+"}"),Duration.ofSeconds(2));
        p.parse("nhận tại Đà Nẵng",AiRequestDeadline.start(AiRequestDeadline.Operation.CRITERIA_EXTRACTION),List.of("Đà Nẵng"));
        var schema=json.readTree(requestBody).at("/format/oneOf/1/properties/extraction/properties");
        var expected=json.readTree("[\"Đà Nẵng\",null]");
        assertEquals(json.readTree("[\"Đà Nẵng\"]"),schema.at("/pickupBranchName/enum"));
        assertEquals(expected,schema.at("/returnBranchName/enum"));
        assertEquals(json.readTree("[null]"),schema.at("/transmission/enum"));
    }
    @Test void conflictDecisionNeedsNoSecondCallOrInventedDraft()throws Exception{
        var p=parser(200,envelope("{\"decision\":\"CONFLICT\"}"),Duration.ofSeconds(2));
        var result=p.parse("Deux exigences",AiRequestDeadline.start(AiRequestDeadline.Operation.CRITERIA_EXTRACTION),List.of());
        assertTrue(!result.conflicts().isEmpty());
        assertNull(result.passengers());assertNull(result.pickupBranchName());
        assertEquals(1,calls.get());
    }
    @Test void typedRentalRequestRestrictsExplicitGearboxAndTotal()throws Exception{
        var p=parser(200,envelope("{\"decision\":\"RENTAL\",\"extraction\":"+valid()+"}"),Duration.ofSeconds(2));
        p.parse("Xe số tay, ngân sách tổng 3.200.000 đồng",AiRequestDeadline.start(AiRequestDeadline.Operation.CRITERIA_EXTRACTION),List.of());
        var schema=json.readTree(requestBody).at("/format/oneOf/1/properties/extraction/properties");
        assertEquals(json.readTree("[\"MANUAL\"]"),schema.at("/transmission/enum"));
        assertEquals(json.readTree("[3200000]"),schema.at("/budgetVndTotal/enum"));
        assertFalse(schema.at("/unresolvedFields/items/enum").toString().contains("budgetVndTotal"));
    }
    @Test void conflictDecisionCannotSmuggleAnExtraction()throws Exception{
        var p=parser(200,envelope("{\"decision\":\"CONFLICT\",\"extraction\":"+valid()+"}"),Duration.ofSeconds(2));
        assertEquals(503,assertThrows(DomainException.class,()->p.parse("synthetic",AiRequestDeadline.start(AiRequestDeadline.Operation.CRITERIA_EXTRACTION),List.of())).status.value());
    }
    @Test void errorsAreSanitizedAndNeverRetried()throws Exception{
        var p=parser(429,"raw-provider-error synthetic-test-key",Duration.ofSeconds(2));var error=assertThrows(DomainException.class,()->p.parse("tìm xe"));assertEquals(503,error.status.value());assertFalse(error.getMessage().contains("synthetic-test-key"));assertEquals(1,calls.get());
        assertEquals(AiDiagnostics.Cause.PROVIDER_HTTP,diagnostics.snapshot().get(0).cause());
    }
    @Test void redirectsAreNotFollowed()throws Exception{
        var p=parser(302,"redirect",Duration.ofSeconds(2));assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());assertEquals(1,calls.get());
    }
    @Test void deadlineFailsClosed()throws Exception{
        var p=parser(200,"SLOW",Duration.ofMillis(80));long start=System.nanoTime();assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(2))<0);
        assertEquals(AiDiagnostics.Cause.PROVIDER_TIMEOUT,diagnostics.snapshot().get(0).cause());
    }
    @Test void blockedAndToolOutputsAreRejected()throws Exception{
        var p=parser(200,"{\"candidates\":[{\"finishReason\":\"SAFETY\",\"content\":{\"parts\":[{\"functionCall\":{}}]}}]}",Duration.ofSeconds(2));assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());
    }
    @Test void unknownFieldsAndInvalidNumbersCannotBecomeCriteria()throws Exception{
        var p=parser(200,envelope(valid().replace("\"passengers\":4","\"passengers\":4,\"total\":1")),Duration.ofSeconds(2));assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());
        var event=diagnostics.snapshot().get(0);assertEquals(AiDiagnostics.SchemaStage.DTO_BIND,event.schemaStage());assertEquals(AiDiagnostics.SchemaField.NONE,event.schemaField());
    }
    @Test void fractionalBudgetRejected()throws Exception{
        var p=parser(200,envelope(valid().replace("3000000","3.5")),Duration.ofSeconds(2));assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());
        var event=diagnostics.snapshot().get(0);assertEquals(AiDiagnostics.SchemaStage.NUMERIC_TYPE,event.schemaStage());assertEquals(AiDiagnostics.SchemaField.BUDGET_VND_TOTAL,event.schemaField());
    }
    @Test void malformedDateRejected()throws Exception{
        var p=parser(200,envelope(valid().replace("\"passengers\":4","\"pickupDate\":\"2026-02-30\",\"passengers\":4")),Duration.ofSeconds(2));
        var error=assertThrows(DomainException.class,()->p.parse("tìm xe"));assertEquals(503,error.status.value());
        assertEquals("Kết quả đọc nhu cầu không hợp lệ; hãy dùng biểu mẫu.",error.getMessage());
        var event=diagnostics.snapshot().get(0);assertEquals(AiDiagnostics.SchemaStage.EXTRACTION_VALIDATION,event.schemaStage());assertEquals(AiDiagnostics.SchemaField.PICKUP_DATE,event.schemaField());
    }
    @Test void malformedJsonIsClassifiedWithoutEchoingProviderContent()throws Exception{
        var logger=(Logger)LoggerFactory.getLogger(AiDiagnostics.class);var appender=new ListAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());appender.start();logger.addAppender(appender);
        try{
            var p=parser(200,envelope("{malformed-provider-content-sentinel"),Duration.ofSeconds(2));
            assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());
            var event=diagnostics.snapshot().get(0);assertEquals(AiDiagnostics.SchemaStage.JSON_PARSE,event.schemaStage());assertEquals(AiDiagnostics.SchemaField.NONE,event.schemaField());
            String log=appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(a,b)->a+"\n"+b);
            assertTrue(log.contains("schema_stage=JSON_PARSE"));assertFalse(log.contains("malformed-provider-content-sentinel"));
        }finally{logger.detachAppender(appender);appender.stop();}
    }
    @Test void schemaDiagnosticsContainOnlyAllowlistedLabels()throws Exception{
        var logger=(Logger)LoggerFactory.getLogger(AiDiagnostics.class);var appender=new ListAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());appender.start();logger.addAppender(appender);
        try{
            String secret="schema-value-sentinel";
            var p=parser(200,envelope(valid().replace("3000000","\""+secret+"\"")),Duration.ofSeconds(2));
            assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());
            var event=diagnostics.snapshot().get(0);
            assertEquals(AiDiagnostics.SchemaStage.NUMERIC_TYPE,event.schemaStage());
            assertEquals(AiDiagnostics.SchemaField.BUDGET_VND_TOTAL,event.schemaField());
            String log=appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(a,b)->a+"\n"+b);
            assertTrue(log.contains("schema_field=BUDGET_VND_TOTAL"));assertFalse(log.contains(secret));
        }finally{logger.detachAppender(appender);appender.stop();}
    }
    @Test void configRejectsRemoteEndpoint(){
        assertThrows(IllegalArgumentException.class,()->new QwenCriteriaParser(json,URI.create("http://example.com/api/chat"),Duration.ofSeconds(1)));
    }
    @Test void beanIsOptInAndStartsWithoutCloudCredentials(){
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean(ObjectMapper.class,ObjectMapper::new)
            .withBean(AiDiagnostics.class,AiDiagnostics::new)
            .withBean(AiProviderCapacity.class,AiProviderCapacity::new)
            .withUserConfiguration(QwenCriteriaParser.class);
        runner.run(context->assertFalse(context.containsBean("qwenCriteriaParser")));
        runner.withPropertyValues("app.qwen-parser-enabled=true").run(context->{assertNull(context.getStartupFailure());assertEquals(1,context.getBeansOfType(CriteriaParser.class).size());});
    }
    @Test void rejectsUnknownModelAndLimitsConcurrentCalls() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new QwenCriteriaParser(json,"qwen3:cloud"));
        var p=parser(200,"SLOW",Duration.ofSeconds(2));
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->assertThrows(DomainException.class,()->p.parse("xe")));
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            while(calls.get()==0&&System.nanoTime()<until)Thread.sleep(5);
            assertEquals(1,calls.get());assertEquals(429,assertThrows(DomainException.class,()->p.parse("xe khác")).status.value());
            first.get();assertEquals(503,assertThrows(DomainException.class,()->p.parse("xe tiếp")).status.value());
        }
    }
    @Test void validIsoDatesRoundTrip() throws Exception {
        var text=valid().replace("\"passengers\":4","\"pickupDate\":\"2100-10-20\",\"returnDate\":\"2100-10-24\",\"passengers\":4");
        var p=parser(200,envelope(text),Duration.ofSeconds(2));assertEquals("2100-10-20",p.parse("20/10/2100 đến24/10/2100").pickupDate());
        assertEquals("^[0-9]{4}-[0-9]{2}-[0-9]{2}$",json.readTree(requestBody).path("format").path("properties").path("pickupDate").path("pattern").asText());
    }
    @Test void nonIsoDatesRejected() throws Exception {
        var p=parser(200,envelope(valid().replace("\"passengers\":4","\"pickupDate\":\"20/10/2100\",\"passengers\":4")),Duration.ofSeconds(2));assertEquals(503,assertThrows(DomainException.class,()->p.parse("20/10/2100")).status.value());
    }
    @Test void oversizedProviderBodyHasItsOwnCause()throws Exception{
        var p=parser(200,"x".repeat(17000),Duration.ofSeconds(2));
        assertEquals(503,assertThrows(DomainException.class,()->p.parse("tìm xe")).status.value());
        assertEquals(AiDiagnostics.Cause.RESPONSE_TOO_LARGE,diagnostics.snapshot().get(0).cause());
    }
}
