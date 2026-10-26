package com.carrental;
import com.carrental.modules.booking.service.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CalendarRentalTermsTest {
 @Test void fourDaysAndEntireReturnDayWithMicrosecondPrecision(){
  var now=Instant.parse("2026-10-17T05:00:00Z");var p=RentalTerms.calendar(LocalDate.of(2026,10,17),LocalDate.of(2026,10,21),now);
  assertEquals(4,p.days());assertEquals(Instant.parse("2026-10-16T17:00:00Z"),p.start());assertEquals(Instant.parse("2026-10-21T16:59:59.999999Z"),p.end());assertEquals(now.plusSeconds(7200),p.holdUntil());
  assertEquals(Instant.parse("2026-10-17T16:59:59.999999Z"),RentalTerms.calendar(LocalDate.of(2026,10,17),LocalDate.of(2026,10,18),Instant.parse("2026-10-17T16:30:00Z")).holdUntil());
 }
 @Test void datesValidateAndLegacyHourlyRuleIsUnchanged(){
  var today=LocalDate.of(2026,10,17);var now=Instant.parse("2026-10-17T05:00:00Z");
  assertThrows(RuntimeException.class,()->RentalTerms.calendar(today,today,now));assertThrows(RuntimeException.class,()->RentalTerms.calendar(today.minusDays(1),today,now));assertThrows(RuntimeException.class,()->RentalTerms.calendar(today,today.plusDays(91),now));assertThrows(RuntimeException.class,()->RentalTerms.calendar(null,today,now));
  assertEquals(90,RentalTerms.calendar(today,today.plusDays(90),now).days());assertEquals(2,RentalTerms.days(now.plusSeconds(1),now.plusSeconds(86402),now));
 }
 @Test void finishedCalendarStillReservesReturnDayAndNextDayIsAvailable(){
  var now=Instant.parse("2026-10-17T05:00:00Z");var p=RentalTerms.calendar(LocalDate.of(2026,10,17),LocalDate.of(2026,10,21),now);
  for(String status:List.of("RETURNED","COMPLETED")){
   var occupied=new SchedulePolicy.Slot("old","dn","dn",p.start(),p.end(),status,p.holdUntil(),true);
   assertTrue(SchedulePolicy.occupies(occupied,now));assertFalse(SchedulePolicy.occupies(occupied,p.end().plusNanos(1000)));
   var sameDay=new SchedulePolicy.Slot("new","dn","dn",Instant.parse("2026-10-20T17:00:00Z"),Instant.parse("2026-10-22T17:00:00Z"),"CONFIRMED",now);
   assertThrows(RuntimeException.class,()->SchedulePolicy.validate("dn",List.of(occupied,sameDay),now));
   var nextDay=new SchedulePolicy.Slot("new","dn","dn",Instant.parse("2026-10-21T17:00:00Z"),Instant.parse("2026-10-22T17:00:00Z"),"CONFIRMED",now);
   assertDoesNotThrow(()->SchedulePolicy.validate("dn",List.of(occupied,nextDay),now));
  }
 }
}
