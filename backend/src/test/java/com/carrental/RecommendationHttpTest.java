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
    "app.ai-recommendation-enabled=true", "app.seed-enabled=true", "app.seed-password=SearchFixtureOnly123!",
    "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP"
})
@Import(RecommendationHttpTest.TimeConfig.class)
class RecommendationHttpTest {
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
        r.add("spring.datasource.url", () -> nativeDb ? url : "jdbc:h2:mem:recommendation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
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

    @Autowired com.carrental.modules.ai.recommendation.RecommendationService recommender;
    @Autowired com.carrental.security.TokenService tokens;
    @Autowired AccountService accounts;
    Map<String,Object> criteria(){return new LinkedHashMap<>(Map.of("pickupDate","2026-10-20","returnDate","2026-10-24","pickupBranchId","dn","returnBranchId","dn","passengers",4,"budgetVndTotal",3000000));}
    ResponseEntity<com.fasterxml.jackson.databind.JsonNode> call(String role,Map<String,Object> c){
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(role!=null)headers.setBearerAuth(tokens.issue(accounts.require(role)));
        return http.exchange("/api/v1/ai/recommendations",HttpMethod.POST,new HttpEntity<>(Map.of("requestId","test-1","criteria",c),headers),com.fasterxml.jackson.databind.JsonNode.class);
    }
    Map<String,Object> quoteInput(String vehicleId,Map<String,Object> c){
        return new LinkedHashMap<>(Map.of("vehicleId",vehicleId,
            "pickupBranchId",c.get("pickupBranchId"),"returnBranchId",c.get("returnBranchId"),
            "pickupDate",c.get("pickupDate"),"returnDate",c.get("returnDate")));
    }
    ResponseEntity<com.fasterxml.jackson.databind.JsonNode> quote(Map<String,Object> input){
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(tokens.issue(accounts.require("demo-user")));
        return http.exchange("/api/v1/bookings/quote",HttpMethod.POST,new HttpEntity<>(input,headers),
            com.fasterxml.jackson.databind.JsonNode.class);
    }
    @Test void httpRoleBoundaries(){
        assertEquals(401,call(null,criteria()).getStatusCode().value());
        assertEquals(403,call("demo-admin",criteria()).getStatusCode().value());
        assertEquals(403,call("demo-staff-dn",criteria()).getStatusCode().value());
        assertEquals(200,call("demo-user",criteria()).getStatusCode().value());
    }
    @Test void budgetIncludesFeeAndDoesNotWrite(){
        int before=jdbc.queryForObject("select count(*) from bookings",Integer.class);
        var c=criteria();c.put("pickupBranchId","dn");c.put("returnBranchId","hn");c.put("budgetVndTotal",4100000);
        var response=call("demo-user",c);assertEquals(200,response.getStatusCode().value());
        var result=response.getBody();assertEquals("OK",result.path("status").asText());
        var v=result.path("results").findValues("vehicleId");assertFalse(v.isEmpty());
        for(var item:result.path("results")){assertEquals(4,item.path("rentalDays").asInt());assertEquals(1500000,item.path("oneWayFee").asLong());assertEquals(4100000,item.path("total").asLong());}
        c.put("budgetVndTotal",4099999);assertEquals("NO_MATCH",call("demo-user",c).getBody().path("status").asText());
        assertEquals(before,jdbc.queryForObject("select count(*) from bookings",Integer.class));
    }
    @Test void recommendationVehicleCanBeQuotedAndQuoteRechecksCurrentPriceAndSchedule(){
        var c=criteria();c.put("pickupDate","2026-10-22");c.put("returnDate","2026-10-26");
        var recommendation=call("demo-user",c);assertEquals(200,recommendation.getStatusCode().value());
        var item=recommendation.getBody().path("results").get(0);assertNotNull(item);
        var input=quoteInput(item.path("vehicleId").asText(),c);
        var bookingsBefore=jdbc.queryForList("select * from bookings order by id");
        var historyBefore=jdbc.queryForList("select * from booking_status_history order by booking_id,occurred_at");
        var response=quote(input);
        assertEquals(200,response.getStatusCode().value());
        assertEquals(item.path("total").asLong(),response.getBody().path("total").asLong());
        assertEquals(item.path("vehicleId").asText(),response.getBody().path("vehicleId").asText());
        var anonymous=http.exchange("/api/v1/bookings/quote",HttpMethod.POST,new HttpEntity<>(input,new HttpHeaders()),
            com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals(401,anonymous.getStatusCode().value());
        assertEquals(bookingsBefore,jdbc.queryForList("select * from bookings order by id"));
        assertEquals(historyBefore,jdbc.queryForList("select * from booking_status_history order by booking_id,occurred_at"));
    }
    @Test void quoteUsesCurrentDailyPriceAfterRecommendation(){
        var c=criteria();c.put("pickupDate","2026-10-22");c.put("returnDate","2026-10-26");
        var recommendation=call("demo-user",c);assertEquals(200,recommendation.getStatusCode().value());
        var item=recommendation.getBody().path("results").get(0);assertNotNull(item);
        String vehicleId=item.path("vehicleId").asText();
        long originalPrice=jdbc.queryForObject("select daily_price from vehicles where id=?",Long.class,vehicleId);
        long currentPrice=originalPrice+50000;
        var input=quoteInput(vehicleId,c);
        jdbc.update("update vehicles set daily_price=? where id=?",currentPrice,vehicleId);
        try {
            var bookingsBefore=jdbc.queryForList("select * from bookings order by id");
            var historyBefore=jdbc.queryForList("select * from booking_status_history order by booking_id,occurred_at");
            var response=quote(input);
            assertEquals(200,response.getStatusCode().value());
            assertEquals(currentPrice,response.getBody().path("dailyPrice").asLong());
            assertEquals(currentPrice*response.getBody().path("rentalDays").asLong(),response.getBody().path("total").asLong());
            assertNotEquals(item.path("total").asLong(),response.getBody().path("total").asLong());
            assertEquals(bookingsBefore,jdbc.queryForList("select * from bookings order by id"));
            assertEquals(historyBefore,jdbc.queryForList("select * from booking_status_history order by booking_id,occurred_at"));
        } finally {
            jdbc.update("update vehicles set daily_price=? where id=?",originalPrice,vehicleId);
        }
    }
    @Test void quoteRejectsRecommendationWhenVehicleScheduleBecomesUnavailable(){
        var c=criteria();c.put("pickupDate","2026-10-22");c.put("returnDate","2026-10-26");
        var recommendation=call("demo-user",c);assertEquals(200,recommendation.getStatusCode().value());
        var item=recommendation.getBody().path("results").get(0);assertNotNull(item);
        String vehicleId=item.path("vehicleId").asText();
        var input=quoteInput(vehicleId,c);
        var pickup=LocalDate.parse((String)c.get("pickupDate"));
        var returns=LocalDate.parse((String)c.get("returnDate"));
        var beforeReservation=new BookingService.Input(vehicleId,"dn","dn",null,null,null,pickup,returns);
        long freshTotal=bookings.quote(user,beforeReservation).total();
        var blocker=bookings.create(user,new BookingService.Input(vehicleId,"dn","dn",null,null,freshTotal,pickup,returns));
        assertNotNull(blocker.id());
        var bookingsBefore=jdbc.queryForList("select * from bookings order by id");
        var historyBefore=jdbc.queryForList("select * from booking_status_history order by booking_id,occurred_at");

        var response=quote(input);
        assertEquals(409,response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains("Xe đã có đơn trùng khoảng thuê."));
        assertEquals(bookingsBefore,jdbc.queryForList("select * from bookings order by id"));
        assertEquals(historyBefore,jdbc.queryForList("select * from booking_status_history order by booking_id,occurred_at"));
    }
    @Test void deterministicRankingSoftPreferencesAndStableTies(){
        jdbc.update("update vehicles set seats=4,transmission='AUTO',daily_price=650000 where id in ('v1','v3')");
        var c=criteria();var first=call("demo-user",c).getBody();var again=call("demo-user",c).getBody();
        assertEquals(first.path("results"),again.path("results"));
        assertEquals("v1",first.path("results").get(0).path("vehicleId").asText());assertEquals("v3",first.path("results").get(1).path("vehicleId").asText());
        assertEquals(65,first.path("results").get(0).path("score").asInt());
        c.put("preferences",Map.of("transmission","MANUAL","amenityIds",List.of("not-installed")));
        var soft=call("demo-user",c).getBody();assertEquals(2,soft.path("results").size());assertEquals(30,soft.path("results").get(0).path("score").asInt());
    }
    @Test void priceSeatAndAmenityScoresUseServerFacts(){
        jdbc.update("update vehicles set seats=4,transmission='AUTO',daily_price=650000 where id in ('v1','v3')");
        jdbc.update("update vehicles set daily_price=600000 where id='v3'");
        var cheaper=call("demo-user",criteria()).getBody();assertEquals("v3",cheaper.path("results").get(0).path("vehicleId").asText());assertEquals(68,cheaper.path("results").get(0).path("score").asInt());
        jdbc.update("update vehicles set daily_price=650000,seats=7 where id='v3'");
        assertEquals("v1",call("demo-user",criteria()).getBody().path("results").get(0).path("vehicleId").asText());
        jdbc.update("insert into amenities(id,name) values('ai-fixture-gps','AI fixture GPS')");
        try {
            jdbc.update("insert into vehicle_amenities(vehicle_id,amenity_id) values('v3','ai-fixture-gps')");
            var c=criteria();c.put("preferences",Map.of("amenityIds",List.of("ai-fixture-gps")));
            var matched=call("demo-user",c).getBody().path("results").get(0);assertEquals("v3",matched.path("vehicleId").asText());assertTrue(matched.path("reasonCodes").toString().contains("AMENITY_MATCH:ai-fixture-gps"));
        } finally {jdbc.update("delete from vehicle_amenities where amenity_id='ai-fixture-gps'");jdbc.update("delete from amenities where id='ai-fixture-gps'");}
    }
    @Test void hardFiltersCalendarReservationAndNoMatch(){
        jdbc.update("update vehicles set status='MAINTENANCE' where id='v3'");
        assertEquals(1,call("demo-user",criteria()).getBody().path("results").size());
        var old=bookings.create(user,new BookingService.Input("v1","dn","dn",null,null,2600000L,LocalDate.of(2026,10,20),LocalDate.of(2026,10,24)));
        assertEquals("NO_MATCH",call("demo-user",criteria()).getBody().path("status").asText());
        jdbc.update("update bookings set status='COMPLETED',policy_version=? where id=?",RentalTerms.CALENDAR_POLICY,old.id());
        assertEquals("NO_MATCH",call("demo-user",criteria()).getBody().path("status").asText());
        var next=criteria();next.put("pickupDate","2026-10-25");next.put("returnDate","2026-10-26");assertEquals("OK",call("demo-user",next).getBody().path("status").asText());
        next.put("passengers",9);assertEquals("NO_MATCH",call("demo-user",next).getBody().path("status").asText());
    }
    @Test void missingInvalidAndClosedRouteNeverReturnInventedResults(){
        var c=criteria();c.remove("pickupDate");var missing=call("demo-user",c).getBody();assertEquals("NEEDS_CLARIFICATION",missing.path("status").asText());assertEquals("pickupDate",missing.path("unresolvedFields").get(0).asText());assertTrue(missing.path("results").isEmpty());
        for(String key:List.of("passengers","budgetVndTotal")){c=criteria();c.put(key,-1);assertEquals(400,call("demo-user",c).getStatusCode().value());}
        c=criteria();c.put("budgetVndTotal",1.5);assertEquals(400,call("demo-user",c).getStatusCode().value());
        c=criteria();c.put("score",100);assertEquals(400,call("demo-user",c).getStatusCode().value());
        c=criteria();c.put("preferences",Map.of("transmission","FLY"));assertEquals(400,call("demo-user",c).getStatusCode().value());
        c=criteria();c.put("returnDate","2026-10-20");assertEquals(400,call("demo-user",c).getStatusCode().value());
        jdbc.update("update branches set status='INACTIVE' where id='dn'");assertFalse(call("demo-user",criteria()).getStatusCode().is2xxSuccessful());
    }
    @Test void disabledFeatureFailsClosedAndRoleCheckPrecedesFlag(){
        var disabled=new com.carrental.modules.ai.recommendation.RecommendationService(search,Clock.fixed(NOW,ZoneOffset.UTC),false);
        var denied=assertThrows(com.carrental.exception.DomainException.class,()->disabled.recommend(user,null));assertEquals(503,denied.status.value());
        var staff=new Actor("staff","STAFF","staff");assertEquals(403,assertThrows(com.carrental.exception.DomainException.class,()->disabled.recommend(staff,null)).status.value());
    }
}
