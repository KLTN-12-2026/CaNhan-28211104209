package com.carrental;

import com.carrental.modules.user.service.*;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.payment.repository.PaymentRepository;
import com.carrental.modules.payment.service.*;
import com.carrental.modules.operation.service.DepositWorkflow;
import com.carrental.security.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Return receipt HTTP, transaction and native MySQL race proof on an isolated schema. */
@SpringBootTest(properties={"app.jwt-secret=qr-demo-test-only-secret-at-least-32-bytes", "app.seed-enabled=true", "app.seed-password=QRDemoTestOnly123!", "app.deposit-enabled=true", "app.return-policy-enabled=true", "app.deposit-method=DEMO_QR_MANUAL", "app.payment-reconcile-ms=86400000", "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"})
@AutoConfigureMockMvc
@Import(ReturnReceiptHttpTest.TimeConfig.class)
class ReturnReceiptHttpTest {
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("qr-demo-synthetic-photos-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        String url=System.getenv("KDRIVE_TEST_DB_URL");
        if(url!=null)CancelBoundaryTest.verifySafeIsolatedDatabase(url,System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),System.getenv("KDRIVE_TEST_DB_USER"),System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        r.add("spring.datasource.url",()->url==null?"jdbc:h2:mem:return-receipt;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1":url);
        r.add("spring.datasource.username",()->url==null?"sa":System.getenv("KDRIVE_TEST_DB_USER"));r.add("spring.datasource.password",()->url==null?"":System.getenv("KDRIVE_TEST_DB_PASSWORD"));r.add("app.license-photo-directory",()->ROOT.toString());
    }
    static class FixtureClock extends Clock {Instant value=Instant.parse("2026-10-23T03:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return value;}}
    @TestConfiguration static class TimeConfig {@Bean @Primary FixtureClock qrDemoClock(){return new FixtureClock();}}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired FixtureClock clock;
    @Autowired LicenseService licenses;@Autowired BookingService bookings;@Autowired PaymentService payments;@Autowired DepositWorkflow workflow;@Autowired PaymentRepository repo;
    @org.springframework.boot.test.mock.mockito.SpyBean VnpayGateway gateway;
    @Autowired TokenService tokens;@Autowired AccountService accounts;
    @Autowired com.carrental.modules.operation.service.RentalWorkflow rental;
    final Actor user=new Actor("demo-user","USER","Synthetic"),staff=new Actor("demo-staff-dn","STAFF","Synthetic"),admin=new Actor("demo-admin","ADMIN","Synthetic");
    @org.springframework.boot.test.mock.mockito.SpyBean com.carrental.modules.contract.service.ContractService contracts;
    @BeforeEach void setup(){
        org.mockito.Mockito.reset(contracts);
        jdbc.update("delete from staff_branches where user_id='demo-staff-hn' and branch_id='dn'");
        org.springframework.test.util.ReflectionTestUtils.setField(bookings,"returnPolicyEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(bookings,"depositEnabled",false);
        CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);
        for(String t:List.of("booking_return_settlements","payment_events","payment_refunds","payment_transactions","reviews","incident_events","incident_cases","vehicle_handover_records","invoice_items","invoices","contracts","booking_status_history","bookings"))jdbc.update("delete from "+t);
        jdbc.update("update vehicles set current_branch_id='dn',status='ACTIVE',daily_price=650000 where id='v1'");clock.value=Instant.parse("2026-10-23T03:00:00Z");
        var old=licenses.mine(user);licenses.save(user,new LicenseService.View(null,"SYNTHETIC-QR-DEMO-NOT-REAL","VN_2025","B",LocalDate.of(2035,12,31),false,"PENDING",old==null?0:old.version()));LicensePhotoFixture.approved(licenses,user);
    }

    final Actor otherStaff = new Actor("demo-staff-hn", "STAFF", "Synthetic");
    org.springframework.mock.web.MockHttpServletResponse post(Actor a, String path, Object input) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1" + path).header("Authorization", "Bearer " + tokens.issue(accounts.require(a.id())))
            .contentType("application/json").content(json.writeValueAsString(input))).andReturn().getResponse();
    }
    org.springframework.mock.web.MockHttpServletResponse get(Actor a, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1" + path).header("Authorization", "Bearer " + tokens.issue(accounts.require(a.id())))).andReturn().getResponse();
    }
    JsonNode body(org.springframework.mock.web.MockHttpServletResponse r) throws Exception { return json.readTree(r.getContentAsString()); }
    BookingService.View current(String id) { return bookings.view(bookings.require(user, id)); }
    BookingService.View open(boolean policy, boolean calendar, String returnedBranch) throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(bookings, "returnPolicyEnabled", policy);
        var b = bookings.create(user, new BookingService.Input("v1", "dn", returnedBranch,
            calendar ? null : Instant.parse("2026-10-26T03:00:00Z"), calendar ? null : Instant.parse("2026-10-28T03:00:00Z"),
            returnedBranch.equals("dn") ? 1300000L : 2800000L,
            calendar ? LocalDate.of(2026,10,26) : null, calendar ? LocalDate.of(2026,10,28) : null,
            policy && calendar && returnedBranch.equals("dn") ? "same-branch-return-v1" : null,
            policy && calendar && returnedBranch.equals("dn") ? true : null));
        assertEquals(200, post(staff, "/bookings/"+b.id()+"/actions", Map.of("next","CONFIRMED","version",b.version())).getStatus());
        clock.value=Instant.parse("2026-10-26T03:00:00Z"); b=current(b.id());
        assertEquals(200, post(staff,"/bookings/"+b.id()+"/actions",Map.of("next","IN_PROGRESS","version",b.version(),"condition","Synthetic handover","licenseChecked",true)).getStatus());
        return current(b.id());
    }
    Map<String,Object> receipt(String id) throws Exception {
        var r=get(staff,"/bookings/"+id+"/return-preview"); assertEquals(200,r.getStatus(),r.getContentAsString());
        var q=body(r); return new LinkedHashMap<>(Map.of("next","RETURNED","version",q.get("version").asLong(),
            "condition","Synthetic return","returnPreviewDate",q.at("/assessment/actualReturnDate").asText(),"expectedLateAmount",q.at("/assessment/amountVnd").asLong()));
    }
    long returns(String id) { return jdbc.queryForObject("select count(*) from vehicle_handover_records where booking_id=? and kind='RETURN'",Long.class,id); }
    long invoices(String id) { return jdbc.queryForObject("select count(*) from invoices where booking_id=?",Long.class,id); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "2026-10-27T03:00:00Z,EARLY,0,0",
        "2026-10-28T16:59:59.999999Z,ON_TIME,0,0",
        "2026-10-28T17:00:00Z,LATE,1,650000",
        "2026-10-30T17:00:00Z,LATE,3,1950000"
    })
    void assessmentUsesVietnamDayAndSavedPriceWithoutChangingBookingOrContract(String now,String timing,int days,long amount) throws Exception {
        var b=open(true,true,"dn"); String contract=jdbc.queryForObject("select snapshot from contracts where booking_id=?",String.class,b.id());
        jdbc.update("update vehicles set daily_price=990000 where id='v1'");
        org.springframework.test.util.ReflectionTestUtils.setField(bookings,"returnPolicyEnabled",false);
        clock.value=Instant.parse(now); var action=receipt(b.id());
        var r=post(staff,"/bookings/"+b.id()+"/actions",action); assertEquals(200,r.getStatus(),r.getContentAsString());
        var returned=body(r); var assessment=returned.get("returnAssessment");
        assertEquals(timing,assessment.get("timing").asText());assertEquals(days,assessment.get("lateDays").asInt());assertEquals(amount,assessment.get("amountVnd").asLong());
        assertEquals("2026-10-28",assessment.get("bookedReturnDate").asText());assertEquals(650000,assessment.get("dailyPriceVnd").asLong());
        assertEquals(amount>0?"UNPAID":"NOT_DUE",assessment.get("collectionStatus").asText());
        assertEquals(b.startAt(),current(b.id()).startAt());assertEquals(b.endAt(),current(b.id()).endAt());assertEquals(1300000,current(b.id()).total());
        assertEquals(contract,jdbc.queryForObject("select snapshot from contracts where booking_id=?",String.class,b.id()));
        assertEquals(1,returns(b.id()));assertEquals(clock.instant(),jdbc.queryForObject("select occurred_at from vehicle_handover_records where booking_id=? and kind='RETURN'",java.sql.Timestamp.class,b.id()).toInstant());
        assertEquals(assessment,body(get(user,"/bookings/"+b.id())).at("/booking/returnAssessment"));
        assertEquals(assessment,body(get(admin,"/bookings/"+b.id())).at("/booking/returnAssessment"));
        if("EARLY".equals(timing)) assertTrue(com.carrental.modules.booking.service.SchedulePolicy.occupies(bookings.slot(bookings.require(user,b.id())),clock.instant()));
        var completed=post(staff,"/bookings/"+b.id()+"/actions",Map.of("next","COMPLETED","version",returned.get("version").asLong()));
        assertEquals(amount>0?409:200,completed.getStatus(),completed.getContentAsString()); assertEquals(amount>0?0:1,invoices(b.id()));
        if(amount==0) assertEquals(1300000,jdbc.queryForObject("select total from invoices where booking_id=?",Long.class,b.id()));
    }
    @Test void missingStaleOrTamperedPreviewCannotWrite() throws Exception {
        var b=open(true,true,"dn");clock.value=Instant.parse("2026-10-29T03:00:00Z");var valid=receipt(b.id());
        for(String field:List.of("returnPreviewDate","expectedLateAmount")) {
            var input=new LinkedHashMap<>(valid);input.remove(field);assertEquals(409,post(staff,"/bookings/"+b.id()+"/actions",input).getStatus());
        }
        for(var entry:Map.<String,Object>of("returnPreviewDate","2026-10-28","expectedLateAmount",0,"version",0).entrySet()) {
            var input=new LinkedHashMap<>(valid);input.put(entry.getKey(),entry.getValue());assertEquals(409,post(staff,"/bookings/"+b.id()+"/actions",input).getStatus());
        }
        assertEquals("IN_PROGRESS",current(b.id()).status());assertNull(current(b.id()).returnAssessment());assertEquals(0,returns(b.id()));
    }
    @Test void midnightRequiresNewPreviewAndDuplicateCannotChargeAgain() throws Exception {
        var b=open(true,true,"dn");clock.value=Instant.parse("2026-10-28T16:59:59Z");var old=receipt(b.id());clock.value=Instant.parse("2026-10-28T17:00:00Z");
        assertEquals(409,post(staff,"/bookings/"+b.id()+"/actions",old).getStatus());assertEquals(0,returns(b.id()));
        var next=receipt(b.id());assertEquals(200,post(staff,"/bookings/"+b.id()+"/actions",next).getStatus());
        assertEquals(409,post(staff,"/bookings/"+b.id()+"/actions",next).getStatus());assertEquals(1,returns(b.id()));assertEquals(650000,current(b.id()).returnAssessment().amountVnd());
    }
    @Test void permissionAndStateNegativesDoNotMutate() throws Exception {
        var b=open(true,true,"dn");var input=receipt(b.id());
        assertEquals(401,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/bookings/"+b.id()+"/return-preview")).andReturn().getResponse().getStatus());
        assertEquals(403,get(user,"/bookings/"+b.id()+"/return-preview").getStatus());assertEquals(403,get(admin,"/bookings/"+b.id()+"/return-preview").getStatus());
        assertEquals(404,get(otherStaff,"/bookings/"+b.id()+"/return-preview").getStatus());
        for(var actor:List.of(user,admin,otherStaff))assertEquals(403,post(actor,"/bookings/"+b.id()+"/actions",input).getStatus());
        assertEquals(0,returns(b.id()));assertNull(current(b.id()).returnAssessment());
        assertEquals(200,post(staff,"/bookings/"+b.id()+"/actions",input).getStatus());assertEquals(409,get(staff,"/bookings/"+b.id()+"/return-preview").getStatus());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,true,dn","true,false,dn","true,true,hn"})
    void flagOffNewLegacyAndCrossBranchKeepOldReturnFlow(boolean policy,boolean calendar,String branch) throws Exception {
        var b=open(policy,calendar,branch);clock.value=Instant.parse("2026-10-31T03:00:00Z");var receiver=branch.equals("dn")?staff:otherStaff;
        assertEquals(409,get(receiver,"/bookings/"+b.id()+"/return-preview").getStatus());
        var returned=post(receiver,"/bookings/"+b.id()+"/actions",Map.of("next","RETURNED","version",b.version(),"condition","Legacy-compatible return"));
        assertEquals(200,returned.getStatus(),returned.getContentAsString());assertTrue(body(returned).get("returnAssessment").isNull());
        assertEquals(200,post(receiver,"/bookings/"+b.id()+"/actions",Map.of("next","COMPLETED","version",body(returned).get("version").asLong())).getStatus());assertEquals(1,invoices(b.id()));
    }
    @Test void recordFailureRollsBackAssessmentStateMaintenanceAndHistory() throws Exception {
        var b=open(true,true,"dn");clock.value=Instant.parse("2026-10-31T03:00:00Z");var action=receipt(b.id());action.put("maintenance",true);
        jdbc.update("insert into vehicle_handover_records(id,booking_id,kind,actor_id,branch_id,occurred_at,condition_note,maintenance) values(?,?,'RETURN','demo-staff-dn','dn',?,'Synthetic duplicate fixture',false)",UUID.randomUUID().toString(),b.id(),java.sql.Timestamp.from(clock.instant()));
        var rejected=post(staff,"/bookings/"+b.id()+"/actions",action);
        assertEquals(409,rejected.getStatus());assertTrue(rejected.getContentAsString().contains("Dữ liệu trùng hoặc đã thay đổi"));
        assertEquals("IN_PROGRESS",current(b.id()).status());assertNull(current(b.id()).returnAssessment());assertEquals(b.version(),current(b.id()).version());
        assertEquals("ACTIVE",jdbc.queryForObject("select status from vehicles where id='v1'",String.class));assertEquals(0,jdbc.queryForObject("select count(*) from booking_status_history where booking_id=? and next_status='RETURNED'",Long.class,b.id()));assertEquals(0,invoices(b.id()));
    }
    @Test void twoStaffWithSamePreviewOnlyRecordOneReceipt() throws Exception {
        var b=open(true,true,"dn");clock.value=Instant.parse("2026-10-31T03:00:00Z");var input=receipt(b.id());jdbc.update("insert into staff_branches(user_id,branch_id) values('demo-staff-hn','dn')");
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->{start.await();return post(staff,"/bookings/"+b.id()+"/actions",input).getStatus();});
            var two=pool.submit(()->{start.await();return post(otherStaff,"/bookings/"+b.id()+"/actions",input).getStatus();});start.countDown();
            var statuses=new ArrayList<>(List.of(one.get(20,java.util.concurrent.TimeUnit.SECONDS),two.get(20,java.util.concurrent.TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        assertEquals(1,returns(b.id()));assertEquals(1,jdbc.queryForObject("select count(*) from booking_status_history where booking_id=? and next_status='RETURNED'",Long.class,b.id()));assertEquals(1950000,current(b.id()).returnAssessment().amountVnd());
    }
    @Test void lateReturnDoesNotConsumeOrRefundVerifiedDemoDeposit() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(bookings,"depositEnabled",true);
        var b=bookings.create(user,new BookingService.Input("v1","dn","dn",null,null,1300000L,LocalDate.of(2026,10,26),LocalDate.of(2026,10,28),"same-branch-return-v1",true));
        b=(BookingService.View)workflow.approve(staff,b.id(),b.version());
        var payment=new LinkedHashMap<String,Object>(Map.of("transactionRef","RETURN-PAID-DEMO","amountVnd",390000,
            "transferTime",clock.instant().toString(),"transferNote",BookingService.requestCode(b.id()),"sourceAccount","DEMO-NOT-REAL",
            "idempotencyKey","return-paid-once","expectedVersion",b.version(),"verified",true));
        var verified=post(staff,"/bookings/"+b.id()+"/deposit/verify",payment);assertEquals(200,verified.getStatus(),verified.getContentAsString());
        b=current(b.id());assertEquals("CONFIRMED",b.status());
        var ledger=jdbc.queryForList("select * from payment_transactions where booking_id=?",b.id());
        var contract=jdbc.queryForObject("select snapshot from contracts where booking_id=?",String.class,b.id());
        clock.value=Instant.parse("2026-10-26T03:00:00Z");
        assertEquals(200,post(staff,"/bookings/"+b.id()+"/actions",Map.of("next","IN_PROGRESS","version",b.version(),"condition","Fixture handover","licenseChecked",true)).getStatus());
        clock.value=Instant.parse("2026-10-31T03:00:00Z");
        assertEquals(200,post(staff,"/bookings/"+b.id()+"/actions",receipt(b.id())).getStatus());
        assertEquals(ledger,jdbc.queryForList("select * from payment_transactions where booking_id=?",b.id()));
        assertEquals(0,jdbc.queryForObject("select count(*) from payment_refunds where booking_id=?",Long.class,b.id()));
        assertEquals(390000,current(b.id()).deposit().appliedAmount());assertEquals(1950000,current(b.id()).returnAssessment().amountVnd());
        assertEquals("UNPAID",current(b.id()).returnAssessment().collectionStatus());assertEquals(contract,jdbc.queryForObject("select snapshot from contracts where booking_id=?",String.class,b.id()));
    }
}
