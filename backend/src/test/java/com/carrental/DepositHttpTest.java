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

/** Internal signed-protocol proof, not live merchant acceptance. */
@SpringBootTest(properties={"app.jwt-secret=deposit-test-only-secret-at-least-32-bytes", "app.seed-enabled=true", "app.seed-password=DepositTestOnly123!", "app.deposit-enabled=true", "app.vnpay-tmn-code=TESTONLY", "app.vnpay-hash-secret=synthetic-secret-not-real-merchant", "app.payment-reconcile-ms=86400000", "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"})
@AutoConfigureMockMvc
@Import(DepositHttpTest.TimeConfig.class)
class DepositHttpTest {
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("deposit-synthetic-photos-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        String url=System.getenv("KDRIVE_TEST_DB_URL");
        if(url!=null)CancelBoundaryTest.verifySafeIsolatedDatabase(url,System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),System.getenv("KDRIVE_TEST_DB_USER"),System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        r.add("spring.datasource.url",()->url==null?"jdbc:h2:mem:deposits;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1":url);
        r.add("spring.datasource.username",()->url==null?"sa":System.getenv("KDRIVE_TEST_DB_USER"));r.add("spring.datasource.password",()->url==null?"":System.getenv("KDRIVE_TEST_DB_PASSWORD"));r.add("app.license-photo-directory",()->ROOT.toString());
    }
    static class FixtureClock extends Clock {Instant value=Instant.parse("2026-10-23T03:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return value;}}
    @TestConfiguration static class TimeConfig {@Bean @Primary FixtureClock depositClock(){return new FixtureClock();}}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired FixtureClock clock;
    @Autowired LicenseService licenses;@Autowired BookingService bookings;@Autowired PaymentService payments;@Autowired DepositWorkflow workflow;@Autowired PaymentRepository repo;
    @org.springframework.boot.test.mock.mockito.SpyBean VnpayGateway gateway;
    @Autowired TokenService tokens;@Autowired AccountService accounts;
    @Autowired com.carrental.modules.operation.service.RentalWorkflow rental;
    final Actor user=new Actor("demo-user","USER","Synthetic"),staff=new Actor("demo-staff-dn","STAFF","Synthetic"),admin=new Actor("demo-admin","ADMIN","Synthetic");
    @BeforeEach void setup(){
        CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);
        for(String t:List.of("payment_events","payment_refunds","payment_transactions","reviews","incident_events","incident_cases","vehicle_handover_records","invoice_items","invoices","contracts","booking_status_history","bookings"))jdbc.update("delete from "+t);
        jdbc.update("update vehicles set current_branch_id='dn',status='ACTIVE' where id='v1'");clock.value=Instant.parse("2026-10-23T03:00:00Z");
        var old=licenses.mine(user);licenses.save(user,new LicenseService.View(null,"SYNTHETIC-DEPOSIT-NOT-REAL","VN_2025","B",LocalDate.of(2035,12,31),false,"PENDING",old==null?0:old.version()));LicensePhotoFixture.approved(licenses,user);
    }
    BookingService.View create(){return bookings.create(user,new BookingService.Input("v1","dn","dn",null,null,1300000L,LocalDate.of(2026,10,26),LocalDate.of(2026,10,28)));}
    BookingService.View approve(String id,long version){return (BookingService.View)workflow.approve(staff,id,version);}
    void receipt(String ref,long amount,String number){
        var fields=new TreeMap<String,String>();fields.put("vnp_TmnCode","TESTONLY");fields.put("vnp_TxnRef",ref);fields.put("vnp_Amount",Long.toString(amount*100));fields.put("vnp_TransactionNo",number);fields.put("vnp_PayDate",VnpayGateway.date(clock.instant()));fields.put("vnp_ResponseCode","00");fields.put("vnp_TransactionStatus","00");fields.put("vnp_SecureHash",gateway.sign(VnpayGateway.canonical(fields)));workflow.notification(fields);
    }
    @Test void approvalDeadlineReuseReceiptIdempotencyAndContract(){
        var b=create();assertTrue(b.deposit().required());assertEquals(390000,b.deposit().amount());
        assertThrows(RuntimeException.class,()->payments.start(user,b.id(),"127.0.0.1"));
        var approved=approve(b.id(),b.version());assertEquals(clock.instant().plusSeconds(1800),approved.expiresAt());
        clock.value=clock.value.plusSeconds(60);var again=approve(b.id(),approved.version());assertEquals(approved.expiresAt(),again.expiresAt());
        var p=payments.start(user,b.id(),"127.0.0.1");assertEquals(p.transactionId(),payments.start(user,b.id(),"127.0.0.1").transactionId());
        receipt(p.transactionId(),390000,"10001");receipt(p.transactionId(),390000,"10001");
        assertEquals("CONFIRMED",bookings.list(user).getFirst().status());assertTrue(repo.payment(p.transactionId()).orElseThrow().applied());
        assertEquals(1,jdbc.queryForObject("select count(*) from contracts where booking_id=?",Integer.class,b.id()));
        assertThrows(RuntimeException.class,()->receipt(p.transactionId(),390001,"10001"));
    }
    @Test void lateReceiptAndChangedLicenseNeverReclaimSlot(){
        var b=create();approve(b.id(),b.version());var p=payments.start(user,b.id(),"127.0.0.1");
        clock.value=p.expiresAt();receipt(p.transactionId(),390000,"10002");
        assertEquals("EXPIRED",bookings.list(user).getFirst().status());assertEquals("APPROVED",repo.refundForPayment(p.transactionId()).orElseThrow().status());
        assertFalse(repo.payment(p.transactionId()).orElseThrow().applied());assertEquals(0,jdbc.queryForObject("select count(*) from contracts",Integer.class));
    }
    @Test void licenseEditAfterApprovalQueuesFullRefund(){
        var b=create();approve(b.id(),b.version());var p=payments.start(user,b.id(),"127.0.0.1");var l=licenses.mine(user);
        licenses.save(user,new LicenseService.View(null,"SYNTHETIC-CHANGED","VN_2025","B",l.expiresOn(),false,"PENDING",l.version()));
        receipt(p.transactionId(),390000,"10003");assertEquals("PENDING",bookings.list(user).getFirst().status());assertEquals(390000,repo.refundForPayment(p.transactionId()).orElseThrow().amount());
    }
    @Test void noShowChecksTimeBranchAndDecisionBounds(){
        var b=create();approve(b.id(),b.version());var p=payments.start(user,b.id(),"127.0.0.1");receipt(p.transactionId(),390000,"10004");var confirmed=bookings.list(user).getFirst();
        assertThrows(RuntimeException.class,()->workflow.noShow(staff,b.id(),confirmed.version(),true,true,"Synthetic no-show"));
        clock.value=Instant.parse("2026-10-27T03:00:00Z");
        assertThrows(RuntimeException.class,()->workflow.noShow(new Actor("demo-staff-hn","STAFF","Other"),b.id(),confirmed.version(),true,true,"Synthetic"));
        workflow.noShow(staff,b.id(),confirmed.version(),true,true,"Synthetic no-show");var refund=repo.refundForPayment(p.transactionId()).orElseThrow();assertEquals("REQUESTED",refund.status());
        assertThrows(RuntimeException.class,()->payments.decide(staff,refund.id(),refund.version(),1,"Synthetic"));
        assertThrows(RuntimeException.class,()->payments.decide(admin,refund.id(),refund.version(),390001,"Synthetic"));
        payments.decide(admin,refund.id(),refund.version(),195000,"Synthetic partial refund");assertEquals("APPROVED",repo.refund(refund.id()).status());
        assertThrows(RuntimeException.class,()->payments.decide(admin,refund.id(),refund.version(),195000,"Duplicate"));
    }
    @Test void returnQueryCannotConfirmAndPublicIpnRejectsUnsigned()throws Exception{
        var b=create();var token="Bearer "+tokens.issue(accounts.require(user.id()));
        assertEquals(200,mvc.perform(get("/api/v1/payments/vnpay/ipn").param("vnp_ResponseCode","00")).andReturn().getResponse().getStatus());
        assertEquals("97",json.readTree(mvc.perform(get("/api/v1/payments/vnpay/ipn")).andReturn().getResponse().getContentAsByteArray()).path("RspCode").asText());
        assertEquals("PENDING",bookings.list(user).getFirst().status());
        assertEquals(403,mvc.perform(get("/api/v1/admin/payments").header("Authorization",token)).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(post("/api/v1/bookings/"+b.id()+"/actions").header("Authorization","Bearer "+tokens.issue(accounts.require(staff.id()))).contentType("application/json").content("{\"next\":\"CONFIRMED\",\"version\":0}")).andReturn().getResponse().getStatus());
    }
    @Test void twoSuccessfulAttemptsApplyOnlyOneAndRefundTheExcess()throws Exception{
        var b=create();approve(b.id(),b.version());var first=payments.start(user,b.id(),"127.0.0.1");
        payments.receive(new VnpayGateway.Receipt(first.transactionId(),390000,null,null,false));
        var second=payments.start(user,b.id(),"127.0.0.1");assertNotEquals(first.transactionId(),second.transactionId());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->receipt(first.transactionId(),390000,"10010"));var c=pool.submit(()->receipt(second.transactionId(),390000,"10011"));
            a.get(15,java.util.concurrent.TimeUnit.SECONDS);c.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
        assertEquals(1,jdbc.queryForObject("select count(*) from contracts",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from payment_transactions where applied=true",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from payment_refunds where amount=390000 and status='APPROVED'",Integer.class));
    }
    @Test void refundTimeoutReservesMoneyAndCannotBeResent(){
        var b=create();approve(b.id(),b.version());var p=payments.start(user,b.id(),"127.0.0.1");clock.value=p.expiresAt();receipt(p.transactionId(),390000,"10012");
        var refund=repo.refundForPayment(p.transactionId()).orElseThrow();
        org.mockito.Mockito.doAnswer(i->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());throw new IllegalStateException("Synthetic timeout, no provider call");}).when(gateway).refund(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        workflow.processRefund(admin,refund.id());assertEquals("UNKNOWN",repo.refund(refund.id()).status());
        assertThrows(RuntimeException.class,()->workflow.processRefund(admin,refund.id()));
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(1)).refund(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
    }
    @Test void earlyCancellationQueuesFullRefundAndHandoverCannotBecomeNoShow(){
        var b=create();approve(b.id(),b.version());var p=payments.start(user,b.id(),"127.0.0.1");receipt(p.transactionId(),390000,"10013");var confirmed=bookings.list(user).getFirst();
        rental.apply(user,b.id(),new BookingService.Action("CANCELLED",confirmed.version(),"Synthetic early cancellation",null,false,false));
        assertEquals(390000,repo.refundForPayment(p.transactionId()).orElseThrow().amount());
        assertEquals("APPROVED",repo.refundForPayment(p.transactionId()).orElseThrow().status());
    }
    @Test void handedOverVehicleCannotBeCancelledAsNoShow(){
        var b=create();approve(b.id(),b.version());var p=payments.start(user,b.id(),"127.0.0.1");receipt(p.transactionId(),390000,"10014");var confirmed=bookings.list(user).getFirst();
        clock.value=Instant.parse("2026-10-26T03:00:00Z");rental.apply(staff,b.id(),new BookingService.Action("IN_PROGRESS",confirmed.version(),null,"Synthetic handover",true,false));
        var active=bookings.list(user).getFirst();clock.value=Instant.parse("2026-10-27T03:00:00Z");
        assertThrows(RuntimeException.class,()->workflow.noShow(staff,b.id(),active.version(),true,true,"Must refuse"));assertEquals("IN_PROGRESS",bookings.list(user).getFirst().status());assertTrue(repo.refundForPayment(p.transactionId()).isEmpty());
    }
    @Test void refundCompletionIsSeparateFromCancellationAndDoesNotResend(){
        var b=create();approve(b.id(),b.version());var p=payments.start(user,b.id(),"127.0.0.1");clock.value=p.expiresAt();receipt(p.transactionId(),390000,"10015");var r=repo.refundForPayment(p.transactionId()).orElseThrow();
        assertEquals("APPROVED",r.status());
        org.mockito.Mockito.doAnswer(i->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return new VnpayGateway.RefundReply(true,false,"20015");}).when(gateway).refund(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        workflow.processRefund(admin,r.id());assertEquals("SUCCEEDED",repo.refund(r.id()).status());assertEquals("EXPIRED",bookings.list(user).getFirst().status());assertThrows(RuntimeException.class,()->workflow.processRefund(admin,r.id()));
    }
    @Test void cancellationRacingReceiptHasOnlySerializableMoneyOutcomes()throws Exception{
        var b=create();var approved=approve(b.id(),b.version());var p=payments.start(user,b.id(),"127.0.0.1");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
        boolean cancelled;
        try{
            var money=pool.submit(()->{ready.countDown();try{assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}receipt(p.transactionId(),390000,"10016");});
            var cancel=pool.submit(()->{ready.countDown();assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));try{rental.apply(user,b.id(),new BookingService.Action("CANCELLED",approved.version(),"Synthetic cancellation race",null,false,false));return true;}catch(com.carrental.exception.DomainException e){assertEquals(409,e.status.value());return false;}});
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));start.countDown();money.get(15,java.util.concurrent.TimeUnit.SECONDS);cancelled=cancel.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
        var finalBooking=bookings.list(user).getFirst();var payment=repo.payment(p.transactionId()).orElseThrow();assertEquals("SUCCEEDED",payment.status());
        if(cancelled){
            assertEquals("CANCELLED",finalBooking.status());assertFalse(payment.applied());assertEquals(390000,repo.refundForPayment(p.transactionId()).orElseThrow().amount());assertEquals(0,jdbc.queryForObject("select count(*) from contracts",Integer.class));
        }else{
            assertEquals("CONFIRMED",finalBooking.status());assertTrue(payment.applied());assertTrue(repo.refundForPayment(p.transactionId()).isEmpty());assertEquals(1,jdbc.queryForObject("select count(*) from contracts",Integer.class));
        }
    }
    @Test void nonterminalSignedNotificationCannotOpenReplacementPayment(){
        var b=create();approve(b.id(),b.version());var p=payments.start(user,b.id(),"127.0.0.1");
        var fields=new TreeMap<String,String>();fields.put("vnp_TmnCode","TESTONLY");fields.put("vnp_TxnRef",p.transactionId());fields.put("vnp_Amount","39000000");fields.put("vnp_ResponseCode","00");fields.put("vnp_TransactionStatus","01");fields.put("vnp_SecureHash",gateway.sign(VnpayGateway.canonical(fields)));
        workflow.notification(fields);assertEquals("UNKNOWN",repo.payment(p.transactionId()).orElseThrow().status());
        assertThrows(com.carrental.exception.DomainException.class,()->payments.start(user,b.id(),"127.0.0.1"));assertEquals(1,jdbc.queryForObject("select count(*) from payment_transactions",Integer.class));
        receipt(p.transactionId(),390000,"10017");assertEquals("CONFIRMED",bookings.list(user).getFirst().status());assertEquals(1,jdbc.queryForObject("select count(*) from contracts",Integer.class));
        workflow.notification(fields);assertEquals("SUCCEEDED",repo.payment(p.transactionId()).orElseThrow().status());
    }
}
