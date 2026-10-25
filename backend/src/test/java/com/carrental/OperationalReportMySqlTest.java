package com.carrental;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.user.service.*;
import com.carrental.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="KDRIVE_TEST_DB_URL",matches="jdbc:mysql:.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.jwt-secret=reports-mysql-test-secret-at-least-32-bytes","app.seed-enabled=true","app.seed-password=ReportsOnly2026!","spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"})
@Import(OperationalReportMySqlTest.TimeConfig.class)
class OperationalReportMySqlTest {
 static class FixtureClock extends Clock {
  volatile Instant value=Instant.parse("2026-10-20T08:00:00Z");
  public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}
 }
 @TestConfiguration static class TimeConfig {@Bean @Primary FixtureClock fixtureClock(){return new FixtureClock();}}
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  String url=System.getenv("KDRIVE_TEST_DB_URL");CancelBoundaryTest.verifySafeIsolatedDatabase(url,System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),System.getenv("KDRIVE_TEST_DB_USER"),System.getenv("KDRIVE_TEST_DB_PASSWORD"));
  r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("KDRIVE_TEST_DB_USER"));r.add("spring.datasource.password",()->System.getenv("KDRIVE_TEST_DB_PASSWORD"));
 }
 @Autowired TestRestTemplate http;@Autowired JdbcTemplate jdbc;@Autowired AccountService accounts;@Autowired LicenseService licenses;@Autowired TokenService tokens;@Autowired FixtureClock clock;
 @MockitoSpyBean BookingService bookings;
 String admin,user,staff;
 String token(String id){return "Bearer "+tokens.issue(accounts.require(id));}
 @BeforeEach void setup(){
  CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);assertTrue(jdbc.queryForObject("select version()",String.class).startsWith("8."));
  for(String table:List.of("reviews","incident_events","incident_cases","vehicle_handover_records","invoice_items","invoices","contracts","booking_status_history","bookings"))jdbc.update("delete from "+table);
  jdbc.update("update vehicles set current_branch_id='dn',status='ACTIVE',daily_price=650000 where id='v1'");
  clock.value=Instant.parse("2026-10-20T08:00:00Z");
  var actor=new Actor("demo-user","USER","Fixture");var old=licenses.mine(actor);licenses.save(actor,new LicenseService.View(old==null?null:old.id(),"REPORT-FIXTURE","VN_2025","B",LocalDate.of(2030,12,31),false,"PENDING",old==null?0:old.version()));LicensePhotoFixture.approved(licenses,actor);
  admin=token("demo-admin");user=token("demo-user");staff=token("demo-staff-dn");
 }
 ResponseEntity<Map> request(HttpMethod method,String path,Object input,String bearer){
  HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(bearer!=null)h.set("Authorization",bearer);
  return http.exchange("/api/v1"+path,method,new HttpEntity<>(input,h),Map.class);
 }
 Map report(){var r=request(HttpMethod.GET,"/admin/reports/operations?from=2026-10-20&to=2026-10-21",null,admin);assertEquals(HttpStatus.OK,r.getStatusCode());return r.getBody();}
 long number(Map m,String key){return ((Number)m.get(key)).longValue();}
 Map create(){var start=clock.instant().plusSeconds(3600);var input=Map.of("vehicleId","v1","pickupBranchId","dn","returnBranchId","dn","startAt",start.toString(),"endAt",start.plusSeconds(86400).toString(),"expectedTotal",650000);
  assertEquals(HttpStatus.OK,request(HttpMethod.POST,"/bookings/quote",input,user).getStatusCode());var r=request(HttpMethod.POST,"/bookings",input,user);assertEquals(HttpStatus.CREATED,r.getStatusCode(),String.valueOf(r.getBody()));return r.getBody();}
 void action(String id,String status,long version){var r=request(HttpMethod.POST,"/bookings/"+id+"/actions",Map.of("next",status,"version",version,"condition","Report fixture vehicle checked","reason","Report fixture workflow","licenseChecked",true,"maintenance",false),staff);assertEquals(HttpStatus.OK,r.getStatusCode(),String.valueOf(r.getBody()));}
 String returned(){var pending=create();String id=(String)pending.get("id");action(id,"CONFIRMED",0);clock.value=Instant.parse("2026-10-20T09:00:00Z");action(id,"IN_PROGRESS",1);clock.value=Instant.parse("2026-10-21T09:00:00Z");action(id,"RETURNED",2);return id;}
 Map<String,List<Map<String,Object>>> rows(){var result=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("bookings","booking_status_history","invoices","invoice_items","contracts","vehicle_handover_records","vehicles"))result.put(table,jdbc.queryForList("select * from "+table+" order by id"));return result;}
 @Test void workflowReadbackAndSnapshotPrices(){
  String id=returned();action(id,"COMPLETED",3);var before=rows();var r=report();assertEquals(1,number((Map)r.get("createdBookings"),"total"));assertEquals(1,number(r,"completedBookings"));assertEquals(1,number(r,"issuedInvoices"));assertEquals("650000",r.get("invoiceTotalVnd"));assertEquals(before,rows());
  assertEquals(jdbc.queryForObject("select count(*) from bookings where created_at>=? and created_at<?",Long.class,java.sql.Timestamp.from(Instant.parse("2026-10-19T17:00:00Z")),java.sql.Timestamp.from(Instant.parse("2026-10-21T17:00:00Z"))),number((Map)r.get("createdBookings"),"total"));
  assertEquals(jdbc.queryForObject("select sum(total) from invoices",java.math.BigDecimal.class).toBigIntegerExact().toString(),r.get("invoiceTotalVnd"));
  jdbc.update("update vehicles set daily_price=900000,name='Changed fixture name' where id='v1'");assertEquals("650000",report().get("invoiceTotalVnd"));assertEquals(before.get("invoices"),rows().get("invoices"));assertEquals(before.get("invoice_items"),rows().get("invoice_items"));
 }
 @Test void concurrentCompletionKeepsOneReadSnapshot()throws Exception{
  String id=returned();CountDownLatch selected=new CountDownLatch(1),resume=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
  doAnswer(invocation->{Object result=invocation.callRealMethod();selected.countDown();assertTrue(resume.await(15,TimeUnit.SECONDS));return result;}).when(bookings).report(any(),any(),any());
  Future<Map> pending=executor.submit(this::report);
  try{assertTrue(selected.await(15,TimeUnit.SECONDS));action(id,"COMPLETED",3);assertEquals(1,jdbc.queryForObject("select count(*) from invoices",Integer.class));resume.countDown();var first=pending.get(15,TimeUnit.SECONDS);assertEquals(0,number(first,"completedBookings"));assertEquals(0,number(first,"issuedInvoices"));assertEquals("0",first.get("invoiceTotalVnd"));assertEquals(1,number((Map)((Map)first.get("createdBookings")).get("byStatus"),"RETURNED"));
  }finally{resume.countDown();pending.cancel(true);executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));doCallRealMethod().when(bookings).report(any(),any(),any());}
  var second=report();assertEquals(1,number(second,"completedBookings"));assertEquals(1,number(second,"issuedInvoices"));assertEquals("650000",second.get("invoiceTotalVnd"));System.out.println("C12 MYSQL SNAPSHOT OLD THEN NEW VERIFIED");
 }
 @Test void httpBoundariesPermissionsAndEmpty(){
  String path="/admin/reports/operations?from=2026-10-20&to=2026-10-21";var before=rows();
  assertEquals(HttpStatus.UNAUTHORIZED,request(HttpMethod.GET,path,null,null).getStatusCode());assertEquals(HttpStatus.FORBIDDEN,request(HttpMethod.GET,path,null,user).getStatusCode());assertEquals(HttpStatus.FORBIDDEN,request(HttpMethod.GET,path,null,staff).getStatusCode());
  for(String range:List.of("from=1970-01-02&to=1970-01-02","from=2038-01-18&to=2038-01-18","from=2024-01-01&to=2024-12-31"))assertEquals(HttpStatus.OK,request(HttpMethod.GET,"/admin/reports/operations?"+range,null,admin).getStatusCode());
  for(String range:List.of("from=1970-01-01&to=1970-01-02","from=2038-01-19&to=2038-01-19","from=2024-01-01&to=2025-01-01"))assertEquals(HttpStatus.BAD_REQUEST,request(HttpMethod.GET,"/admin/reports/operations?"+range,null,admin).getStatusCode());
  var r=report();assertEquals(0,number((Map)r.get("createdBookings"),"total"));assertEquals(0,number(r,"issuedInvoices"));assertEquals("0",r.get("invoiceTotalVnd"));assertEquals(before,rows());
 }
 @Test void representativeVolumePlansAndLatency(){
  // Synthetic query-load rows; real workflow is proved separately above.
  var sql="insert into bookings(id,user_id,vehicle_id,pickup_branch_id,return_branch_id,start_at,end_at,created_at,expires_at,status,daily_price,rental_days,rental_total,one_way_fee,total,customer_name,vehicle_name,pickup_name,return_name,policy_version) values(?,'demo-user','v1','dn','dn',?, ?, ?, ?, 'PENDING',650000,1,650000,0,650000,'Fixture','Fixture','Fixture','Fixture','fixture')";
  var args=new ArrayList<Object[]>();for(int i=0;i<10000;i++)args.add(new Object[]{"load-"+i,java.sql.Timestamp.from(clock.instant().plusSeconds(3600)),java.sql.Timestamp.from(clock.instant().plusSeconds(90000)),java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant().plusSeconds(3600))});jdbc.batchUpdate(sql,args);
  List<Long> milliseconds=new ArrayList<>();for(int i=0;i<5;i++){long start=System.nanoTime();assertEquals(10000,number((Map)report().get("createdBookings"),"total"));milliseconds.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}
  assertEquals(10000,jdbc.queryForObject("select count(*) from bookings",Integer.class));System.out.println("C12 MYSQL 10000 BOOKINGS HTTP LATENCY MS "+milliseconds);
  System.out.println("C12 MYSQL PLAN "+jdbc.queryForList("explain select count(*) from bookings where created_at>='2026-10-19 17:00:00' and created_at<'2026-10-21 17:00:00'"));
  assertTrue(milliseconds.stream().allMatch(x->x<10000),"Local fixture smoke ceiling, not production SLA");
 }
}
