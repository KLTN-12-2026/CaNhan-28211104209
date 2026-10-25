package com.carrental;

import static org.junit.jupiter.api.Assertions.*;
import com.carrental.modules.booking.service.*;
import com.carrental.modules.user.service.*;
import com.carrental.security.Actor;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.jwt-secret=vehicle-search-test-secret-at-least-32-bytes",
    "app.seed-enabled=true", "app.seed-password=SearchFixtureOnly123!",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@Import(VehicleSearchTest.TimeConfig.class)
class VehicleSearchTest {
    static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    @TestConfiguration static class TimeConfig {
        @Bean @Primary Clock searchFixtureClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("KDRIVE_TEST_DB_URL");
        boolean nativeDb = url != null && !url.isBlank();
        if (nativeDb) CancelBoundaryTest.verifySafeIsolatedDatabase(url,
            System.getenv("KDRIVE_TEST_REQUIRED_SCHEMA_PREFIX"), System.getenv("KDRIVE_TEST_FIXTURE_MARKER_TOKEN"),
            System.getenv("KDRIVE_TEST_DB_USER"), System.getenv("KDRIVE_TEST_DB_PASSWORD"));
        r.add("spring.datasource.url", () -> nativeDb ? url : "jdbc:h2:mem:vehiclesearch;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.username", () -> nativeDb ? System.getenv("KDRIVE_TEST_DB_USER") : "sa");
        r.add("spring.datasource.password", () -> nativeDb ? System.getenv("KDRIVE_TEST_DB_PASSWORD") : "");
    }
    @Autowired VehicleSearchService search;
    @Autowired BookingService bookings;
    @Autowired LicenseService licenses;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    final Actor user = new Actor("demo-user", "USER", "Search fixture");
    Instant start = NOW.plusSeconds(3600);
    @BeforeEach void setup() {
        CancelBoundaryTest.verifySafeIsolatedDatabase(jdbc);
        for (String table : List.of("reviews", "incident_events", "incident_cases", "vehicle_handover_records", "invoice_items", "invoices", "contracts", "booking_status_history", "bookings")) jdbc.update("delete from " + table);
        jdbc.update("update branches set status='ACTIVE'");
        jdbc.update("update branch_routes set enabled=true");
        jdbc.update("update vehicles set status='ACTIVE',current_branch_id='dn',daily_price=650000 where id in ('v1','v3')");
        jdbc.update("update vehicles set status='ACTIVE',current_branch_id='hn' where id='v2'");
        var old = licenses.mine(user);
        licenses.save(user, new LicenseService.View(old == null ? null : old.id(), "SEARCH-123", "VN_2025", "B", LocalDate.of(2030,12,31), false, "PENDING", old == null ? 0 : old.version()));
        LicensePhotoFixture.approved(licenses, user);
    }
    VehicleSearchService.Input input(long seconds, Long budget) {
        return new VehicleSearchService.Input(start, start.plusSeconds(seconds), "dn", "hn", budget, null, null, null, null, null);
    }
    VehicleSearchService.Item v1(VehicleSearchService.Result result) {
        return result.items().stream().filter(i -> i.vehicle().id().equals("v1")).findFirst().orElseThrow();
    }
    boolean available() { return search.search(input(86400,null)).items().stream().anyMatch(i -> i.vehicle().id().equals("v1")); }
    @Test void calendarQuoteSearchCreateAndCompletedReservationAgree(){
        LocalDate pickup=LocalDate.of(2026,10,20),returned=LocalDate.of(2026,10,24);
        var request=new BookingService.Input("v1","dn","dn",null,null,null,pickup,returned);
        var quote=bookings.quote(user,request);assertEquals(4,quote.rentalDays());assertEquals(2600000,quote.total());assertEquals("calendar-2026-10-17",quote.policyVersion());
        var found=search.search(new VehicleSearchService.Input(null,null,"dn","dn",null,null,null,null,null,null,pickup,returned));assertEquals(2600000,v1(found).total());
        var b=bookings.create(user,new BookingService.Input("v1","dn","dn",null,null,quote.total(),pickup,returned));
        assertEquals("calendar-2026-10-17",jdbc.queryForObject("select policy_version from bookings where id=?",String.class,b.id()));
        assertThrows(RuntimeException.class,()->bookings.quote(user,new BookingService.Input("v1","dn","dn",null,null,null,returned,returned.plusDays(1))));
        assertDoesNotThrow(()->bookings.quote(user,new BookingService.Input("v1","dn","dn",null,null,null,returned.plusDays(1),returned.plusDays(2))));
        jdbc.update("update bookings set status='COMPLETED' where id=?",b.id());
        assertTrue(search.search(new VehicleSearchService.Input(null,null,"dn","dn",null,null,null,null,null,null,returned,returned.plusDays(1))).items().stream().noneMatch(i->i.vehicle().id().equals("v1")));
        assertThrows(RuntimeException.class,()->bookings.quote(user,new BookingService.Input("v1","dn","dn",null,null,null,returned,returned.plusDays(1))));
        assertEquals(4,jdbc.queryForObject("select rental_days from bookings where id=?",Integer.class,b.id()));
    }
    @Test void calendarRejectsMixedOrIncompleteInput(){
        LocalDate pickup=LocalDate.of(2026,10,20);
        assertThrows(RuntimeException.class,()->bookings.quote(user,new BookingService.Input("v1","dn","dn",start,start.plusSeconds(86400),null,pickup,pickup.plusDays(1))));
        assertThrows(RuntimeException.class,()->search.search(new VehicleSearchService.Input(null,null,"dn","dn",null,null,null,null,null,null,pickup,null)));
    }
    BookingService.Input booking() { return new BookingService.Input("v1","dn","hn",start,start.plusSeconds(86400),null); }
    String pending() {
        var i=booking(); long total=bookings.quote(user,i).total();
        return bookings.create(user,new BookingService.Input(i.vehicleId(),i.pickupBranchId(),i.returnBranchId(),i.startAt(),i.endAt(),total)).id();
    }
    @Test void priceIncludesRouteAndCeilingWithInclusiveBudget() {
        var one=v1(search.search(input(86400,null)));
        assertEquals(1,one.rentalDays()); assertEquals(650000,one.rentalTotal()); assertEquals(1500000,one.oneWayFee()); assertEquals(2150000,one.total());
        assertEquals(2800000,v1(search.search(input(90000,null))).total());
        assertEquals(2150000,v1(search.search(input(86400,2150000L))).total());
        assertTrue(search.search(input(86400,2149999L)).items().isEmpty());
    }
    @Test void searchDoesNotWriteOrLeakCustomerAndGuestQuoteRequiresLogin() {
        var before=jdbc.queryForList("select * from vehicles order by id");
        String url="/api/v1/vehicles/search?startAt=2026-10-20T09:00:00Z&endAt=2026-10-21T09:00:00Z&pickupBranchId=dn&returnBranchId=hn";
        var response=http.getForEntity(url,String.class);
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertTrue(response.getBody().contains("2150000"));
        for(String key:List.of("customerId","expiresAt","licenseNumber","passwordHash","bookingId")) assertFalse(response.getBody().contains(key),key);
        assertEquals(before,jdbc.queryForList("select * from vehicles order by id"));
        assertEquals(0,jdbc.queryForObject("select count(*) from bookings",Integer.class));
        assertEquals(HttpStatus.UNAUTHORIZED,http.postForEntity("/api/v1/bookings/quote",booking(),String.class).getStatusCode());
    }
    @Test void overlapIsExcludedAndExpiredHoldIsReleased() {
        String id=pending(); assertFalse(available());
        jdbc.update("update bookings set expires_at=? where id=?",LocalDateTime.ofInstant(NOW,ZoneOffset.UTC),id);
        assertTrue(available());
    }
    @Test void canceledBookingDoesNotOccupy() { String id=pending(); jdbc.update("update bookings set status='CANCELLED' where id=?",id); assertTrue(available()); }
    @Test void nonActiveVehicleIsExcluded() { for(String status:List.of("MAINTENANCE","INACTIVE")) { jdbc.update("update vehicles set status=? where id='v1'",status); assertFalse(available()); } }
    @Test void wrongCurrentBranchIsExcluded() { jdbc.update("update vehicles set current_branch_id='hn' where id='v1'"); assertFalse(available()); }
    @Test void closedBranchesAndRoutesAreErrors() {
        for(String id:List.of("dn","hn")) { jdbc.update("update branches set status='INACTIVE' where id=?",id); assertThrows(com.carrental.exception.DomainException.class,()->search.search(input(86400,null))); jdbc.update("update branches set status='ACTIVE' where id=?",id); }
        jdbc.update("update branch_routes set enabled=false where pickup_branch_id='dn' and return_branch_id='hn'");
        assertThrows(com.carrental.exception.DomainException.class,()->search.search(input(86400,null)));
    }
    @Test void invalidRequestsAreRejected() {
        for(long seconds:List.of(0L,-1L,90*86400L+1)) assertThrows(com.carrental.exception.DomainException.class,()->search.search(input(seconds,null)));
        assertThrows(com.carrental.exception.DomainException.class,()->search.search(input(86400,-1L)));
        assertEquals(HttpStatus.BAD_REQUEST,http.getForEntity("/api/v1/vehicles/search?pickupBranchId=dn",String.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,http.getForEntity("/api/v1/vehicles/search?startAt=bad&endAt=bad&pickupBranchId=dn&returnBranchId=hn",String.class).getStatusCode());
    }
    @Test void filtersNeverInventMatchingCarsAndUnknownBranchRouteReject() {
        var baseline=v1(search.search(input(86400,null))).vehicle();
        assertEquals("v1",v1(search.search(new VehicleSearchService.Input(start,start.plusSeconds(86400),"dn","hn",null,baseline.seats(),baseline.transmission(),baseline.brandId(),baseline.categoryId(),baseline.name()))).vehicle().id());
        assertTrue(search.search(new VehicleSearchService.Input(start,start.plusSeconds(86400),"dn","hn",null,null,null,"unknown",null,null)).items().isEmpty());
        for(int seats:List.of(0,10)) assertThrows(com.carrental.exception.DomainException.class,()->search.search(new VehicleSearchService.Input(start,start.plusSeconds(86400),"dn","hn",null,seats,null,null,null,null)));
        assertThrows(com.carrental.exception.DomainException.class,()->search.search(new VehicleSearchService.Input(start,start.plusSeconds(86400),"missing","hn",null,null,null,null,null,null)));
        var route=jdbc.queryForMap("select * from branch_routes where pickup_branch_id='dn' and return_branch_id='hn'");
        try {
            jdbc.update("delete from branch_routes where pickup_branch_id='dn' and return_branch_id='hn'");
            assertThrows(com.carrental.exception.DomainException.class,()->search.search(input(86400,null)));
        } finally {
            jdbc.update("insert into branch_routes(id,pickup_branch_id,return_branch_id,fee,enabled,version) values(?,?,?,?,?,?)",route.get("id"),route.get("pickup_branch_id"),route.get("return_branch_id"),route.get("fee"),route.get("enabled"),route.get("version"));
        }
    }
    @Test void priceAndSlotAreRevalidatedWhenCreating() {
        long total=v1(search.search(input(86400,null))).total();
        jdbc.update("update vehicles set daily_price=700000 where id='v1'");
        assertThrows(com.carrental.exception.DomainException.class,()->bookings.create(user,new BookingService.Input("v1","dn","hn",start,start.plusSeconds(86400),total)));
        pending(); assertThrows(com.carrental.exception.DomainException.class,()->bookings.quote(user,booking()));
    }
    @Test void confirmedFuturePositionCanBeUsedButUnconfirmedTransferCannot() {
        String id=pending();
        start=start.plusSeconds(90000);
        var atHn=new VehicleSearchService.Input(start,start.plusSeconds(86400),"hn","hn",null,null,null,null,null,null);
        assertTrue(search.search(atHn).items().stream().noneMatch(i->i.vehicle().id().equals("v1")));
        jdbc.update("update bookings set status='CONFIRMED' where id=?",id);
        assertEquals(650000,v1(search.search(atHn)).total());
        jdbc.update("update bookings set status='IN_PROGRESS',start_at=?,end_at=? where id=?",LocalDateTime.ofInstant(NOW.minusSeconds(90000),ZoneOffset.UTC),LocalDateTime.ofInstant(NOW.minusSeconds(3600),ZoneOffset.UTC),id);
        assertTrue(search.search(atHn).items().stream().noneMatch(i->i.vehicle().id().equals("v1")));
    }
    @Test void rentalTermsPreserveSubMillisecondRoundingAndVietnamOffset() {
        assertEquals(2,RentalTerms.days(start,start.plusSeconds(86400).plusNanos(1000),NOW));
        var request=new VehicleSearchService.Input(start,start.plusSeconds(86400).plusNanos(1000),"dn","hn",null,null,null,null,null,null);
        assertEquals(2800000,v1(search.search(request)).total());
        assertEquals(2800000,bookings.quote(user,new BookingService.Input("v1","dn","hn",request.startAt(),request.endAt(),null)).total());
        assertEquals(start,OffsetDateTime.parse("2026-10-20T16:00:00+07:00").toInstant());
        assertEquals(start,RentalTerms.holdUntil(start,NOW));
        assertEquals(NOW.plusSeconds(7200),RentalTerms.holdUntil(start.plusSeconds(7200),NOW));
    }
}
