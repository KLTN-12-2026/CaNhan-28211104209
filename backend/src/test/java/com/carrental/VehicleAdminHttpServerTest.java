package com.carrental;

import com.carrental.modules.user.service.AccountService;
import com.carrental.modules.vehicle.service.VehicleService;
import com.carrental.security.Actor;
import com.carrental.security.TokenService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
 "app.jwt-secret=vehicle-admin-test-only-secret-at-least-32-bytes", "app.seed-enabled=true",
 "app.seed-password=VehicleAdminTestOnly123!", "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
class VehicleAdminHttpServerTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  String url=System.getenv("KDRIVE_TEST_DB_URL");
  if(url!=null)CancelBoundaryTest.verifySafeIsolatedDatabase(url,System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),System.getenv("KDRIVE_TEST_DB_USER"),System.getenv("KDRIVE_TEST_DB_PASSWORD"));
  r.add("spring.datasource.url",()->url==null?"jdbc:h2:mem:vehicleadmin;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1":url);
  r.add("spring.datasource.username",()->url==null?"sa":System.getenv("KDRIVE_TEST_DB_USER"));
  r.add("spring.datasource.password",()->url==null?"":System.getenv("KDRIVE_TEST_DB_PASSWORD"));
 }
 @Autowired TestRestTemplate http;
 @Autowired JdbcTemplate jdbc;
 @Autowired TokenService tokens;
 @Autowired AccountService accounts;
 @Autowired VehicleService vehicles;
 @Autowired TransactionTemplate tx;
 @Autowired Flyway flyway;
 String token(String id){return tokens.issue(accounts.require(id));}
 ResponseEntity<Map> call(HttpMethod method,String path,Object body,String token){
  HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(token!=null)headers.setBearerAuth(token);
  return http.exchange("/api/v1"+path,method,new HttpEntity<>(body,headers),Map.class);
 }
 Map<String,Object> create(){
  var payload=new HashMap<String,Object>();payload.put("name","Vehicle "+UUID.randomUUID());payload.put("plate","T"+UUID.randomUUID().toString().substring(0,12));
  payload.put("brandId","toyota");payload.put("categoryId","sedan");payload.put("homeBranchId","dn");payload.put("seats",5);payload.put("transmission","AUTO");payload.put("dailyPrice",500000);payload.put("status","ACTIVE");payload.put("createdAt","2099-01-01T00:00:00Z");
  var result=call(HttpMethod.POST,"/admin/vehicles",payload,token("demo-admin"));assertEquals(200,result.getStatusCode().value());return result.getBody();
 }
 int delete(Map v,String version,String token){return call(HttpMethod.DELETE,"/admin/vehicles/"+v.get("id")+version,null,token).getStatusCode().value();}
 Map<String,Object> rows(String id){return jdbc.queryForMap("select * from vehicles where id=?",id);}
 void links(String id){jdbc.update("insert into vehicle_images(id,vehicle_id,url,sort_order) values(?,?,?,0)",UUID.randomUUID().toString(),id,"https://example.test/car.jpg");String amenity=UUID.randomUUID().toString();jdbc.update("insert into amenities(id,name) values(?,?)",amenity,"Amenity "+amenity);jdbc.update("insert into vehicle_amenities(vehicle_id,amenity_id) values(?,?)",id,amenity);}
 void booking(String vehicleId,String state){
  jdbc.update("insert into bookings(id,user_id,vehicle_id,pickup_branch_id,return_branch_id,start_at,end_at,created_at,expires_at,status,daily_price,rental_days,rental_total,one_way_fee,total,customer_name,vehicle_name,pickup_name,return_name,policy_version) values (?,'demo-user',?,'dn','dn','2026-10-01 08:00:00','2026-10-02 08:00:00','2026-09-30 08:00:00','2026-09-30 10:00:00',?,100,1,100,0,100,'Historical renter','Historical vehicle','Pickup','Return','vehicle-test')",UUID.randomUUID().toString(),vehicleId,state);
 }
 @Test void creationOrderIsServerOwnedStableAndEditDoesNotMoveVehicle(){
  var first=create();var second=create();String firstId=(String)first.get("id"),secondId=(String)second.get("id");
  assertNotNull(first.get("createdAt"));assertFalse(first.get("createdAt").toString().startsWith("2099"));
  var list=vehicles.list();assertTrue(index(list,secondId)<index(list,firstId));assertTrue(index(list,firstId)<index(list,"v1"));
  var edit=new HashMap<>(first);edit.put("name","Updated vehicle");edit.put("createdAt","2100-01-01T00:00:00Z");
  var saved=call(HttpMethod.PUT,"/admin/vehicles/"+firstId,edit,token("demo-admin"));assertEquals(200,saved.getStatusCode().value());assertEquals(first.get("createdAt"),saved.getBody().get("createdAt"));
  assertTrue(index(vehicles.list(),secondId)<index(vehicles.list(),firstId));
  jdbc.update("update vehicles set created_at='2026-10-01 00:00:00' where id in (?,?)",firstId,secondId);
  var ties=vehicles.list();assertTrue(index(ties,firstId.compareTo(secondId)>0?firstId:secondId)<index(ties,firstId.compareTo(secondId)>0?secondId:firstId));
 }
 int index(List<VehicleService.View> list,String id){for(int i=0;i<list.size();i++)if(list.get(i).id().equals(id))return i;throw new AssertionError("Missing vehicle "+id);}
 void assertNativeVehicleLockWait()throws InterruptedException{
  if(System.getenv("KDRIVE_TEST_DB_URL")==null)return; // H2 assertions below are separate from native lock proof.
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  String query="select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks l on w.requesting_engine_lock_id=l.engine_lock_id where l.object_schema=database() and l.object_name='vehicles'";
  while(System.nanoTime()<deadline){if(jdbc.queryForObject(query,Integer.class)>0)return;Thread.sleep(20);}
  fail("MySQL must show the competing vehicle lock request waiting before commit");
 }
 @Test void permissionsVersionAndMissingVehicleDoNotWrite(){
  var v=create();var before=rows((String)v.get("id"));
  assertEquals(401,delete(v,"?version=0",null));for(String id:List.of("demo-user","demo-staff-dn"))assertEquals(403,delete(v,"?version=0",token(id)));
  assertEquals(400,delete(v,"?version=-1",token("demo-admin")));assertEquals(400,delete(v,"",token("demo-admin")));assertEquals(400,delete(v,"?version=abc",token("demo-admin")));assertEquals(409,delete(v,"?version=9",token("demo-admin")));
  assertEquals(before,rows((String)v.get("id")));assertEquals(404,delete(Map.of("id",UUID.randomUUID().toString()),"?version=0",token("demo-admin")));
 }
 @Test void deletesUnusedVehicleAndOnlyItsImageAndAmenityLinks(){
  var v=create();String id=(String)v.get("id");links(id);var retained=jdbc.queryForList("select * from vehicles where id='v1'");int amenityCount=jdbc.queryForObject("select count(*) from amenities",Integer.class);
  assertEquals(204,delete(v,"?version=0",token("demo-admin")));
  for(String table:List.of("vehicles","vehicle_images","vehicle_amenities"))assertEquals(0,jdbc.queryForObject("select count(*) from "+table+" where "+(table.equals("vehicles")?"id":"vehicle_id")+"=?",Integer.class,id));
  assertEquals(amenityCount,jdbc.queryForObject("select count(*) from amenities",Integer.class));assertEquals(retained,jdbc.queryForList("select * from vehicles where id='v1'"));
  assertEquals(404,call(HttpMethod.GET,"/vehicles/"+id,null,null).getStatusCode().value());
 }
 @Test void allBookingHistoryBlocksDeleteAndRestoresLinks(){
  for(String state:List.of("PENDING","CONFIRMED","IN_PROGRESS","RETURNED","COMPLETED","CANCELLED","REJECTED","EXPIRED")){
   var v=create();String id=(String)v.get("id");links(id);booking(id,state);
   var before=rows(id);var images=jdbc.queryForList("select * from vehicle_images where vehicle_id=?",id);var amenities=jdbc.queryForList("select * from vehicle_amenities where vehicle_id=?",id);var bookings=jdbc.queryForList("select * from bookings where vehicle_id=?",id);
   var result=call(HttpMethod.DELETE,"/admin/vehicles/"+id+"?version=0",null,token("demo-admin"));assertEquals(409,result.getStatusCode().value());assertTrue(result.getBody().get("message").toString().contains("Ngừng khai thác"));
   assertEquals(before,rows(id));assertEquals(images,jdbc.queryForList("select * from vehicle_images where vehicle_id=?",id));assertEquals(amenities,jdbc.queryForList("select * from vehicle_amenities where vehicle_id=?",id));assertEquals(bookings,jdbc.queryForList("select * from bookings where vehicle_id=?",id));
  }
 }
 @Test void competingDeleteWaitsForVehicleUpdateAndRejectsStaleVersion()throws Exception{
  var v=create();String id=(String)v.get("id");var executor=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Future<Integer>> pending=new java.util.concurrent.atomic.AtomicReference<>();
  try{
   tx.executeWithoutResult(ignored->{var locked=vehicles.lock(id);locked.name="Committed update";
    pending.set(executor.submit(()->{started.countDown();return delete(v,"?version=0",token("demo-admin"));}));
    try{assertTrue(started.await(5,TimeUnit.SECONDS));assertNativeVehicleLockWait();assertThrows(TimeoutException.class,()->pending.get().get(300,TimeUnit.MILLISECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
   });
   assertEquals(409,pending.get().get(10,TimeUnit.SECONDS));assertEquals("Committed update",rows(id).get("name"));
  }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
 }
 @Test void migrationHistoryMatchesFinalResources(){flyway.validate();assertEquals(List.of("1","2","3","4","5","6","7","8","9","10"),jdbc.queryForList("select version from flyway_schema_history where success=true and version is not null order by installed_rank",String.class));}
 @Test void bookingCommittedWhileVehicleLockedBlocksWaitingDelete()throws Exception{
  var v=create();String id=(String)v.get("id");links(id);var executor=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Future<Integer>> pending=new java.util.concurrent.atomic.AtomicReference<>();
  try{
   tx.executeWithoutResult(ignored->{vehicles.lock(id);booking(id,"PENDING");
    pending.set(executor.submit(()->{started.countDown();return delete(v,"?version=0",token("demo-admin"));}));
    try{assertTrue(started.await(5,TimeUnit.SECONDS));assertNativeVehicleLockWait();assertThrows(TimeoutException.class,()->pending.get().get(300,TimeUnit.MILLISECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
   });
   assertEquals(409,pending.get().get(10,TimeUnit.SECONDS));assertEquals(1,jdbc.queryForObject("select count(*) from bookings where vehicle_id=?",Integer.class,id));assertEquals(1,jdbc.queryForObject("select count(*) from vehicle_images where vehicle_id=?",Integer.class,id));assertEquals(1,jdbc.queryForObject("select count(*) from vehicle_amenities where vehicle_id=?",Integer.class,id));
  }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
 }
}
