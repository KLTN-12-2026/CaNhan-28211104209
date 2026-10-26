package com.carrental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.*;
import com.carrental.security.TokenService;
import com.carrental.modules.user.service.AccountService;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.carrental.modules.vehicle.repository.VehicleAmenityRepository;
import com.carrental.modules.vehicle.service.VehicleAmenityService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.jwt-secret=integration-test-only-secret-at-least-32-bytes",
    "app.token-minutes=30",
    "app.seed-enabled=true",
    "app.seed-password=IntegrationOnly123!",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
public class VehicleAmenityHttpServerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TokenService tokens;

    @Autowired
    private AccountService accountService;

    private String adminToken;
    private String userToken;
    private String staffToken;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            CancelBoundaryTest.verifySafeIsolatedDatabase(url,
                System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),
                System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),
                System.getenv("KDRIVE_TEST_DB_USER"),
                System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        }
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:vehicleamenityhttp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        r.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("KDRIVE_TEST_DB_USER"));
        r.add("spring.datasource.password", () -> url == null ? "" : System.getenv("KDRIVE_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void setup() {
        ensureUserExists("demo-admin", "Admin C8", "admin@example.test", "ADMIN");
        ensureUserExists("demo-user", "User C8", "user@example.test", "USER");
        ensureUserExists("demo-staff-hn", "Staff HN C8", "staff-hn@example.test", "STAFF");

        adminToken = "Bearer " + tokens.issue(accountService.require("demo-admin"));
        userToken = "Bearer " + tokens.issue(accountService.require("demo-user"));
        staffToken = "Bearer " + tokens.issue(accountService.require("demo-staff-hn"));
    }

    private void ensureUserExists(String id, String fullName, String email, String role) {
        Long cnt = jdbc.queryForObject("select count(*) from users where id=?", Long.class, id);
        if (cnt == 0) {
            jdbc.update("insert into users(id, full_name, email, phone, password_hash, role, status, token_version, version) values(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, fullName, email, "0900000000", "$2a$10$dummyHashNotUsableDirectly12345678901234567890123456", role, "ACTIVE", 0L, 0L);
        }
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("Authorization", token);
        }
        return headers;
    }


    @MockitoSpyBean private VehicleAmenityRepository links;
    @MockitoSpyBean private JdbcTemplate tracedJdbc;
    @Autowired private VehicleAmenityService service;
    private String amenity() {
        String id=UUID.randomUUID().toString();jdbc.update("insert into amenities(id,name) values (?,?)",id,"Amenity "+id);return id;
    }
    private ResponseEntity<Map> request(String vehicle,HttpMethod method,Map body,String token) {
        return restTemplate.exchange("/api/v1/admin/vehicles/"+vehicle+"/amenities",method,new HttpEntity<>(body,authHeaders(token)),Map.class);
    }
    private Map read(String vehicle) {return request(vehicle,HttpMethod.GET,null,adminToken).getBody();}
    private List<String> ids(String vehicle) {return jdbc.queryForList("select amenity_id from vehicle_amenities where vehicle_id=? order by amenity_id",String.class,vehicle);}
    private Map payload(List<String> ids,Object revision) {Map body=new HashMap();body.put("amenityIds",ids);body.put("expectedRevision",revision);return body;}
    private Map save(String vehicle,List<String> ids) {
        ResponseEntity<Map> response=request(vehicle,HttpMethod.PUT,payload(ids,read(vehicle).get("revision")),adminToken);
        assertEquals(HttpStatus.OK,response.getStatusCode());return response.getBody();
    }
    @Test void permissionsValidationAndAtomicRejection() {
        String a=amenity();save("v1",List.of(a));List<String> before=ids("v1");String revision=(String)read("v1").get("revision");
        for(String token:List.of(userToken,staffToken)) {
            assertEquals(HttpStatus.FORBIDDEN,request("v1",HttpMethod.GET,null,token).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN,request("v1",HttpMethod.PUT,payload(List.of(),revision),token).getStatusCode());
        }
        for(HttpMethod method:List.of(HttpMethod.GET,HttpMethod.PUT))assertEquals(HttpStatus.UNAUTHORIZED,request("v1",method,payload(List.of(),revision),null).getStatusCode());
        for(List<String> invalid:Arrays.asList(null,Arrays.asList((String)null),List.of(" "),List.of(a,a),List.of(a,"unknown"))) {
            assertEquals(HttpStatus.BAD_REQUEST,request("v1",HttpMethod.PUT,payload(invalid,revision),adminToken).getStatusCode());assertEquals(before,ids("v1"));
        }
        assertEquals(HttpStatus.BAD_REQUEST,request("v1",HttpMethod.PUT,Map.of("expectedRevision",revision),adminToken).getStatusCode());
        for(Object bad:Arrays.asList(null,"invalid"))assertEquals(HttpStatus.BAD_REQUEST,request("v1",HttpMethod.PUT,payload(List.of(),bad),adminToken).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,request("v1",HttpMethod.PUT,Map.of("amenityIds",List.of()),adminToken).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,request("missing",HttpMethod.GET,null,adminToken).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,request("missing",HttpMethod.PUT,payload(List.of(),revision),adminToken).getStatusCode());assertEquals(before,ids("v1"));
    }
    @Test void publicLabelsNoOpAndIndependentVehicleEdit() {
        String a=amenity(),b=amenity();save("v2",List.of(b));List<String> other=ids("v2");Map saved=save("v1",List.of(a,b));
        List<Map<String,Object>> vehicleBefore=jdbc.queryForList("select * from vehicles order by id");
        String booking=UUID.randomUUID().toString();
        jdbc.update("insert into bookings(id,user_id,vehicle_id,pickup_branch_id,return_branch_id,start_at,end_at,created_at,expires_at,status,daily_price,rental_days,rental_total,one_way_fee,total,customer_name,vehicle_name,pickup_name,return_name,policy_version) values (?,'demo-user','v1','dn','dn','2026-10-01 08:00:00','2026-10-02 08:00:00','2026-09-30 08:00:00','2026-09-30 10:00:00','COMPLETED',100,1,100,0,100,'Historical renter','Historical vehicle','Snapshot pickup','Snapshot return','amenity-test')",booking);
        jdbc.update("insert into contracts(id,booking_id,issued_at,snapshot,terms_version) values (?,?,'2026-09-30 09:00:00','{}','amenity-test')",UUID.randomUUID().toString(),booking);
        jdbc.update("insert into invoices(id,booking_id,issued_at,total,snapshot) values (?,?,'2026-10-02 09:00:00',100,'{}')",UUID.randomUUID().toString(),booking);
        var history=jdbc.queryForList("select * from bookings where id=?",booking);var contracts=jdbc.queryForList("select * from contracts where booking_id=?",booking);var invoices=jdbc.queryForList("select * from invoices where booking_id=?",booking);
        List<String> reversed=new ArrayList<>(ids("v1"));Collections.reverse(reversed);clearInvocations(links,tracedJdbc);
        assertEquals(saved,request("v1",HttpMethod.PUT,payload(reversed,saved.get("revision")),adminToken).getBody());
        verify(links,never()).deleteByVehicleId(anyString());verify(links,never()).insertLinks(anyString(),anyList());
        assertEquals(0,mockingDetails(tracedJdbc).getInvocations().stream().filter(i->Set.of("update","batchUpdate","execute").contains(i.getMethod().getName())).count());
        var catalog=restTemplate.exchange("/api/v1/admin/amenities",HttpMethod.GET,new HttpEntity<>(authHeaders(adminToken)),List.class).getBody();
        Map item=(Map)catalog.stream().filter(row->a.equals(((Map)row).get("id"))).findFirst().orElseThrow();
        var rename=restTemplate.exchange("/api/v1/admin/amenities/"+a,HttpMethod.PUT,new HttpEntity<>(Map.of("name","Label "+a,"expectedRevision",item.get("revision")),authHeaders(adminToken)),Map.class);assertEquals(HttpStatus.OK,rename.getStatusCode());
        assertEquals(saved.get("revision"),read("v1").get("revision"));
        Map detail=restTemplate.getForObject("/api/v1/vehicles/v1",Map.class);assertTrue(((List<Map>)detail.get("amenities")).stream().anyMatch(row->a.equals(row.get("id"))&&("Label "+a).equals(row.get("name"))));
        List<Map> all=restTemplate.getForObject("/api/v1/vehicles",List.class);Map second=all.stream().filter(row->"v2".equals(row.get("id"))).findFirst().orElseThrow();assertEquals(1,((List)second.get("amenities")).size());
        assertEquals(vehicleBefore,jdbc.queryForList("select * from vehicles order by id"));
        detail.remove("amenities");assertEquals(HttpStatus.OK,restTemplate.exchange("/api/v1/admin/vehicles/v1",HttpMethod.PUT,new HttpEntity<>(detail,authHeaders(adminToken)),Map.class).getStatusCode());assertEquals(new TreeSet<>(List.of(a,b)),new TreeSet<>(ids("v1")));
        String oldRevision=(String)saved.get("revision");save("v1",List.of(a));assertEquals(HttpStatus.CONFLICT,request("v1",HttpMethod.PUT,payload(List.of(b),oldRevision),adminToken).getStatusCode());assertEquals(List.of(a),ids("v1"));
        save("v1",List.of());assertEquals(List.of(),ids("v1"));assertEquals(other,ids("v2"));assertEquals(2L,jdbc.queryForObject("select count(*) from amenities where id in (?,?)",Long.class,a,b));
        assertEquals(history,jdbc.queryForList("select * from bookings where id=?",booking));assertEquals(contracts,jdbc.queryForList("select * from contracts where booking_id=?",booking));assertEquals(invoices,jdbc.queryForList("select * from invoices where booking_id=?",booking));
    }
    @Test void rollbackAfterRealDeleteAndPartialInsert() {
        String a=amenity(),b=amenity();save("v1",List.of(a));String revision=(String)read("v1").get("revision");
        doAnswer(invocation->{jdbc.update("insert into vehicle_amenities(vehicle_id,amenity_id) values (?,?)","v1",b);throw new IllegalStateException("AFTER_REAL_MUTATION");}).when(links).insertLinks(eq("v1"),anyList());
        org.springframework.dao.InvalidDataAccessApiUsageException error=assertThrows(org.springframework.dao.InvalidDataAccessApiUsageException.class,()->service.updateAmenities(new com.carrental.security.Actor("demo-admin","ADMIN","Admin test"),"v1",List.of(b),revision));
        assertEquals("AFTER_REAL_MUTATION",error.getMostSpecificCause().getMessage());assertEquals(List.of(a),ids("v1"));
    }
    @Test void bulkReadUsesBoundedRealQueries() {
        String a=amenity(),b=amenity();save("v1",List.of(a,b));save("v2",List.of(a,b));clearInvocations(tracedJdbc);
        var result=service.getAmenitiesBulk(List.of("v1","v2","v3"));assertEquals(2,result.get("v1").size());assertEquals(List.of(),result.get("v3"));
        assertEquals(2,mockingDetails(tracedJdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("query") && i.getMethod().getParameterTypes().length==3 && i.getMethod().getParameterTypes()[1]==org.springframework.jdbc.core.RowMapper.class).count());
    }
    @Test @EnabledIfEnvironmentVariable(named="KDRIVE_TEST_DB_URL",matches="jdbc:mysql:.*")
    void concurrentMySqlUpdatesHaveOneWinner() throws Exception {
        String a=amenity(),b=amenity();save("v1",List.of());String revision=(String)read("v1").get("revision");
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map>>> futures=new ArrayList<>();for(String id:List.of(a,b))futures.add(pool.submit(()->{ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));return request("v1",HttpMethod.PUT,payload(List.of(id),revision),adminToken);}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();List<Integer> statuses=new ArrayList<>();Map winner=null;for(var f:futures){var response=f.get(20,TimeUnit.SECONDS);statuses.add(response.getStatusCode().value());if(response.getStatusCode().value()==200)winner=response.getBody();}Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
            List<Map> winnerItems=(List<Map>)winner.get("amenities");assertEquals(List.of((String)winnerItems.getFirst().get("id")),ids("v1"));
        }finally{go.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
}
