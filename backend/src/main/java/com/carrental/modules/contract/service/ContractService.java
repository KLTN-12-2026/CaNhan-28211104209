package com.carrental.modules.contract.service;
import com.carrental.modules.booking.entity.Booking;
import com.carrental.modules.booking.service.DepositPolicy;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
@Service public class ContractService {
 public static final String TERMS="Người thuê chịu trách nhiệm đối với vi phạm và thiệt hại thuộc trách nhiệm của mình trong thời gian thuê, theo chứng cứ, căn cứ xử lý và thỏa thuận phù hợp. Yêu cầu bồi thường phải nêu thiệt hại, căn cứ trách nhiệm và khoản được bảo hiểm/bên khác chi trả. Không tự động khấu trừ cọc. Hợp đồng phục vụ đồ án, chưa là mẫu được thẩm định pháp lý.";
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final java.time.Clock clock;private final com.carrental.modules.booking.service.ReturnPolicy returnPolicies;
 public ContractService(JdbcTemplate j,ObjectMapper m,java.time.Clock c,com.carrental.modules.booking.service.ReturnPolicy returnPolicies){jdbc=j;json=m;clock=c;this.returnPolicies=returnPolicies;}
 public void issue(Booking b){
  var snapshot=new LinkedHashMap<String,Object>(Map.of("customer",b.customerName,"vehicle",b.vehicleName,"pickup",b.pickupName,"return",b.returnName,"startAt",b.startAt,"endAt",b.endAt,"rentalTotal",b.rentalTotal,"oneWayFee",b.oneWayFee,"total",b.total,"terms",TERMS));
  snapshot.put("policyVersion",b.policyVersion);snapshot.put("rentalDays",b.rentalDays);
  if(b.returnPolicyVersion!=null)snapshot.put("returnPolicy",returnPolicies.view(b.returnPolicyVersion,b.returnPolicySnapshot,b.returnPolicyAcceptedAt));
  try{
   jdbc.update("insert into contracts(id,booking_id,issued_at,snapshot,terms_version) values(?,?,?,?,?)",UUID.randomUUID().toString(),b.id,java.sql.Timestamp.from(clock.instant()),json.writeValueAsString(snapshot),"terms-2026-09-28");
  }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
 }
 public Object get(String bookingId){return jdbc.queryForList("select id,issued_at,snapshot,terms_version from contracts where booking_id=?",bookingId);}
public void issueDeposit(com.carrental.modules.booking.service.BookingService.View b){
    var snapshot=new LinkedHashMap<String,Object>();
    snapshot.put("customer",b.customerName());snapshot.put("vehicle",b.vehicleName());snapshot.put("pickup",b.pickupName());snapshot.put("return",b.returnName());
    snapshot.put("startAt",b.startAt());snapshot.put("endAt",b.endAt());snapshot.put("rentalTotal",b.rentalTotal());snapshot.put("oneWayFee",b.oneWayFee());snapshot.put("total",b.total());
    snapshot.put("terms",TERMS);snapshot.put("policyVersion",b.policyVersion());snapshot.put("rentalDays",b.rentalDays());snapshot.put("deposit",b.deposit());
    if(b.returnPolicy()!=null)snapshot.put("returnPolicy",b.returnPolicy());
    String termsVersion = DepositPolicy.isDemoQrMethod(b.deposit().method()) ? DepositPolicy.QR_VERSION : DepositPolicy.VNPAY_VERSION;
    try{jdbc.update("insert into contracts(id,booking_id,issued_at,snapshot,terms_version) values(?,?,?,?,?)",UUID.randomUUID().toString(),b.id(),java.sql.Timestamp.from(clock.instant()),json.writeValueAsString(snapshot),termsVersion);}
    catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
  }
}
