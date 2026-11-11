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
@Import(ReturnSettlementHttpTest.TimeConfig.class)
class ReturnSettlementHttpTest {
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("qr-demo-synthetic-photos-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        String url=System.getenv("KDRIVE_TEST_DB_URL");
        if(url!=null)CancelBoundaryTest.verifySafeIsolatedDatabase(url,System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),System.getenv("KDRIVE_TEST_DB_USER"),System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        r.add("spring.datasource.url",()->url==null?"jdbc:h2:mem:return-settlement;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1":url);
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
        jdbc.update("delete from booking_return_settlements where kind='REVERSAL'");
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
    BookingService.View late() throws Exception {
        var b=open(true,true,"dn");clock.value=Instant.parse("2026-10-31T03:00:00Z");
        assertEquals(200,post(staff,"/bookings/"+b.id()+"/actions",receipt(b.id())).getStatus());return current(b.id());
    }
    Map<String,Object> entry(String id,String kind,long amount) {
        var m=new LinkedHashMap<String,Object>();m.put("kind",kind);m.put("amountVnd",amount);m.put("reason","Synthetic reason");m.put("expectedVersion",current(id).version());m.put("idempotencyKey",UUID.randomUUID().toString());m.put("confirmed",true);
        if(kind.equals("RECEIPT")){m.put("method","CASH");m.put("reference","SYNTHETIC-"+UUID.randomUUID());}return m;
    }
    org.springframework.mock.web.MockHttpServletResponse record(Actor a,String id,Map<String,Object> m)throws Exception{return post(a,"/bookings/"+id+"/return-settlements",m);}
    int complete(String id)throws Exception{return post(staff,"/bookings/"+id+"/actions",Map.of("next","COMPLETED","version",current(id).version())).getStatus();}
    @Test void partialReceiptWaiverFinalReceiptAndInvoicePreserveOriginals()throws Exception{
        var b=late();var original=jdbc.queryForMap("select total,rental_total,return_assessment,return_policy_snapshot from bookings where id=?",b.id());var contract=jdbc.queryForList("select * from contracts where booking_id=?",b.id());
        assertEquals(200,record(staff,b.id(),entry(b.id(),"RECEIPT",1000000)).getStatus());assertEquals(409,complete(b.id()));
        assertEquals(950000,current(b.id()).returnSettlement().outstandingAmount());
        assertEquals(200,record(admin,b.id(),entry(b.id(),"WAIVER",450000)).getStatus());assertEquals(500000,current(b.id()).returnSettlement().outstandingAmount());
        assertEquals(200,record(staff,b.id(),entry(b.id(),"RECEIPT",500000)).getStatus());assertEquals("SETTLED",current(b.id()).returnSettlement().status());
        assertEquals(200,complete(b.id()));assertEquals(2800000,jdbc.queryForObject("select total from invoices where booking_id=?",Long.class,b.id()));
        assertEquals(2800000,jdbc.queryForObject("select sum(amount) from invoice_items where invoice_id=(select id from invoices where booking_id=?)",Long.class,b.id()));
        var snapshot=json.readTree(jdbc.queryForObject("select snapshot from invoices where booking_id=?",String.class,b.id()));assertEquals(450000,snapshot.at("/returnSettlement/waivedAmount").asLong());assertEquals(1500000,snapshot.at("/returnSettlement/receivedAmount").asLong());
        assertEquals(original,jdbc.queryForMap("select total,rental_total,return_assessment,return_policy_snapshot from bookings where id=?",b.id()));assertEquals(contract,jdbc.queryForList("select * from contracts where booking_id=?",b.id()));
        assertEquals(0,jdbc.queryForObject("select count(*) from payment_transactions",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from payment_refunds",Integer.class));
        assertEquals(409,complete(b.id()));assertEquals(1,invoices(b.id()));
        assertEquals(body(get(user,"/bookings/"+b.id())).at("/booking/returnSettlement"),body(get(admin,"/bookings/"+b.id())).at("/booking/returnSettlement"));
    }
    @Test void fullWaiverIsNotPaymentAndReversalReopensOutstanding()throws Exception{
        var b=late();var input=entry(b.id(),"WAIVER",1950000);assertEquals(200,record(admin,b.id(),input).getStatus());assertEquals("WAIVED",current(b.id()).returnSettlement().status());
        var id=current(b.id()).returnSettlement().entries().get(0).id();var reverse=entry(b.id(),"REVERSAL",1);reverse.remove("amountVnd");reverse.put("reversesId",id);
        assertEquals(200,record(admin,b.id(),reverse).getStatus());assertEquals(1950000,current(b.id()).returnSettlement().outstandingAmount());assertEquals(409,complete(b.id()));
        reverse.put("idempotencyKey",UUID.randomUUID().toString());reverse.put("expectedVersion",current(b.id()).version());assertEquals(409,record(admin,b.id(),reverse).getStatus());
        assertEquals(200,record(admin,b.id(),entry(b.id(),"WAIVER",1950000)).getStatus());assertEquals(200,complete(b.id()));assertEquals(1300000,jdbc.queryForObject("select total from invoices where booking_id=?",Long.class,b.id()));
        reverse.put("reversesId",current(b.id()).returnSettlement().entries().stream().filter(e->e.kind().equals("WAIVER")&&!e.id().equals(id)).findFirst().orElseThrow().id());reverse.put("expectedVersion",current(b.id()).version());assertEquals(409,record(admin,b.id(),reverse).getStatus());
    }
    @Test void idempotencyRejectsChangedPayloadAndCanReadBackAfterCompletion()throws Exception{
        var b=late();var m=entry(b.id(),"RECEIPT",1950000);assertEquals(200,record(staff,b.id(),m).getStatus());long version=current(b.id()).version();
        assertEquals(200,record(staff,b.id(),m).getStatus());assertEquals(version,current(b.id()).version());assertEquals(1,current(b.id()).returnSettlement().entries().size());
        m.put("amountVnd",1);assertEquals(409,record(staff,b.id(),m).getStatus());m.put("amountVnd",1950000);assertEquals(200,complete(b.id()));assertEquals(200,record(staff,b.id(),m).getStatus());
    }
    @Test void permissionInputAndStateNegativesLeaveLedgerEmpty()throws Exception{
        var b=late();var m=entry(b.id(),"RECEIPT",1);
        for(var a:List.of(user,admin,otherStaff))assertEquals(403,record(a,b.id(),m).getStatus());
        assertEquals(403,record(staff,b.id(),entry(b.id(),"WAIVER",1)).getStatus());assertEquals(403,record(staff,b.id(),entry(b.id(),"REVERSAL",1)).getStatus());
        for(var change:List.<Map<String,Object>>of(Map.of("amountVnd",0),Map.of("amountVnd",-1),Map.of("amountVnd",1.5),Map.of("amountVnd",new java.math.BigDecimal("999999999999999999999999")),Map.of("confirmed",false),Map.of("reason",""),Map.of("reference",""),Map.of("method","ONLINE"))) {var invalid=new LinkedHashMap<>(m);invalid.putAll(change);assertEquals(400,record(staff,b.id(),invalid).getStatus());}
        var excess=entry(b.id(),"RECEIPT",1950001);assertEquals(409,record(staff,b.id(),excess).getStatus());m.put("expectedVersion",0);assertEquals(409,record(staff,b.id(),m).getStatus());assertEquals(0,current(b.id()).returnSettlement().entries().size());
    }
    @Test void concurrentReceiptAndWaiverCannotExceedBalance()throws Exception{
        var b=late();var one=entry(b.id(),"RECEIPT",1950000);var two=entry(b.id(),"WAIVER",1950000);
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var x=pool.submit(()->{start.await();return record(staff,b.id(),one).getStatus();});var y=pool.submit(()->{start.await();return record(admin,b.id(),two).getStatus();});start.countDown();var statuses=new ArrayList<>(List.of(x.get(20,java.util.concurrent.TimeUnit.SECONDS),y.get(20,java.util.concurrent.TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        assertEquals(1,current(b.id()).returnSettlement().entries().size());assertEquals(0,current(b.id()).returnSettlement().outstandingAmount());
    }
    @org.springframework.boot.test.mock.mockito.SpyBean com.carrental.modules.booking.repository.ReturnSettlementRepository ledger;
    @Test void insertFailureRollsBackVersionAndLedger()throws Exception{
        var b=late();org.mockito.Mockito.doAnswer(invocation->{invocation.callRealMethod();throw new org.springframework.dao.DataIntegrityViolationException("Synthetic rollback probe");}).when(ledger).insert(org.mockito.ArgumentMatchers.any());
        try{assertEquals(409,record(staff,b.id(),entry(b.id(),"RECEIPT",100)).getStatus());}finally{org.mockito.Mockito.reset(ledger);}
        assertEquals(b.version(),current(b.id()).version());assertEquals(0,current(b.id()).returnSettlement().entries().size());assertEquals(1950000,current(b.id()).returnSettlement().outstandingAmount());
    }
    @Test void receiptReversalRetainsAuditAndCanReplaceWrongEntry()throws Exception{
        var b=late();var m=entry(b.id(),"RECEIPT",1000000);assertEquals(200,record(staff,b.id(),m).getStatus());
        var original=current(b.id()).returnSettlement().entries().get(0);var reverse=entry(b.id(),"REVERSAL",0);reverse.remove("amountVnd");reverse.put("reversesId",original.id());
        assertEquals(200,record(admin,b.id(),reverse).getStatus());assertEquals(0,current(b.id()).returnSettlement().receivedAmount());assertEquals(1950000,current(b.id()).returnSettlement().outstandingAmount());assertEquals(original,current(b.id()).returnSettlement().entries().stream().filter(e->e.id().equals(original.id())).findFirst().orElseThrow());
        var corrected=entry(b.id(),"RECEIPT",1950000);corrected.put("reference",m.get("reference"));assertEquals(200,record(staff,b.id(),corrected).getStatus());assertEquals("PAID",current(b.id()).returnSettlement().status());
    }
    @Test void twoStaffSameKeyRecordExactlyOnce()throws Exception{
        var b=late();var m=entry(b.id(),"RECEIPT",1950000);var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var x=pool.submit(()->{start.await();return record(staff,b.id(),m).getStatus();});var y=pool.submit(()->{start.await();return record(staff,b.id(),m).getStatus();});start.countDown();assertEquals(200,x.get(20,java.util.concurrent.TimeUnit.SECONDS));assertEquals(200,y.get(20,java.util.concurrent.TimeUnit.SECONDS));}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        assertEquals(1,current(b.id()).returnSettlement().entries().size());assertEquals(b.version()+1,current(b.id()).version());
    }
    @Test void noAssessmentOrWrongStateCannotCreateDebt()throws Exception{
        var b=open(true,true,"dn");assertEquals(409,record(staff,b.id(),entry(b.id(),"RECEIPT",1)).getStatus());
        clock.value=Instant.parse("2026-10-27T03:00:00Z");assertEquals(200,post(staff,"/bookings/"+b.id()+"/actions",receipt(b.id())).getStatus());assertEquals(409,record(admin,b.id(),entry(b.id(),"WAIVER",1)).getStatus());
        assertEquals(0,jdbc.queryForObject("select count(*) from booking_return_settlements",Integer.class));
    }
    @Test void reversalRacingCompletionCannotLeaveCompletedUnpaid()throws Exception{
        var b=late();assertEquals(200,record(staff,b.id(),entry(b.id(),"RECEIPT",1950000)).getStatus());
        var paid=current(b.id());var reverse=entry(b.id(),"REVERSAL",0);reverse.remove("amountVnd");reverse.put("reversesId",paid.returnSettlement().entries().get(0).id());
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var x=pool.submit(()->{start.await();return record(admin,b.id(),reverse).getStatus();});var y=pool.submit(()->{start.await();return post(staff,"/bookings/"+b.id()+"/actions",Map.of("next","COMPLETED","version",paid.version())).getStatus();});start.countDown();var statuses=new ArrayList<>(List.of(x.get(20,java.util.concurrent.TimeUnit.SECONDS),y.get(20,java.util.concurrent.TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        var result=current(b.id());if(result.status().equals("COMPLETED")){assertEquals(0,result.returnSettlement().outstandingAmount());assertEquals(1,invoices(b.id()));}else{assertEquals("RETURNED",result.status());assertEquals(1950000,result.returnSettlement().outstandingAmount());assertEquals(0,invoices(b.id()));}
    }
}
