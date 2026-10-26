package com.carrental;

import com.carrental.modules.user.service.*;
import com.carrental.modules.user.repository.DriverLicenseRepository;
import com.carrental.security.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"app.jwt-secret=license-photo-test-only-secret-at-least-32-bytes", "app.seed-enabled=true", "app.seed-password=LicensePhotoTestOnly123!", "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"})
@AutoConfigureMockMvc
@Import(LicensePhotoHttpTest.TimeConfig.class)
class LicensePhotoHttpTest {
    static final Path ROOT;
    static { try { ROOT=Files.createTempDirectory("kdrive-synthetic-license-"); } catch(Exception e){throw new IllegalStateException(e);} }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("KDRIVE_TEST_DB_URL");
        if(url!=null) CancelBoundaryTest.verifySafeIsolatedDatabase(url,System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),System.getenv("KDRIVE_TEST_DB_USER"),System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        r.add("spring.datasource.url",()->url==null?"jdbc:h2:mem:licensephotos;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1":url);
        r.add("spring.datasource.username",()->url==null?"sa":System.getenv("KDRIVE_TEST_DB_USER"));
        r.add("spring.datasource.password",()->url==null?"":System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        r.add("app.license-photo-directory",()->ROOT.toString());
    }
    static class FixtureClock extends Clock {
        Instant value=Instant.parse("2026-10-23T03:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return value;}
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary FixtureClock licensePhotoClock(){return new FixtureClock();} }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired LicenseService licenses; @Autowired AccountService accounts; @Autowired TokenService tokens;
    @Autowired DriverLicenseRepository repo; @Autowired FixtureClock clock;
    @org.springframework.boot.test.mock.mockito.SpyBean LicensePhotoStore store;
    final Actor user=new Actor("demo-user","USER","Fixture");
    String userToken,staffToken,otherStaff,adminToken;
    @BeforeEach void setup() {
        CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);
        for(String table:List.of("reviews","incident_events","incident_cases","vehicle_handover_records","invoice_items","invoices","contracts","booking_status_history","bookings"))jdbc.update("delete from "+table);
        clock.value=Instant.parse("2026-10-23T03:00:00Z");
        var old=licenses.mine(user);
        licenses.save(user,new LicenseService.View(null,"SYNTHETIC-NOT-A-REAL-LICENSE","VN_2025","B",LocalDate.of(2035,12,31),false,"PENDING",old==null?0:old.version()));
        userToken=token("demo-user");staffToken=token("demo-staff-dn");otherStaff=token("demo-staff-hn");adminToken=token("demo-admin");
    }
    String token(String id){return "Bearer "+tokens.issue(accounts.require(id));}
    JsonNode call(MockHttpServletRequestBuilder req,String token,Object body,int expected)throws Exception {
        if(token!=null)req.header("Authorization",token);
        if(body!=null)req.contentType("application/json").content(json.writeValueAsBytes(body));
        var response=mvc.perform(req).andReturn().getResponse();
        assertEquals(expected,response.getStatus(),response.getContentAsString());
        return response.getContentAsString().isBlank()?json.createObjectNode():json.readTree(response.getContentAsByteArray());
    }
    JsonNode upload(long version,int expected)throws Exception {
        return call(multipart("/api/v1/me/license/photos").file(LicensePhotoFixture.image("front")).file(LicensePhotoFixture.image("back")).param("version",Long.toString(version)).with(r->{r.setMethod("PUT");return r;}),userToken,null,expected);
    }
    Map<String,Object> input(){return Map.of("vehicleId","v1","pickupBranchId","dn","returnBranchId","dn","pickupDate","2026-10-26","returnDate","2026-10-28","expectedTotal",1300000);}
    JsonNode create()throws Exception {return call(post("/api/v1/bookings"),userToken,input(),201);}
    JsonNode review(String id,long version,boolean approved,String actor,int expected)throws Exception {
        return call(post("/api/v1/bookings/"+id+"/license-photo-review"),actor,Map.of("version",version,"approve",approved,"reason","Kiểm thử ảnh giả lập"),expected);
    }
    @Test void missingPhotosBlockCreateButNotQuote()throws Exception {
        jdbc.update("update driver_licenses set front_photo=null,back_photo=null,photo_review_status='MISSING' where user_id='demo-user'");
        assertEquals(1300000,call(post("/api/v1/bookings/quote"),userToken,input(),200).path("total").asLong());
        assertTrue(call(post("/api/v1/bookings"),userToken,input(),400).path("message").asText().contains("hai mặt"));
        assertEquals(0,jdbc.queryForObject("select count(*) from bookings",Integer.class));
    }
    @Test void uploadReadbackReviewPermissionsAndOriginalStillRequired()throws Exception {
        var uploaded=upload(licenses.mine(user).version(),200);
        assertEquals("PENDING",uploaded.path("photos").path("status").asText());
        assertTrue(uploaded.path("photos").path("front").asBoolean());
        var response=mvc.perform(get("/api/v1/me/license/photos/front").header("Authorization",userToken)).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertEquals("no-store, private",response.getHeader("Cache-Control"));assertEquals("image/png",response.getContentType());
        assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(response.getContentAsByteArray())));
        var b=create();String id=b.path("id").asText();
        call(post("/api/v1/bookings/"+id+"/actions"),staffToken,Map.of("next","CONFIRMED","version",0),400);
        review(id,uploaded.path("version").asLong(),true,otherStaff,404);
        review(id,uploaded.path("version").asLong(),true,adminToken,403);
        review(id,uploaded.path("version").asLong(),true,userToken,403);
        var approved=review(id,uploaded.path("version").asLong(),true,staffToken,200);
        assertEquals("PENDING",approved.path("status").asText()); // Not physical verification.
        var confirmed=call(post("/api/v1/bookings/"+id+"/actions"),staffToken,Map.of("next","CONFIRMED","version",0),200);
        clock.value=Instant.parse("2026-10-26T03:00:00Z");
        var action=new HashMap<String,Object>();action.put("next","IN_PROGRESS");action.put("version",confirmed.path("version").asLong());action.put("condition","Synthetic vehicle condition");action.put("licenseChecked",false);
        call(post("/api/v1/bookings/"+id+"/actions"),staffToken,action,400);
        action.put("licenseChecked",true);
        assertEquals("IN_PROGRESS",call(post("/api/v1/bookings/"+id+"/actions"),staffToken,action,200).path("status").asText());
        assertEquals("VERIFIED",licenses.mine(user).status());
        call(get("/api/v1/bookings/"+id+"/license-photos/front"),staffToken,null,409);
    }
    @Test void replacementDeletesOldFilesResetsReviewAndRejectsStaleDecision()throws Exception {
        var uploaded=upload(licenses.mine(user).version(),200);String id=create().path("id").asText();
        var approved=review(id,uploaded.path("version").asLong(),true,staffToken,200);
        var old=repo.findByUserId(user.id()).orElseThrow();String front=old.frontPhoto,back=old.backPhoto;
        var next=upload(approved.path("version").asLong(),200);
        assertFalse(Files.exists(ROOT.resolve(front)));assertFalse(Files.exists(ROOT.resolve(back)));
        assertEquals("PENDING",next.path("photos").path("status").asText());assertTrue(next.path("photos").path("reviewedAt").isNull());
        review(id,approved.path("version").asLong(),true,staffToken,409);
        review(id,next.path("version").asLong(),false,staffToken,200);
        call(post("/api/v1/bookings/"+id+"/actions"),staffToken,Map.of("next","CONFIRMED","version",0),400);
    }
    @Test void malformedOversizedMissingPartsAndUnauthorizedDoNotMutate()throws Exception {
        long v=licenses.mine(user).version();
        for(var bad:List.of(new MockMultipartFile("front","fake.png","image/png","not an image".getBytes()),new MockMultipartFile("front","huge.jpg","image/jpeg",new byte[5*1024*1024+1]))) {
            call(multipart("/api/v1/me/license/photos").file(bad).file(LicensePhotoFixture.image("back")).param("version",""+v).with(r->{r.setMethod("PUT");return r;}),userToken,null,400);
        }
        call(multipart("/api/v1/me/license/photos").file(LicensePhotoFixture.image("front")).param("version",""+v).with(r->{r.setMethod("PUT");return r;}),userToken,null,400);
        assertEquals(v,licenses.mine(user).version());
        call(get("/api/v1/me/license/photos/front"),null,null,401);
        call(get("/api/v1/me/license/photos/front"),staffToken,null,403);
        call(get("/api/v1/me/license/photos/invalid"),userToken,null,404);
    }
    @Test void changedDetailsInvalidateApprovalAndMissingFilesCannotBeApproved()throws Exception {
        var uploaded=upload(licenses.mine(user).version(),200);String id=create().path("id").asText();
        review(id,uploaded.path("version").asLong(),true,staffToken,200);
        var old=licenses.mine(user);
        var changed=licenses.save(user,new LicenseService.View(null,"CHANGED-SYNTHETIC","VN_2025","B",old.expiresOn(),false,"VERIFIED",old.version()));
        assertEquals("PENDING",changed.photos().status());assertEquals("PENDING",changed.status());
        Files.delete(ROOT.resolve(repo.findByUserId(user.id()).orElseThrow().frontPhoto));
        review(id,changed.version(),true,staffToken,404);
        assertEquals("PENDING",licenses.mine(user).photos().status());
    }
    @Test void anotherCustomerCannotReadOwnerPhotosAndStaleUploadCreatesNoFiles()throws Exception {
        var uploaded=upload(licenses.mine(user).version(),200);
        jdbc.update("insert into users(id,email,password_hash,full_name,role,status,token_version,version) values('photo-other','photo-other@example.test','no-login-hash','Other','USER','ACTIVE',0,0) on duplicate key update status='ACTIVE'");
        call(get("/api/v1/me/license/photos/front?userId=demo-user"),token("photo-other"),null,404);
        var before=files(); upload(uploaded.path("version").asLong()-1,409); assertEquals(before,files());
        assertEquals(uploaded.path("version").asLong(),licenses.mine(user).version());
    }
    @Test void secondDiskWriteFailureRollsBackMetadataAndRemovesFirstNewFile()throws Exception {
        upload(licenses.mine(user).version(),200);
        var before=licenses.mine(user);var beforeFiles=files();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation->{
            if(calls.incrementAndGet()==2)throw new IllegalStateException("Synthetic disk failure");
            return invocation.callRealMethod();
        }).when(store).write(org.mockito.ArgumentMatchers.any(byte[].class));
        var failure=assertThrows(Exception.class,()->upload(before.version(),200));
        assertTrue(failure.getMessage().contains("Synthetic disk failure"));assertEquals(2,calls.get());
        assertEquals(before,licenses.mine(user));assertEquals(beforeFiles,files());
    }
    Set<String> files()throws Exception {try(var stream=Files.list(ROOT)){return stream.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet());}}
}
