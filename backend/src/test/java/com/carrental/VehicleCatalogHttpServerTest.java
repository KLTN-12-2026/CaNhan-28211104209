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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.jwt-secret=integration-test-only-secret-at-least-32-bytes",
    "app.token-minutes=30",
    "app.seed-enabled=true",
    "app.seed-password=IntegrationOnly123!",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
public class VehicleCatalogHttpServerTest {

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
        r.add("spring.datasource.url", () -> url == null ? "jdbc:h2:mem:vehiclecataloghttp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
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

    private ResponseEntity<Map> call(String kind, HttpMethod method, String suffix, Map<String,Object> body, String token) {
        return restTemplate.exchange("/api/v1/admin/"+kind+suffix,method,new HttpEntity<>(body,authHeaders(token)),Map.class);
    }
    private Map<String,Object> createItem(String kind) {
        ResponseEntity<Map> response=call(kind,HttpMethod.POST,"",Map.of("name","  Catalog "+UUID.randomUUID()+"  "),adminToken);
        assertEquals(HttpStatus.CREATED,response.getStatusCode());
        return response.getBody();
    }
    @Test void validationPermissionsAndNoWrites() {
        for(String kind:List.of("vehicle-brands","vehicle-categories","amenities")) {
            String table=kind.equals("vehicle-brands")?"vehicle_brands":kind.equals("amenities")?"amenities":"vehicle_categories";
            long before=jdbc.queryForObject("select count(*) from "+table,Long.class);
            for(String token:List.of(userToken,staffToken)) {
                assertEquals(HttpStatus.FORBIDDEN,call(kind,HttpMethod.POST,"",Map.of("name","Denied"),token).getStatusCode());
                assertEquals(HttpStatus.FORBIDDEN,restTemplate.exchange("/api/v1/admin/"+kind,HttpMethod.GET,new HttpEntity<>(authHeaders(token)),String.class).getStatusCode());
                assertEquals(HttpStatus.FORBIDDEN,call(kind,HttpMethod.PUT,"/unknown",Map.of("name","Denied","expectedRevision","0".repeat(64)),token).getStatusCode());
            }
            assertEquals(HttpStatus.UNAUTHORIZED,restTemplate.exchange("/api/v1/admin/"+kind,HttpMethod.GET,new HttpEntity<>(authHeaders(null)),String.class).getStatusCode());
            assertEquals(HttpStatus.UNAUTHORIZED,call(kind,HttpMethod.PUT,"/unknown",Map.of("name","Denied","expectedRevision","0".repeat(64)),null).getStatusCode());
            assertEquals(HttpStatus.UNAUTHORIZED,call(kind,HttpMethod.POST,"",Map.of("name","Denied"),null).getStatusCode());
            for(Map<String,Object> input:List.of(Map.<String,Object>of(),Map.<String,Object>of("name","  "),Map.<String,Object>of("name","x".repeat(101))))
                assertEquals(HttpStatus.BAD_REQUEST,call(kind,HttpMethod.POST,"",input,adminToken).getStatusCode());
            assertEquals(before,jdbc.queryForObject("select count(*) from "+table,Long.class));
        }
    }
    @Test void createReadUpdateConflictAndForeignKeyPreservation() {
        for(String kind:List.of("vehicle-brands","vehicle-categories","amenities")) {
            String table=kind.equals("vehicle-brands")?"vehicle_brands":kind.equals("amenities")?"amenities":"vehicle_categories";
            Map item=createItem(kind);String id=(String)item.get("id"),name=(String)item.get("name"),revision=(String)item.get("revision");
            Map publicCatalog=restTemplate.getForObject("/api/v1/vehicles/catalog",Map.class);
            String publicKey=kind.equals("vehicle-brands")?"brands":kind.equals("amenities")?"amenities":"categories";
            assertTrue(((List<Map>)publicCatalog.get(publicKey)).stream().anyMatch(row->id.equals(row.get("id")) && name.equals(row.get("name"))));
            assertEquals(name.strip(),name);assertTrue(revision.matches("[a-f0-9]{64}"));
            assertEquals(name,jdbc.queryForObject("select name from "+table+" where id=?",String.class,id));
            assertEquals(HttpStatus.CONFLICT,call(kind,HttpMethod.POST,"",Map.of("name"," "+name+" "),adminToken).getStatusCode());
            for(Map<String,Object> input:List.of(Map.<String,Object>of("name","Rename"),Map.<String,Object>of("name","Rename","expectedRevision","invalid")))
                assertEquals(HttpStatus.BAD_REQUEST,call(kind,HttpMethod.PUT,"/"+id,input,adminToken).getStatusCode());
            assertEquals(HttpStatus.NOT_FOUND,call(kind,HttpMethod.PUT,"/missing",Map.of("name","Rename","expectedRevision",revision),adminToken).getStatusCode());
            String key=kind.equals("vehicle-brands")?"brand_id":"category_id";
            String oldKey=jdbc.queryForObject("select "+key+" from vehicles where id='v1'",String.class);
            if(kind.equals("amenities")) jdbc.update("insert into vehicle_amenities(vehicle_id,amenity_id) values('v1',?)",id);
            else jdbc.update("update vehicles set "+key+"=? where id='v1'",id);
            List<Map<String,Object>> amenitiesBefore=jdbc.queryForList("select * from vehicle_amenities order by vehicle_id,amenity_id");
            String booking=UUID.randomUUID().toString();
            jdbc.update("insert into bookings(id,user_id,vehicle_id,pickup_branch_id,return_branch_id,start_at,end_at,created_at,expires_at,status,daily_price,rental_days,rental_total,one_way_fee,total,customer_name,vehicle_name,pickup_name,return_name,policy_version) values (?,'demo-user','v1','dn','dn','2026-10-01 08:00:00','2026-10-02 08:00:00','2026-09-30 08:00:00','2026-09-30 10:00:00','COMPLETED',100,1,100,0,100,'Historical renter','Historical vehicle','Snapshot pickup','Snapshot return','catalog-test')",booking);
            String snapshot="{\"catalogName\":\"Historical label\"}";
            jdbc.update("insert into contracts(id,booking_id,issued_at,snapshot,terms_version) values (?,?,'2026-09-30 09:00:00',?,'catalog-test')",UUID.randomUUID().toString(),booking,snapshot);
            jdbc.update("insert into invoices(id,booking_id,issued_at,total,snapshot) values (?,?,'2026-10-02 09:00:00',100,?)",UUID.randomUUID().toString(),booking,snapshot);
            List<Map<String,Object>> bookingBefore=jdbc.queryForList("select * from bookings where id=?",booking);
            List<Map<String,Object>> contractBefore=jdbc.queryForList("select * from contracts where booking_id=?",booking);
            List<Map<String,Object>> invoiceBefore=jdbc.queryForList("select * from invoices where booking_id=?",booking);
            List<Map<String,Object>> vehicleBefore=jdbc.queryForList("select * from vehicles order by id");
            String newName="Renamed "+UUID.randomUUID();
            ResponseEntity<Map> edited=call(kind,HttpMethod.PUT,"/"+id,Map.of("name",newName,"expectedRevision",revision),adminToken);
            assertEquals(HttpStatus.OK,edited.getStatusCode());assertNotEquals(revision,edited.getBody().get("revision"));
            assertEquals(HttpStatus.CONFLICT,call(kind,HttpMethod.PUT,"/"+id,Map.of("name","Stale","expectedRevision",revision),adminToken).getStatusCode());
            assertEquals(newName,jdbc.queryForObject("select name from "+table+" where id=?",String.class,id));
            assertEquals(vehicleBefore,jdbc.queryForList("select * from vehicles order by id"),"Catalog rename must not rewrite referenced vehicles");
            assertEquals(bookingBefore,jdbc.queryForList("select * from bookings where id=?",booking));
            assertEquals(contractBefore,jdbc.queryForList("select * from contracts where booking_id=?",booking));
            assertEquals(invoiceBefore,jdbc.queryForList("select * from invoices where booking_id=?",booking));
            assertEquals(amenitiesBefore,jdbc.queryForList("select * from vehicle_amenities order by vehicle_id,amenity_id"));
            if(!kind.equals("amenities")) jdbc.update("update vehicles set "+key+"=? where id='v1'",oldKey);
            ResponseEntity<List> list=restTemplate.exchange("/api/v1/admin/"+kind,HttpMethod.GET,new HttpEntity<>(authHeaders(adminToken)),List.class);
            assertEquals(HttpStatus.OK,list.getStatusCode());assertTrue(list.getBody().stream().anyMatch(row->id.equals(((Map)row).get("id"))));
            Map other=createItem(kind);
            assertEquals(HttpStatus.CONFLICT,call(kind,HttpMethod.PUT,"/"+id,Map.of("name",other.get("name"),"expectedRevision",edited.getBody().get("revision")),adminToken).getStatusCode());
            assertEquals(newName,jdbc.queryForObject("select name from "+table+" where id=?",String.class,id));
            assertEquals(HttpStatus.BAD_REQUEST,restTemplate.exchange("/api/v1/admin/"+kind+"/"+id,HttpMethod.DELETE,new HttpEntity<>(authHeaders(adminToken)),String.class).getStatusCode());
        }
        assertEquals(HttpStatus.OK,restTemplate.getForEntity("/api/v1/vehicles/catalog",Map.class).getStatusCode());
    }
    @Test void explicitNullRevisionAndUpdateValidationPreserveRow() {
        for(String kind:List.of("vehicle-brands","vehicle-categories","amenities")) {
            Map item=createItem(kind);String id=(String)item.get("id");
            Map<String,Object> body=new HashMap<>();body.put("name","No revision");body.put("expectedRevision",null);
            assertEquals(HttpStatus.BAD_REQUEST,call(kind,HttpMethod.PUT,"/"+id,body,adminToken).getStatusCode());
            for(String invalid:List.of("  ","x".repeat(101))) {
                assertEquals(HttpStatus.BAD_REQUEST,call(kind,HttpMethod.PUT,"/"+id,Map.of("name",invalid,"expectedRevision",item.get("revision")),adminToken).getStatusCode());
            }
            String table=kind.equals("vehicle-brands")?"vehicle_brands":kind.equals("amenities")?"amenities":"vehicle_categories";
            assertEquals(item.get("name"),jdbc.queryForObject("select name from "+table+" where id=?",String.class,id));
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="KDRIVE_TEST_DB_URL",matches="jdbc:mysql:.*")
    void concurrentMySqlWritesHaveOneWinner() throws Exception {
        for(String kind:List.of("vehicle-brands","vehicle-categories","amenities")) {
            Map item=createItem(kind);String id=(String)item.get("id"),revision=(String)item.get("revision");
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start=new CountDownLatch(1);
                List<Future<HttpStatusCode>> jobs=new ArrayList<>();
                for(int i=0;i<2;i++){final int index=i;jobs.add(workers.submit(()->{start.await();return call(kind,HttpMethod.PUT,"/"+id,Map.of("name","Parallel "+index+UUID.randomUUID(),"expectedRevision",revision),adminToken).getStatusCode();}));}
                start.countDown();List<Integer> statuses=new ArrayList<>();for(Future<HttpStatusCode> job:jobs)statuses.add(job.get(30,TimeUnit.SECONDS).value());
                Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
                CountDownLatch createStart=new CountDownLatch(1);String duplicate="Concurrent "+UUID.randomUUID();jobs.clear();
                for(int i=0;i<2;i++)jobs.add(workers.submit(()->{createStart.await();return call(kind,HttpMethod.POST,"",Map.of("name",duplicate),adminToken).getStatusCode();}));
                createStart.countDown();statuses.clear();for(Future<HttpStatusCode> job:jobs)statuses.add(job.get(30,TimeUnit.SECONDS).value());Collections.sort(statuses);assertEquals(List.of(201,409),statuses);String table=kind.equals("vehicle-brands")?"vehicle_brands":kind.equals("amenities")?"amenities":"vehicle_categories";assertEquals(1L,jdbc.queryForObject("select count(*) from "+table+" where name=?",Long.class,duplicate));
            } finally {workers.shutdownNow();}
        }
    }
    @Test void deleteUnusedCatalogRequiresAdminAndCurrentRevision() {
        for(String kind:List.of("vehicle-brands","vehicle-categories","amenities")) {
            Map item=createItem(kind);String path="/"+item.get("id"),query="?expectedRevision="+item.get("revision");
            assertEquals(401,call(kind,HttpMethod.DELETE,path+query,null,null).getStatusCode().value());
            for(String actor:List.of(userToken,staffToken))assertEquals(403,call(kind,HttpMethod.DELETE,path+query,null,actor).getStatusCode().value());
            assertEquals(400,call(kind,HttpMethod.DELETE,path,null,adminToken).getStatusCode().value());
            assertEquals(400,call(kind,HttpMethod.DELETE,path+"?expectedRevision=invalid",null,adminToken).getStatusCode().value());
            assertEquals(409,call(kind,HttpMethod.DELETE,path+"?expectedRevision="+"0".repeat(64),null,adminToken).getStatusCode().value());
            assertEquals(204,call(kind,HttpMethod.DELETE,path+query,null,adminToken).getStatusCode().value());
            assertEquals(404,call(kind,HttpMethod.DELETE,path+query,null,adminToken).getStatusCode().value());
            Map catalog=restTemplate.getForObject("/api/v1/vehicles/catalog",Map.class);
            String key=kind.equals("vehicle-brands")?"brands":kind.equals("vehicle-categories")?"categories":"amenities";
            assertFalse(((List<Map>)catalog.get(key)).stream().anyMatch(row->row.get("id").equals(item.get("id"))));
        }
    }
    @Test void referencedCatalogDeletionNeverReclassifiesVehiclesOrRemovesLinks() {
        for(String kind:List.of("vehicle-brands","vehicle-categories","amenities")) {
            Map item=createItem(kind);String id=(String)item.get("id"),column=kind.equals("vehicle-brands")?"brand_id":"category_id";
            String old=kind.equals("amenities")?null:jdbc.queryForObject("select "+column+" from vehicles where id='v1'",String.class);
            try {
                if(kind.equals("amenities"))jdbc.update("insert into vehicle_amenities(vehicle_id,amenity_id) values('v1',?)",id);
                else jdbc.update("update vehicles set "+column+"=? where id='v1'",id);
                var vehicles=jdbc.queryForList("select * from vehicles order by id");var links=jdbc.queryForList("select * from vehicle_amenities order by vehicle_id,amenity_id");
                var result=call(kind,HttpMethod.DELETE,"/"+id+"?expectedRevision="+item.get("revision"),null,adminToken);
                assertEquals(409,result.getStatusCode().value());assertTrue(result.getBody().get("message").toString().contains("1 xe"));
                assertEquals(vehicles,jdbc.queryForList("select * from vehicles order by id"));assertEquals(links,jdbc.queryForList("select * from vehicle_amenities order by vehicle_id,amenity_id"));
            } finally {
                if(kind.equals("amenities"))jdbc.update("delete from vehicle_amenities where vehicle_id='v1' and amenity_id=?",id);
                else jdbc.update("update vehicles set "+column+"=? where id='v1'",old);
            }
        }
    }
    @Autowired com.carrental.modules.vehicle.repository.VehicleCatalogRepository catalogRepository;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Test void deleteWaitsForCatalogRenameAndRejectsStaleRevision()throws Exception {
        for(String kind:List.of("vehicle-brands","vehicle-categories","amenities")) {
            Map item=createItem(kind);String id=(String)item.get("id"),table=kind.equals("vehicle-brands")?"vehicle_brands":kind.equals("vehicle-categories")?"vehicle_categories":"amenities";
            var worker=Executors.newSingleThreadExecutor();var future=new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
            try {
                transactions.executeWithoutResult(ignored->{catalogRepository.lock(kind,id).orElseThrow();catalogRepository.update(kind,id,"Updated "+id);
                    future.set(worker.submit(()->call(kind,HttpMethod.DELETE,"/"+id+"?expectedRevision="+item.get("revision"),null,adminToken).getStatusCode().value()));
                    if(System.getenv("KDRIVE_TEST_DB_URL")!=null){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int waits=0;while(System.nanoTime()<end){waits=jdbc.queryForObject("select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks l on w.requesting_engine_lock_id=l.engine_lock_id where l.object_schema=database() and l.object_name=?",Integer.class,table);if(waits>0)break;try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}assertTrue(waits>0,"Actual MySQL lock wait required");}
                });
                assertEquals(409,future.get().get(10,TimeUnit.SECONDS));assertEquals("Updated "+id,jdbc.queryForObject("select name from "+table+" where id=?",String.class,id));
            } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
        }
    }
}
