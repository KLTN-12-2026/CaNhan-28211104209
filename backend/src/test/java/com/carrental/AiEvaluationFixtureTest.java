package com.carrental;

import com.carrental.modules.ai.itinerary.service.QwenItinerarySequencePlanner;
import com.carrental.modules.ai.routing.OsrmRoutingClient;
import com.carrental.modules.location.service.VisitMetadata;
import com.carrental.modules.user.service.AccountService;
import com.carrental.security.TokenService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Test-only H2 fixture: real security/controller/services/persistence, loopback provider transports. */
@SpringBootTest(properties={"app.seed-enabled=false", "app.ai-recommendation-enabled=true",
    "app.qwen-parser-enabled=false", "app.gemini-parser-enabled=false",
    "app.jwt-secret=synthetic-evaluation-test-key-not-a-live-secret",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Import(AiEvaluationFixtureTest.TimeConfig.class)
class AiEvaluationFixtureTest {
    static final Instant NOW=Instant.parse("2027-02-01T00:00:00Z");
    static final String URL="jdbc:h2:mem:ai_evaluation_g24;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    @TestConfiguration static class TimeConfig {
        @Bean @Primary Clock evaluationClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        // No environment DB override: this class can only write to its own in-memory H2.
        r.add("spring.datasource.url",()->URL);r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired AccountService accounts;
    @Autowired com.carrental.modules.booking.repository.BookingRepository bookingRepository;
    @MockBean QwenItinerarySequencePlanner planner;
    @MockBean OsrmRoutingClient routing;

    Path repo=Path.of("..").toAbsolutePath().normalize();
    Path data=repo.resolve("scripts/ai/evaluation/scenarios-v1");
    JsonNode facts;
    Map<String,String> auth=new HashMap<>();

    String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    List<JsonNode> cases(String suite)throws Exception{
        var rows=new ArrayList<JsonNode>();
        for(String line:Files.readAllLines(data.resolve(suite+".jsonl"),StandardCharsets.UTF_8))rows.add(json.readTree(line));
        assertEquals(30,rows.size());return rows;
    }
    void initialize()throws Exception{
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
            assertEquals("jdbc:h2:mem:ai_evaluation_g24",connection.getMetaData().getURL());
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from users",Integer.class));
        for(String role:List.of("USER","STAFF","ADMIN")){
            String id="eval-"+role;
            jdbc.update("insert into users(id,email,password_hash,full_name,role,status) values(?,?,?,?,?,'ACTIVE')",id,id+"@example.invalid","not-a-login-hash","Synthetic evaluator",role);
            auth.put(role,tokens.issue(accounts.require(id)));
        }
        for(JsonNode b:facts.path("branches"))jdbc.update("insert into branches(id,name,address,status) values(?,?,?,?)",b.path("id").asText(),b.path("id").asText(),"Synthetic address",b.path("status").asText());
        for(JsonNode r:facts.path("routes"))jdbc.update("insert into branch_routes(id,pickup_branch_id,return_branch_id,fee,enabled) values(?,?,?,?,true)",r.path("pickup").asText()+"-"+r.path("returned").asText(),r.path("pickup").asText(),r.path("returned").asText(),r.path("fee").asLong());
        jdbc.update("insert into vehicle_brands(id,name) values('eval-brand','Synthetic brand')");
        jdbc.update("insert into vehicle_categories(id,name) values('eval-car','Synthetic car')");
        for(String a:List.of("wifi","gps","child"))jdbc.update("insert into amenities(id,name) values(?,?)",a,a);
        jdbc.update("insert into location_categories(id,name) values('culture','culture')");
    }
    void loadVehicles(JsonNode s){
        jdbc.update("delete from bookings");jdbc.update("delete from vehicle_amenities");jdbc.update("delete from vehicles");
        Set<String> selected=new HashSet<>();s.path("vehicleIds").forEach(x->selected.add(x.asText()));
        for(JsonNode v:facts.path("vehicles"))if(selected.contains(v.path("id").asText())){
            jdbc.update("insert into vehicles(id,name,plate,brand_id,category_id,home_branch_id,current_branch_id,seats,transmission,daily_price,status) values(?,?,?,'eval-brand','eval-car',?,?,?,?,?,?)",
                v.path("id").asText(),v.path("name").asText(),"EVAL-"+v.path("id").asText(),v.path("currentBranchId").asText(),v.path("currentBranchId").asText(),v.path("seats").asInt(),v.path("transmission").asText(),v.path("dailyPrice").asLong(),v.path("status").asText());
            for(JsonNode a:v.path("amenityIds"))jdbc.update("insert into vehicle_amenities(vehicle_id,amenity_id) values(?,?)",v.path("id").asText(),a.asText());
        }
        int n=0;
        for(JsonNode b:s.path("bookings")){
            Instant start=LocalDate.parse(b.path("pickupDate").asText()).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
            Instant end=LocalDate.parse(b.path("returnDate").asText()).plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().minusNanos(1000);
            Instant expires=b.path("expiresAt").isNull()?NOW.plusSeconds(7200):Instant.parse(b.path("expiresAt").asText());
            jdbc.update("insert into bookings(id,user_id,vehicle_id,pickup_branch_id,return_branch_id,start_at,end_at,created_at,expires_at,status,daily_price,rental_days,rental_total,one_way_fee,total,customer_name,vehicle_name,pickup_name,return_name,policy_version) values(?,'eval-USER',?,?,?,?,?,?,?,?,200000,2,400000,0,400000,'Synthetic','Synthetic','Synthetic','Synthetic','calendar-2026-10-17')",
                "eval-booking-"+n,b.path("vehicleId").asText(),b.path("pickupBranchId").asText(),b.path("returnBranchId").asText(),LocalDateTime.ofInstant(start,ZoneOffset.UTC),LocalDateTime.ofInstant(end,ZoneOffset.UTC),LocalDateTime.ofInstant(NOW.minusSeconds(86400),ZoneOffset.UTC),LocalDateTime.ofInstant(expires,ZoneOffset.UTC),b.path("status").asText());
            var readback=bookingRepository.findById("eval-booking-"+(n++)).orElseThrow();
            assertEquals(start,readback.startAt,"Fixture UTC start");assertEquals(end,readback.endAt,"Fixture UTC end");assertEquals(expires,readback.expiresAt,"Fixture UTC expiry");
        }
    }
    void loadLocations(JsonNode s)throws Exception{
        jdbc.update("delete from locations");
        for(JsonNode l:s.path("locations")){
            String meta=null;
            if(!l.path("metadata").isNull())meta=json.writeValueAsString(json.treeToValue(l.path("metadata"),VisitMetadata.class).validated(Clock.fixed(NOW,ZoneOffset.UTC)));
            jdbc.update("insert into locations(id,category_id,name,latitude,longitude,status,visit_metadata) values(?,?,?,?,?,?,?)",l.path("id").asText(),l.path("categoryId").asText(),l.path("name").asText(),l.path("latitude").decimalValue(),l.path("longitude").decimalValue(),l.path("status").asText(),meta);
        }
    }
    Map<String,Object> snapshot(){
        Map<String,Object> result=new TreeMap<>();
        for(String t:List.of("vehicles","vehicle_amenities","bookings","booking_status_history","itineraries","itinerary_days","itinerary_items","locations"))
            result.put(t,jdbc.queryForList("select * from "+t).toString());
        return result;
    }

    final class Providers implements AutoCloseable {
        HttpServer server; ExecutorService executor=Executors.newCachedThreadPool();
        AtomicInteger modelCalls=new AtomicInteger(),routeCalls=new AtomicInteger();
        QwenItinerarySequencePlanner qwen; OsrmRoutingClient osrm; Semaphore held;
        JsonNode scenario; String cause;
        Providers(JsonNode s)throws Exception{
            scenario=s;cause=s.path("provider").path("cause").asText("");
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(executor);
            server.createContext("/api/chat",exchange->{
                modelCalls.incrementAndGet();exchange.getRequestBody().readAllBytes();
                try{
                    if(cause.equals("QWEN_TIMEOUT"))Thread.sleep(600);
                    Object ordered=cause.equals("QWEN_INVALID_IDS")?List.of("a","outside-catalog"):json.convertValue(s.path("proposedOrder"),List.class);
                    String content=json.writeValueAsString(Map.of("orderedLocationIds",ordered,"reasonCode","QWEN_OPTIMIZED"));
                    byte[] bytes=json.writeValueAsBytes(Map.of("done",true,"message",Map.of("role","assistant","content",content)));
                    exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}
            });
            server.createContext("/route/v1/driving/",exchange->{
                routeCalls.incrementAndGet();
                try{
                    if(cause.equals("OSRM_UNAVAILABLE")){exchange.sendResponseHeaders(503,-1);return;}
                    String raw=exchange.getRequestURI().getPath().substring("/route/v1/driving/".length());
                    var ids=new ArrayList<String>();
                    for(String coordinates:raw.split(";")){
                        String[] xy=coordinates.split(",");String id=null;
                        for(JsonNode l:s.path("locations"))if(Math.abs(Double.parseDouble(xy[0])-l.path("longitude").asDouble())<0.0000001 && Math.abs(Double.parseDouble(xy[1])-l.path("latitude").asDouble())<0.0000001)id=l.path("id").asText();
                        if(id==null)throw new IllegalStateException("Unknown synthetic coordinate");ids.add(id);
                    }
                    var legs=new ArrayList<Map<String,Object>>();double distance=0,duration=0;boolean noRoute=false;
                    for(int i=0;i<ids.size()-1;i++){
                        JsonNode match=null;
                        for(JsonNode leg:s.path("legs"))if(leg.path("source").asText().equals(ids.get(i)) && leg.path("target").asText().equals(ids.get(i+1)))match=leg;
                        if(match==null)throw new IllegalStateException("Missing directed fixture leg");
                        if(match.path("status").asText().equals("NO_ROUTE")){noRoute=true;break;}
                        double d=match.path("distanceMeters").asDouble(),t=match.path("durationSeconds").asDouble();distance+=d;duration+=t;
                        legs.add(Map.of("distance",d,"duration",t));
                    }
                    byte[] bytes=json.writeValueAsBytes(noRoute?Map.of("code","NoRoute"):Map.of("code","Ok","routes",List.of(Map.of("distance",distance,"duration",duration,"legs",legs))));
                    exchange.sendResponseHeaders(noRoute?400:200,bytes.length);exchange.getResponseBody().write(bytes);
                }finally{exchange.close();}
            });
            server.start();
            URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            var qc=QwenItinerarySequencePlanner.class.getDeclaredConstructor(ObjectMapper.class,URI.class,Duration.class,String.class,boolean.class);qc.setAccessible(true);
            qwen=qc.newInstance(json,base.resolve("/api/chat"),Duration.ofMillis(cause.equals("QWEN_TIMEOUT")?150:5000),"qwen3:8b",!cause.equals("QWEN_DISABLED"));
            var oc=OsrmRoutingClient.class.getDeclaredConstructor(ObjectMapper.class,URI.class);oc.setAccessible(true);osrm=oc.newInstance(json,base);
            if(cause.equals("QWEN_CAPACITY")){held=(Semaphore)ReflectionTestUtils.getField(qwen,"capacity");assertTrue(held.tryAcquire());}
            when(planner.planSequence(anyString(),anyList(),anyInt())).thenAnswer(a->qwen.planSequence(a.getArgument(0),a.getArgument(1),a.getArgument(2)));
            when(routing.route(anyList())).thenAnswer(a->osrm.route(a.getArgument(0)));
        }
        boolean proof(){
            if(cause.isEmpty())return false;
            if(cause.equals("QWEN_DISABLED") || cause.equals("QWEN_CAPACITY")){assertEquals(0,modelCalls.get());assertEquals(0,routeCalls.get());}
            else if(cause.startsWith("QWEN_")){assertEquals(1,modelCalls.get());assertEquals(0,routeCalls.get());}
            else{assertEquals(1,modelCalls.get());assertEquals(1,routeCalls.get());}
            var capacity=(Semaphore)ReflectionTestUtils.getField(qwen,"capacity");assertEquals(held==null?1:0,capacity.availablePermits());
            return true;
        }
        public void close()throws Exception{
            if(held!=null){held.release();assertEquals(1,held.availablePermits());}
            server.stop(0);executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));
        }
    }

    @Test void captureRealApiResponsesWithIsolatedSyntheticFacts()throws Exception{
        String destination=System.getProperty("ai.eval.output");Path output=null;
        if(destination!=null){output=Path.of(destination).toAbsolutePath().normalize();assertTrue(output.startsWith(repo.resolve(".runtime")));assertFalse(Files.exists(output),"Refuse existing capture directory");Files.createDirectories(output);}
        facts=json.readTree(data.resolve("facts.json").toFile());initialize();
        var observations=new ArrayList<ObjectNode>();
        for(String suite:List.of("recommendation","itinerary"))for(JsonNode c:cases(suite)){
            String id=c.path("caseId").asText();JsonNode s=facts.path("scenarios").path(c.path("fixtureRef").asText());
            reset(planner,routing);if(suite.equals("recommendation"))loadVehicles(s);else loadLocations(s);
            try(Providers provider=suite.equals("itinerary")?new Providers(s):null){
                var before=snapshot();var request=post(suite.equals("recommendation")?"/api/v1/ai/recommendations":"/api/v1/ai/itineraries/preview").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(c.path("input")));
                String actor=c.path("actor").asText();if(!actor.equals("GUEST"))request.header("Authorization","Bearer "+auth.get(actor));
                long begin=System.nanoTime();var response=mvc.perform(request).andReturn().getResponse();double ms=(System.nanoTime()-begin)/1_000_000.0;
                assertEquals(before,snapshot(),id+" must not mutate persistence");
                boolean causeConfirmed=provider!=null && provider.proof();
                ObjectNode row=json.createObjectNode();row.put("caseId",id);row.put("httpStatus",response.getStatus());row.set("body",json.readTree(response.getContentAsByteArray()));row.put("durationMs",ms);row.putNull("error");
                if(causeConfirmed)row.put("causeCode",s.path("provider").path("cause").asText());else row.putNull("causeCode");
                row.set("evidence",json.valueToTree(Map.of("fixtureVerified",true,"noMutation",true,"causeConfirmed",causeConfirmed)));observations.add(row);
            }
        }
        assertEquals(60,observations.size());assertEquals(60,observations.stream().map(o->o.path("caseId").asText()).distinct().count());
        assertEquals(5,observations.stream().filter(o->o.path("evidence").path("causeConfirmed").asBoolean()).count());
        assertTrue(observations.stream().noneMatch(o->o.path("httpStatus").asInt()>=500),"Unexpected API server error");
        if(output!=null){
            var lines=new ArrayList<String>();for(var row:observations)lines.add(json.writeValueAsString(row));
            Files.write(output.resolve("observations.jsonl"),lines,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            var hashes=new TreeMap<String,String>();hashes.put("manifest.json",hash(data.resolve("manifest.json")));
            JsonNode manifest=json.readTree(data.resolve("manifest.json").toFile());
            for(JsonNode e:manifest.path("datasets"))hashes.put(e.path("path").asText(),hash(data.resolve(e.path("path").asText())));
            hashes.put("facts.json",hash(data.resolve("facts.json")));
            var sources=new TreeMap<String,String>();
            try(var paths=Files.walk(repo.resolve("backend/src/main"))){for(Path p:paths.filter(Files::isRegularFile).toList())sources.put(repo.relativize(p).toString().replace('\\','/'),hash(p));}
            for(String p:List.of("backend/pom.xml","backend/src/test/java/com/carrental/AiEvaluationFixtureTest.java"))sources.put(p,hash(repo.resolve(p)));
            var git=new ProcessBuilder("git","rev-parse","HEAD").directory(repo.toFile()).start();String revision=new String(git.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();assertEquals(0,git.waitFor());
            Map<String,Object> capture=new LinkedHashMap<>();capture.put("schemaVersion","kdrive-ai-capture-v1");capture.put("captureKind","spring-h2-synthetic");capture.put("datasetHashes",hashes);capture.put("sourceRevision",revision);capture.put("sourceHashes",sources);
            capture.put("observationsPath","observations.jsonl");capture.put("observationsSha256",hash(output.resolve("observations.jsonl")));capture.put("model","synthetic");capture.put("graph","synthetic");capture.put("clockInstant",NOW.toString());
            Files.writeString(output.resolve("capture.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(capture),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        }
    }
}
