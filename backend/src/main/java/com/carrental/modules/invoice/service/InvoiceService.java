package com.carrental.modules.invoice.service;
import com.carrental.modules.booking.entity.Booking;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
@Service public class InvoiceService {
 @org.springframework.beans.factory.annotation.Autowired private com.carrental.modules.booking.service.ReturnPolicy returnPolicy;
 @org.springframework.beans.factory.annotation.Autowired private com.carrental.modules.booking.service.ReturnSettlementService settlements;
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final java.time.Clock clock;
 public InvoiceService(JdbcTemplate j,ObjectMapper m,java.time.Clock c){jdbc=j;json=m;clock=c;}
 public record Report(long count,String totalVnd){}
 public Report report(java.time.Instant from,java.time.Instant to){
  return jdbc.queryForObject("select count(*) as amount,coalesce(sum(total),0) as total_vnd from invoices where issued_at>=? and issued_at<?",
   (rs,row)->new Report(rs.getLong("amount"),rs.getBigDecimal("total_vnd").toBigIntegerExact().toString()),java.sql.Timestamp.from(from),java.sql.Timestamp.from(to));
 }
 public void issue(Booking b){
  String id=UUID.randomUUID().toString();
  var assessment=returnPolicy.assessment(b.returnAssessment);
  var settlement=settlements.summary(b.id,assessment);
  if(settlement!=null && settlement.outstandingAmount()>0) throw com.carrental.exception.DomainException.conflict("Khoản vượt hạn chưa được xử lý đủ.");
  long total=settlement==null?b.total:Math.addExact(b.total,Math.subtractExact(settlement.originalAmount(),settlement.waivedAmount()));
  var snapshot=new LinkedHashMap<String,Object>(Map.of("customer",b.customerName,"vehicle",b.vehicleName,"pickup",b.pickupName,"return",b.returnName,"startAt",b.startAt,"endAt",b.endAt,"note","Tiền thuê và phí tuyến theo báo giá. Không xác nhận đã thanh toán; cọc, phụ phí hoặc sự cố theo hồ sơ riêng."));
  snapshot.put("policyVersion",b.policyVersion);snapshot.put("rentalDays",b.rentalDays);
  if(settlement!=null){snapshot.put("returnAssessment",assessment);snapshot.put("returnSettlement",settlement);snapshot.put("note","Thu khoản vượt hạn không xác nhận toàn bộ tiền thuê đã thanh toán. Cọc giữ chỗ theo hồ sơ riêng.");}
  try{jdbc.update("insert into invoices(id,booking_id,issued_at,total,snapshot) values(?,?,?,?,?)",id,b.id,java.sql.Timestamp.from(clock.instant()),total,json.writeValueAsString(snapshot));}
  catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
  item(id,"Tiền thuê xe",b.rentalDays,b.dailyPrice,b.rentalTotal);item(id,"Phí tuyến nhận/trả",1,b.oneWayFee,b.oneWayFee);
  if(settlement!=null && settlement.originalAmount()>0) {
   item(id,"Khoản vượt hạn sau miễn/giảm",1,settlement.originalAmount()-settlement.waivedAmount(),settlement.originalAmount()-settlement.waivedAmount());
  }
 }
 private void item(String id,String name,int qty,long price,long amount){jdbc.update("insert into invoice_items(id,invoice_id,name,quantity,unit_price,amount) values(?,?,?,?,?,?)",UUID.randomUUID().toString(),id,name,qty,price,amount);}
 public Object get(String bookingId){
  var rows=jdbc.queryForList("select id,issued_at,total,snapshot from invoices where booking_id=?",bookingId);
  for(var row:rows)row.put("items",jdbc.queryForList("select name,quantity,unit_price,amount from invoice_items where invoice_id=?",row.get("id")));
  return rows;
 }
}
