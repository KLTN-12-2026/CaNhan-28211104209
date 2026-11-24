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

/** QR DEMO HTTP tests - internal signed-protocol proof, not live merchant acceptance. */
@SpringBootTest(properties={"app.jwt-secret=qr-demo-test-only-secret-at-least-32-bytes", "app.seed-enabled=true", "app.seed-password=QRDemoTestOnly123!", "app.deposit-enabled=true", "app.deposit-method=DEMO_QR_MANUAL", "app.payment-reconcile-ms=86400000", "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"})
@AutoConfigureMockMvc
@Import(QrDemoHttpTest.TimeConfig.class)
class QrDemoHttpTest {
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("qr-demo-synthetic-photos-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        String url=System.getenv("KDRIVE_TEST_DB_URL");
        if(url!=null)CancelBoundaryTest.verifySafeIsolatedDatabase(url,System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),System.getenv("KDRIVE_TEST_DB_USER"),System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        r.add("spring.datasource.url",()->url==null?"jdbc:h2:mem:qr-demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1":url);
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
        CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);
        for(String t:List.of("payment_events","payment_refunds","payment_transactions","reviews","incident_events","incident_cases","vehicle_handover_records","invoice_items","invoices","contracts","booking_status_history","bookings"))jdbc.update("delete from "+t);
        jdbc.update("update vehicles set current_branch_id='dn',status='ACTIVE' where id='v1'");clock.value=Instant.parse("2026-10-23T03:00:00Z");
        var old=licenses.mine(user);licenses.save(user,new LicenseService.View(null,"SYNTHETIC-QR-DEMO-NOT-REAL","VN_2025","B",LocalDate.of(2035,12,31),false,"PENDING",old==null?0:old.version()));LicensePhotoFixture.approved(licenses,user);
    }
    BookingService.View create(){return bookings.create(user,new BookingService.Input("v1","dn","dn",null,null,1300000L,LocalDate.of(2026,10,26),LocalDate.of(2026,10,28)));}
    BookingService.View current(String id){return bookings.view(bookings.require(user,id));}
    BookingService.View approve(){var b=create();return (BookingService.View)workflow.approve(staff,b.id(),b.version());}
    org.springframework.mock.web.MockHttpServletResponse post(Actor actor,String url,Object body)throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1"+url).header("Authorization","Bearer "+tokens.issue(accounts.require(actor.id()))).contentType("application/json").content(json.writeValueAsString(body))).andReturn().getResponse();
    }
    org.springframework.mock.web.MockHttpServletResponse get(Actor actor,String url)throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1"+url).header("Authorization","Bearer "+tokens.issue(accounts.require(actor.id())))).andReturn().getResponse();
    }
    Map<String,Object> input(String id,String ref,long amount,Instant time){return new LinkedHashMap<>(Map.of("transactionRef",ref,"amountVnd",amount,"transferTime",time.toString(),"transferNote",BookingService.requestCode(id),"sourceAccount","DEMO-PAYER","idempotencyKey","key-"+ref,"expectedVersion",current(id).version(),"verified",true));}
    void verify(String id,String ref,long amount)throws Exception {
        var response=post(staff,"/bookings/"+id+"/deposit/verify",input(id,ref,amount,clock.instant()));
        assertEquals(200,response.getStatus(),response.getContentAsString());
    }
    void report(String id)throws Exception {assertEquals(200,post(user,"/bookings/"+id+"/deposit/report",Map.of("idempotencyKey","report-"+id)).getStatus());}
    long count(String table,String id){return jdbc.queryForObject("select count(*) from "+table+" where booking_id=?",Long.class,id);}
    private JsonNode qrRead(Actor actor, String id, String endpoint) throws Exception {
        var response=get(actor,endpoint); assertEquals(200,response.getStatus(),response.getContentAsString());
        var data=json.readTree(response.getContentAsString());
        if(endpoint.equals("/bookings")) {
            for(var row:data) if(id.equals(row.path("id").asText())) return row.path("qrReconciliation");
            fail("Booking absent from authorized list");
        }
        return endpoint.endsWith("/deposit") ? data.path("qrReconciliation") : data.path("booking").path("qrReconciliation");
    }
    private List<Object> qrSnapshot() {
        return List.of(jdbc.queryForList("select * from bookings order by id"),jdbc.queryForList("select * from payment_transactions order by id"),
                jdbc.queryForList("select * from payment_refunds order by id"),jdbc.queryForList("select * from payment_events order by id"),
                jdbc.queryForList("select * from contracts order by id"),jdbc.queryForList("select * from booking_status_history order by id"));
    }
    @Test void reconciliationGetConsistentPrivateAndReadOnly() throws Exception {
        var b=approve(); report(b.id()); verify(b.id(),"READ-PARTIAL",100000);
        var before=qrSnapshot(); JsonNode expected=null;
        for(var actor:List.of(user,staff,admin)) for(var endpoint:List.of("/bookings","/bookings/"+b.id(),"/bookings/"+b.id()+"/deposit")) {
            var summary=qrRead(actor,b.id(),endpoint); assertFalse(summary.isMissingNode());
            if(expected==null) expected=summary; else assertEquals(expected,summary);
            assertEquals("RECEIVED_INCOMPLETE",summary.path("stage").asText());
            assertEquals(100000,summary.path("receivedAmount").asLong());assertEquals(290000,summary.path("shortfallAmount").asLong());
            assertFalse(summary.has("sourceAccount"));assertFalse(summary.has("transactionRef"));assertFalse(summary.has("actorId"));
        }
        assertEquals(before,qrSnapshot());
        assertNull(current(b.id()).qrReconciliation()); // Mutation/internal View remains unchanged.
    }
    @Test void reconciliationGetPreservesPermissionsAndDoesNotQueryUnauthorizedProjection() throws Exception {
        var b=approve();var wrong=new Actor("demo-staff-hn","STAFF","Other");
        jdbc.update("insert into users(id,email,password_hash,full_name,role,status,token_version,version) select 'qr-other-user','qr-other@example.test',password_hash,'Other fixture','USER','ACTIVE',0,0 from users where id='demo-user' and not exists(select 1 from users where id='qr-other-user')");
        var other=new Actor("qr-other-user","USER","Other");var before=qrSnapshot();
        for(var actor:List.of(wrong,other)) {
            for(var endpoint:List.of("/bookings/"+b.id(),"/bookings/"+b.id()+"/deposit")) assertEquals(404,get(actor,endpoint).getStatus());
            assertEquals(0,json.readTree(get(actor,"/bookings").getContentAsString()).size());
        }
        assertEquals(401,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/bookings/"+b.id())).andReturn().getResponse().getStatus());
        assertEquals(before,qrSnapshot());
    }
    @Test void reconciliationProjectionMapsRefundPresenceAndRetryWithoutChangingOldTotals() throws Exception {
        var b=approve();verify(b.id(),"READ-REFUND",100000);var payment=repo.forBooking(b.id()).getFirst();
        var refund=json.valueToTree(payments.createManualRefund(admin,payment.id(),100000,"Fixture refund"));
        payments.decide(admin,refund.path("id").asText(),refund.path("version").asLong(),0,"Decline fixture refund");
        repo.event(b.id(),payment.id(),"DEPOSIT_RETRY","Private diagnostic",admin.id(),clock.instant(),null);
        var response=json.readTree(get(user,"/bookings/"+b.id()+"/deposit").getContentAsString());
        assertEquals(100000,response.path("deposit").path("receivedAmount").asLong());
        assertEquals(290000,response.path("deposit").path("missingAmount").asLong());
        var summary=response.path("qrReconciliation");assertEquals(0,summary.path("eligibleAmount").asLong());assertEquals(390000,summary.path("shortfallAmount").asLong());
        assertEquals("NONE",summary.path("processingIssue").asText());
        assertFalse(summary.toString().contains("Private diagnostic"));
    }
    @Test void reconciliationOmittedForNonQrAndSharedClockAcrossList() throws Exception {
        var b=approve();var other=bookings.create(user,new BookingService.Input("v1","dn","dn",null,null,1300000L,LocalDate.of(2026,11,2),LocalDate.of(2026,11,4)));
        var rows=json.readTree(get(user,"/bookings").getContentAsString());assertEquals(2,rows.size());
        assertEquals(rows.get(0).path("qrReconciliation").path("checkedAt"),rows.get(1).path("qrReconciliation").path("checkedAt"));
        for(String update:List.of("deposit_required=false","deposit_required=true,deposit_method='VNPAY_SANDBOX'")) {
            jdbc.update("update bookings set "+update+" where id=?",other.id());
            for(String endpoint:List.of("/bookings","/bookings/"+other.id(),"/bookings/"+other.id()+"/deposit"))
                assertTrue(qrRead(user,other.id(),endpoint).isMissingNode());
        }
        assertFalse(qrRead(user,b.id(),"/bookings").isMissingNode());
    }
    @Test void authenticatedFullFlowAndOneContract()throws Exception {
        var b=approve();assertEquals("DEMO_QR_MANUAL",b.deposit().method());assertEquals(390000,b.deposit().amount());
        var payload=get(user,"/bookings/"+b.id()+"/deposit/qr-payload");assertEquals(200,payload.getStatus());
        assertEquals("KDRIVE_DEMO|"+BookingService.requestCode(b.id())+"|390000",json.readTree(payload.getContentAsString()).path("payload").asText());
        report(b.id());verify(b.id(),"A",390000);verify(b.id(),"A",390000);
        assertEquals("CONFIRMED",current(b.id()).status());assertEquals(390000,current(b.id()).deposit().appliedAmount());assertEquals(1,count("contracts",b.id()));assertEquals(1,count("payment_transactions",b.id()));
    }
    @Test void partialReceiptsAccumulateWithoutDoubleAllocation()throws Exception {
        var b=approve();verify(b.id(),"A",200000);assertEquals("PENDING",current(b.id()).status());assertEquals(190000,current(b.id()).deposit().missingAmount());
        verify(b.id(),"B",190000);assertEquals("CONFIRMED",current(b.id()).status());assertEquals(390000,current(b.id()).deposit().appliedAmount());assertEquals(1,count("contracts",b.id()));
    }
    org.springframework.mock.web.MockHttpServletResponse adminSearch(Actor actor,String q)throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/payments").param("q",q).header("Authorization","Bearer "+tokens.issue(accounts.require(actor.id())))).andReturn().getResponse();
    }
    java.util.Set<String> refs(JsonNode rows){var out=new java.util.TreeSet<String>();rows.forEach(r->out.add(r.path("providerTransaction").asText()));return out;}
    @Test void adminServerSearchReachesBeyondRecentHistoryWithLiteralWildcards()throws Exception {
        // D2: an old receipt pushed out of the 200 most recent rows stays findable by server-side prefix search.
        var b=approve();verify(b.id(),"OLD-REF_1",500000);var old=repo.forBooking(b.id()).getFirst();
        Instant base=clock.instant().plusSeconds(60);
        for(int i=0;i<205;i++) jdbc.update("insert into payment_transactions(id,booking_id,amount,status,created_at,expires_at,provider_transaction,applied,version) values(?,?,?,?,?,?,?,false,0)",
            String.format("bulk%028d",i),b.id(),1000,"FAILED",java.sql.Timestamp.from(base.plusSeconds(i)),java.sql.Timestamp.from(base.plusSeconds(i)),"BULK-"+i);
        var recent=json.readTree(get(admin,"/admin/payments").getContentAsString());
        assertEquals(200,recent.get("payments").size());assertFalse(refs(recent.get("payments")).contains("OLD-REF_1"));assertFalse(recent.has("search"),"no query keeps the old response");
        for(String q:List.of("old-ref_","OLD-REF","  Old-Ref_1  ")) {
            var r=adminSearch(admin,q);assertEquals(200,r.getStatus(),q);var found=json.readTree(r.getContentAsString());
            assertEquals(java.util.Set.of("OLD-REF_1"),refs(found.get("payments")),q);
            assertTrue(java.util.stream.StreamSupport.stream(found.get("refunds").spliterator(),false).anyMatch(x->x.get("paymentId").asText().equals(old.id())&&x.get("kind").asText().equals("EXCESS")),"refunds of the matched receipt are included: "+q);
            assertEquals(q.strip(),found.get("search").get("query").asText());assertFalse(found.get("search").get("truncated").asBoolean());
            assertEquals(0,found.get("refundableAmounts").get(old.id()).asLong());
            assertTrue(found.get("refundPayments").size()>=1);
        }
        for(String literal:List.of("old-re_","old-ref%","%%%","!!!","___")) {
            var r=adminSearch(admin,literal);assertEquals(200,r.getStatus(),literal);
            assertEquals(0,json.readTree(r.getContentAsString()).get("payments").size(),"wildcard must be literal: "+literal);
        }
        var byBooking=json.readTree(adminSearch(admin,b.id().substring(0,8)).getContentAsString());
        assertEquals(200,byBooking.get("payments").size());assertTrue(byBooking.get("search").get("truncated").asBoolean(),"capped results are flagged");
        assertEquals(400,adminSearch(admin,"ab").getStatus());assertEquals(400,adminSearch(admin,"x".repeat(65)).getStatus());
        var blank=json.readTree(adminSearch(admin,"   ").getContentAsString());assertFalse(blank.has("search"));assertEquals(200,blank.get("payments").size());
        for(Actor other:List.of(user,staff)) assertEquals(403,adminSearch(other,"old-ref").getStatus(),other.role());
        assertEquals(206,count("payment_transactions",b.id()),"search is read-only");
    }
    /** Confirmed booking cancelled by Admin as a late customer cancellation (within 24h of the pickup day). */
    BookingService.View lateCustomerCancelled(String ref)throws Exception {
        var b=approve();verify(b.id(),ref,390000);assertEquals("CONFIRMED",current(b.id()).status());
        clock.value=Instant.parse("2026-10-25T05:00:00Z"); // 12h before the start of the pickup day (Vietnam)
        var c=current(b.id());rental.apply(admin,b.id(),new BookingService.Action("CANCELLED",c.version(),"Synthetic late cancellation",null,false,false,"LATE_CUSTOMER"));
        assertEquals("CANCELLED",current(b.id()).status());
        var pending=repo.refunds(b.id());assertEquals(1,pending.size());assertEquals("REQUESTED",pending.getFirst().status());assertEquals("LATE_CUSTOMER",pending.getFirst().kind());
        return current(b.id());
    }
    @Test void lateReceiptOnCancelledBookingNeverOverridesThePendingAdminDecisionOnEarlierMoney()throws Exception {
        // D3-pre: a receipt recorded after a late cancellation must not auto-approve a full refund of the earlier receipt.
        var b=lateCustomerCancelled("EARLY-A");var earlier=repo.forBooking(b.id()).getFirst();
        clock.value=clock.value.plusSeconds(600);
        var late=input(b.id(),"AFTER-B",100000,clock.instant().minusSeconds(60));
        assertEquals(200,post(staff,"/bookings/"+b.id()+"/deposit/verify",late).getStatus());
        var forEarlier=repo.refunds(b.id()).stream().filter(r->r.paymentId().equals(earlier.id())).toList();
        assertEquals(1,forEarlier.size(),"earlier receipt keeps only its pending Admin decision");
        assertEquals("REQUESTED",forEarlier.getFirst().status());assertEquals("LATE_CUSTOMER",forEarlier.getFirst().kind());
        assertEquals(0,repo.reserved(earlier.id(),null),"no automatic reservation on the earlier receipt");
        var newer=repo.forBooking(b.id()).stream().filter(p->"AFTER-B".equals(p.providerTransaction())).findFirst().orElseThrow();
        var forNewer=repo.refunds(b.id()).stream().filter(r->r.paymentId().equals(newer.id())).toList();
        assertEquals(1,forNewer.size());assertEquals("APPROVED",forNewer.getFirst().status());assertEquals(100000,forNewer.getFirst().amount());
        assertEquals("CANCELLED",current(b.id()).status());assertEquals(1,count("contracts",b.id()));
    }
    @Test void depositLedgerIsRoleScopedForTheCustomer()throws Exception {
        // D1: same booking, same data; the owner gets no internal identities, idempotency keys or internal event log.
        var b=approve();verify(b.id(),"A",200000);
        var created=(PaymentRepository.Refund)payments.createManualRefund(admin,repo.forBooking(b.id()).getFirst().id(),50000,"Synthetic manual request");
        payments.decide(admin,created.id(),created.version(),50000,"Synthetic decision");
        var customer=json.readTree(get(user,"/bookings/"+b.id()+"/deposit").getContentAsString());
        assertFalse(customer.has("events"),"USER must not receive the internal event log");
        var pay=customer.get("payments").get(0);
        for(String hidden:List.of("verifierId","idempotencyKey","version","applied","expiresAt","providerPayDate")) assertFalse(pay.has(hidden),"USER payment exposes "+hidden);
        for(String kept:List.of("id","amount","status","appliedAmount","providerTransaction","transferTime","transferNote","sourceAccount","verifiedAt")) assertTrue(pay.has(kept),"USER payment misses "+kept);
        assertEquals(200000,pay.get("amount").asLong());assertEquals(0,pay.get("appliedAmount").asLong());assertEquals("A",pay.get("providerTransaction").asText());
        var refund=customer.get("refunds").get(0);
        for(String hidden:List.of("approvedBy","requestDate","version")) assertFalse(refund.has(hidden),"USER refund exposes "+hidden);
        for(String kept:List.of("id","amount","status","kind","reason","approvedAt")) assertTrue(refund.has(kept),"USER refund misses "+kept);
        assertEquals(50000,refund.get("amount").asLong());assertEquals("MANUAL_EXCESS",refund.get("kind").asText());assertEquals("APPROVED",refund.get("status").asText());
        assertFalse(customer.toString().contains(staff.id()),"USER ledger leaks the verifier id");
        assertFalse(customer.toString().contains(admin.id()),"USER ledger leaks the approver id");
        assertFalse(customer.toString().contains("key-A"),"USER ledger leaks the idempotency key");
        assertTrue(customer.has("qrReconciliation"));assertTrue(customer.has("deposit"));
        for(Actor operator:List.of(staff,admin)) {
            var full=json.readTree(get(operator,"/bookings/"+b.id()+"/deposit").getContentAsString());
            assertTrue(full.get("events").size()>0,operator.role()+" keeps events");
            assertEquals(staff.id(),full.get("payments").get(0).get("verifierId").asText());
            assertEquals("key-A",full.get("payments").get(0).get("idempotencyKey").asText());
            assertEquals(admin.id(),full.get("refunds").get(0).get("approvedBy").asText());
        }
    }
    @Test void excessRefundThenEarlyCancellationNeverExceedsReceived()throws Exception {
        var b=approve();verify(b.id(),"A",500000);var p=repo.forBooking(b.id()).getFirst();assertEquals(390000,p.appliedAmount());assertEquals(110000,current(b.id()).deposit().excessAmount());
        var r=repo.refundForPayment(p.id()).orElseThrow();assertEquals(110000,r.amount());payments.processManualRefund(admin,r.id());
        var processing=repo.refund(r.id());payments.completeManualRefund(admin,r.id(),"DEMO-PAYER","REF1",clock.instant(),processing.version());
        var c=current(b.id());rental.apply(user,b.id(),new BookingService.Action("CANCELLED",c.version(),"Early test",null,false,false));
        assertEquals(500000,repo.reserved(p.id(),null));assertEquals(2,repo.refunds(b.id()).size());
    }
    @Test void partialExpiryRefundsActualReceivedNotMissingBalance()throws Exception {
        var b=approve();verify(b.id(),"A",200000);clock.value=b.deposit().reviewDueAt();workflow.settleQr(b.id(),null);
        assertEquals("EXPIRED",current(b.id()).status());assertEquals(200000,repo.refunds(b.id()).getFirst().amount());assertEquals(0,count("contracts",b.id()));
    }
    @Test void transferAtPaymentDeadlineIsLateEvenWithReviewExtension()throws Exception {
        var b=approve();report(b.id());clock.value=b.deposit().paymentDueAt();verify(b.id(),"A",390000);
        assertEquals("PENDING",current(b.id()).status());assertEquals(0,current(b.id()).deposit().appliedAmount());assertEquals(390000,repo.refunds(b.id()).getFirst().amount());
    }
    @Test void timelyReceiptVerifiedDuringReviewSucceeds()throws Exception {
        var b=approve();report(b.id());var request=input(b.id(),"A",390000,clock.instant());clock.value=b.deposit().paymentDueAt().plusSeconds(1);
        assertEquals(200,post(staff,"/bookings/"+b.id()+"/deposit/verify",request).getStatus());assertEquals("CONFIRMED",current(b.id()).status());
    }
    @Test void reviewDeadlineDoesNotReclaimSlot()throws Exception {
        var b=approve();report(b.id());var request=input(b.id(),"A",390000,clock.instant());clock.value=current(b.id()).deposit().reviewDueAt();
        assertEquals(200,post(staff,"/bookings/"+b.id()+"/deposit/verify",request).getStatus());assertEquals("EXPIRED",current(b.id()).status());assertEquals(390000,repo.refunds(b.id()).getFirst().amount());
    }
    @Test void repeatedReportsAndApprovalNeverExtendDeadline()throws Exception {
        var b=approve();report(b.id());var first=current(b.id());report(b.id());clock.value=clock.value.plusSeconds(30);
        workflow.approve(staff,b.id(),current(b.id()).version());assertEquals(first.expiresAt(),current(b.id()).expiresAt());assertEquals(1,jdbc.queryForObject("select count(*) from payment_events where booking_id=? and kind='TRANSFER_REPORTED'",Integer.class,b.id()));
    }
    @Test void reportAtDeadlineRejectedAndDoesNotChangeHold()throws Exception {
        var b=approve();clock.value=b.deposit().paymentDueAt();assertEquals(409,post(user,"/bookings/"+b.id()+"/deposit/report",Map.of()).getStatus());assertNull(current(b.id()).deposit().reportedAt());
    }
    @Test void invalidLicenseKeepsReceiptAndQueuesRefund()throws Exception {
        var b=approve();var l=licenses.mine(user);licenses.save(user,new LicenseService.View(null,"SYNTHETIC-CHANGED","VN_2025","B",l.expiresOn(),false,"PENDING",l.version()));
        verify(b.id(),"A",390000);assertEquals(1,count("payment_transactions",b.id()));assertEquals("PENDING",current(b.id()).status());assertEquals(390000,repo.refunds(b.id()).getFirst().amount());assertEquals(0,count("contracts",b.id()));
    }
    @Autowired PaymentSettings paymentSettings;
    @Test void disabledNewDepositsKeepExistingQrReadsAndSettlement() throws Exception {
        var b=approve();
        Object previous=org.springframework.test.util.ReflectionTestUtils.getField(bookings,"depositEnabled");
        boolean enabled=paymentSettings.enabled;
        try {
            // Simulate restart with the creation flag disabled; keep the persisted QR snapshot.
            org.springframework.test.util.ReflectionTestUtils.setField(bookings,"depositEnabled",false);
            org.springframework.test.util.ReflectionTestUtils.setField(paymentSettings,"enabled",false);
            assertFalse(paymentSettings.acceptingNew());
            var quote=bookings.quote(user,new BookingService.Input("v1","dn","dn",null,null,1300000L,LocalDate.of(2026,11,5),LocalDate.of(2026,11,7)));
            assertFalse(quote.deposit().required());
            var before=qrSnapshot();
            for(var endpoint:List.of("/bookings","/bookings/"+b.id(),"/bookings/"+b.id()+"/deposit"))
                assertEquals("AWAITING_TRANSFER",qrRead(user,b.id(),endpoint).path("stage").asText());
            assertEquals(before,qrSnapshot());
            var response=get(user,"/bookings/"+b.id()+"/deposit");assertEquals(200,response.getStatus());
            assertFalse(json.readTree(response.getContentAsString()).path("acceptingNew").asBoolean(true));
            report(b.id());verify(b.id(),"FLAG-OFF-OLD-QR",390000);
            assertEquals("CONFIRMED",current(b.id()).status());assertEquals(1,count("contracts",b.id()));
            assertEquals("APPLIED",qrRead(user,b.id(),"/bookings/"+b.id()).path("stage").asText());
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(bookings,"depositEnabled",previous);
            org.springframework.test.util.ReflectionTestUtils.setField(paymentSettings,"enabled",enabled);
        }
    }
    @Test void contractFailureRetainsMoneyAndRecoveryIssuesOnce()throws Exception {
        var b=approve();org.mockito.Mockito.doThrow(new IllegalStateException("Synthetic contract failure")).when(contracts).issueDeposit(org.mockito.ArgumentMatchers.any());
        verify(b.id(),"A",390000);assertEquals(1,count("payment_transactions",b.id()));assertEquals("PENDING",current(b.id()).status());assertEquals(0,current(b.id()).deposit().appliedAmount());assertEquals(0,count("contracts",b.id()));
        var before=qrSnapshot();
        for(var endpoint:List.of("/bookings","/bookings/"+b.id(),"/bookings/"+b.id()+"/deposit")) {
            var summary=qrRead(staff,b.id(),endpoint);
            assertEquals("AWAITING_APPLICATION",summary.path("stage").asText());
            assertEquals("RETRY_REQUIRED",summary.path("processingIssue").asText());
            assertEquals(390000,summary.path("receivedAmount").asLong());
        }
        assertEquals(before,qrSnapshot());
        org.mockito.Mockito.doCallRealMethod().when(contracts).issueDeposit(org.mockito.ArgumentMatchers.any());workflow.settleQr(b.id(),repo.forBooking(b.id()).getFirst().id());assertEquals("CONFIRMED",current(b.id()).status());assertEquals(1,count("contracts",b.id()));
        workflow.settleQr(b.id(),repo.forBooking(b.id()).getFirst().id());
        var summary=qrRead(user,b.id(),"/bookings/"+b.id()+"/deposit");
        assertEquals("APPLIED",summary.path("stage").asText());assertEquals("NONE",summary.path("processingIssue").asText());
        assertEquals(1,count("contracts",b.id()));assertEquals(1,count("payment_transactions",b.id()));
    }
    @Test void wrongActorBranchMemoAndMalformedReceiptRejected()throws Exception {
        var b=approve();var req=input(b.id(),"A",390000,clock.instant());
        assertEquals(403,post(user,"/bookings/"+b.id()+"/deposit/verify",req).getStatus());assertEquals(403,post(admin,"/bookings/"+b.id()+"/deposit/verify",req).getStatus());
        req.put("transferNote","WRONG");assertEquals(400,post(staff,"/bookings/"+b.id()+"/deposit/verify",req).getStatus());req.put("transferTime",null);assertEquals(400,post(staff,"/bookings/"+b.id()+"/deposit/verify",req).getStatus());assertEquals(0,count("payment_transactions",b.id()));
    }
    @Test void idempotencyRejectsChangedPayloadAndDuplicateBankReference()throws Exception {
        var b=approve();verify(b.id(),"A",100000);var req=input(b.id(),"A",200000,clock.instant());assertEquals(409,post(staff,"/bookings/"+b.id()+"/deposit/verify",req).getStatus());req.put("idempotencyKey","new-key");assertEquals(409,post(staff,"/bookings/"+b.id()+"/deposit/verify",req).getStatus());assertEquals(1,count("payment_transactions",b.id()));
        var other=bookings.create(user,new BookingService.Input("v1","dn","dn",null,null,1300000L,LocalDate.of(2026,11,2),LocalDate.of(2026,11,4)));
        workflow.approve(staff,other.id(),other.version());
        var otherReceipt=input(other.id(),"A",100000,clock.instant());otherReceipt.put("idempotencyKey","other-booking-key");
        assertEquals(409,post(staff,"/bookings/"+other.id()+"/deposit/verify",otherReceipt).getStatus());
        assertEquals(0,count("payment_transactions",other.id()));assertEquals(100000,current(b.id()).deposit().receivedAmount());
    }
    @Test void receiptValidationRejectsMalformedAndNonIntegerAmounts()throws Exception {
        var b=approve();String endpoint="/bookings/"+b.id()+"/deposit/verify";
        for(var invalid:List.of(
            Map.entry("transactionRef", " "), Map.entry("transactionRef", "X".repeat(31)),
            Map.entry("sourceAccount", " "), Map.entry("sourceAccount", "X".repeat(101)),
            Map.entry("idempotencyKey", " "), Map.entry("idempotencyKey", "X".repeat(65)),
            Map.entry("verified", false), Map.entry("amountVnd", 0), Map.entry("amountVnd", -1),
            Map.entry("amountVnd", new java.math.BigDecimal("1.5")),
            Map.entry("amountVnd", "100000"),
            Map.entry("amountVnd", new java.math.BigInteger("9223372036854775808")),
            Map.entry("transferTime", clock.instant().plusSeconds(1).toString()),
            Map.entry("transferTime", clock.instant().minusSeconds(1).toString()))) {
            var req=input(b.id(),"VALIDATION",100000,clock.instant());req.put(invalid.getKey(),invalid.getValue());
            assertEquals(400,post(staff,endpoint,req).getStatus(),invalid.getKey()+" invalid: "+invalid.getValue());
            assertEquals(0,count("payment_transactions",b.id()));assertEquals(0,count("contracts",b.id()));
        }
    }
    @Test void exactReplayWithOldVersionKeepsSingleReceiptAndSummaryCorrelation()throws Exception {
        var b=approve();var first=input(b.id(),"PARTIAL",100000,clock.instant());
        assertEquals(200,post(staff,"/bookings/"+b.id()+"/deposit/verify",first).getStatus());
        assertEquals(200,post(staff,"/bookings/"+b.id()+"/deposit/verify",first).getStatus());
        assertEquals(1,count("payment_transactions",b.id()));assertEquals(0,count("contracts",b.id()));
        var last=input(b.id(),"FULL",290000,clock.instant());
        var response=post(staff,"/bookings/"+b.id()+"/deposit/verify",last);assertEquals(200,response.getStatus());
        var body=json.readTree(response.getContentAsString());assertEquals(b.id(),body.get("id").asText());assertFalse(body.has("booking"));assertFalse(body.has("payments"));
        assertEquals(200,post(staff,"/bookings/"+b.id()+"/deposit/verify",last).getStatus());
        assertEquals(2,count("payment_transactions",b.id()));assertEquals(1,count("contracts",b.id()));
        assertEquals(2,jdbc.queryForObject("select count(*) from payment_events where booking_id=? and kind='PAYMENT_RECEIVED'",Integer.class,b.id()));
        var summary=get(staff,"/bookings/"+b.id()+"/deposit");assertEquals(200,summary.getStatus());
        var entries=json.readTree(summary.getContentAsString()).get("payments");assertEquals(2,entries.size());
        assertTrue(java.util.stream.StreamSupport.stream(entries.spliterator(),false).anyMatch(p->p.get("providerTransaction").asText().equals("FULL")&&p.get("idempotencyKey").asText().equals("key-FULL")&&p.get("amount").asLong()==290000));
        assertEquals(390000,current(b.id()).deposit().appliedAmount());
    }
    @Test void ledgerReadAndStaleReceiptRespectOwnerAndPickupRights()throws Exception {
        var b=approve();String endpoint="/bookings/"+b.id()+"/deposit";
        assertEquals(200,get(user,endpoint).getStatus());assertEquals(200,get(staff,endpoint).getStatus());assertEquals(200,get(admin,endpoint).getStatus());
        assertEquals(404,get(new Actor("demo-staff-hn","STAFF","Wrong branch"),endpoint).getStatus());
        jdbc.update("insert into users(id,email,password_hash,full_name,role,status,token_version,version) select 'qr-other-user','qr-other@example.test',password_hash,'Other fixture','USER','ACTIVE',0,0 from users where id='demo-user' and not exists(select 1 from users where id='qr-other-user')");
        assertEquals(404,get(new Actor("qr-other-user","USER","Other fixture"),endpoint).getStatus());
        var req=input(b.id(),"STALE",100000,clock.instant());req.put("expectedVersion",-1);
        assertEquals(409,post(staff,endpoint+"/verify",req).getStatus());assertEquals(0,count("payment_transactions",b.id()));
    }
    @Test void adminHistoryIncludesSettledReceiptsAndReservedRefundBalance()throws Exception {
        var b=approve();verify(b.id(),"HISTORY-FULL",390000);
        var history=json.readTree(get(admin,"/admin/payments").getContentAsString());
        assertEquals(1,history.get("payments").size());assertEquals(0,history.get("unresolved").size());
        String applied=history.get("payments").get(0).get("id").asText();
        assertEquals(0,history.get("refundableAmounts").get(applied).asLong());assertEquals(200,history.get("historyLimit").asInt());
        assertEquals(403,get(user,"/admin/payments").getStatus());assertEquals(403,get(staff,"/admin/payments").getStatus());
        var other=bookings.create(user,new BookingService.Input("v1","dn","dn",null,null,1300000L,LocalDate.of(2026,11,2),LocalDate.of(2026,11,4)));
        workflow.approve(staff,other.id(),other.version());verify(other.id(),"HISTORY-PARTIAL",100000);
        String payment=repo.forBooking(other.id()).getFirst().id();
        var created=post(admin,"/admin/refunds/create-manual",Map.of("paymentId",payment,"amount",25000,"reason","Synthetic G3 partial refund"));assertEquals(200,created.getStatus());
        String refund=json.readTree(created.getContentAsString()).get("id").asText();
        assertEquals(200,post(admin,"/admin/refunds/"+refund+"/decide",Map.of("expectedVersion",0,"amount",20000,"reason","Synthetic G3 reviewed")).getStatus());
        var reserved=json.readTree(get(admin,"/admin/payments").getContentAsString());assertEquals(80000,reserved.get("refundableAmounts").get(payment).asLong());
        assertEquals(1,reserved.get("refundPayments").size());assertEquals("DEMO-PAYER",reserved.get("refundPayments").get(0).get("sourceAccount").asText());
        assertEquals("PENDING",current(other.id()).status());assertEquals(0,count("contracts",other.id()));
    }
    @Test void manualRefundLifecycleRejectsInvalidDataAndChangedReplay()throws Exception {
        var b=approve();verify(b.id(),"G3-PARTIAL",100000);var payment=repo.forBooking(b.id()).getFirst();
        var create=Map.of("paymentId",payment.id(),"amount",50000,"reason","Synthetic G3 request");
        assertEquals(403,post(user,"/admin/refunds/create-manual",create).getStatus());assertEquals(403,post(staff,"/admin/refunds/create-manual",create).getStatus());
        var response=post(admin,"/admin/refunds/create-manual",create);assertEquals(200,response.getStatus());
        String id=json.readTree(response.getContentAsString()).get("id").asText(),base="/admin/refunds/"+id;
        assertEquals(409,post(admin,base+"/process-manual",Map.of()).getStatus());
        var missingAmount=new LinkedHashMap<String,Object>(Map.of("expectedVersion",0,"reason","Synthetic missing amount"));
        assertEquals(400,post(admin,base+"/decide",missingAmount).getStatus());
        missingAmount.put("amount",null);assertEquals(400,post(admin,base+"/decide",missingAmount).getStatus());
        assertEquals("REQUESTED",repo.refund(id).status());
        for(Object amount:List.of(new java.math.BigDecimal("1.5"),"10000",-1,100001)) {
            assertEquals(400,post(admin,base+"/decide",Map.of("expectedVersion",0,"amount",amount,"reason","Synthetic invalid")).getStatus());
            assertEquals("REQUESTED",repo.refund(id).status());
        }
        assertEquals(409,post(admin,base+"/decide",Map.of("expectedVersion",99,"amount",40000,"reason","Synthetic stale")).getStatus());
        assertEquals(200,post(admin,base+"/decide",Map.of("expectedVersion",0,"amount",40000,"reason","Synthetic approved")).getStatus());
        assertEquals("APPROVED",repo.refund(id).status());assertNull(repo.refund(id).providerTransaction());
        assertEquals(200,post(admin,base+"/process-manual",Map.of()).getStatus());var processing=repo.refund(id);assertEquals("PROCESSING",processing.status());
        var completion=new LinkedHashMap<String,Object>(Map.of("recipientAccount","DEMO-PAYER","reference","G3-REFUND","transferTime",clock.instant().toString(),"expectedVersion",processing.version()));
        assertEquals(403,post(user,base+"/complete-manual",completion).getStatus());assertEquals(403,post(staff,base+"/complete-manual",completion).getStatus());
        completion.put("recipientAccount","WRONG");assertEquals(400,post(admin,base+"/complete-manual",completion).getStatus());completion.put("recipientAccount","DEMO-PAYER");
        completion.put("transferTime",clock.instant().plusSeconds(1).toString());assertEquals(400,post(admin,base+"/complete-manual",completion).getStatus());completion.put("transferTime",clock.instant().toString());
        assertEquals(200,post(admin,base+"/complete-manual",completion).getStatus());assertEquals("SUCCEEDED",repo.refund(id).status());
        assertEquals(200,post(admin,base+"/complete-manual",completion).getStatus());
        for(var change:List.of(Map.entry("recipientAccount","WRONG"),Map.entry("reference","OTHER"),Map.entry("transferTime",clock.instant().minusSeconds(1).toString()))) {
            var changed=new LinkedHashMap<>(completion);changed.put(change.getKey(),change.getValue());assertEquals(409,post(admin,base+"/complete-manual",changed).getStatus());
        }
        assertEquals(1,count("payment_refunds",b.id()));assertEquals(1,jdbc.queryForObject("select count(*) from payment_events where booking_id=? and kind='REFUND_SUCCEEDED'",Integer.class,b.id()));
        assertEquals(400,post(admin,"/admin/refunds/create-manual",Map.of("paymentId",payment.id(),"amount",60001,"reason","Synthetic over reserved")).getStatus());
        assertEquals(40000,repo.reserved(payment.id(),null));assertEquals("PENDING",current(b.id()).status());assertEquals(0,count("contracts",b.id()));
    }
    @Test void refundZeroDecisionAndOldReceiptContextRemainAuditable()throws Exception {
        var b=approve();verify(b.id(),"G3-OLD",100000);var payment=repo.forBooking(b.id()).getFirst();
        var response=post(admin,"/admin/refunds/create-manual",Map.of("paymentId",payment.id(),"amount",10000,"reason","Synthetic zero decision"));
        String id=json.readTree(response.getContentAsString()).get("id").asText();
        assertEquals(400,post(admin,"/admin/refunds/"+id+"/decide",Map.of("expectedVersion",0,"amount",0,"reason"," ")).getStatus());
        assertEquals(200,post(admin,"/admin/refunds/"+id+"/decide",Map.of("expectedVersion",0,"amount",0,"reason","Synthetic no refund")).getStatus());
        assertEquals("DECLINED",repo.refund(id).status());assertEquals(0,repo.reserved(payment.id(),null));
        assertEquals(409,post(admin,"/admin/refunds/"+id+"/process-manual",Map.of()).getStatus());
        jdbc.update("update payment_transactions set created_at=? where id=?",java.sql.Timestamp.from(clock.instant().minusSeconds(86400)),payment.id());
        for(int n=0;n<201;n++)repo.createPayment("g3-history-"+n,b.id(),1,clock.instant(),clock.instant().plusSeconds(60));
        var history=json.readTree(get(admin,"/admin/payments").getContentAsString());assertEquals(200,history.get("payments").size());
        assertTrue(java.util.stream.StreamSupport.stream(history.get("payments").spliterator(),false).noneMatch(p->p.get("id").asText().equals(payment.id())));
        assertEquals(payment.id(),history.get("refundPayments").get(0).get("id").asText());assertEquals("DEMO-PAYER",history.get("refundPayments").get(0).get("sourceAccount").asText());
    }
    @Test void refundRequiresSeparateVerifiedCompletion()throws Exception {
        var b=approve();verify(b.id(),"A",500000);var r=repo.refunds(b.id()).getFirst();payments.processManualRefund(admin,r.id());var processing=repo.refund(r.id());assertEquals("PROCESSING",processing.status());
        assertThrows(com.carrental.exception.DomainException.class,()->payments.completeManualRefund(admin,r.id(),"WRONG","REF",clock.instant(),processing.version()));
        payments.completeManualRefund(admin,r.id(),"DEMO-PAYER","REF",clock.instant(),processing.version());assertEquals("SUCCEEDED",repo.refund(r.id()).status());payments.completeManualRefund(admin,r.id(),"DEMO-PAYER","REF",clock.instant(),processing.version());
    }
    @Test void qrPngDecodesAndIsProtected()throws Exception {
        var b=approve();var response=get(user,"/bookings/"+b.id()+"/deposit/qr");assertEquals(200,response.getStatus());
        var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(response.getContentAsByteArray()));
        var bitmap=new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.client.j2se.BufferedImageLuminanceSource(image)));
        assertEquals("KDRIVE_DEMO|"+BookingService.requestCode(b.id())+"|390000",new com.google.zxing.MultiFormatReader().decode(bitmap).getText());
        assertEquals(401,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/bookings/"+b.id()+"/deposit/qr")).andReturn().getResponse().getStatus());
    }
    @Test void twoStaffReceiptsAreSerializedAndOnlyOneContract()throws Exception {
        var b=approve();var request1=new BookingService.VerifyInput("A",390000,clock.instant(),BookingService.requestCode(b.id()),"DEMO-PAYER","A",b.version(),true);
        var request2=new BookingService.VerifyInput("B",390000,clock.instant(),BookingService.requestCode(b.id()),"DEMO-PAYER","B",b.version(),true);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {var futures=List.of(request1,request2).stream().map(r->pool.submit(()->{start.await();try{workflow.verifyQrDeposit(staff,b.id(),r);}catch(com.carrental.exception.DomainException e){assertEquals(409,e.status.value());}return true;})).toList();start.countDown();for(var f:futures)assertTrue(f.get(20,java.util.concurrent.TimeUnit.SECONDS));}
        finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
        assertEquals(1,count("contracts",b.id()));assertEquals(390000,current(b.id()).deposit().appliedAmount());
        long received=repo.forBooking(b.id()).stream().mapToLong(PaymentRepository.Payment::amount).sum();assertEquals(received-390000,repo.refunds(b.id()).stream().mapToLong(PaymentRepository.Refund::amount).sum());
    }
    @Test void cancellationRacingQrReceiptKeepsMoneyAndScheduleConsistent()throws Exception {
        var b=approve();var input=new BookingService.VerifyInput("RACE",390000,clock.instant(),BookingService.requestCode(b.id()),"DEMO-PAYER","RACE",b.version(),true);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var money=pool.submit(()->{start.await();try{workflow.verifyQrDeposit(staff,b.id(),input);}catch(com.carrental.exception.DomainException e){assertEquals(409,e.status.value());}return true;});
            var cancel=pool.submit(()->{start.await();try{rental.apply(user,b.id(),new BookingService.Action("CANCELLED",b.version(),"QR fixture cancellation",null,false,false));}catch(com.carrental.exception.DomainException e){assertEquals(409,e.status.value());}return true;});
            start.countDown();assertTrue(money.get(20,java.util.concurrent.TimeUnit.SECONDS));assertTrue(cancel.get(20,java.util.concurrent.TimeUnit.SECONDS));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
        var result=current(b.id());assertTrue(Set.of("CANCELLED","CONFIRMED").contains(result.status()));
        if("CANCELLED".equals(result.status())) {assertEquals(0,count("contracts",b.id()));for(var p:repo.forBooking(b.id()))assertEquals(p.amount(),repo.reserved(p.id(),null));}
        else assertEquals(1,count("contracts",b.id()));
    }
    @Test void wrongPickupAndNoShowAfterDeliveryAreRejected()throws Exception {
        var b=approve();var wrong=new Actor("demo-staff-hn","STAFF","Other");assertEquals(403,post(wrong,"/bookings/"+b.id()+"/deposit/verify",input(b.id(),"A",390000,clock.instant())).getStatus());
        verify(b.id(),"A",390000);clock.value=Instant.parse("2026-10-26T03:00:00Z");
        rental.apply(staff,b.id(),new BookingService.Action("IN_PROGRESS",current(b.id()).version(),null,"Fixture handover",true,false));
        clock.value=Instant.parse("2026-10-27T03:00:00Z");assertThrows(com.carrental.exception.DomainException.class,()->workflow.noShow(staff,b.id(),current(b.id()).version(),true,true,"Must not cancel delivered car"));assertEquals("IN_PROGRESS",current(b.id()).status());
    }
    @Test void noShowRefundRemainsAdminDecisionIncludingZero()throws Exception {
        var b=approve();verify(b.id(),"A",390000);clock.value=Instant.parse("2026-10-27T03:00:00Z");workflow.noShow(staff,b.id(),current(b.id()).version(),true,true,"Contacted, not delivered");
        var refund=repo.refunds(b.id()).getFirst();assertEquals("REQUESTED",refund.status());payments.decide(admin,refund.id(),refund.version(),0,"Admin decision, no refund");assertEquals("DECLINED",repo.refund(refund.id()).status());
    }

}
