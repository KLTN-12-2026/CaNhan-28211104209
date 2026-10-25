package com.carrental;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"app.jwt-secret=integration-test-only-secret-at-least-32-bytes","app.seed-enabled=true","app.seed-password=IntegrationOnly123!","spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"})
@AutoConfigureMockMvc
@Import(RentalIntegrationTest.TimeConfig.class)
class RentalIntegrationTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  String url=System.getenv("KDRIVE_TEST_DB_URL");
  r.add("spring.datasource.url",()->url==null?"jdbc:h2:mem:kdrive;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1":url);
  r.add("spring.datasource.username",()->url==null?"sa":System.getenv("KDRIVE_TEST_DB_USER"));
  r.add("spring.datasource.password",()->url==null?"":System.getenv("KDRIVE_TEST_DB_PASSWORD"));
 }
 static class MutableClock extends Clock {
  Instant value=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return value;}
 }
 @TestConfiguration static class TimeConfig {@Bean @Primary MutableClock testClock(){return new MutableClock();}}
 @Autowired com.carrental.modules.user.service.LicenseService licenses;
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired MutableClock clock;
 private JsonNode request(MockHttpServletRequestBuilder req,String token,Object body,int status)throws Exception{
  if(token!=null)req.header("Authorization","Bearer "+token);
  if(body!=null)req.contentType("application/json").content(json.writeValueAsString(body));
  var result=mvc.perform(req).andReturn();assertEquals(status,result.getResponse().getStatus(),result.getResponse().getContentAsString());
  String content=result.getResponse().getContentAsString();return content.isBlank()?json.createObjectNode():json.readTree(content);
 }
 private String login(String email)throws Exception{return request(post("/api/v1/auth/login"),null,Map.of("email",email,"password","IntegrationOnly123!"),200).path("token").asText();}
 @Test void realApiPermissionsOneWayRentalDocumentsIncidentAndRevocation()throws Exception{
  String user=login("user@example.test"),dn=login("staff.dn@example.test"),hn=login("staff.hn@example.test"),admin=login("admin@example.test");
  request(get("/api/v1/admin/accounts"),user,null,403);
  request(get("/api/v1/vehicles"),null,null,200);
  // L05: Missing or null transmission/status returns 400 (not NPE 500)
  request(post("/api/v1/admin/vehicles"),admin,Map.of("name","Car Null","plate","29A-99999","dailyPrice",500000,"seats",5,"homeBranchId","dn"),400);
  request(patch("/api/v1/vehicles/v1/operation"),dn,Map.of("version",0),400);
  // L06: Route update with outdated version returns 409
  request(put("/api/v1/admin/branch-routes"),admin,Map.of("pickupBranchId","dn","returnBranchId","hn","fee",1600000,"enabled",true,"expectedVersion",99L),409);
  var licNode=request(get("/api/v1/me/license"),user,null,200);
  long currentLicVer=licNode.has("version")?licNode.path("version").asLong():0L;
  request(put("/api/v1/me/license"),user,Map.of("licenseNumber","TEST-ONLY","scheme","VN_2025","licenseClass","C1","expiresOn",LocalDate.now().plusYears(1).toString(),"automaticOnly",false,"version",currentLicVer),200);
  LicensePhotoFixture.approved(licenses,new com.carrental.security.Actor("demo-user","USER","Fixture"));
  Instant start=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plus(Duration.ofDays(3));
  var input=new HashMap<String,Object>();input.put("vehicleId","v1");input.put("pickupBranchId","dn");input.put("returnBranchId","hn");input.put("startAt",start.toString());input.put("endAt",start.plus(Duration.ofHours(25)).toString());
  JsonNode quote=request(post("/api/v1/bookings/quote"),user,input,200);assertEquals(2,quote.path("rentalDays").asInt());assertEquals(2800000,quote.path("total").asLong());
  input.put("expectedTotal",1);request(post("/api/v1/bookings"),user,input,409);
  input.put("expectedTotal",2800000);JsonNode created=request(post("/api/v1/bookings"),user,input,201);String id=created.path("id").asText();
  request(post("/api/v1/bookings"),user,input,409);
  String other=request(post("/api/v1/auth/register"),null,Map.of("email","other@example.test","password","IntegrationOnly123!","fullName","Other"),201).path("token").asText();
  request(get("/api/v1/bookings/"+id),other,null,404);
  request(post("/api/v1/bookings/"+id+"/actions"),admin,Map.of("next","CONFIRMED","version",0),403);
  request(post("/api/v1/bookings/"+id+"/actions"),hn,Map.of("next","CONFIRMED","version",0),403);
  JsonNode confirmed=request(post("/api/v1/bookings/"+id+"/actions"),dn,Map.of("next","CONFIRMED","version",0),200);
  request(post("/api/v1/bookings/"+id+"/actions"),dn,Map.of("next","IN_PROGRESS","version",0,"condition","Good","licenseChecked",true),409);
  long version=confirmed.path("version").asLong();assertEquals(1,version);
  request(post("/api/v1/bookings/"+id+"/actions"),dn,Map.of("next","IN_PROGRESS","version",version,"condition","Good","licenseChecked",true),409);
  jdbc.update("update vehicles set daily_price=900000 where id='v1'");
  assertTrue(request(get("/api/v1/bookings/"+id+"/contract"),user,null,200).get(0).path("snapshot").asText().contains("2800000"));
  clock.value=start.plusSeconds(1);
  request(post("/api/v1/bookings/"+id+"/actions"),dn,Map.of("next","IN_PROGRESS","version",version,"condition","Good","licenseChecked",false),400);
  JsonNode handed=request(post("/api/v1/bookings/"+id+"/actions"),dn,Map.of("next","IN_PROGRESS","version",version,"condition","Good","licenseChecked",true),200);
  assertEquals(0,request(get("/api/v1/bookings/"+id+"/invoice"),user,null,200).size());
  request(post("/api/v1/bookings/"+id+"/actions"),dn,Map.of("next","RETURNED","version",handed.path("version").asLong(),"condition","Good"),403);
  JsonNode returned=request(post("/api/v1/bookings/"+id+"/actions"),hn,Map.of("next","RETURNED","version",handed.path("version").asLong(),"condition","Good"),200);
  assertEquals("hn",request(get("/api/v1/vehicles/v1"),null,null,200).path("currentBranchId").asText());
  request(post("/api/v1/bookings/"+id+"/actions"),hn,Map.of("next","COMPLETED","version",returned.path("version").asLong()),200);
  assertEquals(2800000,request(get("/api/v1/bookings/"+id+"/invoice"),user,null,200).get(0).path("total").asLong());
  request(post("/api/v1/reviews"),user,Map.of("bookingId",id,"rating",5,"content","Good"),200);
  request(post("/api/v1/reviews"),user,Map.of("bookingId",id,"rating",5,"content","Again"),409);
  // L01 custody assertion: occurredAt before handover or after return must be rejected (400)
  request(post("/api/v1/incidents"),user,Map.of("bookingId",id,"kind","VIOLATION","occurredAt",start.minusSeconds(10).toString(),"description","Trước giao xe","evidenceReference","TEST-ONLY"),400);
  request(post("/api/v1/incidents"),user,Map.of("bookingId",id,"kind","VIOLATION","occurredAt",start.plusSeconds(10).toString(),"description","Sau trả xe","evidenceReference","TEST-ONLY"),400);
  // Valid occurredAt within actual custody window [start.plusSeconds(1), start.plusSeconds(1)]
  JsonNode incident=request(post("/api/v1/incidents"),user,Map.of("bookingId",id,"kind","VIOLATION","occurredAt",start.plusSeconds(1).toString(),"description","Thông báo thử nghiệm sau khi trả xe","evidenceReference","TEST-ONLY"),200);
  String caseId=incident.path("id").asText();
  // L02: Staff of different branch cannot block vehicle
  request(post("/api/v1/incidents"),dn,Map.of("bookingId",id,"kind","VIOLATION","occurredAt",start.plusSeconds(1).toString(),"description","Staff DN blocks vehicle now in HN","evidenceReference","TEST-ONLY","blockVehicle",true),403);
  request(post("/api/v1/incidents/"+caseId+"/actions"),hn,Map.of("next","ASSESSED","version",0,"assessedDamage",1000,"thirdPartyCoverage",900,"requestedAmount",500,"responsibilityBasis","Test basis","note","Test"),400);
  request(post("/api/v1/incidents/"+caseId+"/actions"),hn,Map.of("next","ASSESSED","version",0,"assessedDamage",1000,"thirdPartyCoverage",900,"requestedAmount",100,"responsibilityBasis","Test basis","note","Test"),200);
  request(post("/api/v1/incidents/"+caseId+"/actions"),admin,Map.of("next","CLAIMED","version",1,"note","Approved based on evidence"),200);
  request(post("/api/v1/incidents/"+caseId+"/actions"),user,Map.of("next","DISPUTED","version",2,"note","Please review evidence"),200);
  assertEquals(4,jdbc.queryForObject("select count(*) from incident_events where case_id=?",Integer.class,caseId));
  assertEquals("OPEN",jdbc.queryForObject("select previous_status from incident_events where case_id=? and action='ASSESSED'",String.class,caseId));
  assertEquals("Test basis",jdbc.queryForObject("select responsibility_basis from incident_events where case_id=? and action='ASSESSED'",String.class,caseId));
  assertEquals(100L,jdbc.queryForObject("select requested_amount from incident_events where case_id=? and action='CLAIMED'",Long.class,caseId));
  request(post("/api/v1/incidents/"+caseId+"/actions"),hn,Map.of("next","ASSESSED","version",3,"assessedDamage",2000,"thirdPartyCoverage",0,"requestedAmount",2000,"responsibilityBasis","Revised basis","note","Reassess"),200);
  assertEquals(1,jdbc.queryForObject("select count(*) from incident_events where case_id=? and action='ASSESSED' and responsibility_basis='Test basis' and requested_amount=100",Integer.class,caseId));
  assertEquals(1,jdbc.queryForObject("select count(*) from incident_events where case_id=? and action='ASSESSED' and previous_status='DISPUTED' and responsibility_basis='Revised basis' and requested_amount=2000",Integer.class,caseId));
  var events=request(get("/api/v1/incidents/"+caseId+"/events"),user,null,200);
  assertEquals(5,events.size());assertTrue(events.get(0).has("actor_id"));
  assertEquals("COMPLETED",request(get("/api/v1/bookings/"+id),user,null,200).path("booking").path("status").asText());
  String second=login("user@example.test");request(post("/api/v1/auth/logout"),user,null,204);request(get("/api/v1/auth/me"),second,null,401);
 }
}
