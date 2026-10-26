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
@SpringBootTest(properties={"app.jwt-secret=qr-demo-test-only-secret-at-least-32-bytes", "app.seed-enabled=true", "app.seed-password=QRDemoTestOnly123!", "app.deposit-enabled=true", "app.return-policy-enabled=true", "app.deposit-method=DEMO_QR_MANUAL", "app.payment-reconcile-ms=86400000", "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"})
@AutoConfigureMockMvc
@Import(ReturnPolicyHttpTest.TimeConfig.class)
class ReturnPolicyHttpTest {
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("qr-demo-synthetic-photos-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        String url=System.getenv("KDRIVE_TEST_DB_URL");
        if(url!=null)CancelBoundaryTest.verifySafeIsolatedDatabase(url,System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"),System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),System.getenv("KDRIVE_TEST_DB_USER"),System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        r.add("spring.datasource.url",()->url==null?"jdbc:h2:mem:return-policy;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1":url);
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
        org.springframework.test.util.ReflectionTestUtils.setField(bookings,"returnPolicyEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(bookings,"depositEnabled",true);
        CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);
        for(String t:List.of("payment_events","payment_refunds","payment_transactions","reviews","incident_events","incident_cases","vehicle_handover_records","invoice_items","invoices","contracts","booking_status_history","bookings"))jdbc.update("delete from "+t);
        jdbc.update("update vehicles set current_branch_id='dn',status='ACTIVE',daily_price=650000 where id='v1'");clock.value=Instant.parse("2026-10-23T03:00:00Z");
        var old=licenses.mine(user);licenses.save(user,new LicenseService.View(null,"SYNTHETIC-QR-DEMO-NOT-REAL","VN_2025","B",LocalDate.of(2035,12,31),false,"PENDING",old==null?0:old.version()));LicensePhotoFixture.approved(licenses,user);
    }
    BookingService.View create(){return bookings.create(user,new BookingService.Input("v1","dn","dn",null,null,1300000L,LocalDate.of(2026,10,26),LocalDate.of(2026,10,28),com.carrental.modules.booking.service.ReturnPolicy.VERSION,true));}
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

    Map<String,Object> bookingInput(){return new LinkedHashMap<>(Map.of("vehicleId","v1","pickupBranchId","dn","returnBranchId","dn","pickupDate","2026-10-26","returnDate","2026-10-28","expectedTotal",1300000));}
    JsonNode body(org.springframework.mock.web.MockHttpServletResponse r)throws Exception{return json.readTree(r.getContentAsString());}
    @Test void quoteRequiresNoAckAndCreateRejectsMissingFalseOrStaleConsent()throws Exception{
        var i=bookingInput();var q=post(user,"/bookings/quote",i);assertEquals(200,q.getStatus());
        assertEquals("same-branch-return-v1",body(q).at("/returnPolicy/version").asText());assertTrue(body(q).at("/returnPolicy/acceptedAt").isNull());
        assertEquals(409,post(user,"/bookings",i).getStatus());
        i.put("returnPolicyVersion","same-branch-return-v1");assertEquals(400,post(user,"/bookings",i).getStatus());
        i.put("returnPolicyAccepted",false);assertEquals(400,post(user,"/bookings",i).getStatus());
        i.put("returnPolicyAccepted",true);i.put("returnPolicyVersion","stale-v0");assertEquals(409,post(user,"/bookings",i).getStatus());
        assertEquals(0,jdbc.queryForObject("select count(*) from bookings",Integer.class));
    }
    @Test void acceptedSnapshotUsesServerClockAndIgnoresClientTerms()throws Exception{
        var i=bookingInput();i.put("returnPolicyVersion","same-branch-return-v1");i.put("returnPolicyAccepted",true);i.put("returnPolicyAcceptedAt","2000-01-01T00:00:00Z");i.put("returnPolicy",Map.of("dailyPriceVnd",1,"text","fake"));
        var r=post(user,"/bookings",i);assertEquals(201,r.getStatus(),r.getContentAsString());var policy=body(r).get("returnPolicy");
        assertEquals(clock.instant().toString(),policy.get("acceptedAt").asText());assertEquals(650000,policy.at("/terms/dailyPriceVnd").asLong());assertEquals("Asia/Ho_Chi_Minh",policy.at("/terms/timeZone").asText());assertNotEquals("fake",policy.at("/terms/text").asText());
        assertEquals(policy,body(get(user,"/bookings/"+body(r).get("id").asText())).at("/booking/returnPolicy"));
    }
    @Test void disabledFlagKeepsOldApiAndRefusesStaleOptIn()throws Exception{
        org.springframework.test.util.ReflectionTestUtils.setField(bookings,"returnPolicyEnabled",false);
        var i=bookingInput();assertTrue(body(post(user,"/bookings/quote",i)).get("returnPolicy").isNull());
        var stale=new LinkedHashMap<>(i);stale.put("returnPolicyVersion","same-branch-return-v1");stale.put("returnPolicyAccepted",true);assertEquals(409,post(user,"/bookings",stale).getStatus());
        var r=post(user,"/bookings",i);assertEquals(201,r.getStatus());assertTrue(body(r).get("returnPolicy").isNull());
    }
    @Test void changingFlagAfterQuoteRequiresNewQuote()throws Exception{
        var i=bookingInput();var q=post(user,"/bookings/quote",i);assertEquals(200,q.getStatus());i.put("returnPolicyVersion",body(q).at("/returnPolicy/version").asText());i.put("returnPolicyAccepted",true);
        org.springframework.test.util.ReflectionTestUtils.setField(bookings,"returnPolicyEnabled",false);assertEquals(409,post(user,"/bookings",i).getStatus());assertEquals(0,jdbc.queryForObject("select count(*) from bookings",Integer.class));
    }
    @Test void crossBranchAndLegacyAreNotOptedIn()throws Exception{
        var i=bookingInput();i.put("returnBranchId","hn");i.put("expectedTotal",2800000);var r=post(user,"/bookings",i);assertEquals(201,r.getStatus(),r.getContentAsString());assertTrue(body(r).get("returnPolicy").isNull());
        assertEquals(200,post(user,"/bookings/"+body(r).get("id").asText()+"/actions",Map.of("next","CANCELLED","version",body(r).get("version").asLong(),"reason","End cross-branch fixture")).getStatus());
        i=bookingInput();i.remove("pickupDate");i.remove("returnDate");i.put("startAt","2026-11-01T03:00:00Z");i.put("endAt","2026-11-03T03:00:00Z");r=post(user,"/bookings",i);assertEquals(201,r.getStatus(),r.getContentAsString());assertTrue(body(r).get("returnPolicy").isNull());
    }
    void assertStoredAndContract(String id,String snapshot)throws Exception{
        assertEquals(snapshot,jdbc.queryForObject("select return_policy_snapshot from bookings where id=?",String.class,id));
        var c=get(user,"/bookings/"+id+"/contract");assertEquals(200,c.getStatus());var stored=json.readTree(body(c).get(0).get("snapshot").asText());assertEquals(650000,stored.at("/returnPolicy/terms/dailyPriceVnd").asLong());assertEquals("same-branch-return-v1",stored.at("/returnPolicy/version").asText());assertEquals(clock.instant().toString(),stored.at("/returnPolicy/acceptedAt").asText());
    }
    @Test void normalConfirmationUsesImmutableSnapshotAfterPriceAndFlagChange()throws Exception{
        org.springframework.test.util.ReflectionTestUtils.setField(bookings,"depositEnabled",false);var b=create();var snapshot=jdbc.queryForObject("select return_policy_snapshot from bookings where id=?",String.class,b.id());
        jdbc.update("update vehicles set daily_price=990000 where id='v1'");org.springframework.test.util.ReflectionTestUtils.setField(bookings,"returnPolicyEnabled",false);
        var r=post(staff,"/bookings/"+b.id()+"/actions",Map.of("next","CONFIRMED","version",b.version()));assertEquals(200,r.getStatus(),r.getContentAsString());assertStoredAndContract(b.id(),snapshot);
    }
    @Test void qrConfirmationUsesImmutableSnapshotAndOneContract()throws Exception{
        var b=approve();var snapshot=jdbc.queryForObject("select return_policy_snapshot from bookings where id=?",String.class,b.id());jdbc.update("update vehicles set daily_price=990000 where id='v1'");org.springframework.test.util.ReflectionTestUtils.setField(bookings,"returnPolicyEnabled",false);
        verify(b.id(),"RETURN-POLICY-QR",390000);assertEquals("CONFIRMED",current(b.id()).status());assertEquals(1,count("contracts",b.id()));assertStoredAndContract(b.id(),snapshot);
    }
}
