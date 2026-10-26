package com.carrental;

import static org.junit.jupiter.api.Assertions.*;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.user.service.LicenseService;
import com.carrental.security.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:queueorder;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","app.jwt-secret=queue-order-test-secret-at-least-32-bytes","app.seed-enabled=true","app.seed-password=QueueOrderTestOnly123!"})
@Import(BookingQueueOrderTest.TimeConfig.class)
@Transactional
class BookingQueueOrderTest {
 static class MutableClock extends Clock {
  Instant value=Instant.parse("2026-10-15T16:59:59Z");
  public ZoneId getZone(){return ZoneOffset.UTC;}
  public Clock withZone(ZoneId zone){return this;}
  public Instant instant(){return value;}
 }
 @TestConfiguration static class TimeConfig {@Bean @Primary MutableClock queueClock(){return new MutableClock();}}
 @Autowired BookingService bookings;
 @Autowired LicenseService licenses;
 @Autowired MutableClock clock;
 @Autowired ObjectMapper json;
 @Test void newestCreationFirstForStaffAdminAndOwnerWithoutWideningReadPermissions() throws Exception {
  var user=new Actor("demo-user","USER","Fixture");
  licenses.save(user,new LicenseService.View(null,"QUEUE-TEST","VN_2025","B",LocalDate.of(2035,12,31),false,"PENDING",0));
        LicensePhotoFixture.approved(licenses, user);
  var old=bookings.create(user,new BookingService.Input("v1","dn","dn",Instant.parse("2026-10-20T03:00:00Z"),Instant.parse("2026-10-21T03:00:00Z"),650000L));
  clock.value=Instant.parse("2026-10-15T17:00:00Z");
  var quote=bookings.quote(user,new BookingService.Input("v1","dn","dn",Instant.parse("2026-10-22T03:00:00Z"),Instant.parse("2026-10-23T03:00:00Z"),null));
  var newest=bookings.create(user,new BookingService.Input("v1","dn","dn",quote.startAt(),quote.endAt(),quote.total()));
  for(var actor:List.of(user,new Actor("demo-staff-dn","STAFF","DN"),new Actor("demo-admin","ADMIN","Admin"))){
   var rows=bookings.list(actor);assertEquals(List.of(newest.id(),old.id()),rows.stream().map(BookingService.View::id).toList());
   assertEquals(clock.value,rows.getFirst().createdAt());
  }
  assertTrue(bookings.list(new Actor("unrelated-user","USER","Other")).isEmpty());
  assertTrue(bookings.list(new Actor("demo-staff-hn","STAFF","HN")).isEmpty());
  assertEquals("2026-10-15T17:00:00Z",json.readTree(json.writeValueAsString(newest)).get("createdAt").asText());
 }
}
