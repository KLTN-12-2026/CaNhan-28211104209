package com.carrental;

import com.carrental.modules.user.service.AccountService;
import com.carrental.security.TokenService;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit integration entrypoint, not a default Surefire test: never downloads or starts a provider. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "app.seed-enabled=false","app.qwen-parser-enabled=true","app.qwen-model=qwen3:8b",
    "app.gemini-parser-enabled=false","app.token-minutes=120","app.jwt-secret=live-baseline-synthetic-auth-key-not-production",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"})
@Import(AiLiveBaselineIT.TimeConfig.class)
class AiLiveBaselineIT {
    @TestConfiguration static class TimeConfig {
        @Bean @Primary Clock baselineClock(){return Clock.fixed(Instant.parse("2027-02-01T00:00:00Z"),ZoneOffset.UTC);}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:h2:mem:ai_live_baseline;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AccountService accounts;
    @Autowired TokenService tokens;
    @LocalServerPort int port;
    Path repo=Path.of("..").toAbsolutePath().normalize();
    String hash(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    Map<String,Object> snapshot(){
        Map<String,Object> s=new TreeMap<>();
        for(String t:List.of("users","branches","bookings","booking_status_history","itineraries","itinerary_days","itinerary_items"))s.put(t,jdbc.queryForList("select * from "+t).toString());
        return s;
    }
    @Test void parserDevQualityAndPerformanceThroughRealQwen()throws Exception{
        assertEquals("1",System.getenv("KDRIVE_EVAL_LIVE"),"Explicit live run required");
        String output=Objects.requireNonNull(System.getProperty("ai.eval.output"));
        Path out=Path.of(output).toAbsolutePath().normalize();assertTrue(out.startsWith(repo.resolve(".runtime")));assertFalse(Files.exists(out));
        try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){assertEquals("jdbc:h2:mem:ai_live_baseline",c.getMetaData().getURL());}
        Path data=repo.resolve("scripts/ai/evaluation/parser-v1");JsonNode facts=json.readTree(data.resolve("facts.json").toFile());
        assertEquals(0,jdbc.queryForObject("select count(*) from branches",Integer.class));
        for(JsonNode b:facts.path("branches")){
            jdbc.update("insert into branches(id,name,address,status) values(?,?,?,?)",b.path("id").asText(),b.path("name").asText(),"Synthetic parser fixture",b.path("status").asText());
            assertEquals(b.path("name").asText(),jdbc.queryForObject("select name from branches where id=?",String.class,b.path("id").asText()));
            assertEquals(b.path("status").asText(),jdbc.queryForObject("select status from branches where id=?",String.class,b.path("id").asText()));
        }
        assertEquals(facts.path("branches").size(),jdbc.queryForObject("select count(*) from branches",Integer.class));
        jdbc.update("insert into users(id,email,password_hash,full_name,role,status) values('eval-live-user','synthetic@example.invalid','not-a-login-hash','Synthetic baseline','USER','ACTIVE')");
        Map<String,String> hashes=new TreeMap<>(),sources=new TreeMap<>();
        for(String p:List.of("manifest.json","facts.json","dev.jsonl","heldout.jsonl"))hashes.put(p,hash(data.resolve(p)));
        try(var files=Files.walk(repo.resolve("backend/src/main"))){for(Path p:files.filter(Files::isRegularFile).toList())sources.put(repo.relativize(p).toString().replace('\\','/'),hash(p));}
        sources.put("backend/src/test/java/com/carrental/AiLiveBaselineIT.java",hash(repo.resolve("backend/src/test/java/com/carrental/AiLiveBaselineIT.java")));
        Files.createDirectories(out);
        String base="http://127.0.0.1:"+port;
        Map<String,Object> preflight=Map.of("datasetHashes",hashes,"baseUrl",base,"clockInstant","2027-02-01T00:00:00Z","branchFacts",facts.path("branches"),"sourceHashes",sources,"fixtureVerified",true,"readOnlyConfirmed",true);
        Path preflightPath=out.resolve("preflight.json");Files.writeString(preflightPath,json.writeValueAsString(preflight),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        var before=snapshot();var exits=new LinkedHashMap<String,Integer>();
        for(String series:List.of("quality","performance")){
            List<String> command=List.of(Objects.requireNonNull(System.getenv("AI_EVAL_PYTHON")),"-X","utf8",repo.resolve("scripts/ai/evaluation/run_evaluation.py").toString(),"--dataset",data.toString(),"--suites","parser","--base-url",base,"--preflight",preflightPath.toString(),"--output",out.resolve(series).toString(),"--allow-draft","--series",series,"--ollama-observe");
            var builder=new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).redirectOutput(out.resolve(series+".log").toFile());
            builder.environment().put("KDRIVE_EVAL_TOKEN",tokens.issue(accounts.require("eval-live-user")));
            Process process=builder.start();
            try{
                assertTrue(process.waitFor(3,TimeUnit.HOURS),"Bounded baseline timeout");
                exits.put(series,process.exitValue());assertTrue(Set.of(0,1).contains(process.exitValue()),"Runner preflight/environment blocked; inspect local report");
            }finally{if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));}}
            assertEquals(before,snapshot(),"Parser must not mutate persistence");
            JsonNode report=json.readTree(out.resolve(series).resolve("summary.json").toFile());
            assertEquals(series.equals("quality")?100:60,report.path("totals").path("planned").asInt());
            assertEquals(series.equals("quality")?100:60,report.path("totals").path("executed").asInt());
            assertTrue(report.path("modelIdentityStable").asBoolean());assertFalse(report.path("certified").asBoolean());
        }
        Files.writeString(out.resolve("readback.json"),json.writeValueAsString(Map.of("fixture","H2 isolated parser catalog","noMutation",true,"seriesExits",exits,"heldoutSent",false)),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
}
