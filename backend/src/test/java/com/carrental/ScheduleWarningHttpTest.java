package com.carrental;

import com.carrental.modules.booking.service.*;
import com.carrental.modules.booking.repository.BookingRepository;
import com.carrental.modules.operation.service.RentalWorkflow;
import com.carrental.modules.user.service.*;
import com.carrental.security.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:schedule-warnings;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "app.jwt-secret=schedule-warning-test-secret-at-least-32-bytes",
        "app.seed-enabled=true", "app.seed-password=WarningFixtureOnly123!", "app.deposit-enabled=false", "app.return-policy-enabled=false"})
@AutoConfigureMockMvc
@Import(ScheduleWarningHttpTest.TimeConfig.class)
class ScheduleWarningHttpTest {
    static final Path PHOTOS;
    static { try { PHOTOS=Files.createTempDirectory("schedule-warning-synthetic-"); } catch(Exception e) {throw new IllegalStateException(e);} }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {r.add("app.license-photo-directory",()->PHOTOS.toString());}
    static class TestClock extends Clock {
        Instant now=Instant.parse("2026-10-25T03:00:00Z");
        public Instant instant(){return now;} public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
    }
    @TestConfiguration static class TimeConfig {@Bean @Primary TestClock warningClock(){return new TestClock();}}
    @Autowired BookingService bookings;
    @Autowired RentalWorkflow rental;
    @Autowired LicenseService licenses;
    @Autowired AccountService accounts;
    @Autowired TokenService tokens;
    @Autowired TestClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @org.springframework.boot.test.mock.mockito.SpyBean BookingRepository repo;
    final Actor user=new Actor("demo-user","USER","Synthetic"), staff=new Actor("demo-staff-dn","STAFF","DN"),
            wrongStaff=new Actor("demo-staff-hn","STAFF","HN"), admin=new Actor("demo-admin","ADMIN","Admin");
    String a,b;
    Actor other;
    @BeforeEach void setup() {
        CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);
        for(String t:List.of("booking_return_settlements","payment_events","payment_refunds","payment_transactions","reviews","incident_events","incident_cases","vehicle_handover_records","invoice_items","invoices","contracts","booking_status_history","bookings"))jdbc.update("delete from "+t);
        jdbc.update("update vehicles set current_branch_id='dn',status='ACTIVE',daily_price=650000 where id='v1'");
        clock.now=Instant.parse("2026-10-25T03:00:00Z");
        var old=licenses.mine(user);
        licenses.save(user,new LicenseService.View(null,"SYNTHETIC-WARNING-NOT-REAL","VN_2025","B",LocalDate.of(2035,12,31),false,"PENDING",old==null?0:old.version()));
        LicensePhotoFixture.approved(licenses,user);
        a=bookings.create(user,new BookingService.Input("v1","dn","dn",null,null,1300000L,LocalDate.of(2026,10,25),LocalDate.of(2026,10,27))).id();
        action(a,"CONFIRMED");action(a,"IN_PROGRESS");
        clock.now=Instant.parse("2026-10-27T03:00:00Z");
        b=bookings.create(user,new BookingService.Input("v1","dn","dn",null,null,650000L,LocalDate.of(2026,10,28),LocalDate.of(2026,10,29))).id();
        action(b,"CONFIRMED");
        var account=accounts.register("warning-"+UUID.randomUUID()+"@example.test","Other synthetic","WarningFixtureOnly123!");
        other=new Actor(account.id,"USER","Other synthetic");
        jdbc.update("update bookings set user_id=? where id=?",other.id(),a);
        clearInvocations(repo);
    }
    void action(String id,String next){var current=bookings.view(bookings.require(admin,id));rental.apply(staff,id,new BookingService.Action(next,current.version(),"Synthetic","Synthetic condition",true,false));}
    org.springframework.mock.web.MockHttpServletResponse response(Actor actor,String path)throws Exception{
        return mvc.perform(get("/api/v1/bookings"+path).header("Authorization","Bearer "+tokens.issue(accounts.require(actor.id())))).andReturn().getResponse();
    }
    JsonNode read(Actor actor,String path)throws Exception{var r=response(actor,path);assertEquals(200,r.getStatus());return json.readTree(r.getContentAsString());}
    JsonNode row(JsonNode list,String id){for(var node:list)if(id.equals(node.path("id").asText()))return node;throw new AssertionError("Missing row "+id);}
    @Test void staffAndAdminGetEarlyWarningAndExistingFieldsUnchanged()throws Exception{
        for(var actor:List.of(staff,admin)){
            var list=read(actor,"");var detail=read(actor,"/"+a).get("booking");
            assertEquals(row(list,a),detail);
            var warning=detail.at("/scheduleWarnings/items/0");
            assertEquals("RETURN_DUE_NEXT_DAY_BOOKING",warning.path("kind").asText());
            assertEquals(b,warning.path("relatedBookingId").asText());assertEquals("2026-10-27",warning.path("bookedReturnDate").asText());
            assertEquals(clock.now.toString(),detail.at("/scheduleWarnings/checkedAt").asText());
            ObjectNode withoutWarning=detail.deepCopy();withoutWarning.remove("scheduleWarnings");
            // Compare the wire JSON on both sides (valueToTree retains Java LongNode vs parsed IntNode).
            assertEquals(json.readTree(json.writeValueAsString(bookings.view(bookings.require(admin,a)))),withoutWarning);
            for(String field:List.of("deposit","returnPolicy","returnAssessment","returnSettlement"))assertTrue(detail.has(field));
        }
    }
    @Test void overdueListAndSingleDetailUseCompleteScheduleAndRefreshAfterReturn()throws Exception{
        clock.now=Instant.parse("2026-10-27T17:00:00Z");
        var next=read(staff,"/"+b).at("/booking/scheduleWarnings/items/0");
        assertEquals("PREDECESSOR_NOT_RETURNED",next.path("kind").asText());assertEquals(a,next.path("relatedBookingId").asText());assertEquals(1,next.path("lateDays").asInt());
        assertEquals("OVERDUE",read(admin,"/"+a).at("/booking/scheduleWarnings/items/0/kind").asText());
        action(a,"RETURNED");
        assertTrue(read(staff,"/"+b).at("/booking/scheduleWarnings/items").isEmpty());
        assertTrue(read(admin,"/"+a).at("/booking/scheduleWarnings/items").isEmpty());
    }
    @Test void usersNeverReceiveWarningsOrOtherBookingIds()throws Exception{
        clock.now=Instant.parse("2026-10-27T17:00:00Z");
        var list=read(user,"");assertEquals(1,list.size());assertEquals(b,list.get(0).path("id").asText());
        assertFalse(list.get(0).has("scheduleWarnings"));
        var detail=response(user,"/"+b);assertFalse(detail.getContentAsString().contains(a));
        assertFalse(json.readTree(detail.getContentAsString()).get("booking").has("scheduleWarnings"));
        assertEquals(404,response(user,"/"+a).getStatus());
        assertFalse(read(other,"/"+a).get("booking").has("scheduleWarnings"));
        verify(repo,never()).searchSchedule(anyList(),any());
    }
    @Test void wrongBranchCannotReadOrDiscoverRelatedIds()throws Exception{
        assertTrue(read(wrongStaff,"").isEmpty());assertEquals(404,response(wrongStaff,"/"+a).getStatus());assertEquals(404,response(wrongStaff,"/"+b).getStatus());
        verify(repo,never()).searchSchedule(anyList(),any());
    }
    @Test void scheduleReadIsBatchedAndDoesNotWrite()throws Exception{
        jdbc.update("update vehicles set current_branch_id='dn',status='ACTIVE' where id='v3'");
        var request=new BookingService.Input("v3","dn","dn",null,null,null,LocalDate.of(2026,10,28),LocalDate.of(2026,10,29));
        long total=bookings.quote(user,request).total();
        String otherVehicle=bookings.create(user,new BookingService.Input("v3","dn","dn",null,null,total,request.pickupDate(),request.returnDate())).id();
        clearInvocations(repo);
        var before=jdbc.queryForList("select id,status,version,return_assessment,deposit_amount from bookings order by id");
        var list=read(admin,"");assertEquals(3,list.size());
        assertTrue(row(list,otherVehicle).at("/scheduleWarnings/items").isEmpty());
        verify(repo,times(1)).searchSchedule(argThat(ids->ids.size()==2 && new HashSet<>(ids).equals(Set.of("v1","v3"))),eq(clock.now));
        assertEquals(before,jdbc.queryForList("select id,status,version,return_assessment,deposit_amount from bookings order by id"));
        assertEquals(0,jdbc.queryForObject("select count(*) from payment_transactions",Integer.class));
    }
    @Test void noRiskIsExplicitEmptyArrayAndTimestampAdvances()throws Exception{
        clock.now=Instant.parse("2026-10-26T03:00:00Z");
        var early=read(staff,"/"+a).at("/booking/scheduleWarnings");assertTrue(early.path("items").isArray());assertTrue(early.path("items").isEmpty());
        clock.now=clock.now.plusSeconds(60);var later=read(staff,"/"+a).at("/booking/scheduleWarnings");
        assertEquals(clock.now.toString(),later.path("checkedAt").asText());assertNotEquals(early.path("checkedAt"),later.path("checkedAt"));
    }
}
