package com.carrental;

import com.carrental.modules.operation.service.OperationalReportService;
import com.carrental.security.Actor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:reports;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","app.jwt-secret=report-test-only-secret-at-least-32-bytes","app.seed-enabled=true","app.seed-password=ReportsOnly2026!"})
@AutoConfigureMockMvc
@Import(OperationalReportIntegrationTest.FixedTime.class)
class OperationalReportIntegrationTest {
 @org.springframework.test.context.DynamicPropertySource
 static void database(org.springframework.test.context.DynamicPropertyRegistry registry){
  String url=System.getenv("KDRIVE_TEST_DB_URL");
  if(url!=null&&!url.isBlank()){
   CancelBoundaryTest.verifySafeIsolatedDatabase(url,System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),System.getenv("KDRIVE_TEST_DB_USER"),System.getenv("KDRIVE_TEST_DB_PASSWORD"));
   registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->System.getenv("KDRIVE_TEST_DB_USER"));registry.add("spring.datasource.password",()->System.getenv("KDRIVE_TEST_DB_PASSWORD"));
  }
 }
 @TestConfiguration static class FixedTime {@Bean @Primary Clock reportClock(){return Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"),ZoneOffset.UTC);}}
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired OperationalReportService service;
 String login(String email)throws Exception{
  var r=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("email",email,"password","ReportsOnly2026!")))).andReturn().getResponse();
  assertEquals(200,r.getStatus());return json.readTree(r.getContentAsString()).path("token").asText();
 }
 JsonNode request(String query,String token,int expected)throws Exception{
  var b=get("/api/v1/admin/reports/operations"+query);if(token!=null)b.header("Authorization","Bearer "+token);
  var r=mvc.perform(b).andReturn().getResponse();assertEquals(expected,r.getStatus(),r.getContentAsString());return json.readTree(r.getContentAsString());
 }
 @BeforeEach void clean(){jdbc.update("delete from invoice_items");jdbc.update("delete from invoices");jdbc.update("delete from booking_status_history");jdbc.update("delete from bookings");}
 @Test void dateValidationAndReadPermissions()throws Exception{
  String admin=login("admin@example.test");
  for(String q:List.of("","?from=2026-10-12","?from=&to=2026-10-12","?from=2026-02-29&to=2026-03-01","?from=2026-10-13&to=2026-10-12","?from=2024-01-01&to=2025-01-01","?from=1970-01-01&to=1970-01-02","?from=2038-01-19&to=2038-01-19","?from=2026-10-12&from=2026-10-12&to=2026-10-12","?from=2026-10-12&to=2026-10-12&branch=dn","?from=2026-1-01&to=2026-01-02","?from=2026-10-12T00:00:00Z&to=2026-10-12"))request(q,admin,400);
  for(String q:List.of("?from=2024-01-01&to=2024-12-31","?from=2024-02-29&to=2024-02-29","?from=1970-01-02&to=1970-01-02","?from=2038-01-18&to=2038-01-18"))request(q,admin,200);
  String q="?from=2026-10-12&to=2026-10-12";request(q,null,401);request(q,login("user@example.test"),403);request(q,login("staff.dn@example.test"),403);
  assertThrows(com.carrental.exception.DomainException.class,()->service.read(new Actor("demo-user","USER","Fixture"),"2026-10-12","2026-10-12"));
  assertEquals(0,jdbc.queryForObject("select count(*) from bookings",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from invoices",Integer.class));
 }
 @Test void emptyIsHonestZeroWithCompleteStatusSet()throws Exception{
  var r=request("?from=2026-10-12&to=2026-10-12",login("admin@example.test"),200);
  assertEquals("2026-10-11T17:00:00Z",r.path("fromInclusive").asText());assertEquals("2026-10-12T17:00:00Z",r.path("toExclusive").asText());assertEquals("Asia/Ho_Chi_Minh",r.path("timeZone").asText());
  assertEquals("2026-10-13T00:00:00Z",r.path("asOf").asText());assertEquals(0,r.path("createdBookings").path("total").asLong());assertEquals(8,r.path("createdBookings").path("byStatus").size());r.path("createdBookings").path("byStatus").forEach(n->assertEquals(0,n.asLong()));assertEquals(0,r.path("completedBookings").asLong());assertEquals(0,r.path("issuedInvoices").asLong());assertEquals("0",r.path("invoiceTotalVnd").asText());
  assertFalse(r.has("users"));assertFalse(r.has("paidTotal"));
 }
 // Direct rows exercise report queries only; not proof of rental workflow execution.
 void booking(String id,String created,String state,String expires){
  jdbc.update("insert into bookings(id,user_id,vehicle_id,pickup_branch_id,return_branch_id,start_at,end_at,created_at,expires_at,status,daily_price,rental_days,rental_total,one_way_fee,total,customer_name,vehicle_name,pickup_name,return_name,policy_version) values(?,'demo-user','v1','dn','dn',?, ?, ?, ?, ?,650000,1,650000,0,650000,'Fixture','Fixture','Fixture','Fixture','fixture')",id,ts("2026-10-14T00:00:00Z"),ts("2026-10-15T00:00:00Z"),ts(created),ts(expires),state);
 }
 java.sql.Timestamp ts(String value){return java.sql.Timestamp.from(Instant.parse(value));}
 void completion(String id,String at){jdbc.update("insert into booking_status_history(id,booking_id,previous_status,next_status,occurred_at) values(?,?,'RETURNED','COMPLETED',?)",UUID.randomUUID().toString(),id,ts(at));}
 void invoice(String id,String at,long total){jdbc.update("insert into invoices(id,booking_id,issued_at,total,snapshot) values(?,?,?,?,'{}')",UUID.randomUUID().toString(),id,ts(at),total);}
 @Test void aggregatesUseOwnTimestampsEffectiveExpiryAndFirstCompletionWithoutWrites()throws Exception{
  booking("rA","2026-10-11T17:00:00Z","PENDING","2026-10-13T00:00:00Z");
  booking("rB","2026-10-12T00:00:00Z","COMPLETED","2026-10-12T01:00:00Z");
  booking("rC","2026-10-12T16:59:59.999999Z","RETURNED","2026-10-12T01:00:00Z");
  booking("rD","2026-10-12T12:00:00Z","CANCELLED","2026-10-12T13:00:00Z");
  booking("rE","2026-10-11T16:59:59.999999Z","COMPLETED","2026-10-11T17:00:00Z");
  booking("rF","2026-10-12T17:00:00Z","CONFIRMED","2026-10-12T18:00:00Z");
  booking("rG","2026-10-10T00:00:00Z","COMPLETED","2026-10-10T01:00:00Z");
  completion("rB","2026-10-12T01:00:00Z");completion("rB","2026-10-12T02:00:00Z");completion("rE","2026-10-11T17:00:00Z");completion("rG","2026-10-12T17:00:00Z");
  invoice("rB","2026-10-12T01:00:00Z",650000);invoice("rE","2026-10-11T17:00:00Z",500000);invoice("rG","2026-10-12T17:00:00Z",900000);
  var before=jdbc.queryForList("select * from bookings order by id");var invoices=jdbc.queryForList("select * from invoices order by booking_id");var history=jdbc.queryForList("select * from booking_status_history order by id");
  var r=request("?from=2026-10-12&to=2026-10-12",login("admin@example.test"),200);assertEquals(4,r.path("createdBookings").path("total").asLong());
  var groups=r.path("createdBookings").path("byStatus");assertEquals(8,groups.size());for(String state:List.of("EXPIRED","COMPLETED","RETURNED","CANCELLED"))assertEquals(1,groups.path(state).asLong());for(String state:List.of("PENDING","CONFIRMED","IN_PROGRESS","REJECTED"))assertEquals(0,groups.path(state).asLong());
  assertEquals(2,r.path("completedBookings").asLong());assertEquals(2,r.path("issuedInvoices").asLong());assertEquals("1150000",r.path("invoiceTotalVnd").asText());
  assertEquals(before,jdbc.queryForList("select * from bookings order by id"));assertEquals(invoices,jdbc.queryForList("select * from invoices order by booking_id"));assertEquals(history,jdbc.queryForList("select * from booking_status_history order by id"));
  // A first completion outside the range must not move into it due to a later duplicate.
  completion("rG","2026-10-14T00:00:00Z");assertEquals(0,request("?from=2026-10-14&to=2026-10-14",login("admin@example.test"),200).path("completedBookings").asLong());
 }
 @Test void invoiceMoneyStaysExactBeyondLongSumAndJavascriptPrecision()throws Exception{
  booking("large1","2026-10-12T00:00:00Z","COMPLETED","2026-10-12T01:00:00Z");booking("large2","2026-10-12T00:00:00Z","COMPLETED","2026-10-12T01:00:00Z");
  invoice("large1","2026-10-12T01:00:00Z",Long.MAX_VALUE);invoice("large2","2026-10-12T01:00:00Z",Long.MAX_VALUE);
  var r=request("?from=2026-10-12&to=2026-10-12",login("admin@example.test"),200);assertTrue(r.path("invoiceTotalVnd").isTextual());assertEquals("18446744073709551614",r.path("invoiceTotalVnd").asText());
 }
}
