package com.carrental.modules.operation.service;

import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.invoice.service.InvoiceService;
import com.carrental.security.Actor;
import com.carrental.exception.DomainException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class OperationalReportService {
 private static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
 private final BookingService bookings;private final InvoiceService invoices;private final Clock clock;
 public OperationalReportService(BookingService bookings,InvoiceService invoices,Clock clock){this.bookings=bookings;this.invoices=invoices;this.clock=clock;}
 public record CreatedBookings(long total,Map<String,Long> byStatus){}
 public record Report(LocalDate fromDate,LocalDate toDate,String timeZone,Instant fromInclusive,Instant toExclusive,Instant asOf,CreatedBookings createdBookings,long completedBookings,long issuedInvoices,String invoiceTotalVnd){}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Report read(Actor actor,String fromText,String toText){
  actor.require("ADMIN");
  LocalDate from=parse(fromText),to=parse(toText);
  long days=ChronoUnit.DAYS.between(from,to)+1;
  if(days<1||days>366||from.isBefore(LocalDate.of(1970,1,2))||to.isAfter(LocalDate.of(2038,1,18)))throw DomainException.bad("Khoảng ngày phải từ 1 đến 366 ngày, trong miền 02/01/1970–18/01/2038.");
  Instant start=from.atStartOfDay(ZONE).toInstant(),end=to.plusDays(1).atStartOfDay(ZONE).toInstant(),asOf=clock.instant();
  var booking=bookings.report(start,end,asOf);var invoice=invoices.report(start,end);
  return new Report(from,to,ZONE.getId(),start,end,asOf,new CreatedBookings(booking.total(),booking.byStatus()),booking.completed(),invoice.count(),invoice.totalVnd());
 }
 private LocalDate parse(String value){
  if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw DomainException.bad("Ngày phải có định dạng YYYY-MM-DD.");
  try{return LocalDate.parse(value);}catch(java.time.format.DateTimeParseException e){throw DomainException.bad("Ngày không hợp lệ.");}
 }
}
