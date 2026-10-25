package com.carrental;
import org.junit.jupiter.api.Test;
import com.carrental.modules.booking.service.SchedulePolicy;
import com.carrental.modules.user.service.LicensePolicy;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PolicyTest {
 private final Instant now=Instant.parse("2026-09-28T00:00:00Z");
 private SchedulePolicy.Slot slot(String id,String from,String to,int start,int end,String state){return new SchedulePolicy.Slot(id,from,to,now.plusSeconds(start*3600L),now.plusSeconds(end*3600L),state,now.plusSeconds(7200));}
 @Test void oneWayMustReachNextPickup(){
  assertDoesNotThrow(()->SchedulePolicy.validate("dn",List.of(slot("1","dn","hn",24,48,"CONFIRMED"),slot("2","hn","dn",50,72,"CONFIRMED")),now));
  assertThrows(RuntimeException.class,()->SchedulePolicy.validate("dn",List.of(slot("1","dn","hn",24,48,"CONFIRMED"),slot("2","dn","dn",50,72,"CONFIRMED")),now));
 }
 @Test void overlapAndUnconfirmedRelocationAreRejected(){
  assertThrows(RuntimeException.class,()->SchedulePolicy.validate("dn",List.of(slot("1","dn","hn",24,48,"PENDING"),slot("2","hn","dn",50,72,"CONFIRMED")),now));
  assertThrows(RuntimeException.class,()->SchedulePolicy.validate("dn",List.of(slot("1","dn","dn",24,48,"CONFIRMED"),slot("2","dn","dn",47,72,"CONFIRMED")),now));
 }
 @Test void expiredHoldNoLongerMovesVehicle(){
  var hold=new SchedulePolicy.Slot("old","dn","hn",now.plusSeconds(24*3600),now.plusSeconds(48*3600),"PENDING",now);
  assertDoesNotThrow(()->SchedulePolicy.validate("dn",List.of(hold,slot("2","dn","dn",50,72,"CONFIRMED")),now));
 }
 @Test void overdueVehicleCannotSupportNextBooking(){
  assertThrows(RuntimeException.class,()->SchedulePolicy.validate("dn",List.of(slot("1","dn","hn",-48,-1,"IN_PROGRESS"),slot("2","hn","dn",24,48,"CONFIRMED")),now));
 }
 @Test void licenseIsCompatibilityNotAlphabeticalRank(){
  LocalDate expiry=LocalDate.of(2027,1,1);
  assertTrue(LicensePolicy.compatible("VN_2025","C1",false,"MANUAL",7,expiry,now));
  assertFalse(LicensePolicy.compatible("VN_2025","B1",false,"AUTO",5,expiry,now));
  assertTrue(LicensePolicy.compatible("VN_PRE_2025","B1_AUTO",false,"AUTO",5,expiry,now));
  assertFalse(LicensePolicy.compatible("VN_PRE_2025","B1_AUTO",false,"MANUAL",5,expiry,now));
  assertFalse(LicensePolicy.compatible("VN_2025","B",false,"AUTO",5,LocalDate.of(2026,9,27),now));
 }
}
