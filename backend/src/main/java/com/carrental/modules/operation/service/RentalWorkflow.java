package com.carrental.modules.operation.service;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.contract.service.ContractService;
import com.carrental.modules.invoice.service.InvoiceService;
import com.carrental.modules.user.service.LicenseService;
import com.carrental.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
@Service public class RentalWorkflow {
 private final BookingService bookings;private final ContractService contracts;private final InvoiceService invoices;private final LicenseService licenses;private final JdbcTemplate jdbc;private final ObjectMapper json;private final java.time.Clock clock;
 @org.springframework.beans.factory.annotation.Autowired private com.carrental.modules.payment.service.PaymentService payments;
 public RentalWorkflow(BookingService b,ContractService c,InvoiceService i,LicenseService l,JdbcTemplate j,ObjectMapper m,java.time.Clock clk){bookings=b;contracts=c;invoices=i;licenses=l;jdbc=j;json=m;clock=clk;}
 @Transactional public Object apply(Actor a,String id,BookingService.Action action){
  var b=bookings.change(a,id,action);
  if(b.depositRequired&&java.util.Set.of("CANCELLED","REJECTED").contains(b.status))payments.cancellation(bookings.view(b));
  if("CONFIRMED".equals(b.status))contracts.issue(b);
  if("COMPLETED".equals(b.status))invoices.issue(b);
  if("IN_PROGRESS".equals(b.status)||"RETURNED".equals(b.status)){
   boolean handover="IN_PROGRESS".equals(b.status);String snapshot=null;
   try{if(handover)snapshot=json.writeValueAsString(licenses.forBooking(b.userId));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
   jdbc.update("insert into vehicle_handover_records(id,booking_id,kind,actor_id,branch_id,occurred_at,condition_note,license_snapshot,maintenance) values(?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),b.id,handover?"HANDOVER":"RETURN",a.id(),handover?b.pickupBranchId:b.returnBranchId,java.sql.Timestamp.from(clock.instant()),action.condition(),snapshot,action.maintenance());
  }
  return bookings.view(b);
 }
 public Object contract(Actor a,String id){bookings.require(a,id);return contracts.get(id);}
 public Object invoice(Actor a,String id){bookings.require(a,id);return invoices.get(id);}
}
